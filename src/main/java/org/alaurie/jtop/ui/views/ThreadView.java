package org.alaurie.jtop.ui.views;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.style.Modifier;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.model.JvmProcess;
import org.alaurie.jtop.model.ThreadSnapshot;
import org.alaurie.jtop.service.JvmMonitorService;
import org.alaurie.jtop.ui.JTopApp;
import org.alaurie.jtop.ui.style.Theme;
import org.alaurie.jtop.ui.style.UiFormatter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
/// Detailed Btop-style Thread Inspector view with dynamic stack trace inspector modal filling 100% available height.
public class ThreadView implements View {

    private final TableState tableState = new TableState();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public enum SortField {
        CPU_DESC("CPU% ↓"),
        ID_ASC("ID ↑"),
        NAME_ASC("Name ↑"),
        STATE_ASC("State ↑");

        private final String label;
        SortField(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private SortField currentSort = SortField.CPU_DESC;
    private boolean showVirtualOnly = false;
    private boolean showStackTraceModal = false;

    public ThreadView() {
        tableState.select(0);
    }

    public void cycleSort() {
        SortField[] values = SortField.values();
        currentSort = values[(currentSort.ordinal() + 1) % values.length];
    }

    public void toggleVirtualOnly() {
        showVirtualOnly = !showVirtualOnly;
    }

    public void toggleStackTraceModal() {
        showStackTraceModal = !showStackTraceModal;
    }

    public boolean isShowingStackTraceModal() {
        return showStackTraceModal;
    }

    public void moveSelectionUp() {
        tableState.selectPrevious();
    }

    public void moveSelectionDown(int count) {
        tableState.selectNext(count);
    }

    public void render(Rect area, Buffer buffer, JvmProcess process, JvmMetricsSnapshot metrics, JvmMonitorService monitorService) {
        boolean hasDeadlock = metrics != null && metrics.hasDeadlock();

        List<Rect> chunks = Layout.vertical()
            .constraints(
                hasDeadlock ? Constraint.length(2) : Constraint.length(0),
                Constraint.fill()
            )
            .split(area);

        // Deadlock Alert Banner if present
        if (hasDeadlock) {
            renderDeadlockBanner(chunks.get(0), buffer, metrics.deadlockedThreadIds());
        }

        // Process threads list
        List<ThreadSnapshot> threads = metrics != null && metrics.threads() != null
            ? new ArrayList<>(metrics.threads())
            : new ArrayList<>();

        if (showVirtualOnly) {
            threads = threads.stream().filter(ThreadSnapshot::isVirtual).collect(Collectors.toList());
        }

        switch (currentSort) {
            case CPU_DESC -> threads.sort(Comparator.comparingDouble(ThreadSnapshot::cpuPercent).reversed());
            case ID_ASC -> threads.sort(Comparator.comparingLong(ThreadSnapshot::threadId));
            case NAME_ASC -> threads.sort(Comparator.comparing(ThreadSnapshot::threadName, String.CASE_INSENSITIVE_ORDER));
            case STATE_ASC -> threads.sort(Comparator.comparing(t -> t.state().name()));
        }

        if (tableState.selected() == null || tableState.selected() >= threads.size()) {
            tableState.select(Math.max(0, threads.size() - 1));
        }

        // If stack trace modal is open, split lower area into Table + Stack Trace Inspector
        List<Rect> mainAreaChunks = showStackTraceModal
            ? Layout.vertical().constraints(Constraint.percentage(55), Constraint.percentage(45)).split(chunks.get(1))
            : List.of(chunks.get(1));

        renderThreadsTable(mainAreaChunks.get(0), buffer, threads, metrics);

        if (showStackTraceModal && mainAreaChunks.size() > 1) {
            ThreadSnapshot selectedThread = getSelectedThread(threads);
            List<String> lazyStackTrace = (selectedThread != null && monitorService != null)
                ? monitorService.fetchThreadStackTrace(selectedThread.threadId())
                : List.of();
            renderStackTraceInspector(mainAreaChunks.get(1), buffer, selectedThread, lazyStackTrace);
        }
    }

    private void renderDeadlockBanner(Rect area, Buffer buffer, List<Long> deadlockedIds) {
        Block alertBlock = Block.builder()
            .title(" 🚨 DEADLOCK DETECTED ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.RED)
            .build();

        String msg = " CRITICAL: Deadlock detected in target JVM! Thread IDs: " + deadlockedIds;

        Paragraph para = Paragraph.builder()
            .text(msg)
            .block(alertBlock)
            .style(Style.create().bg(Color.RED).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
            .build();

        para.render(area, buffer);
    }

    private void renderThreadsTable(Rect area, Buffer buffer, List<ThreadSnapshot> threads, JvmMetricsSnapshot metrics) {
        List<Row> rows = new ArrayList<>();
        for (ThreadSnapshot t : threads) {
            String typeStr = t.isVirtual() ? "Virtual" : "Platform";
            Style typeStyle = t.isVirtual() ? Style.create().fg(Color.MAGENTA) : Style.create().fg(Color.CYAN);
            Style stateStyle = getThreadStateStyle(t.state());

            rows.add(Row.from(
                Cell.from(String.valueOf(t.threadId())),
                Cell.from(truncate(t.threadName(), 40)),
                Cell.from(t.state().name()).style(stateStyle),
                Cell.from(String.format("%.1f%%", t.cpuPercent())),
                Cell.from(typeStr).style(typeStyle)
            ));
        }

        Row headerRow = Row.from("TID", "Thread Name", "State", "CPU %", "Type")
            .style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD));

        String title = String.format(" active threads (%d)  |  Sort: [%s]  |  Virtual Only: [%s]  |  Stack Trace: [Enter]",
            threads.size(), currentSort.getLabel(), showVirtualOnly ? "ON" : "OFF");

        Block tableBlock = Block.builder()
            .title(" " + title + " ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.YELLOW)
            .build();

        Table table = Table.builder()
            .header(headerRow)
            .rows(rows)
            .widths(
                Constraint.length(10),
                Constraint.percentage(45),
                Constraint.percentage(20),
                Constraint.percentage(12),
                Constraint.percentage(13)
            )
            .highlightStyle(Style.create().bg(Color.BLUE).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
            .highlightSymbol("> ")
            .block(tableBlock)
            .build();

        table.render(area, buffer, tableState);
    }

    private void renderStackTraceInspector(Rect area, Buffer buffer, ThreadSnapshot thread, List<String> stackTrace) {
        Block block = Block.builder()
            .title(thread != null ? " thread stack trace & lock info: " + thread.threadName() + " (TID " + thread.threadId() + ") " : " thread stack trace ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.MAGENTA)
            .build();

        if (thread == null) {
            Paragraph.builder().text("No thread selected.").block(block).build().render(area, buffer);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("State: %s  |  Type: %s  |  Daemon: %s  |  CPU: %.1f%%\n",
            thread.state(), thread.isVirtual() ? "Virtual" : "Platform", thread.isDaemon() ? "Yes" : "No", thread.cpuPercent()));

        if (thread.lockName() != null && !thread.lockName().isBlank()) {
            sb.append(String.format("Locking on: %s", thread.lockName()));
            if (thread.lockOwnerName() != null && !thread.lockOwnerName().isBlank()) {
                sb.append(String.format("  (Held by: %s)", thread.lockOwnerName()));
            }
            sb.append("\n");
        }

        sb.append("Stack Trace Frames:\n");
        if (stackTrace != null && !stackTrace.isEmpty()) {
            // Dynamically calculate how many stack frames fit into 100% of the modal box height
            int maxFrames = Math.max(1, area.height() - 4);
            for (int i = 0; i < Math.min(maxFrames, stackTrace.size()); i++) {
                sb.append("  at ").append(stackTrace.get(i)).append("\n");
            }
        } else {
            sb.append("  (No stack trace available for this thread)\n");
        }

        Paragraph para = Paragraph.builder()
            .text(sb.toString())
            .block(block)
            .style(Style.create().fg(Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private ThreadSnapshot getSelectedThread(List<ThreadSnapshot> threads) {
        Integer sel = tableState.selected();
        if (sel != null && sel >= 0 && sel < threads.size()) {
            return threads.get(sel);
        }
        return null;
    }

    public int getFilteredThreadCount(JvmMetricsSnapshot metrics) {
        if (metrics == null || metrics.threads() == null) return 0;
        if (!showVirtualOnly) return metrics.threads().size();
        return (int) metrics.threads().stream().filter(ThreadSnapshot::isVirtual).count();
    }

    Style getThreadStateStyle(Thread.State state) {
        return UiFormatter.getThreadStateStyle(state, Theme.BTOP);
    }

    private String truncate(String val, int max) {
        return UiFormatter.truncate(val, max);
    }

    @Override
    public void render(Rect area, Buffer buffer, ViewContext context) {
        render(area, buffer, context.currentProcess(), context.currentMetrics(), context.monitorService());
    }

    @Override
    public boolean handleKey(KeyEvent keyEvent, ViewContext context, JTopApp app) {
        if (keyEvent.isKey(KeyCode.UP)) {
            moveSelectionUp();
            return true;
        } else if (keyEvent.isKey(KeyCode.DOWN)) {
            moveSelectionDown(getFilteredThreadCount(context.currentMetrics()));
            return true;
        } else if (keyEvent.isChar('s')) {
            cycleSort();
            return true;
        } else if (keyEvent.isChar('v')) {
            toggleVirtualOnly();
            return true;
        } else if (keyEvent.isKey(KeyCode.ENTER)) {
            toggleStackTraceModal();
            return true;
        }
        return false;
    }
}
