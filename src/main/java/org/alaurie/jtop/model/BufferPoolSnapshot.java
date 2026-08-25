package org.alaurie.jtop.model;

/// Snapshot of off-heap buffer pools (e.g. direct memory, mapped files).
public record BufferPoolSnapshot(
    String name,
    long count,
    long memoryUsed,
    long totalCapacity
) {
    public double usagePercentage() {
        return totalCapacity > 0 ? (double) memoryUsed / totalCapacity * 100.0 : 0.0;
    }
}
