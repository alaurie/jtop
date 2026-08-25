package org.alaurie.jtop.model;

import java.util.Map;

/// Snapshot of Framework-aware telemetry (Spring Boot / Quarkus / Micronaut).
public record FrameworkInfo(
    String frameworkType,
    String version,
    int activeDbConnections,
    int maxDbConnections,
    int waitingDbThreads,
    int activeHttpThreads,
    int maxHttpThreads,
    Map<String, String> logLevels
) {
    public boolean isDetected() {
        return frameworkType != null && !frameworkType.equalsIgnoreCase("Vanilla") && !frameworkType.equalsIgnoreCase("None");
    }
}
