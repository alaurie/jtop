package org.alaurie.jtop.service.collector;

import org.alaurie.jtop.model.MemoryPoolSnapshot;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/// Collects heap/non-heap usage, per-pool snapshots, and allocation rate.
public class MemoryCollector {

    public record MemResult(MemoryUsage heap, MemoryUsage nonHeap,
            List<MemoryPoolSnapshot> pools, double allocRateMbPerSec) {}

    private final MemoryMXBean memMxBean;
    private final List<MemoryPoolMXBean> poolMxBeans;
    private long prevEdenBytes = 0;

    public MemoryCollector(MemoryMXBean memMxBean, List<MemoryPoolMXBean> poolMxBeans) {
        this.memMxBean = memMxBean;
        this.poolMxBeans = poolMxBeans;
    }

    public MemResult collect(double deltaSec) {
        MemoryUsage heapUsage = memMxBean != null
                ? memMxBean.getHeapMemoryUsage()
                : new MemoryUsage(0, 0, 0, 0);
        MemoryUsage nonHeapUsage = memMxBean != null
                ? memMxBean.getNonHeapMemoryUsage()
                : new MemoryUsage(0, 0, 0, 0);

        long currentEdenBytes = 0;
        List<MemoryPoolSnapshot> poolSnapshots = new ArrayList<>();
        for (MemoryPoolMXBean pool : poolMxBeans) {
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
        if (prevEdenBytes > 0 && currentEdenBytes > prevEdenBytes) {
            long deltaEdenBytes = currentEdenBytes - prevEdenBytes;
            allocationRateMbPerSec = (deltaEdenBytes / 1024.0 / 1024.0) / deltaSec;
        }
        this.prevEdenBytes = currentEdenBytes;

        return new MemResult(heapUsage, nonHeapUsage, poolSnapshots, Math.max(0.0, allocationRateMbPerSec));
    }
}
