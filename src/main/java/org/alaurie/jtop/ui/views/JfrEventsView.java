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
import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.model.JvmProcess;
import org.alaurie.jtop.ui.JTopApp;
import org.alaurie.jtop.ui.style.UiFormatter;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Interactive JDK Flight Recorder (JFR) Event Stream view implementing the deep View seam.
 */
public class JfrEventsView implements View {

    public record JfrEventItem(
        long eventId,
        String timestamp,
        String eventType,
        String category,
        String threadName,
        long durationMs,
        String detail,
        List<String> stackTrace
    ) {}

    public enum EventCategory {
        ALL("ALL"),
        SLOW_IO("SLOW I/O"),
        LOCK_PARKS("LOCK PARKS"),
        GC_PAUSES("GC PAUSES"),
        ALLOCATIONS("ALLOCATIONS");

        private final String label;
        EventCategory(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final TableState tableState = new TableState();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final List<JfrEventItem> eventBuffer = new ArrayList<>();

    private EventCategory currentCategory = EventCategory.ALL;
    private boolean sortByDuration = true;
    private boolean showEventModal = false;

    public JfrEventsView() {
        tableState.select(0);
        seedSampleEvents();
    }

    private void seedSampleEvents() {
        String t = LocalTime.now().format(TIME_FMT);
        eventBuffer.add(new JfrEventItem(101, t, "jdk.SocketRead", "SLOW I/O", "http-nio-8080-exec-1", 45, "Read 4,096 B from 10.0.1.50:5432", List.of("at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:325)", "at org.example.OrderDao.fetch(OrderDao.java:88)")));
        eventBuffer.add(new JfrEventItem(102, t, "jdk.GarbageCollection", "GC PAUSES", "G1 Young", 18, "G1 Evacuation Pause (128 MB -> 12 MB)", List.of("at java.lang.System.gc(Native Method)")));
        eventBuffer.add(new JfrEventItem(103, t, "jdk.ThreadPark", "LOCK PARKS", "http-nio-8080-exec-2", 82, "Parked on Lock java.lang.Object@1a2b3c", List.of("at java.util.concurrent.locks.LockSupport.park(LockSupport.java:221)", "at org.example.OrderService.checkout(OrderService.java:104)")));
        eventBuffer.add(new JfrEventItem(104, t, "jdk.FileWrite", "SLOW I/O", "logging-appender-1", 12, "Wrote 1,024 B to /var/log/app.log", List.of("at java.io.FileOutputStream.write(FileOutputStream.java:310)")));
        eventBuffer.add(new JfrEventItem(105, t, "jdk.ObjectAllocation", "ALLOCATIONS", "worker-1", 5, "Allocated 512 KB in TLAB", List.of("at org.example.DataProcessor.parse(DataProcessor.java:42)")));
    }

    public void cycleCategory() {
        EventCategory[] values = EventCategory.values();
        currentCategory = values[(currentCategory.ordinal() + 1) % values.length];
    }

    public void toggleSortDuration() {
        sortByDuration = !sortByDuration;
    }

    public void toggleEventModal() {
        showEventModal = !showEventModal;
    }

    public void moveSelectionUp() {
        tableState.selectPrevious();
    }

    public void moveSelectionDown(int count) {
        tableState.selectNext(count);
    }

    @Override
    public void render(Rect area, Buffer buffer, ViewContext ctx) {
        render(area, buffer, ctx.currentProcess(), ctx.currentMetrics());
    }

    @Override
    public boolean handleKey(KeyEvent keyEvent, ViewContext ctx, JTopApp app) {
        if (keyEvent.isKey(KeyCode.UP)) {
            moveSelectionUp();
        } else if (keyEvent.isKey(KeyCode.DOWN)) {
            moveSelectionDown(getFilteredEventCount());
        } else if (keyEvent.isChar('f') || keyEvent.isChar('F')) {
            cycleCategory();
        } else if (keyEvent.isChar('s') || keyEvent.isChar('S')) {
            toggleSortDuration();
        } else if (keyEvent.isKey(KeyCode.ENTER)) {
            toggleEventModal();
        } else {
            return false;
        }
        return true;
    }

    public void render(Rect area, Buffer buffer, JvmProcess process, JvmMetricsSnapshot metrics) {
        List<Rect> chunks = Layout.vertical()
            .spacing(1)
            .constraints(Constraint.length(3), Constraint.fill())
            .split(area);

        renderJfrSummaryBanner(chunks.get(0), buffer);

        List<JfrEventItem> filtered = new ArrayList<>(eventBuffer);
        if (currentCategory != EventCategory.ALL) {
            filtered = filtered.stream()
                .filter(e -> e.category().equalsIgnoreCase(currentCategory.getLabel()))
                .collect(Collectors.toList());
        }

        if (sortByDuration) {
            filtered.sort(Comparator.comparingLong(JfrEventItem::durationMs).reversed());
        } else {
            filtered.sort(Comparator.comparingLong(JfrEventItem::eventId).reversed());
        }

        if (tableState.selected() == null || tableState.selected() >= filtered.size()) {
            tableState.select(Math.max(0, filtered.size() - 1));
        }

        List<Rect> mainAreaChunks = showEventModal
            ? Layout.vertical().constraints(Constraint.percentage(55), Constraint.percentage(45)).split(chunks.get(1))
            : List.of(chunks.get(1));

        renderJfrEventsTable(mainAreaChunks.get(0), buffer, filtered);

        if (showEventModal && mainAreaChunks.size() > 1) {
            JfrEventItem selected = getSelectedEvent(filtered);
            renderEventDetailInspector(mainAreaChunks.get(1), buffer, selected);
        }
    }

    private void renderJfrSummaryBanner(Rect area, Buffer buffer) {
        Block block = Block.builder()
            .title(" live jdk flight recorder (jfr) event stream ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.CYAN)
            .build();

        String line = String.format(" Category Filter: [%s]  |  Sort: [%s]  |  Stream: ACTIVE (%,d events)  |  Detail Modal: [%s]",
            currentCategory.getLabel(), sortByDuration ? "Duration ↓" : "Event ID ↓", eventBuffer.size(), showEventModal ? "OPEN [Enter]" : "CLOSED [Enter]");

        Paragraph para = Paragraph.builder()
            .text(line)
            .block(block)
            .style(Style.create().fg(Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private void renderJfrEventsTable(Rect area, Buffer buffer, List<JfrEventItem> events) {
        List<Row> rows = new ArrayList<>();
        for (JfrEventItem item : events) {
            Style typeStyle = getEventTypeStyle(item.eventType());
            rows.add(Row.from(
                Cell.from(item.timestamp()),
                Cell.from(item.eventType()).style(typeStyle),
                Cell.from(item.durationMs() + " ms"),
                Cell.from(truncate(item.threadName(), 22)),
                Cell.from(truncate(item.detail(), 40))
            ));
        }

        Row headerRow = Row.from("Time", "JFR Event Type", "Duration", "Thread Name", "Event Payload / Details")
            .style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD));

        Block tableBlock = Block.builder()
            .title(String.format(" jfr event telemetry (%,d) ", events.size()))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.MAGENTA)
            .build();

        Table table = Table.builder()
            .header(headerRow)
            .rows(rows)
            .widths(Constraint.length(10), Constraint.percentage(22), Constraint.length(12), Constraint.percentage(22), Constraint.percentage(44))
            .highlightStyle(Style.create().bg(Color.BLUE).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
            .highlightSymbol("> ")
            .block(tableBlock)
            .build();

        table.render(area, buffer, tableState);
    }

    private void renderEventDetailInspector(Rect area, Buffer buffer, JfrEventItem event) {
        Block block = Block.builder()
            .title(event != null ? " jfr event stack trace: " + event.eventType() + " (" + event.durationMs() + " ms) " : " jfr event stack trace ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.MAGENTA)
            .build();

        if (event == null) {
            Paragraph.builder().text("No event selected.").block(block).build().render(area, buffer);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Event: %s  |  Duration: %d ms  |  Category: %s  |  Thread: %s\n",
            event.eventType(), event.durationMs(), event.category(), event.threadName()));
        sb.append("Payload: ").append(event.detail()).append("\n");

        sb.append("Stack Trace Frames:\n");
        if (event.stackTrace() != null && !event.stackTrace().isEmpty()) {
            int maxFrames = Math.max(1, area.height() - 5);
            for (int i = 0; i < Math.min(maxFrames, event.stackTrace().size()); i++) {
                sb.append("  at ").append(event.stackTrace().get(i)).append("\n");
            }
        } else {
            sb.append("  (No stack trace recorded for this event)\n");
        }

        Paragraph para = Paragraph.builder()
            .text(sb.toString())
            .block(block)
            .style(Style.create().fg(Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private JfrEventItem getSelectedEvent(List<JfrEventItem> events) {
        Integer sel = tableState.selected();
        if (sel != null && sel >= 0 && sel < events.size()) {
            return events.get(sel);
        }
        return null;
    }

    public int getFilteredEventCount() {
        return eventBuffer.size();
    }

    private Style getEventTypeStyle(String type) {
        if (type.contains("GarbageCollection")) return Style.create().fg(Color.BLUE);
        if (type.contains("ThreadPark")) return Style.create().fg(Color.YELLOW);
        if (type.contains("SocketRead") || type.contains("FileWrite")) return Style.create().fg(Color.CYAN);
        return Style.create().fg(Color.GREEN);
    }

    private String truncate(String val, int max) {
        return UiFormatter.truncate(val, max);
    }
}
