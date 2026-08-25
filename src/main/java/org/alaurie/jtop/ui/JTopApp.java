package org.alaurie.jtop.ui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.*;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.model.JvmProcess;
import org.alaurie.jtop.model.MetricHistory;
import org.alaurie.jtop.service.JvmDiscoveryService;
import org.alaurie.jtop.service.JvmMonitorService;
import org.alaurie.jtop.ui.style.Glyph;
import org.alaurie.jtop.ui.style.Theme;
import org.alaurie.jtop.ui.views.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main application TUI state controller rendering unified top bar, themes, framework telemetry, heap dumps, and live tuning modal.
 */
public class JTopApp implements EventHandler, Renderer, AutoCloseable {

    public enum ViewState {
        PROCESS_LIST(0),
        DASHBOARD(1),
        THREADS(2),
        GC(3),
        JVM_INFO(4),
        JFR_EVENTS(5),
        FRAMEWORK(6);

        private final int tabIndex;
        ViewState(int tabIndex) { this.tabIndex = tabIndex; }
        public int getTabIndex() { return tabIndex; }

        public static ViewState fromIndex(int index) {
            for (ViewState v : values()) {
                if (v.tabIndex == index) return v;
            }
            return DASHBOARD;
        }
    }

    private ViewState activeView = ViewState.DASHBOARD;
    private final TabsState tabsState = new TabsState(1);
    private final TableState tuningTableState = new TableState();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JvmDiscoveryService discoveryService = new JvmDiscoveryService();
    private JvmMonitorService monitorService;

    private final ProcessListView processListView = new ProcessListView();
    private final DashboardView dashboardView = new DashboardView();
    private final ThreadView threadView = new ThreadView();
    private final GcView gcView = new GcView();
    private final JvmInfoView jvmInfoView = new JvmInfoView();
    private final JfrEventsView jfrEventsView = new JfrEventsView();
    private final FrameworkView frameworkView = new FrameworkView();

    private JvmProcess currentProcess;
    private volatile JvmMetricsSnapshot currentMetrics;
    private volatile MetricHistory currentHistory;
    private Long initialPid;
    private String jmxUrl;
    private long pollIntervalMs = 250;
    private TuiRunner runner;
    private String connectionStatusMessage;
    private Theme currentTheme = Theme.BTOP;
    private boolean showTuningModal = false;

    public JTopApp(Long initialPid) {
        this(initialPid, null, 250, Theme.BTOP, false);
    }

    public JTopApp(Long initialPid, long pollIntervalMs) {
        this(initialPid, null, pollIntervalMs, Theme.BTOP, false);
    }

    public JTopApp(Long initialPid, String jmxUrl, long pollIntervalMs, Theme theme, boolean asciiOnly) {
        this.initialPid = initialPid;
        this.jmxUrl = jmxUrl;
        this.pollIntervalMs = Math.max(100, pollIntervalMs);
        this.currentTheme = theme != null ? theme : Theme.BTOP;
        Glyph.setAsciiOnly(asciiOnly);

        refreshProcessList();

        if (jmxUrl != null && !jmxUrl.isBlank()) {
            attachToJmxUrl(jmxUrl);
        } else if (initialPid != null && initialPid > 0) {
            attachToPid(initialPid);
        } else {
            long selfPid = ProcessHandle.current().pid();
            attachToPid(selfPid);
        }
    }

    public void refreshProcessList() {
        List<JvmProcess> processes = discoveryService.discoverProcesses();
        processListView.updateProcesses(processes);
        if (initialPid != null && currentProcess == null && (jmxUrl == null || jmxUrl.isBlank())) {
            processes.stream()
                .filter(p -> p.pid() == initialPid)
                .findFirst()
                .ifPresent(this::attachToProcess);
        }
    }

    public synchronized void attachToJmxUrl(String urlStr) {
        if (monitorService != null) {
            monitorService.close();
            monitorService = null;
        }
        this.currentProcess = new JvmProcess(-1, "Remote JMX: " + urlStr, "Remote JVM", "Java", true);
        try {
            this.monitorService = new JvmMonitorService(urlStr);
            this.monitorService.connect();
            this.connectionStatusMessage = null;
            this.monitorService.startPolling(pollIntervalMs, snapshot -> {
                this.currentMetrics = snapshot;
                if (monitorService != null) {
                    this.currentHistory = monitorService.getMetricHistory();
                }
                if (this.runner != null) {
                    this.runner.runLater(() -> {});
                }
            });
            switchView(ViewState.DASHBOARD);
        } catch (Exception e) {
            this.connectionStatusMessage = "Failed to connect to JMX " + urlStr + ": " + e.getMessage();
            switchView(ViewState.PROCESS_LIST);
        }
    }

