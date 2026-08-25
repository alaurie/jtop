package org.alaurie.jtop.integration;

import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.service.JvmMonitorService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class DeadlockIntegrationTest {

    private final ReentrantLock lockA = new ReentrantLock();
    private final ReentrantLock lockB = new ReentrantLock();

    @Test
    void testDeadlockDetection() throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(2);

        Thread threadA = new Thread(() -> {
            try {
                if (lockA.tryLock(2, TimeUnit.SECONDS)) {
                    readyLatch.countDown();
                    Thread.sleep(100);
                    lockB.tryLock(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (lockA.isHeldByCurrentThread()) lockA.unlock();
                if (lockB.isHeldByCurrentThread()) lockB.unlock();
            }
        }, "Deadlock-Thread-A");

        Thread threadB = new Thread(() -> {
            try {
                if (lockB.tryLock(2, TimeUnit.SECONDS)) {
                    readyLatch.countDown();
                    Thread.sleep(100);
                    lockA.tryLock(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (lockB.isHeldByCurrentThread()) lockB.unlock();
                if (lockA.isHeldByCurrentThread()) lockA.unlock();
            }
        }, "Deadlock-Thread-B");

        threadA.setDaemon(true);
        threadB.setDaemon(true);
        threadA.start();
        threadB.start();

        readyLatch.await(2, TimeUnit.SECONDS);
        Thread.sleep(150);

        long currentPid = ProcessHandle.current().pid();
        try (JvmMonitorService monitorService = new JvmMonitorService(currentPid)) {
            monitorService.connect();
            JvmMetricsSnapshot snapshot = monitorService.pollSnapshot();
            assertNotNull(snapshot);
        } finally {
            threadA.interrupt();
            threadB.interrupt();
            threadA.join(1000);
            threadB.join(1000);
        }
    }
}
