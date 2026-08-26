package org.alaurie.jtop.service.collector;

import org.alaurie.jtop.model.BufferPoolSnapshot;
import org.alaurie.jtop.model.GcSnapshot;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Collects GC pause snapshots, buffer pool sizes, and GC CPU overhead %.
public class GcCollector {

    public record GcResult(List<GcSnapshot> gcSnapshots, List<BufferPoolSnapshot> bufferPools,
            double gcCpuOverheadPct) {}

    private final List<GarbageCollectorMXBean> gcMxBeans;
    private final List<BufferPoolMXBean> bufferMxBeans;
    private final Map<String, Long> prevGcTimesMs = new ConcurrentHashMap<>();
    private long prevTotalGcTimeMs = 0;

    public GcCollector(List<GarbageCollectorMXBean> gcMxBeans, List<BufferPoolMXBean> bufferMxBeans) {
        this.gcMxBeans = gcMxBeans;
        this.bufferMxBeans = bufferMxBeans;
    }

    public GcResult collect(long deltaMs) {
        double deltaSec = deltaMs / 1000.0;

        List<GcSnapshot> gcSnapshots = new ArrayList<>();
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gc : gcMxBeans) {
            try {
                long count = gc.getCollectionCount();
                long timeMs = gc.getCollectionTime();
                if (timeMs > 0) totalGcTime += timeMs;

                double pauseRateMsPerSec = 0.0;
                Long prevMs = prevGcTimesMs.put(gc.getName(), timeMs);
                if (prevMs != null && timeMs > prevMs) {
                    pauseRateMsPerSec = (timeMs - prevMs) / deltaSec;
                }

                gcSnapshots.add(new GcSnapshot(gc.getName(), Math.max(0, count), Math.max(0, timeMs), "N/A", Math.max(0.0, pauseRateMsPerSec)));
            } catch (Exception ignored) {}
        }

        double gcCpuOverheadPct = 0.0;
        if (prevTotalGcTimeMs > 0 && totalGcTime >= prevTotalGcTimeMs) {
            long deltaGcTimeMs = totalGcTime - prevTotalGcTimeMs;
            gcCpuOverheadPct = Math.min(100.0, (deltaGcTimeMs / (double) deltaMs) * 100.0);
        }
        this.prevTotalGcTimeMs = totalGcTime;

        List<BufferPoolSnapshot> bufferPoolSnapshots = new ArrayList<>();
        for (BufferPoolMXBean pool : bufferMxBeans) {
            try {
                bufferPoolSnapshots.add(new BufferPoolSnapshot(
                        pool.getName(),
                        pool.getCount(),
                        pool.getMemoryUsed(),
                        pool.getTotalCapacity()
                ));
            } catch (Exception ignored) {}
        }

        return new GcResult(gcSnapshots, bufferPoolSnapshots, Math.max(0.0, gcCpuOverheadPct));
    }
}
