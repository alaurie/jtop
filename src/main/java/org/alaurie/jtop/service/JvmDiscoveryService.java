package org.alaurie.jtop.service;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.alaurie.jtop.model.JvmProcess;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/// Service for discovering running JVM processes on local, containerized (Linux/Docker/K8s), and cross-platform hosts.
public class JvmDiscoveryService {

    public List<JvmProcess> discoverProcesses() {
        Map<Long, JvmProcess> processMap = new HashMap<>();

        // 1. Discover via JDK VirtualMachine.list()
        try {
            List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();
            for (VirtualMachineDescriptor vmd : descriptors) {
                long pid = parsePid(vmd.id());
                if (pid <= 0) continue;

                String displayName = vmd.displayName();
                if (displayName == null || displayName.isBlank()) {
                    displayName = "JVM Process [" + pid + "]";
                }

                String mainClass = parseMainClass(displayName);
                String jvmVersion = System.getProperty("java.version", "Unknown");
                boolean attachable = checkAttachable(pid);
                String containerName = detectContainerName(pid);

                processMap.put(pid, new JvmProcess(pid, displayName, mainClass, jvmVersion, attachable, containerName));
            }
        } catch (Throwable e) {
            // Fallback if VirtualMachine.list() fails
        }

        // 2. Discover via ProcessHandle.allProcesses()
        try {
            long currentPid = ProcessHandle.current().pid();
            ProcessHandle.allProcesses().forEach(ph -> {
                long pid = ph.pid();
                if (processMap.containsKey(pid)) return;

                ph.info().command().ifPresent(cmd -> {
                    String lowerCmd = cmd.toLowerCase();
                    if (lowerCmd.endsWith("/java") || lowerCmd.endsWith("/java.exe") || lowerCmd.endsWith("\\java.exe") || lowerCmd.endsWith("/javaw.exe")) {
                        String cmdLine = ph.info().commandLine().orElse(cmd);
                        String displayName = parseDisplayNameFromCmd(cmdLine, pid);
                        String mainClass = parseMainClass(displayName);
                        boolean attachable = checkAttachable(pid);
                        String containerName = detectContainerName(pid);

                        processMap.put(pid, new JvmProcess(
                            pid,
                            displayName,
                            mainClass,
                            pid == currentPid ? System.getProperty("java.version") : "Java Process",
                            attachable,
                            containerName
                        ));
                    }
                });
            });
        } catch (Throwable e) {
            // Ignore inspection errors
        }

        return processMap.values().stream()
                .sorted(Comparator.comparingLong(JvmProcess::pid))
                .collect(Collectors.toList());
    }

    private String detectContainerName(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return "Host";
        }
        try {
            Path cgroupPath = Path.of("/proc/" + pid + "/cgroup");
            if (Files.exists(cgroupPath)) {
                List<String> lines = Files.readAllLines(cgroupPath);
                for (String line : lines) {
                    if (line.contains("docker-") || line.contains("docker/")) {
                        int idx = line.lastIndexOf("docker-");
                        if (idx >= 0) {
                            String id = line.substring(idx + 7);
                            int dotIdx = id.indexOf('.');
                            if (dotIdx > 0) id = id.substring(0, dotIdx);
                            return "docker:" + (id.length() > 12 ? id.substring(0, 12) : id);
                        }
                    }
                    if (line.contains("kubepods") || line.contains("pod")) {
                        int podIdx = line.indexOf("pod");
                        if (podIdx >= 0) {
                            String podPart = line.substring(podIdx);
                            int slashIdx = podPart.indexOf('/');
                            String podId = slashIdx > 0 ? podPart.substring(0, slashIdx) : podPart;
                            return "k8s:" + (podId.length() > 16 ? podId.substring(0, 16) : podId);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "Host";
    }

    private long parsePid(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String parseMainClass(String displayName) {
        if (displayName == null || displayName.isBlank()) return "Unknown";
        String firstToken = displayName.trim().split("\\s+")[0];
        if (firstToken.endsWith(".jar")) {
            int slashIdx = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
            return slashIdx >= 0 ? firstToken.substring(slashIdx + 1) : firstToken;
        }
        return firstToken;
    }

    private String parseDisplayNameFromCmd(String cmdLine, long pid) {
        if (cmdLine == null || cmdLine.isBlank()) {
            return "Java PID " + pid;
        }
        String[] tokens = cmdLine.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].endsWith(".jar")) {
                return tokens[i];
            }
            if (tokens[i].equals("-jar") && i + 1 < tokens.length) {
                return tokens[i + 1];
            }
            if (tokens[i].contains(".") && !tokens[i].startsWith("-") && !tokens[i].contains("/") && !tokens[i].contains("\\")) {
                return tokens[i];
            }
        }
        return "Java PID " + pid;
    }

    private boolean checkAttachable(long pid) {
        if (pid == ProcessHandle.current().pid()) {
            return true;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