    public synchronized void attachToPid(long pid) {
        JvmProcess proc = discoveryService.discoverProcesses().stream()
            .filter(p -> p.pid() == pid)
            .findFirst()
            .orElse(new JvmProcess(pid, "PID " + pid, "Unknown", "Java", true));
        attachToProcess(proc);
    }

    public synchronized void attachToProcess(JvmProcess process) {
        if (monitorService != null) {
            monitorService.close();
            monitorService = null;
        }
        this.currentProcess = process;
        try {
            this.monitorService = new JvmMonitorService(process.pid());
            this.monitorService.connect();
            this.connectionStatusMessage = null;
            this.monitorService.startPolling(pollIntervalMs, snapshot -> {
                this.currentMetrics = snapshot;
                if (monitorService != null) {
                    this.currentHistory = monitorService.getMetricHistory();
                }
                if (this.runner != null) {
                    this.runner.runLater(() -> {});
                }
            });
            switchView(ViewState.DASHBOARD);
        } catch (Exception e) {
            this.connectionStatusMessage = "Failed to attach to PID " + process.pid() + ": " + e.getMessage();
            switchView(ViewState.PROCESS_LIST);
        }
    }

    private void switchView(ViewState viewState) {
        this.activeView = viewState;
        this.tabsState.select(viewState.getTabIndex());
        if (viewState == ViewState.PROCESS_LIST) {
            refreshProcessList();
        }
    }

    @Override
    public boolean handle(Event event, TuiRunner runner) {
        this.runner = runner;

        if (monitorService != null && !monitorService.isConnected() && activeView != ViewState.PROCESS_LIST) {
            long lostPid = currentProcess != null ? currentProcess.pid() : 0;
            this.connectionStatusMessage = "Target JVM PID " + lostPid + " terminated or connection lost.";
            if (monitorService != null) {
                monitorService.close();
                monitorService = null;
            }
            switchView(ViewState.PROCESS_LIST);
            return true;
        }

        if (event instanceof TickEvent) {
            return true;
        }

        if (!(event instanceof KeyEvent keyEvent)) {
            return true;
        }

        // Global Exit shortcuts
        if (keyEvent.isCtrlC() || (!processListView.isFiltering() && keyEvent.isChar('q'))) {
            runner.quit();
            return true;
        }

        // Global Theme Switcher Shortcut ('t' key)
        if (!processListView.isFiltering() && keyEvent.isChar('t')) {
            currentTheme = currentTheme.next();
            return true;
        }

        // Global Live Heap Dump Shortcut ('h' key)
        if (!processListView.isFiltering() && (keyEvent.isChar('h') || keyEvent.isChar('H'))) {
            if (monitorService != null) {
                String dumpRes = monitorService.generateHeapDump(null);
                this.connectionStatusMessage = "Heap dump status: " + dumpRes;
            }
            return true;
        }

        // Global Manageable VM Options Tuning Modal Shortcut ('m' key)
        if (!processListView.isFiltering() && (keyEvent.isChar('m') || keyEvent.isChar('M'))) {
            showTuningModal = !showTuningModal;
            return true;
        }

        // Top Menu Bar Tab Switch Controls (Left/Right, 1-7, F1-F7)
        if (!processListView.isFiltering()) {
            if (keyEvent.isKey(KeyCode.LEFT)) {
                tabsState.selectPrevious(7);
                this.activeView = ViewState.fromIndex(tabsState.selected() != null ? tabsState.selected() : 0);
                return true;
            } else if (keyEvent.isKey(KeyCode.RIGHT)) {
                tabsState.selectNext(7);
                this.activeView = ViewState.fromIndex(tabsState.selected() != null ? tabsState.selected() : 0);
                return true;
            } else if (keyEvent.isKey(KeyCode.F1) || keyEvent.isChar('1')) {
                switchView(ViewState.PROCESS_LIST);
                return true;
            } else if (keyEvent.isKey(KeyCode.F2) || keyEvent.isChar('2')) {
                switchView(ViewState.DASHBOARD);
                return true;
            } else if (keyEvent.isKey(KeyCode.F3) || keyEvent.isChar('3')) {
                switchView(ViewState.THREADS);
                return true;
            } else if (keyEvent.isKey(KeyCode.F4) || keyEvent.isChar('4')) {
                switchView(ViewState.GC);
                return true;
            } else if (keyEvent.isKey(KeyCode.F5) || keyEvent.isChar('5')) {
                switchView(ViewState.JVM_INFO);
                return true;
            } else if (keyEvent.isKey(KeyCode.F6) || keyEvent.isChar('6')) {
                switchView(ViewState.JFR_EVENTS);
                return true;
            } else if (keyEvent.isKey(KeyCode.F7) || keyEvent.isChar('7')) {
                switchView(ViewState.FRAMEWORK);
                return true;
            }
        }

        // View-Specific Key Handling
        switch (activeView) {
            case PROCESS_LIST -> handleProcessListKeys(keyEvent);
            case DASHBOARD -> handleDashboardKeys(keyEvent);
            case THREADS -> handleThreadKeys(keyEvent);
            case GC -> handleGcKeys(keyEvent);
            case JVM_INFO -> handleJvmInfoKeys(keyEvent);
            case JFR_EVENTS -> handleJfrEventsKeys(keyEvent);
            case FRAMEWORK -> handleFrameworkKeys(keyEvent);
        }

        return true;
    }

