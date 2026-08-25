package org.alaurie.jtop.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class JTopLoggerTest {

    @Test
    void testJTopLoggerFileCreation() {
        JTopLogger.info("Integration test info log message");
        JTopLogger.debug("Integration test debug log message");
        JTopLogger.warn("Integration test warning", new RuntimeException("Test warning exception"));

        assertNotNull(JTopLogger.getLogFilePath(), "Log file path should be resolved");
        assertTrue(Files.exists(JTopLogger.getLogFilePath()), "jtop.log file should exist under ~/.jtop/");
    }
}
