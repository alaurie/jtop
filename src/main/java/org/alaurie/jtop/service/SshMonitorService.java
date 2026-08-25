package org.alaurie.jtop.service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Service for configuring remote SSH connection credentials, identity keys, and 1Password / OpenSSH agent sockets.
public class SshMonitorService {

    public record SshCredentials(
        String host,
        int port,
        String user,
        String keyPath,
        String agentSocketPath,
        boolean useAgent
    ) {}

    public static SshCredentials parseSshUrl(String sshUrl, String customKeyPath) {
        String cleaned = sshUrl.replace("ssh://", "");
        String user = System.getProperty("user.name", "root");
        String host = "localhost";
        int port = 22;

        if (cleaned.contains("@")) {
            String[] parts = cleaned.split("@", 2);
            user = parts[0];
            cleaned = parts[1];
        }

        if (cleaned.contains(":")) {
            String[] hostPort = cleaned.split(":", 2);
            host = hostPort[0];
            try {
                port = Integer.parseInt(hostPort[1]);
            } catch (NumberFormatException ignored) {}
        } else {
            host = cleaned;
        }

        String agentSocket = System.getenv("SSH_AUTH_SOCK");
        boolean useAgent = agentSocket != null && !agentSocket.isBlank() && Files.exists(Path.of(agentSocket));

        String keyPath = customKeyPath != null ? customKeyPath : discoverDefaultKeyPath();

        return new SshCredentials(host, port, user, keyPath, agentSocket, useAgent);
    }

    private static String discoverDefaultKeyPath() {
        String home = System.getProperty("user.home", "");
        List<String> candidates = List.of(
            home + "/.ssh/id_ed25519",
            home + "/.ssh/id_rsa",
            home + "/.ssh/id_ecdsa"
        );
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    public static List<String> getAvailableAuthMethods(SshCredentials creds) {
        List<String> methods = new ArrayList<>();
        if (creds.useAgent()) {
            methods.add("1Password / OpenSSH Agent (" + creds.agentSocketPath() + ")");
        }
        if (creds.keyPath() != null) {
            methods.add("Private Key (" + creds.keyPath() + ")");
        }
        methods.add("Interactive Password");
        return methods;
    }
}