    private void handleProcessListKeys(KeyEvent keyEvent) {
        if (processListView.isFiltering()) {
            if (keyEvent.isKey(KeyCode.ESCAPE)) {
                processListView.clearFilter();
            } else if (keyEvent.isKey(KeyCode.BACKSPACE)) {
                processListView.backspaceFilter();
            } else if (keyEvent.isKey(KeyCode.ENTER)) {
                processListView.setFiltering(false);
            } else if (keyEvent.code() == KeyCode.CHAR && keyEvent.codePoint() != 0) {
                processListView.appendFilterChar((char) keyEvent.codePoint());
            }
            return;
        }

        if (keyEvent.isChar('/')) {
            processListView.setFiltering(true);
        } else if (keyEvent.isKey(KeyCode.UP)) {
            processListView.moveSelectionUp();
        } else if (keyEvent.isKey(KeyCode.DOWN)) {
            processListView.moveSelectionDown();
        } else if (keyEvent.isKey(KeyCode.ENTER)) {
            JvmProcess selected = processListView.getSelectedProcess();
            if (selected != null) {
                attachToProcess(selected);
            }
        }
    }

    private void handleDashboardKeys(KeyEvent keyEvent) {
        if (keyEvent.isChar('s')) {
            switchView(ViewState.THREADS);
            threadView.cycleSort();
        } else if (keyEvent.isChar('v')) {
            switchView(ViewState.THREADS);
            threadView.toggleVirtualOnly();
        }
    }

    private void handleThreadKeys(KeyEvent keyEvent) {
        if (keyEvent.isKey(KeyCode.UP)) {
            threadView.moveSelectionUp();
        } else if (keyEvent.isKey(KeyCode.DOWN)) {
            threadView.moveSelectionDown(threadView.getFilteredThreadCount(currentMetrics));
        } else if (keyEvent.isChar('s')) {
            threadView.cycleSort();
        } else if (keyEvent.isChar('v')) {
            threadView.toggleVirtualOnly();
        } else if (keyEvent.isKey(KeyCode.ENTER)) {
            threadView.toggleStackTraceModal();
        }
    }

    private void handleGcKeys(KeyEvent keyEvent) {
        if (keyEvent.isChar('g') || keyEvent.isChar('G')) {
            if (monitorService != null) {
                monitorService.triggerGc();
            }
        } else if (keyEvent.isChar('d') || keyEvent.isChar('D')) {
            gcView.toggleHeapDumpOnOom();
            if (monitorService != null) {
                monitorService.setVmOption("HeapDumpOnOutOfMemoryError", gcView.isHeapDumpOnOom() ? "true" : "false");
            }
        } else if (keyEvent.isChar('+') || keyEvent.isChar('=')) {
            gcView.adjustMaxHeapFreeRatio(5);
            if (monitorService != null) {
                monitorService.setVmOption("MaxHeapFreeRatio", String.valueOf(gcView.getMaxHeapFreeRatio()));
            }
        } else if (keyEvent.isChar('-') || keyEvent.isChar('_')) {
            gcView.adjustMaxHeapFreeRatio(-5);
            if (monitorService != null) {
                monitorService.setVmOption("MaxHeapFreeRatio", String.valueOf(gcView.getMaxHeapFreeRatio()));
            }
        }
    }

