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
        var processMap = new HashMap<Long, JvmProcess>();

        // 1. Discover via JDK VirtualMachine.list()
        try {
            var descriptors = VirtualMachine.list();
            for (var vmd : descriptors) {
                var pid = parsePid(vmd.id());
                if (pid <= 0) continue;

                var displayName = vmd.displayName();
                if (displayName == null || displayName.isBlank()) {
                    displayName = "JVM Process [" + pid + "]";
                }

                var mainClass = parseMainClass(displayName);
                var jvmVersion = System.getProperty("java.version", "Unknown");
                var attachable = checkAttachable(pid);
                var containerName = detectContainerName(pid);

                processMap.put(pid, new JvmProcess(pid, displayName, mainClass, jvmVersion, attachable, containerName));
            }
        } catch (Throwable e) {
            // Fallback if VirtualMachine.list() fails
        }

        // 2. Discover via ProcessHandle.allProcesses()
        try {
            var currentPid = ProcessHandle.current().pid();
            ProcessHandle.allProcesses().forEach(ph -> {
                var pid = ph.pid();
                if (processMap.containsKey(pid)) return;

                ph.info().command().ifPresent(cmd -> {
                    var lowerCmd = cmd.toLowerCase();
                    if (lowerCmd.endsWith("/java") || lowerCmd.endsWith("/java.exe") || lowerCmd.endsWith("\\java.exe") || lowerCmd.endsWith("/javaw.exe")) {
                        var cmdLine = ph.info().commandLine().orElse(cmd);
                        var displayName = parseDisplayNameFromCmd(cmdLine, pid);
                        var mainClass = parseMainClass(displayName);
                        var attachable = checkAttachable(pid);
                        var containerName = detectContainerName(pid);

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
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return "Host";
        }
        try {
            var cgroupPath = Path.of("/proc/" + pid + "/cgroup");
            if (Files.exists(cgroupPath)) {
                var lines = Files.readAllLines(cgroupPath);
                for (var line : lines) {
                    if (line.contains("docker-") || line.contains("docker/")) {
                        var idx = line.lastIndexOf("docker-");
                        if (idx >= 0) {
                            var id = line.substring(idx + 7);
                            var dotIdx = id.indexOf('.');
                            if (dotIdx > 0) id = id.substring(0, dotIdx);
                            return "docker:" + (id.length() > 12 ? id.substring(0, 12) : id);
                        }
                    }
                    if (line.contains("kubepods") || line.contains("pod")) {
                        var podIdx = line.indexOf("pod");
                        if (podIdx >= 0) {
                            var podPart = line.substring(podIdx);
                            var slashIdx = podPart.indexOf('/');
                            var podId = slashIdx > 0 ? podPart.substring(0, slashIdx) : podPart;
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
        var firstToken = displayName.trim().split("\\s+")[0];
        if (firstToken.endsWith(".jar")) {
            var slashIdx = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
            return slashIdx >= 0 ? firstToken.substring(slashIdx + 1) : firstToken;
        }
        return firstToken;
    }

    private String parseDisplayNameFromCmd(String cmdLine, long pid) {
        if (cmdLine == null || cmdLine.isBlank()) {
            return "Java PID " + pid;
        }
        var tokens = cmdLine.split("\\s+");
        for (var i = 0; i < tokens.length; i++) {
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
