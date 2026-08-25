package org.alaurie.jtop.ui.views;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.style.Modifier;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.gauge.LineGauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.sparkline.Sparkline;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;

import org.alaurie.jtop.model.*;
import org.alaurie.jtop.ui.style.Glyph;
import org.alaurie.jtop.ui.style.Theme;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Responsive btop-inspired JVM Dashboard dynamically filling 100% container heights with theme support.
public class DashboardView {

    private final TableState threadTableState = new TableState();
    private final TableState poolTableState = new TableState();
    private final TableState gcTableState = new TableState();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public DashboardView() {
        poolTableState.select(1);
        gcTableState.select(0);
    }

    public void render(Rect area, Buffer buffer, JvmProcess process, JvmMetricsSnapshot metrics, MetricHistory history, Theme theme) {
        boolean isCompactHeight = area.height() < 30;
        boolean isCompactWidth = area.width() < 100;

        // Main Content Grid (Top 52%, Bottom 48%)
        List<Rect> contentRows = Layout.vertical()
            .constraints(
                isCompactHeight ? Constraint.percentage(48) : Constraint.percentage(52),
                isCompactHeight ? Constraint.percentage(52) : Constraint.percentage(48)
            )
            .split(area);

        List<Rect> topRow = Layout.horizontal()
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(contentRows.get(0));

        List<Rect> bottomRow = Layout.horizontal()
            .constraints(Constraint.percentage(40), Constraint.percentage(60))
            .split(contentRows.get(1));

        renderCpuAndLoadBox(topRow.get(0), buffer, metrics, history, isCompactHeight, theme);
        renderMemoryOverviewBox(topRow.get(1), buffer, metrics, isCompactHeight, theme);
        renderGcCollectorsBox(bottomRow.get(0), buffer, metrics, theme);
        renderTopThreadsBox(bottomRow.get(1), buffer, metrics, isCompactWidth, theme);
    }

