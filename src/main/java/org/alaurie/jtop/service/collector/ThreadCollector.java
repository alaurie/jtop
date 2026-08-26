package org.alaurie.jtop.service.collector;

import org.alaurie.jtop.model.ThreadSnapshot;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Collects per-thread CPU%, virtual-thread counts, and deadlock detection.
public class ThreadCollector {

    public record ThreadResult(List<ThreadSnapshot> threads, int platformCount,
            int virtualCount, List<Long> deadlockedIds) {}

    private final ThreadMXBean threadMxBean;
    private final boolean isSelf;
    private final Map<Long, Long> prevThreadCpuTimes = new ConcurrentHashMap<>();

    public ThreadCollector(ThreadMXBean threadMxBean, boolean isSelf) {
        this.threadMxBean = threadMxBean;
        this.isSelf = isSelf;
    }

    public ThreadResult collect(long deltaMs) {
        if (threadMxBean == null) {
            return new ThreadResult(List.of(), 0, 0, List.of());
        }

        // Deadlock detection
        List<Long> deadlockedThreadIds = List.of();
        try {
            long[] deadlocked = threadMxBean.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                deadlockedThreadIds = Arrays.stream(deadlocked).boxed().toList();
            }
        } catch (Exception ignored) {}

        // Thread telemetry
        List<ThreadSnapshot> threadList = new ArrayList<>();
        int platformCount = 0;
        int virtualCount = 0;

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
                    Long prevNs = prevThreadCpuTimes.put(id, cpuTimeNs);
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

        return new ThreadResult(threadList, platformCount, virtualCount, deadlockedThreadIds);
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
}
