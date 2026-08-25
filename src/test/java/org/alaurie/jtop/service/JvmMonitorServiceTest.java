package org.alaurie.jtop.service;

import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.model.MetricHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JvmMonitorServiceTest {

    @Test
    void testSelfMonitoringMetrics() throws Exception {
        long currentPid = ProcessHandle.current().pid();
        try (JvmMonitorService monitorService = new JvmMonitorService(currentPid)) {
            monitorService.connect();
            assertTrue(monitorService.isConnected(), "Monitor service should be connected to self PID");

            JvmMetricsSnapshot snapshot = monitorService.pollSnapshot();
            assertNotNull(snapshot);
            assertTrue(snapshot.timestamp() > 0);
            assertTrue(snapshot.heapUsed() > 0, "Heap used should be > 0");
            assertNotNull(snapshot.memoryPools());
            assertNotNull(snapshot.gcSnapshots());
            assertNotNull(snapshot.threads());
            assertNotNull(snapshot.bufferPools());
            assertNotNull(snapshot.runtimeInfo());
            assertTrue(snapshot.platformThreadCount() > 0, "Platform thread count should be > 0");
            assertFalse(snapshot.hasDeadlock(), "Self JVM should not have deadlocks by default");

            // Lazy Stack Trace Fetching test for first thread
            if (!snapshot.threads().isEmpty()) {
                long firstTid = snapshot.threads().get(0).threadId();
                List<String> stackTrace = monitorService.fetchThreadStackTrace(firstTid);
                assertNotNull(stackTrace);
            }

            // Second poll for CPU delta calculation
            Thread.sleep(100);
            JvmMetricsSnapshot secondSnapshot = monitorService.pollSnapshot();
            assertNotNull(secondSnapshot);

            MetricHistory history = monitorService.getMetricHistory();
            assertNotNull(history);
            assertNotNull(history.cpuHistory());
            assertFalse(history.cpuHistory().isEmpty());
        }
    }
}
