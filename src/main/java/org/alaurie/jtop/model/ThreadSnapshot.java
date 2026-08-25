package org.alaurie.jtop.model;

import java.util.List;

/// Snapshot of an individual thread (platform or virtual) including stack trace frames and lock ownership.
public record ThreadSnapshot(
    long threadId,
    String threadName,
    Thread.State state,
    double cpuPercent,
    boolean isVirtual,
    boolean isDaemon,
    String lockName,
    String lockOwnerName,
    List<String> stackTrace
) {}
