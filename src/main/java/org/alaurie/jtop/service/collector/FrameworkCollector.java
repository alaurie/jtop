package org.alaurie.jtop.service.collector;

import org.alaurie.jtop.model.FrameworkInfo;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.Map;
import java.util.Set;

/// Detects and collects framework-level telemetry (Spring Boot, Quarkus, HikariCP, Agroal).
public class FrameworkCollector {

    private final MBeanServerConnection mbeanConnection;

    public FrameworkCollector(MBeanServerConnection mbeanConnection) {
        this.mbeanConnection = mbeanConnection;
    }

    public FrameworkInfo collect() {
        if (mbeanConnection == null) return new FrameworkInfo("Vanilla", "JDK 25", 0, 0, 0, 0, 0, Map.of());

        String type = "Vanilla";
        String ver = "JDK 25";
        int activeDb = 0;
        int maxDb = 0;
        int waitingDb = 0;
        int activeHttp = 0;
        int maxHttp = 0;

        try {
            Set<ObjectName> springNames = mbeanConnection.queryNames(new ObjectName("org.springframework.boot:*"), null);
            Set<ObjectName> hikariNames = mbeanConnection.queryNames(new ObjectName("com.zaxxer.hikari:*"), null);

            if (!springNames.isEmpty() || !hikariNames.isEmpty()) {
                type = "Spring Boot";
                ver = "3.x";

                for (ObjectName name : hikariNames) {
                    try {
                        Object activeObj = mbeanConnection.getAttribute(name, "ActiveConnections");
                        Object waitingObj = mbeanConnection.getAttribute(name, "ThreadsAwaitingConnection");
                        Object totalObj = mbeanConnection.getAttribute(name, "TotalConnections");
                        if (activeObj instanceof Integer i) activeDb += i;
                        if (waitingObj instanceof Integer i) waitingDb += i;
                        if (totalObj instanceof Integer i) maxDb += i;
                    } catch (Throwable ignored) {}
                }
            }

            Set<ObjectName> quarkusNames = mbeanConnection.queryNames(new ObjectName("io.quarkus:*"), null);
            Set<ObjectName> agroalNames = mbeanConnection.queryNames(new ObjectName("io.agroal:*"), null);

            if (!quarkusNames.isEmpty() || !agroalNames.isEmpty()) {
                type = "Quarkus";
                ver = "3.x";

                for (ObjectName name : agroalNames) {
                    try {
                        Object activeObj = mbeanConnection.getAttribute(name, "ActiveCount");
                        Object maxObj = mbeanConnection.getAttribute(name, "MaxCapacity");
                        if (activeObj instanceof Long l) activeDb += l.intValue();
                        if (maxObj instanceof Integer i) maxDb += i;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return new FrameworkInfo(type, ver, activeDb, maxDb, waitingDb, activeHttp, maxHttp, Map.of());
    }
}
