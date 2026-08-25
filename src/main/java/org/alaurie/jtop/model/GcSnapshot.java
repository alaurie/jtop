package org.alaurie.jtop.model;

/// Snapshot of a garbage collector (e.g. G1 Young Generation, ZGC, Shenandoah).
public record GcSnapshot(
    String name,
    long collectionCount,
    long collectionTimeMs,
    String lastGcCause,
    double gcPauseRateMsPerSec
) {
    public double averagePauseMs() {
        return collectionCount > 0 ? (double) collectionTimeMs / collectionCount : 0.0;
    }
}
