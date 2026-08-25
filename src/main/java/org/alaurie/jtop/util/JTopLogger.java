package org.alaurie.jtop.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/// Centralized JDK System.Logger facade for jtop standardized logging.
/// Routes logs safely to `~/.jtop/jtop.log` during TUI execution to prevent terminal screen buffer corruption.
public class JTopLogger {

    private static final System.Logger LOGGER = System.getLogger("jtop");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static Path logFilePath;

    static {
        try {
            String userHome = System.getProperty("user.home", ".");
            Path logDir = Path.of(userHome, ".jtop");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            logFilePath = logDir.resolve("jtop.log");
        } catch (Throwable ignored) {}
    }

    public static void info(String message) {
        LOGGER.log(System.Logger.Level.INFO, message);
        writeToFile("INFO", message, null);
    }

    public static void warn(String message, Throwable t) {
        LOGGER.log(System.Logger.Level.WARNING, message, t);
        writeToFile("WARN", message, t);
    }

    public static void error(String message, Throwable t) {
        LOGGER.log(System.Logger.Level.ERROR, message, t);
        writeToFile("ERROR", message, t);
    }

    public static void debug(String message) {
        LOGGER.log(System.Logger.Level.DEBUG, message);
        writeToFile("DEBUG", message, null);
    }

    private static synchronized void writeToFile(String level, String msg, Throwable t) {
        if (logFilePath == null) return;
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFilePath.toFile(), true))) {
            String time = LocalDateTime.now().format(FMT);
            writer.println(String.format("[%s] [%s] %s", time, level, msg));
            if (t != null) {
                t.printStackTrace(writer);
            }
        } catch (Throwable ignored) {}
    }

    public static Path getLogFilePath() {
        return logFilePath;
    }
}
