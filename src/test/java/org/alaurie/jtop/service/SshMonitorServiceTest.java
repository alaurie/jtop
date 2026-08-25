package org.alaurie.jtop.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SshMonitorServiceTest {

    @Test
    void testParseSshUrl() {
        SshMonitorService.SshCredentials creds = SshMonitorService.parseSshUrl("ssh://admin@10.0.1.50:2222", null);

        assertNotNull(creds);
        assertEquals("10.0.1.50", creds.host());
        assertEquals(2222, creds.port());
        assertEquals("admin", creds.user());

        List<String> methods = SshMonitorService.getAvailableAuthMethods(creds);
        assertNotNull(methods);
        assertFalse(methods.isEmpty(), "Available SSH auth methods should not be empty");
    }

    @Test
    void testParseSshUrlWithCustomKey() {
        SshMonitorService.SshCredentials creds = SshMonitorService.parseSshUrl("ssh://deploy@192.168.1.100", "/custom/path/key.pem");

        assertNotNull(creds);
        assertEquals("192.168.1.100", creds.host());
        assertEquals(22, creds.port());
        assertEquals("deploy", creds.user());
        assertEquals("/custom/path/key.pem", creds.keyPath());
    }

    @Test
    void testFallbackAuthMethodsWithoutAgent() {
        // Mock credentials without SSH agent
        SshMonitorService.SshCredentials creds = new SshMonitorService.SshCredentials(
            "10.0.1.50", 22, "testuser", null, null, false
        );

        List<String> methods = SshMonitorService.getAvailableAuthMethods(creds);
        assertNotNull(methods);
        assertTrue(methods.contains("Interactive Password"), "Should include Interactive Password fallback");
    }
}
