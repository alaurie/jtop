package org.alaurie.jtop.model;

/// Immutable record representing a discovered local or containerized JVM process.
public record JvmProcess(
    long pid,
    String displayName,
    String mainClass,
    String jvmVersion,
    boolean isAttachable,
    String containerName
) {
    public JvmProcess(long pid, String displayName, String mainClass, String jvmVersion, boolean isAttachable) {
        this(pid, displayName, mainClass, jvmVersion, isAttachable, "Host");
    }
}