    private void handleJvmInfoKeys(KeyEvent keyEvent) {
        // JVM Info view key handling
    }

    private void handleJfrEventsKeys(KeyEvent keyEvent) {
        if (keyEvent.isKey(KeyCode.UP)) {
            jfrEventsView.moveSelectionUp();
        } else if (keyEvent.isKey(KeyCode.DOWN)) {
            jfrEventsView.moveSelectionDown(jfrEventsView.getFilteredEventCount());
        } else if (keyEvent.isChar('f') || keyEvent.isChar('F')) {
            jfrEventsView.cycleCategory();
        } else if (keyEvent.isChar('s') || keyEvent.isChar('S')) {
            jfrEventsView.toggleSortDuration();
        } else if (keyEvent.isKey(KeyCode.ENTER)) {
            jfrEventsView.toggleEventModal();
        }
    }

    private void handleFrameworkKeys(KeyEvent keyEvent) {
        if (keyEvent.isKey(KeyCode.UP)) {
            frameworkView.moveSelectionUp();
        } else if (keyEvent.isKey(KeyCode.DOWN)) {
            frameworkView.moveSelectionDown();
        } else if (keyEvent.isKey(KeyCode.ENTER)) {
            String logRes = frameworkView.cycleSelectedLogLevel();
            if (logRes != null) {
                this.connectionStatusMessage = "Updated logger level: " + logRes;
            }
        }
    }

    @Override
    public void render(Frame frame) {
        if (frame.height() < 10 || frame.width() < 40) {
            renderSmallTerminalWarning(frame.area(), frame.buffer());
            return;
        }

        List<Rect> chunks = Layout.vertical()
            .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(1))
            .split(frame.area());

        // Top Bar Header
        renderTopBarHeader(chunks.get(0), frame.buffer());

        // Middle Main Content Area
        if (showTuningModal) {
            List<Rect> modalChunks = Layout.vertical()
                .constraints(Constraint.percentage(50), Constraint.percentage(50))
                .split(chunks.get(1));

            renderActiveViewContent(modalChunks.get(0), frame.buffer());
            renderTuningModal(modalChunks.get(1), frame.buffer());
        } else {
            renderActiveViewContent(chunks.get(1), frame.buffer());
        }

