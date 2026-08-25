package org.alaurie.jtop.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkDocsLinkProviderTest {

    @Test
    void testParseMajorVersion() {
        assertEquals("25", JdkDocsLinkProvider.parseMajorVersion("25.0.2+10-jvmci-b01"));
        assertEquals("21", JdkDocsLinkProvider.parseMajorVersion("21.0.1"));
        assertEquals("17", JdkDocsLinkProvider.parseMajorVersion("17"));
    }

    @Test
    void testJdkApiDocsUrlGeneration() {
        String url = JdkDocsLinkProvider.getJdkApiDocsUrl("25", "java.lang.management.MemoryMXBean");
        assertNotNull(url);
        assertTrue(url.contains("javase/25/docs/api/java.base/java/lang/management/MemoryMXBean.html"));
    }

    @Test
    void testJepUrls() {
        assertEquals("https://openjdk.org/jeps/491", JdkDocsLinkProvider.getLoomPinningJepUrl());
        assertEquals("https://openjdk.org/jeps/485", JdkDocsLinkProvider.getStreamGatherersJepUrl());
    }
}
