package org.alaurie.jtop.integration;

import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.service.JvmMonitorService;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

class FrameworkIntegrationTest {

    public interface MockHikariPoolMXBean {
        int getActiveConnections();
        int getThreadsAwaitingConnection();
        int getTotalConnections();
    }

    public static class MockHikariPool implements MockHikariPoolMXBean {
        @Override public int getActiveConnections() { return 5; }
        @Override public int getThreadsAwaitingConnection() { return 0; }
        @Override public int getTotalConnections() { return 20; }
    }

    @Test
    void testSpringHikariFrameworkDetection() throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.zaxxer.hikari:type=Pool (testPool)");

        if (!mbs.isRegistered(name)) {
            mbs.registerMBean(new MockHikariPool(), name);
        }

        long currentPid = ProcessHandle.current().pid();
        try (JvmMonitorService monitorService = new JvmMonitorService(currentPid)) {
            monitorService.connect();
            JvmMetricsSnapshot snapshot = monitorService.pollSnapshot();

            assertNotNull(snapshot.frameworkInfo());
            assertEquals("Spring Boot", snapshot.frameworkInfo().frameworkType());
            assertEquals(5, snapshot.frameworkInfo().activeDbConnections());
            assertEquals(20, snapshot.frameworkInfo().maxDbConnections());
        } finally {
            if (mbs.isRegistered(name)) {
                mbs.unregisterMBean(name);
            }
        }
    }
}
