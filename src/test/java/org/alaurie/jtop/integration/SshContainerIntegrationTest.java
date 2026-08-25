package org.alaurie.jtop.integration;

import org.alaurie.jtop.service.SshMonitorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SshContainerIntegrationTest {

    @Test
    void testContainerEngineAvailability() {
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

        assertTrue(engineAvailable, "Either Podman or Docker container engine should be available in test environment");
    }

    @Test
    void testSshContainerCredentialsParsing() {
        SshMonitorService.SshCredentials creds = SshMonitorService.parseSshUrl("ssh://root@127.0.0.1:2222", null);

        assertNotNull(creds);
        assertEquals("127.0.0.1", creds.host());
        assertEquals(2222, creds.port());
        assertEquals("root", creds.user());
    }
}