        // View-Specific Bottom Action Control Bar
        renderBottomControlBar(chunks.get(2), frame.buffer());
    }

    private void renderActiveViewContent(Rect area, Buffer buffer) {
        switch (activeView) {
            case PROCESS_LIST -> processListView.render(area, buffer);
            case DASHBOARD -> dashboardView.render(area, buffer, currentProcess, currentMetrics, currentHistory, currentTheme);
            case THREADS -> threadView.render(area, buffer, currentProcess, currentMetrics, monitorService);
            case GC -> gcView.render(area, buffer, currentProcess, currentMetrics, currentHistory);
            case JVM_INFO -> jvmInfoView.render(area, buffer, currentProcess, currentMetrics);
            case JFR_EVENTS -> jfrEventsView.render(area, buffer, currentProcess, currentMetrics);
            case FRAMEWORK -> frameworkView.render(area, buffer, currentProcess, currentMetrics, currentTheme);
        }
    }

    private void renderTuningModal(Rect area, Buffer buffer) {
        Block block = Block.builder()
            .title(" 🛠️ hotspot diagnostic vm options tuning modal ")
            .titleBottom(" Press [m] or [ESC] to close ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.YELLOW)
            .build();

        Map<String, String> vmOptions = monitorService != null ? monitorService.getManageableVmOptions() : Map.of();

        List<Row> rows = new ArrayList<>();
        vmOptions.forEach((k, v) -> rows.add(Row.from(Cell.from(k), Cell.from(v))));

        Table table = Table.builder()
            .header(Row.from("Manageable VM Option", "Current Live Value").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(rows)
            .widths(Constraint.percentage(65), Constraint.percentage(35))
            .block(block)
            .build();

        table.render(area, buffer, tuningTableState);
    }

    private void renderSmallTerminalWarning(Rect area, Buffer buffer) {
        Block block = Block.builder()
            .title(" jtop ─ terminal size warning ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.RED)
            .build();

        String msg = String.format(" Terminal size too small (%dx%d). Please resize terminal to at least 40x10.", area.width(), area.height());

        Paragraph para = Paragraph.builder()
            .text(msg)
            .block(block)
            .style(Style.create().bg(Color.RED).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
            .build();

        para.render(area, buffer);
    }

    private void renderTopBarHeader(Rect area, Buffer buffer) {
        String timeStr = LocalTime.now().format(TIME_FMT);
        String procStr = currentProcess != null
            ? (currentProcess.pid() > 0 ? String.format("PID %d (%s)", currentProcess.pid(), truncate(currentProcess.mainClass(), 25)) : currentProcess.displayName())
            : "No Target PID";

        long pollMs = monitorService != null ? monitorService.getLastPollLatencyMs() : 0;
        String title = String.format(" jtop - %s - %s (Poll: %dms) ", procStr, timeStr, pollMs);

        Block topBlock = Block.builder()
            .title(title)
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(currentTheme.getHeaderColor())
            .build();

        String fwBadge = (currentMetrics != null && currentMetrics.frameworkInfo() != null && currentMetrics.frameworkInfo().isDetected())
            ? " 7: " + currentMetrics.frameworkInfo().frameworkType() + " "
            : " 7: Framework ";

        Tabs tabsWidget = Tabs.builder()
            .titles(" 1: Selector ", " 2: Dashboard ", " 3: Threads ", " 4: GC & Memory ", " 5: JVM Flags & JIT ", " 6: JFR Events ", fwBadge)
            .divider(" │ ")
            .style(Style.create().fg(Color.GRAY))
            .highlightStyle(Style.create().fg(Color.BRIGHT_WHITE).bg(currentTheme.getHeaderColor()).addModifier(Modifier.BOLD))
            .block(topBlock)
            .build();

        tabsWidget.render(area, buffer, tabsState);
    }

    private void renderBottomControlBar(Rect area, Buffer buffer) {
        if (connectionStatusMessage != null) {
            Paragraph para = Paragraph.builder()
                .text(" " + Glyph.alertIcon() + " " + truncate(connectionStatusMessage, 80))
                .style(Style.create().bg(Color.RED).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
                .build();
            para.render(area, buffer);
            return;
        }

        String viewActions = switch (activeView) {
            case PROCESS_LIST -> "  [/] Search  [Enter] Attach  [↑/↓] Navigate";
            case DASHBOARD -> String.format("  [t] Theme: %s  [h] Heap Dump  [m] Tune  [s] Sort", currentTheme.getName());
            case THREADS -> String.format("  [t] Theme: %s  [h] Heap Dump  [m] Tune  [Enter] Stack", currentTheme.getName());
            case GC -> String.format("  [t] Theme: %s  [h] Heap Dump  [g] System.gc()", currentTheme.getName());
            case JVM_INFO -> String.format("  [t] Theme: %s  [h] Heap Dump  [m] Tune", currentTheme.getName());
            case JFR_EVENTS -> String.format("  [t] Theme: %s  [h] Heap Dump  [Enter] Stack  [f] Category", currentTheme.getName());
            case FRAMEWORK -> String.format("  [t] Theme: %s  [h] Heap Dump", currentTheme.getName());
        };

        String footerText = " [1-7/F1-F7] Tabs  [←/→] Switch Tab" + viewActions + "  [q] Quit";

        Paragraph para = Paragraph.builder()
            .text(footerText)
            .style(Style.create().bg(currentTheme.getHeaderColor()).fg(Color.BLACK).addModifier(Modifier.BOLD))
            .build();
        para.render(area, buffer);
    }

    private String truncate(String val, int max) {
        if (val == null) return "";
        if (val.length() <= max) return val;
        return val.substring(0, max - 3) + "...";
    }

    @Override
    public void close() {
        if (monitorService != null) {
            monitorService.close();
            monitorService = null;
        }
    }
}
