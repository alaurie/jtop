package org.alaurie.jtop.service.collector;

import com.sun.management.OperatingSystemMXBean;
import org.alaurie.jtop.model.JvmRuntimeInfo;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.Map;

/// Collects JVM runtime info: JVM identity, class loading, JIT compilation, and system properties.
public class RuntimeCollector {

    private final RuntimeMXBean runtimeMxBean;
    private final ClassLoadingMXBean classLoadingMxBean;
    private final CompilationMXBean compilationMxBean;
    private final OperatingSystemMXBean osMxBean;
    private final boolean isSelf;
    private final int processors;

    public RuntimeCollector(RuntimeMXBean runtimeMxBean, ClassLoadingMXBean classLoadingMxBean,
            CompilationMXBean compilationMxBean, OperatingSystemMXBean osMxBean,
            boolean isSelf, int processors) {
        this.runtimeMxBean = runtimeMxBean;
        this.classLoadingMxBean = classLoadingMxBean;
        this.compilationMxBean = compilationMxBean;
        this.osMxBean = osMxBean;
        this.isSelf = isSelf;
        this.processors = processors;
    }

    public JvmRuntimeInfo collect(long committedVirtualMem, long totalPhysMem, long freePhysMem,
            long totalSwap, long freeSwap, long openFds, long maxFds) {
        if (runtimeMxBean == null) return null;

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

        return new JvmRuntimeInfo(
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
}
