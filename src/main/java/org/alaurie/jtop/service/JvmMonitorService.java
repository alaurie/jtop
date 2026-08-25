package org.alaurie.jtop.service;

import com.sun.management.OperatingSystemMXBean;
import com.sun.tools.attach.VirtualMachine;
import org.alaurie.jtop.model.*;

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

    private final Map<Long, Long> previousThreadCpuTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> previousGcTimesMs = new ConcurrentHashMap<>();
    private long previousTimestampMs = System.currentTimeMillis();
    private long previousProcessCpuTimeNs = 0;
    private long previousProcessNanoTime = System.nanoTime();
    private long previousEdenUsedBytes = 0;
    private long previousTotalGcTimeMs = 0;
    private long lastPollLatencyMs = 0;

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

        long startNano = System.nanoTime();
        long timestamp = System.currentTimeMillis();
        long currentNanoTime = System.nanoTime();
        long deltaMs = Math.max(1, timestamp - previousTimestampMs);
        double deltaSec = deltaMs / 1000.0;
        long deltaWallNs = Math.max(1, currentNanoTime - previousProcessNanoTime);
        this.previousTimestampMs = timestamp;
        this.previousProcessNanoTime = currentNanoTime;

        try {
            // CPU Load
            double processCpu = 0.0;
            double systemCpu = 0.0;
            long openFds = 0;
            long maxFds = 0;
            long committedVirtualMem = 0;
            long totalPhysMem = 0;
            long freePhysMem = 0;
            long totalSwap = 0;
            long freeSwap = 0;
            int processors = Runtime.getRuntime().availableProcessors();

            if (osMxBean != null) {
                long currentCpuNs = osMxBean.getProcessCpuTime();
                if (previousProcessCpuTimeNs > 0 && currentCpuNs >= previousProcessCpuTimeNs) {
                    long deltaCpuNs = currentCpuNs - previousProcessCpuTimeNs;
                    processCpu = Math.min(100.0, (deltaCpuNs / (double) deltaWallNs) * 100.0);
                } else {
                    double rawCpu = osMxBean.getProcessCpuLoad();
                    processCpu = rawCpu >= 0 ? rawCpu * 100.0 : 0.0;
                }
                this.previousProcessCpuTimeNs = currentCpuNs;

                double sysLoad = osMxBean.getCpuLoad();
                systemCpu = sysLoad >= 0 ? sysLoad * 100.0 : 0.0;

                try {
                    Method mOpen = osMxBean.getClass().getMethod("getOpenFileDescriptorCount");
                    openFds = (Long) mOpen.invoke(osMxBean);
                } catch (Throwable ignored) {}
                try {
                    Method mMax = osMxBean.getClass().getMethod("getMaxFileDescriptorCount");
                    maxFds = (Long) mMax.invoke(osMxBean);
                } catch (Throwable ignored) {}

                try {
                    committedVirtualMem = osMxBean.getCommittedVirtualMemorySize();
                    totalPhysMem = osMxBean.getTotalMemorySize();
                    freePhysMem = osMxBean.getFreeMemorySize();
                    totalSwap = osMxBean.getTotalSwapSpaceSize();
                    freeSwap = osMxBean.getFreeSwapSpaceSize();
                } catch (Throwable ignored) {}
            }

            // Memory Usage & Allocation Rate
            MemoryUsage heapUsage = memoryMxBean != null ? memoryMxBean.getHeapMemoryUsage() : new MemoryUsage(0, 0, 0, 0);
            MemoryUsage nonHeapUsage = memoryMxBean != null ? memoryMxBean.getNonHeapMemoryUsage() : new MemoryUsage(0, 0, 0, 0);

            long currentEdenBytes = 0;
            List<MemoryPoolSnapshot> poolSnapshots = new ArrayList<>();
            for (MemoryPoolMXBean pool : memoryPoolMxBeans) {
                try {
                    MemoryUsage u = pool.getUsage();
                    if (u != null) {
                        if (pool.getName().toLowerCase().contains("eden")) {
                            currentEdenBytes = u.getUsed();
                        }
                        poolSnapshots.add(new MemoryPoolSnapshot(pool.getName(), u.getUsed(), u.getCommitted(), u.getMax()));
                    }
                } catch (Exception ignored) {}
            }

            if (currentEdenBytes == 0) {
                currentEdenBytes = heapUsage.getUsed();
            }

            double allocationRateMbPerSec = 0.0;
            if (previousEdenUsedBytes > 0 && currentEdenBytes > previousEdenUsedBytes) {
                long deltaEdenBytes = currentEdenBytes - previousEdenUsedBytes;
                allocationRateMbPerSec = (deltaEdenBytes / 1024.0 / 1024.0) / deltaSec;
            }
            this.previousEdenUsedBytes = currentEdenBytes;

            // GC Snapshots & GC Overhead %
            List<GcSnapshot> gcSnapshots = new ArrayList<>();
            long totalGcTime = 0;
            for (GarbageCollectorMXBean gc : gcMxBeans) {
                try {
                    long count = gc.getCollectionCount();
                    long timeMs = gc.getCollectionTime();
                    if (timeMs > 0) totalGcTime += timeMs;

                    double pauseRateMsPerSec = 0.0;
                    Long prevMs = previousGcTimesMs.put(gc.getName(), timeMs);
                    if (prevMs != null && timeMs > prevMs) {
                        pauseRateMsPerSec = (timeMs - prevMs) / deltaSec;
                    }

                    gcSnapshots.add(new GcSnapshot(gc.getName(), Math.max(0, count), Math.max(0, timeMs), "N/A", Math.max(0.0, pauseRateMsPerSec)));
                } catch (Exception ignored) {}
            }

            double gcCpuOverheadPct = 0.0;
            if (previousTotalGcTimeMs > 0 && totalGcTime >= previousTotalGcTimeMs) {
                long deltaGcTimeMs = totalGcTime - previousTotalGcTimeMs;
                gcCpuOverheadPct = Math.min(100.0, (deltaGcTimeMs / (double) deltaMs) * 100.0);
            }
            this.previousTotalGcTimeMs = totalGcTime;

            // Off-Heap Buffer Pools (Direct & Mapped Memory)
            List<BufferPoolSnapshot> bufferPoolSnapshots = new ArrayList<>();
            for (BufferPoolMXBean pool : bufferPoolMxBeans) {
                try {
                    bufferPoolSnapshots.add(new BufferPoolSnapshot(
                        pool.getName(),
                        pool.getCount(),
                        pool.getMemoryUsed(),
                        pool.getTotalCapacity()
                    ));
                } catch (Exception ignored) {}
            }

            // Deadlock Detection
            List<Long> deadlockedThreadIds = List.of();
            if (threadMxBean != null) {
                try {
                    long[] deadlocked = threadMxBean.findDeadlockedThreads();
                    if (deadlocked != null && deadlocked.length > 0) {
                        deadlockedThreadIds = Arrays.stream(deadlocked).boxed().toList();
                    }
                } catch (Exception ignored) {}
            }

            // Threads Telemetry
            List<ThreadSnapshot> threadList = new ArrayList<>();
            int platformCount = 0;
            int virtualCount = 0;

            if (threadMxBean != null) {
                long[] threadIds = threadMxBean.getAllThreadIds();
                platformCount = threadIds != null ? threadIds.length : 0;

                if (threadIds != null) {
                    ThreadInfo[] infos = threadMxBean.getThreadInfo(threadIds, 0);
                    for (int i = 0; i < threadIds.length; i++) {
                        long id = threadIds[i];
                        ThreadInfo info = infos[i];
                        if (info == null) continue;

                        double threadCpuPct = 0.0;
                        try {
                            long cpuTimeNs = threadMxBean.getThreadCpuTime(id);
                            Long prevNs = previousThreadCpuTimes.put(id, cpuTimeNs);
                            if (prevNs != null && cpuTimeNs > prevNs) {
                                double deltaCpuMs = (cpuTimeNs - prevNs) / 1_000_000.0;
                                threadCpuPct = Math.min(100.0, (deltaCpuMs / deltaMs) * 100.0);
                            }
                        } catch (Exception ignored) {}

                        boolean isVirtual = isVirtualThread(info, id);
                        if (isVirtual) virtualCount++;

                        threadList.add(new ThreadSnapshot(
                            id,
                            info.getThreadName(),
                            info.getThreadState(),
                            threadCpuPct,
                            isVirtual,
                            info.isDaemon(),
                            info.getLockName(),
                            info.getLockOwnerName(),
                            List.of()
                        ));
                    }
                }
            }

            // Runtime, System Specs & JIT Info
            JvmRuntimeInfo runtimeInfo = null;
            if (runtimeMxBean != null) {
                long loadedClasses = classLoadingMxBean != null ? classLoadingMxBean.getLoadedClassCount() : 0;
                long totalLoadedClasses = classLoadingMxBean != null ? classLoadingMxBean.getTotalLoadedClassCount() : 0;
                long unloadedClasses = classLoadingMxBean != null ? classLoadingMxBean.getUnloadedClassCount() : 0;

                String compilerName = compilationMxBean != null ? compilationMxBean.getName() : "HotSpot JIT";
                long compilationTime = compilationMxBean != null && compilationMxBean.isCompilationTimeMonitoringSupported()
                    ? compilationMxBean.getTotalCompilationTime() : 0;

                Map<String, String> sysProps = new HashMap<>();
                try {
                    if (isSelf) {
                        System.getProperties().forEach((k, v) -> sysProps.put(String.valueOf(k), String.valueOf(v)));
                    } else {
                        runtimeMxBean.getSystemProperties().forEach((k, v) -> sysProps.put(String.valueOf(k), String.valueOf(v)));
                    }
                } catch (Throwable ignored) {}

                runtimeInfo = new JvmRuntimeInfo(
                    runtimeMxBean.getVmName(),
                    runtimeMxBean.getVmVendor(),
                    runtimeMxBean.getVmVersion(),
                    runtimeMxBean.getSpecVersion(),
                    runtimeMxBean.getStartTime(),
                    runtimeMxBean.getUptime(),
                    runtimeMxBean.getInputArguments(),
                    sysProps,
                    loadedClasses,
                    totalLoadedClasses,
                    unloadedClasses,
                    compilerName,
                    compilationTime,
                    processors,
                    osMxBean != null ? osMxBean.getName() : System.getProperty("os.name"),
                    osMxBean != null ? osMxBean.getArch() : System.getProperty("os.arch"),
                    committedVirtualMem,
                    totalPhysMem,
                    freePhysMem,
                    totalSwap,
                    freeSwap,
                    openFds,
                    maxFds
                );
            }

            // Framework Telemetry (Spring Boot / Quarkus / HikariCP / Agroal MBeans)
            FrameworkInfo frameworkInfo = pollFrameworkTelemetry();

            // Update history buffers using Java 25 Gatherers.windowSliding
            updateHistory(processCpu, heapUsage.getUsed(), totalGcTime);

            this.lastPollLatencyMs = (System.nanoTime() - startNano) / 1_000_000;
            this.consecutiveFailures = 0;

            return new JvmMetricsSnapshot(
                timestamp,
                processCpu,
                systemCpu,
                heapUsage.getUsed(),
                heapUsage.getMax(),
                nonHeapUsage.getUsed(),
                poolSnapshots,
                gcSnapshots,
                platformCount,
                virtualCount,
                threadList,
                runtimeInfo,
                Math.max(0.0, allocationRateMbPerSec),
                bufferPoolSnapshots,
                deadlockedThreadIds,
                Math.max(0.0, gcCpuOverheadPct),
                frameworkInfo
            );
        } catch (Throwable t) {
            this.consecutiveFailures++;
            if (this.consecutiveFailures >= 3) {
                this.connected = false;
            }
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

    private FrameworkInfo pollFrameworkTelemetry() {
        if (mbeanConnection == null) return new FrameworkInfo("Vanilla", "JDK 25", 0, 0, 0, 0, 0, Map.of());

        String type = "Vanilla";
        String ver = "JDK 25";
        int activeDb = 0;
        int maxDb = 0;
        int waitingDb = 0;
        int activeHttp = 0;
        int maxHttp = 0;

        try {
            Set<ObjectName> springNames = mbeanConnection.queryNames(new ObjectName("org.springframework.boot:*"), null);
            Set<ObjectName> hikariNames = mbeanConnection.queryNames(new ObjectName("com.zaxxer.hikari:*"), null);

            if (!springNames.isEmpty() || !hikariNames.isEmpty()) {
                type = "Spring Boot";
                ver = "3.x";

                for (ObjectName name : hikariNames) {
                    try {
                        Object activeObj = mbeanConnection.getAttribute(name, "ActiveConnections");
                        Object waitingObj = mbeanConnection.getAttribute(name, "ThreadsAwaitingConnection");
                        Object totalObj = mbeanConnection.getAttribute(name, "TotalConnections");
                        if (activeObj instanceof Integer i) activeDb += i;
                        if (waitingObj instanceof Integer i) waitingDb += i;
                        if (totalObj instanceof Integer i) maxDb += i;
                    } catch (Throwable ignored) {}
                }
            }

            Set<ObjectName> quarkusNames = mbeanConnection.queryNames(new ObjectName("io.quarkus:*"), null);
            Set<ObjectName> agroalNames = mbeanConnection.queryNames(new ObjectName("io.agroal:*"), null);

            if (!quarkusNames.isEmpty() || !agroalNames.isEmpty()) {
                type = "Quarkus";
                ver = "3.x";

                for (ObjectName name : agroalNames) {
                    try {
                        Object activeObj = mbeanConnection.getAttribute(name, "ActiveCount");
                        Object maxObj = mbeanConnection.getAttribute(name, "MaxCapacity");
                        if (activeObj instanceof Long l) activeDb += l.intValue();
                        if (maxObj instanceof Integer i) maxDb += i;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return new FrameworkInfo(type, ver, activeDb, maxDb, waitingDb, activeHttp, maxHttp, Map.of());
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

    private boolean isVirtualThread(ThreadInfo info, long threadId) {
        if (info == null) return false;
        if (isSelf) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.threadId() == threadId) {
                    return t.isVirtual();
                }
            }
        }
        String name = info.getThreadName();
        return name != null && (name.contains("VirtualThread") || name.startsWith("virtual-") || name.startsWith("unparker-"));
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
