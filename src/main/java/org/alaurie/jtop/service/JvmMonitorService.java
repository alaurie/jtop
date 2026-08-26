package org.alaurie.jtop.service;

import com.sun.management.OperatingSystemMXBean;
import com.sun.tools.attach.VirtualMachine;
import org.alaurie.jtop.model.*;
import org.alaurie.jtop.service.collector.*;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.*;
import java.lang.management.MemoryPoolMXBean;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;

/// Service for polling metric snapshots from a target JVM using Attach API, Remote JMX, or Framework MBeans.
public class JvmMonitorService implements AutoCloseable {

    private final long targetPid;
    private final String jmxUrlStr;
    private final boolean isSelf;
    private volatile boolean connected = false;
    private int consecutiveFailures = 0;

    private VirtualMachine attachedVm;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbeanConnection;

    private OperatingSystemMXBean osMxBean;
    private MemoryMXBean memoryMxBean;
    private ThreadMXBean threadMxBean;
    private com.sun.management.ThreadMXBean sunThreadMxBean;
    private RuntimeMXBean runtimeMxBean;
    private ClassLoadingMXBean classLoadingMxBean;
    private CompilationMXBean compilationMxBean;
    private List<MemoryPoolMXBean> memoryPoolMxBeans = new ArrayList<>();
    private List<GarbageCollectorMXBean> gcMxBeans = new ArrayList<>();
    private List<BufferPoolMXBean> bufferPoolMxBeans = new ArrayList<>();

    // --- per-poll prev-state (owned by collectors; kept here only for history timing) ---
    private long previousTimestampMs = System.currentTimeMillis();
    private long previousProcessNanoTime = System.nanoTime();
    private long lastPollLatencyMs = 0;

    // --- telemetry collector adapters ---
    private CpuCollector cpuCollector;
    private MemoryCollector memoryCollector;
    private GcCollector gcCollector;
    private ThreadCollector threadCollector;
    private RuntimeCollector runtimeCollector;
    private FrameworkCollector frameworkCollector;

