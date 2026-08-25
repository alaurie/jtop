package org.alaurie.jtop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JTopAppTest {

    @Test
    void testViewStateEnumMapping() {
        assertEquals(JTopApp.ViewState.PROCESS_LIST, JTopApp.ViewState.fromIndex(0));
        assertEquals(JTopApp.ViewState.DASHBOARD, JTopApp.ViewState.fromIndex(1));
        assertEquals(JTopApp.ViewState.THREADS, JTopApp.ViewState.fromIndex(2));
        assertEquals(JTopApp.ViewState.GC, JTopApp.ViewState.fromIndex(3));
        assertEquals(JTopApp.ViewState.JVM_INFO, JTopApp.ViewState.fromIndex(4));
        assertEquals(JTopApp.ViewState.JFR_EVENTS, JTopApp.ViewState.fromIndex(5));
        assertEquals(JTopApp.ViewState.FRAMEWORK, JTopApp.ViewState.fromIndex(6));
    }

    @Test
    void testAppLifecycle() {
        long selfPid = ProcessHandle.current().pid();
        try (JTopApp app = new JTopApp(selfPid)) {
            assertNotNull(app);
        }
    }
}
