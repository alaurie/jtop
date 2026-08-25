package org.alaurie.jtop.integration;

import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.service.JvmMonitorService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcThrashingIntegrationTest {

    @Test
    void testAllocationRateAndGcMetrics() throws Exception {
        long currentPid = ProcessHandle.current().pid();
        try (JvmMonitorService monitorService = new JvmMonitorService(currentPid)) {
            monitorService.connect();

            // Initial poll
            JvmMetricsSnapshot initial = monitorService.pollSnapshot();
            assertNotNull(initial);

            // Allocate temporary objects to generate allocation deltas
            List<byte[]> garbage = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                garbage.add(new byte[1024 * 100]); // 50 MB total
            }

            Thread.sleep(150);

            // Second poll to calculate allocation rate
            JvmMetricsSnapshot second = monitorService.pollSnapshot();
            assertNotNull(second);

            assertTrue(second.heapAllocationRateMbPerSec() >= 0.0, "Allocation rate should be >= 0.0 MB/s");
            assertTrue(second.gcCpuOverheadPct() >= 0.0, "GC CPU overhead should be >= 0.0%");

            garbage.clear();
        }
    }
}
