package org.alaurie.jtop.util;

/**
 * Provider for generating version-specific JDK & OpenJDK specification documentation links.
 */
public class JdkDocsLinkProvider {

    public static String parseMajorVersion(String javaVersion) {
        if (javaVersion == null || javaVersion.isBlank()) return "25";
        String clean = javaVersion.replaceAll("[^0-9.]", "");
        if (clean.contains(".")) {
            String first = clean.split("\\.")[0];
            if (first.equals("1")) {
                String[] parts = clean.split("\\.");
                if (parts.length > 1) return parts[1];
            }
            return first;
        }
        return clean.isBlank() ? "25" : clean;
    }

    public static String getJdkApiDocsUrl(String javaVersion, String className) {
        String major = parseMajorVersion(javaVersion);
        if (className == null || className.isBlank()) {
            return "https://docs.oracle.com/en/java/javase/" + major + "/docs/api/";
        }
        return "https://docs.oracle.com/en/java/javase/" + major + "/docs/api/java.base/" + className.replace('.', '/') + ".html";
    }

    public static String getGcTuningGuideUrl(String javaVersion) {
        String major = parseMajorVersion(javaVersion);
        return "https://docs.oracle.com/en/java/javase/" + major + "/gctuning/index.html";
    }

    public static String getLoomPinningJepUrl() {
        return "https://openjdk.org/jeps/491"; // JEP 491: Synchronize Virtual Threads without Pinning
    }

    public static String getStreamGatherersJepUrl() {
        return "https://openjdk.org/jeps/485"; // JEP 485: Stream Gatherers
    }
}
