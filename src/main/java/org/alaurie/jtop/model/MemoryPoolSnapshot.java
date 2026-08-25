package org.alaurie.jtop.model;

/// Snapshot of a JVM memory pool (e.g. G1 Eden Space, Metaspace).
public record MemoryPoolSnapshot(
    String name,
    long used,
    long committed,
    long max
) {
    public double usagePercentage() {
        if (max <= 0) {
            return committed > 0 ? (double) used / committed * 100.0 : 0.0;
        }
        return (double) used / max * 100.0;
    }
}
