package org.alaurie.jtop.model;

import java.util.List;

/// Complete metrics snapshot captured at a given timestamp.
public record JvmMetricsSnapshot(
    long timestamp,
    double processCpuLoad,
    double systemCpuLoad,
    long heapUsed,
    long heapMax,
    long nonHeapUsed,
    List<MemoryPoolSnapshot> memoryPools,
    List<GcSnapshot> gcSnapshots,
    int platformThreadCount,
    int virtualThreadCount,
    List<ThreadSnapshot> threads,
    JvmRuntimeInfo runtimeInfo,
    double heapAllocationRateMbPerSec,
    List<BufferPoolSnapshot> bufferPools,
    List<Long> deadlockedThreadIds,
    double gcCpuOverheadPct,
    FrameworkInfo frameworkInfo
) {
    public boolean hasDeadlock() {
        return deadlockedThreadIds != null && !deadlockedThreadIds.isEmpty();
    }
}