    private void renderCpuAndLoadBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, MetricHistory history, boolean isCompactHeight, Theme theme) {
        double procCpu = metrics != null ? metrics.processCpuLoad() : 0.0;
        double sysCpu = metrics != null ? metrics.systemCpuLoad() : 0.0;
        boolean highCpu = procCpu >= 80.0;

        Block outerBlock = Block.builder()
            .title(" cpu and load ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(highCpu ? theme.getAlertColor() : Color.GRAY)
            .build();

        outerBlock.render(area, buffer);

        List<Rect> inner = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(outerBlock.inner(area));

        LineGauge procGauge = LineGauge.builder()
            .percent((int) Math.min(100, Math.max(0, procCpu)))
            .label(String.format("Process  %4.1f%%", procCpu))
            .lineSet(LineGauge.THICK)
            .filledColor(getCpuColor(procCpu, theme))
            .unfilledColor(Color.DARK_GRAY)
            .build();
        procGauge.render(inner.get(0), buffer);

        LineGauge sysGauge = LineGauge.builder()
            .percent((int) Math.min(100, Math.max(0, sysCpu)))
            .label(String.format("System   %4.1f%%", sysCpu))
            .lineSet(LineGauge.THICK)
            .filledColor(getCpuColor(sysCpu, theme))
            .unfilledColor(Color.DARK_GRAY)
            .build();
        sysGauge.render(inner.get(1), buffer);

        // Load History Waveform Sub-Box
        Block sparkBlock = Block.builder()
            .title(" load history ")
            .titleBottom("Last 5m                                                    Last 5m")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(Color.GRAY)
            .build();

        sparkBlock.render(inner.get(2), buffer);

        Rect sparkInner = sparkBlock.inner(inner.get(2));
        if (history != null && history.cpuHistory() != null && !history.cpuHistory().isEmpty()) {
            List<Double> cpuList = history.cpuHistory();
            int width = Math.max(1, sparkInner.width());

            long[] sparkData = new long[width];
            int listSize = cpuList.size();
            for (int i = 0; i < width; i++) {
                int srcIdx = listSize - width + i;
                sparkData[i] = srcIdx >= 0 ? cpuList.get(srcIdx).longValue() : 0;
            }

            Sparkline sparkline = Sparkline.builder()
                .data(sparkData)
                .max(100)
                .foreground(highCpu ? theme.getAlertColor() : theme.getHeaderColor())
                .build();
            sparkline.render(sparkInner, buffer);
        }
    }

    private void renderMemoryOverviewBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, boolean isCompactHeight, Theme theme) {
        long heapUsed = metrics != null ? metrics.heapUsed() : 0;
        long heapMax = metrics != null && metrics.heapMax() > 0 ? metrics.heapMax() : Math.max(1, heapUsed);
        int heapPct = (int) Math.min(100, (double) heapUsed / heapMax * 100.0);
        boolean highMem = heapPct >= 85;

        Block outerBlock = Block.builder()
            .title(" memory overview & pools ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(highMem ? theme.getAlertColor() : Color.GRAY)
            .build();

        outerBlock.render(area, buffer);

        List<Rect> inner = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(outerBlock.inner(area));

        LineGauge heapGauge = LineGauge.builder()
            .percent(heapPct)
            .label(String.format("Heap (%d%%)                              %s / %s", heapPct, formatBytes(heapUsed), formatBytes(heapMax)))
            .lineSet(LineGauge.THICK)
            .filledColor(getMemColor(heapPct, theme))
            .unfilledColor(Color.DARK_GRAY)
            .build();
        heapGauge.render(inner.get(0), buffer);

        long nonHeapUsed = metrics != null ? metrics.nonHeapUsed() : 0;
        LineGauge nonHeapGauge = LineGauge.builder()
            .percent(50)
            .label(String.format("Non-Heap                                %s", formatBytes(nonHeapUsed)))
            .lineSet(LineGauge.THICK)
            .filledColor(theme.getMemoryColor())
            .unfilledColor(Color.DARK_GRAY)
            .build();
        nonHeapGauge.render(inner.get(1), buffer);

        // Memory Pools Breakdown Table Sub-Box
        Block poolBlock = Block.builder()
            .title(" memory pool breakdown ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(Color.GRAY)
            .build();

        List<Row> poolRows = new ArrayList<>();
        if (metrics != null && metrics.memoryPools() != null) {
            for (MemoryPoolSnapshot pool : metrics.memoryPools()) {
                poolRows.add(Row.from(
                    Cell.from(pool.name()),
                    Cell.from(formatBytes(pool.used())),
                    Cell.from(String.format("%.1f%%", pool.usagePercentage())),
                    Cell.from(pool.max() > 0 ? formatBytes(pool.max()) : "N/A")
                ));
            }
        }

        Table poolTable = Table.builder()
            .header(Row.from("Pool", "Used", "Usage%", "Max").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(poolRows)
            .widths(Constraint.percentage(45), Constraint.percentage(20), Constraint.percentage(15), Constraint.percentage(20))
            .highlightStyle(Style.create().bg(theme.getMemoryColor()).fg(Color.BLACK).addModifier(Modifier.BOLD))
            .block(poolBlock)
            .build();

        poolTable.render(inner.get(2), buffer, poolTableState);
    }

    private void renderGcCollectorsBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, Theme theme) {
        double gcOverhead = metrics != null ? metrics.gcCpuOverheadPct() : 0.0;
        boolean highGc = gcOverhead > 15.0;

        Block block = Block.builder()
            .title(highGc ? " gc collectors (THRASHING DETECTED) " : " gc collectors ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(highGc ? theme.getAlertColor() : Color.GRAY)
            .build();

        List<Row> gcRows = new ArrayList<>();
        if (metrics != null && metrics.gcSnapshots() != null) {
            for (GcSnapshot gc : metrics.gcSnapshots()) {
                gcRows.add(Row.from(
                    Cell.from(gc.name()),
                    Cell.from(String.valueOf(gc.collectionCount())),
                    Cell.from(gc.collectionTimeMs() + "ms"),
                    Cell.from(gc.collectionTimeMs() + "ms")
                ));
            }
        }

        Table gcTable = Table.builder()
            .header(Row.from("Collector", "Count", "Time (ms)", "Total Time").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(gcRows)
            .widths(Constraint.percentage(40), Constraint.percentage(20), Constraint.percentage(20), Constraint.percentage(20))
            .highlightStyle(Style.create().bg(theme.getGcColor()).fg(Color.BLACK).addModifier(Modifier.BOLD))
            .block(block)
            .build();

        gcTable.render(area, buffer, gcTableState);
    }

    private void renderTopThreadsBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, boolean isCompactWidth, Theme theme) {
        int platformCount = metrics != null ? metrics.platformThreadCount() : 0;
        int virtualCount = metrics != null ? metrics.virtualThreadCount() : 0;
        boolean hasDeadlock = metrics != null && metrics.hasDeadlock();

        String alertIcon = Glyph.alertIcon();
        Block block = Block.builder()
            .title(hasDeadlock
                ? String.format(" top thread activity %s DEADLOCK (P: %d | V: %d) ", alertIcon, platformCount, virtualCount)
                : String.format(" top thread activity (P: %d | V: %d) ", platformCount, virtualCount))
            .titleBottom(String.format("Total P/V threads, (P: %d / %d)                                Paging [< >]", platformCount, virtualCount))
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(hasDeadlock ? theme.getAlertColor() : Color.GRAY)
            .build();

        List<ThreadSnapshot> threads = metrics != null && metrics.threads() != null
            ? new ArrayList<>(metrics.threads())
            : new ArrayList<>();

        threads.sort(Comparator.comparingDouble(ThreadSnapshot::cpuPercent).reversed());

        Rect innerArea = block.inner(area);
        int availableRows = Math.max(1, innerArea.height() - 2);
        int limit = Math.min(availableRows, threads.size());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ThreadSnapshot t = threads.get(i);
            String typeStr = t.isVirtual() ? "V" : "P";
            Style stateStyle = getThreadStateStyle(t.state(), theme);
            String detailStr = t.stackTrace() != null && !t.stackTrace().isEmpty()
                ? truncate(t.stackTrace().get(0), 20)
                : "Active";

            if (isCompactWidth) {
                rows.add(Row.from(
                    Cell.from(String.valueOf(t.threadId())),
                    Cell.from(truncate(t.threadName(), 30)),
                    Cell.from(t.state().name()).style(stateStyle),
                    Cell.from(String.format("%.1f%%", t.cpuPercent())),
                    Cell.from(typeStr)
                ));
            } else {
                rows.add(Row.from(
                    Cell.from(String.valueOf(t.threadId())),
                    Cell.from(truncate(t.threadName(), 25)),
                    Cell.from(t.state().name()).style(stateStyle),
                    Cell.from(String.format("%.1f%%", t.cpuPercent())),
                    Cell.from(typeStr),
                    Cell.from(detailStr)
                ));
            }
        }

        Table table = isCompactWidth
            ? Table.builder()
                .header(Row.from("TID", "Thread Name", "State", "CPU% ⇅", "Type").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
                .rows(rows)
                .widths(Constraint.length(6), Constraint.percentage(45), Constraint.percentage(25), Constraint.percentage(15), Constraint.length(6))
                .block(block)
                .build()
            : Table.builder()
                .header(Row.from("TID", "Thread Name", "State", "CPU% ⇅", "Type", "Details").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
                .rows(rows)
                .widths(Constraint.length(6), Constraint.percentage(30), Constraint.percentage(22), Constraint.percentage(12), Constraint.length(6), Constraint.percentage(24))
                .block(block)
                .build();

        table.render(area, buffer, threadTableState);
    }

    private Color getCpuColor(double pct, Theme theme) {
        if (pct >= 80.0) return theme.getAlertColor();
        if (pct >= 50.0) return Color.YELLOW;
        return theme.getHeaderColor();
    }

    private Color getMemColor(int pct, Theme theme) {
        if (pct >= 85) return theme.getAlertColor();
        if (pct >= 65) return Color.YELLOW;
        return theme.getThreadsColor();
    }

    private Style getThreadStateStyle(Thread.State state, Theme theme) {
        return switch (state) {
            case RUNNABLE -> Style.create().fg(theme.getThreadsColor());
            case WAITING -> Style.create().fg(Color.GRAY);
            case TIMED_WAITING -> Style.create().fg(theme.getHeaderColor());
            case BLOCKED -> Style.create().bg(theme.getAlertColor()).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD);
            default -> Style.create().fg(Color.GRAY);
        };
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private String truncate(String val, int max) {
        if (val == null) return "";
        if (val.length() <= max) return val;
        return val.substring(0, max - 3) + "...";
    }
}
