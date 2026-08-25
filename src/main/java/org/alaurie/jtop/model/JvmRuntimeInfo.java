package org.alaurie.jtop.model;

import java.util.List;
import java.util.Map;

/// Snapshot of JVM runtime info, system specs, flags, class loading, file descriptors, vendor detection, and JIT compilation statistics.
public record JvmRuntimeInfo(
    String vmName,
    String vmVendor,
    String vmVersion,
    String javaVersion,
    long startTimeMs,
    long uptimeMs,
    List<String> inputArguments,
    Map<String, String> systemProperties,
    long loadedClassCount,
    long totalLoadedClassCount,
    long unloadedClassCount,
    String compilerName,
    long totalCompilationTimeMs,
    int availableProcessors,
    String osName,
    String osArch,
    long committedVirtualMemoryBytes,
    long totalPhysicalMemoryBytes,
    long freePhysicalMemoryBytes,
    long totalSwapSpaceBytes,
    long freeSwapSpaceBytes,
    long openFileDescriptors,
    long maxFileDescriptors
) {
    public enum VendorType {
        AZUL("Azul Prime/Zulu"),
        AWS_CORRETTO("AWS Corretto"),
        MICROSOFT("Microsoft OpenJDK"),
        GRAALVM("GraalVM"),
        OPENJDK("OpenJDK"),
        ORACLE("Oracle JDK");

        private final String displayName;
        VendorType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public VendorType detectVendorType() {
        String vendor = (vmVendor != null ? vmVendor : "").toLowerCase();
        String name = (vmName != null ? vmName : "").toLowerCase();

        if (vendor.contains("azul") || name.contains("zing") || name.contains("prime")) {
            return VendorType.AZUL;
        }
        if (vendor.contains("amazon") || name.contains("corretto")) {
            return VendorType.AWS_CORRETTO;
        }
        if (vendor.contains("microsoft")) {
            return VendorType.MICROSOFT;
        }
        if (vendor.contains("graal") || name.contains("graal")) {
            return VendorType.GRAALVM;
        }
        if (vendor.contains("oracle")) {
            return VendorType.ORACLE;
        }
        return VendorType.OPENJDK;
    }

    public String formattedUptime() {
        long seconds = uptimeMs / 1000;
        long days = seconds / (24 * 3600);
        seconds %= (24 * 3600);
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        if (days > 0) {
            return String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%02dh %02dm %02ds", hours, minutes, seconds);
        } else {
            return String.format("%02dm %02ds", minutes, seconds);
        }
    }
}