    private final List<Double> cpuHistoryBuffer = new CopyOnWriteArrayList<>();
    private final List<Long> heapHistoryBuffer = new CopyOnWriteArrayList<>();
    private final List<Long> gcTimeHistoryBuffer = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY_SIZE = 100;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("jtop-monitor-", 0).factory()
    );

    public JvmMonitorService(long targetPid) {
        this.targetPid = targetPid;
        this.jmxUrlStr = null;
        this.isSelf = (targetPid == ProcessHandle.current().pid());
    }

    public JvmMonitorService(String jmxUrlStr) {
        this.targetPid = -1;
        this.jmxUrlStr = parseJmxUrl(jmxUrlStr);
        this.isSelf = false;
    }

    private String parseJmxUrl(String urlOrHostPort) {
        if (urlOrHostPort.startsWith("service:jmx:")) {
            return urlOrHostPort;
        }
        if (!urlOrHostPort.contains(":")) {
            return "service:jmx:rmi:///jndi/rmi://" + urlOrHostPort + ":9999/jmxrmi";
        }
        return "service:jmx:rmi:///jndi/rmi://" + urlOrHostPort + "/jmxrmi";
    }

    public void connect() throws Exception {
        if (jmxUrlStr != null) {
            JMXServiceURL url = new JMXServiceURL(jmxUrlStr);
            this.jmxConnector = JMXConnectorFactory.connect(url);
            this.mbeanConnection = jmxConnector.getMBeanServerConnection();
            initPlatformProxies();
            initRemotePoolsAndGc();
            this.connected = true;
            return;
        }

        if (isSelf) {
            this.mbeanConnection = ManagementFactory.getPlatformMBeanServer();
            this.osMxBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            this.memoryMxBean = ManagementFactory.getMemoryMXBean();
            this.threadMxBean = ManagementFactory.getThreadMXBean();
            this.runtimeMxBean = ManagementFactory.getRuntimeMXBean();
            this.classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();
            this.compilationMxBean = ManagementFactory.getCompilationMXBean();
            if (this.threadMxBean instanceof com.sun.management.ThreadMXBean sunBean) {
                this.sunThreadMxBean = sunBean;
            }
            this.memoryPoolMxBeans = ManagementFactory.getMemoryPoolMXBeans();
            this.gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans();
            this.bufferPoolMxBeans = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            this.connected = true;
        } else {
            try {
                this.attachedVm = VirtualMachine.attach(String.valueOf(targetPid));
            } catch (Exception e) {
                this.connected = false;
                throw new IllegalStateException("Failed to attach to PID " + targetPid + ": " + e.getMessage()
                    + "\nEnsure target process is owned by same user, or run: sudo -u <user> jtop --pid " + targetPid
                    + "\nAlternatively, connect via Remote JMX: jtop --jmx host:port", e);
            }

            String connectorAddress = attachedVm.startLocalManagementAgent();
            if (connectorAddress == null) {
                connectorAddress = attachedVm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            }
            if (connectorAddress == null) {
                this.connected = false;
                throw new IllegalStateException("Failed to obtain local JMX connector address for PID " + targetPid + ". DisableAttachMechanism may be set.");
            }
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            this.jmxConnector = JMXConnectorFactory.connect(url);
            this.mbeanConnection = jmxConnector.getMBeanServerConnection();
            initPlatformProxies();
            initRemotePoolsAndGc();
            this.connected = true;
        }

        if (threadMxBean != null && threadMxBean.isThreadCpuTimeSupported() && !threadMxBean.isThreadCpuTimeEnabled()) {
            try {
                threadMxBean.setThreadCpuTimeEnabled(true);
            } catch (Exception ignored) {}
        }
        if (threadMxBean != null && threadMxBean.isThreadContentionMonitoringSupported() && !threadMxBean.isThreadContentionMonitoringEnabled()) {
            try {
                threadMxBean.setThreadContentionMonitoringEnabled(true);
            } catch (Exception ignored) {}
        }
        // Initialise telemetry collector adapters after MBeans are ready
        this.cpuCollector       = new CpuCollector(osMxBean);
        this.memoryCollector    = new MemoryCollector(memoryMxBean, memoryPoolMxBeans);
        this.gcCollector        = new GcCollector(gcMxBeans, bufferPoolMxBeans);
        this.threadCollector    = new ThreadCollector(threadMxBean, isSelf);
        this.runtimeCollector   = new RuntimeCollector(runtimeMxBean, classLoadingMxBean,
                                      compilationMxBean, osMxBean, isSelf,
                                      Runtime.getRuntime().availableProcessors());
        this.frameworkCollector = new FrameworkCollector(mbeanConnection);

    }

    public boolean isConnected() {
        if (!connected) return false;
        if (!isSelf && jmxUrlStr == null && targetPid > 0) {
            boolean alive = ProcessHandle.of(targetPid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                this.connected = false;
                return false;
            }
        }
        return connected;
    }

    private void initPlatformProxies() throws Exception {
        this.osMxBean = ManagementFactory.newPlatformMXBeanProxy(
            mbeanConnection, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
        this.memoryMxBean = ManagementFactory.newPlatformMXBeanProxy(
            mbeanConnection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        this.threadMxBean = ManagementFactory.newPlatformMXBeanProxy(
            mbeanConnection, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        this.runtimeMxBean = ManagementFactory.newPlatformMXBeanProxy(
            mbeanConnection, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
        try {
            this.classLoadingMxBean = ManagementFactory.newPlatformMXBeanProxy(
                mbeanConnection, ManagementFactory.CLASS_LOADING_MXBEAN_NAME, ClassLoadingMXBean.class);
        } catch (Exception ignored) {}
        try {
            this.compilationMxBean = ManagementFactory.newPlatformMXBeanProxy(
                mbeanConnection, ManagementFactory.COMPILATION_MXBEAN_NAME, CompilationMXBean.class);
        } catch (Exception ignored) {}

        try {
            this.sunThreadMxBean = ManagementFactory.newPlatformMXBeanProxy(
                mbeanConnection, ManagementFactory.THREAD_MXBEAN_NAME, com.sun.management.ThreadMXBean.class);
        } catch (Exception ignored) {}
    }

    private void initRemotePoolsAndGc() {
        try {
            Set<ObjectName> poolNames = mbeanConnection.queryNames(new ObjectName(ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE + ",*"), null);
            for (ObjectName name : poolNames) {
                MemoryPoolMXBean proxy = ManagementFactory.newPlatformMXBeanProxy(mbeanConnection, name.getCanonicalName(), MemoryPoolMXBean.class);
                memoryPoolMxBeans.add(proxy);
            }
            Set<ObjectName> gcNames = mbeanConnection.queryNames(new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*"), null);
            for (ObjectName name : gcNames) {
                GarbageCollectorMXBean proxy = ManagementFactory.newPlatformMXBeanProxy(mbeanConnection, name.getCanonicalName(), GarbageCollectorMXBean.class);
                gcMxBeans.add(proxy);
            }
            Set<ObjectName> bufferNames = mbeanConnection.queryNames(new ObjectName("java.nio:type=BufferPool,*"), null);
            for (ObjectName name : bufferNames) {
                BufferPoolMXBean proxy = ManagementFactory.newPlatformMXBeanProxy(mbeanConnection, name.getCanonicalName(), BufferPoolMXBean.class);
                bufferPoolMxBeans.add(proxy);
            }
        } catch (Exception ignored) {}
    }

    public JvmMetricsSnapshot pollSnapshot() {
        if (!isConnected()) {
            throw new IllegalStateException("Target JVM connection lost or process terminated.");
        }

        var startNano = System.nanoTime();
        var timestamp = System.currentTimeMillis();
        var currentNano = System.nanoTime();
        var deltaMs = Math.max(1, timestamp - previousTimestampMs);
        var deltaSec = deltaMs / 1000.0;
        var deltaWallNs = Math.max(1, currentNano - previousProcessNanoTime);
        this.previousTimestampMs = timestamp;
        this.previousProcessNanoTime = currentNano;

        try {
            var cpu = cpuCollector.collect(deltaWallNs);
            var mem = memoryCollector.collect(deltaSec);
            var gc  = gcCollector.collect(deltaMs);
            var thr = threadCollector.collect(deltaMs);
            var rt  = runtimeCollector.collect(
                          cpu.committedVirtualMem(), cpu.totalPhysMem(), cpu.freePhysMem(),
                          cpu.totalSwap(), cpu.freeSwap(), cpu.openFds(), cpu.maxFds());
            var fw  = frameworkCollector.collect();

            long totalGcTimeMs = gc.gcSnapshots().stream()
                .mapToLong(GcSnapshot::collectionTimeMs).sum();
            updateHistory(cpu.processCpuPct(), mem.heap().getUsed(), totalGcTimeMs);
            this.lastPollLatencyMs = (System.nanoTime() - startNano) / 1_000_000;
            this.consecutiveFailures = 0;

            return new JvmMetricsSnapshot(
                timestamp,
                cpu.processCpuPct(), cpu.systemCpuPct(),
                mem.heap().getUsed(), mem.heap().getMax(), mem.nonHeap().getUsed(),
                mem.pools(), gc.gcSnapshots(),
                thr.platformCount(), thr.virtualCount(), thr.threads(),
                rt,
                mem.allocRateMbPerSec(), gc.bufferPools(),
                thr.deadlockedIds(), gc.gcCpuOverheadPct(), fw
            );
        } catch (Throwable t) {
            this.consecutiveFailures++;
            if (this.consecutiveFailures >= 3) this.connected = false;
            throw new IllegalStateException("MBean polling exception: " + t.getMessage(), t);
        }
    }


    public String generateHeapDump(String targetFilePath) {
        try {
            ObjectName name = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
            if (mbeanConnection != null && mbeanConnection.isRegistered(name)) {
                String dumpPath = targetFilePath != null ? targetFilePath : "/tmp/jtop_dump_pid" + (targetPid > 0 ? targetPid : "self") + "_" + System.currentTimeMillis() + ".hprof";
                mbeanConnection.invoke(name, "dumpHeap", new Object[]{dumpPath, true}, new String[]{"java.lang.String", "boolean"});
                return dumpPath;
            }
        } catch (Throwable t) {
            return "Failed: " + t.getMessage();
        }
        return "HotSpotDiagnostic MBean not registered";
    }

    public Map<String, String> getManageableVmOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        try {
            ObjectName name = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
            if (mbeanConnection != null && mbeanConnection.isRegistered(name)) {
                // 1. Query DiagnosticOptions CompositeData array for remote/containerized JMX
                try {
                    Object diagAttr = mbeanConnection.getAttribute(name, "DiagnosticOptions");
                    if (diagAttr instanceof Object[] array) {
                        for (Object item : array) {
                            try {
                                Method mName = item.getClass().getMethod("getName");
                                Method mVal = item.getClass().getMethod("getValue");
                                String optName = String.valueOf(mName.invoke(item));
                                String optVal = String.valueOf(mVal.invoke(item));
                                options.put(optName, optVal);
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}

                // 2. Query individual standard options as fallback
                if (options.isEmpty()) {
                    List<String> optionNames = List.of(
                        "HeapDumpOnOutOfMemoryError",
                        "HeapDumpPath",
                        "PrintGC",
                        "PrintGCDetails",
                        "MaxHeapFreeRatio",
                        "MinHeapFreeRatio",
                        "ClassUnloading",
                        "ManagementServer",
                        "C4PauseTarget",
                        "ZingGCDetails",
                        "GraalCompileMethod",
                        "GraalPrintProperties"
                    );
                    for (String optName : optionNames) {
                        try {
                            Object compResult = mbeanConnection.invoke(name, "getVMOption", new Object[]{optName}, new String[]{"java.lang.String"});
                            if (compResult != null) {
                                Method getValueMethod = compResult.getClass().getMethod("getValue");
                                Object val = getValueMethod.invoke(compResult);
                                options.put(optName, String.valueOf(val));
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return options;
    }

    public boolean setVmOption(String optionName, String newValue) {
        try {
            ObjectName name = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
            if (mbeanConnection != null && mbeanConnection.isRegistered(name)) {
                mbeanConnection.invoke(name, "setVMOption", new Object[]{optionName, newValue}, new String[]{"java.lang.String", "java.lang.String"});
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public void softEvictHikariConnections() {
        try {
            if (mbeanConnection != null) {
                Set<ObjectName> hikariNames = mbeanConnection.queryNames(new ObjectName("com.zaxxer.hikari:*"), null);
                for (ObjectName name : hikariNames) {
                    try {
                        mbeanConnection.invoke(name, "softEvictConnections", new Object[0], new String[0]);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public void flushAgroalConnections() {
        try {
            if (mbeanConnection != null) {
                Set<ObjectName> agroalNames = mbeanConnection.queryNames(new ObjectName("io.agroal:*"), null);
                for (ObjectName name : agroalNames) {
                    try {
                        mbeanConnection.invoke(name, "flush", new Object[]{"ALL"}, new String[]{"java.lang.String"});
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public void setLogLevel(String loggerName, String levelStr) {
        try {
            if (mbeanConnection != null) {
                Set<ObjectName> logbackNames = mbeanConnection.queryNames(new ObjectName("ch.qos.logback.classic:*"), null);
                for (ObjectName name : logbackNames) {
                    try {
                        mbeanConnection.invoke(name, "setLoggerLevel", new Object[]{loggerName, levelStr}, new String[]{"java.lang.String", "java.lang.String"});
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }


    public List<String> fetchThreadStackTrace(long threadId) {
        if (threadMxBean == null || !isConnected()) return List.of();
        try {
            ThreadInfo info = threadMxBean.getThreadInfo(threadId, 15);
            if (info != null && info.getStackTrace() != null) {
                return Arrays.stream(info.getStackTrace()).map(StackTraceElement::toString).collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    public long getLastPollLatencyMs() {
        return lastPollLatencyMs;
    }

    public void triggerGc() {
        try {
            if (memoryMxBean != null && isConnected()) {
                memoryMxBean.gc();
            }
        } catch (Exception ignored) {}
    }


    private synchronized void updateHistory(double cpu, long heapUsed, long gcTime) {
        cpuHistoryBuffer.add(cpu);
        heapHistoryBuffer.add(heapUsed);
        gcTimeHistoryBuffer.add(gcTime);

        if (cpuHistoryBuffer.size() > MAX_HISTORY_SIZE) {
            List<List<Double>> windows = cpuHistoryBuffer.stream()
                .gather(Gatherers.windowSliding(MAX_HISTORY_SIZE))
                .toList();
            if (!windows.isEmpty()) {
                cpuHistoryBuffer.clear();
                cpuHistoryBuffer.addAll(windows.getLast());
            }
        }

        if (heapHistoryBuffer.size() > MAX_HISTORY_SIZE) {
            List<List<Long>> windows = heapHistoryBuffer.stream()
                .gather(Gatherers.windowSliding(MAX_HISTORY_SIZE))
                .toList();
            if (!windows.isEmpty()) {
                heapHistoryBuffer.clear();
                heapHistoryBuffer.addAll(windows.getLast());
            }
        }

        if (gcTimeHistoryBuffer.size() > MAX_HISTORY_SIZE) {
            List<List<Long>> windows = gcTimeHistoryBuffer.stream()
                .gather(Gatherers.windowSliding(MAX_HISTORY_SIZE))
                .toList();
            if (!windows.isEmpty()) {
                gcTimeHistoryBuffer.clear();
                gcTimeHistoryBuffer.addAll(windows.getLast());
            }
        }
    }

    public synchronized MetricHistory getMetricHistory() {
        return new MetricHistory(
            new ArrayList<>(cpuHistoryBuffer),
            new ArrayList<>(heapHistoryBuffer),
            new ArrayList<>(gcTimeHistoryBuffer)
        );
    }

    public void startPolling(long intervalMs, java.util.function.Consumer<JvmMetricsSnapshot> listener) {
        executor.scheduleAtFixedRate(() -> {
            try {
                JvmMetricsSnapshot snapshot = pollSnapshot();
                listener.accept(snapshot);
            } catch (Exception e) {
                // Background polling exception caught
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        this.connected = false;
        executor.shutdownNow();
        if (jmxConnector != null) {
            try {
                jmxConnector.close();
            } catch (Exception ignored) {}
        }
        if (attachedVm != null) {
            try {
                attachedVm.detach();
            } catch (Exception ignored) {}
        }
    }
}
