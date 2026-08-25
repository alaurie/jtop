package org.alaurie.jtop.integration;

import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.service.JvmMonitorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpringQuarkusContainerIntegrationTest {

    @Test
    void testContainerEngineDetection() {
        boolean engineAvailable = false;
        String engineName = "none";

        try {
            Process p = new ProcessBuilder("podman", "--version").start();
            if (p.waitFor() == 0) {
                engineAvailable = true;
                engineName = "podman";
            }
        } catch (Exception ignored) {}

        if (!engineAvailable) {
            try {
                Process p = new ProcessBuilder("docker", "--version").start();
                if (p.waitFor() == 0) {
                    engineAvailable = true;
                    engineName = "docker";
                }
            } catch (Exception ignored) {}
        }

        assertTrue(engineAvailable, "Container engine (Podman or Docker) should be available for containerized framework integration tests");
    }

    @Test
    void testRemoteJmxContainerConnection() throws Exception {
        long currentPid = ProcessHandle.current().pid();
        try (JvmMonitorService service = new JvmMonitorService(currentPid)) {
            service.connect();
            JvmMetricsSnapshot snapshot = service.pollSnapshot();
            assertNotNull(snapshot);
            assertNotNull(snapshot.frameworkInfo());
        }
    }
}
