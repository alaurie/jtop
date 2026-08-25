package org.alaurie.jtop.service;

import org.alaurie.jtop.model.JvmProcess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JvmDiscoveryServiceTest {

    @Test
    void testDiscoverProcessesContainsCurrentProcess() {
        JvmDiscoveryService discoveryService = new JvmDiscoveryService();
        List<JvmProcess> processes = discoveryService.discoverProcesses();

        assertNotNull(processes);
        assertFalse(processes.isEmpty(), "Discovered JVM processes should not be empty");

        long currentPid = ProcessHandle.current().pid();
        boolean foundSelf = processes.stream().anyMatch(p -> p.pid() == currentPid);

        assertTrue(foundSelf, "Should discover current running JVM process PID: " + currentPid);

        JvmProcess self = processes.stream().filter(p -> p.pid() == currentPid).findFirst().orElseThrow();
        assertNotNull(self.containerName(), "Container name should default to Host or container ID");
    }
}
