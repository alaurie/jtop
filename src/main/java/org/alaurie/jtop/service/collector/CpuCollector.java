package org.alaurie.jtop.service.collector;

import com.sun.management.OperatingSystemMXBean;

import java.lang.reflect.Method;

/// Collects CPU load, file-descriptor counts, and OS memory sizes from the OS MXBean.
public class CpuCollector {

    public record CpuResult(double processCpuPct, double systemCpuPct, long openFds, long maxFds,
            long committedVirtualMem, long totalPhysMem, long freePhysMem, long totalSwap, long freeSwap) {}

    private final OperatingSystemMXBean osMxBean;
    private long prevCpuNs = 0;
    private long prevNanoTime = System.nanoTime();

    public CpuCollector(OperatingSystemMXBean osMxBean) {
        this.osMxBean = osMxBean;
    }

    public CpuResult collect(long deltaWallNs) {
        if (osMxBean == null) {
            return new CpuResult(0.0, 0.0, 0, 0, 0, 0, 0, 0, 0);
        }

        // Process CPU %
        double processCpu = 0.0;
        long currentCpuNs = osMxBean.getProcessCpuTime();
        if (prevCpuNs > 0 && currentCpuNs >= prevCpuNs) {
            long deltaCpuNs = currentCpuNs - prevCpuNs;
            processCpu = Math.min(100.0, (deltaCpuNs / (double) deltaWallNs) * 100.0);
        } else {
            double rawCpu = osMxBean.getProcessCpuLoad();
            processCpu = rawCpu >= 0 ? rawCpu * 100.0 : 0.0;
        }
        this.prevCpuNs = currentCpuNs;

        // System CPU %
        double sysLoad = osMxBean.getCpuLoad();
        double systemCpu = sysLoad >= 0 ? sysLoad * 100.0 : 0.0;

        // File descriptors
        long openFds = 0;
        long maxFds = 0;
        try {
            Method mOpen = osMxBean.getClass().getMethod("getOpenFileDescriptorCount");
            openFds = (Long) mOpen.invoke(osMxBean);
        } catch (Throwable ignored) {}
        try {
            Method mMax = osMxBean.getClass().getMethod("getMaxFileDescriptorCount");
            maxFds = (Long) mMax.invoke(osMxBean);
        } catch (Throwable ignored) {}

        // OS memory sizes
        long committedVirtualMem = 0;
        long totalPhysMem = 0;
        long freePhysMem = 0;
        long totalSwap = 0;
        long freeSwap = 0;
        try {
            committedVirtualMem = osMxBean.getCommittedVirtualMemorySize();
            totalPhysMem = osMxBean.getTotalMemorySize();
            freePhysMem = osMxBean.getFreeMemorySize();
            totalSwap = osMxBean.getTotalSwapSpaceSize();
            freeSwap = osMxBean.getFreeSwapSpaceSize();
        } catch (Throwable ignored) {}

        return new CpuResult(processCpu, systemCpu, openFds, maxFds,
                committedVirtualMem, totalPhysMem, freePhysMem, totalSwap, freeSwap);
    }
}
