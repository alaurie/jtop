package org.alaurie.jtop.ui.views;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.style.Modifier;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.gauge.LineGauge;
import dev.tamboui.widgets.sparkline.Sparkline;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import org.alaurie.jtop.model.*;
import org.alaurie.jtop.ui.JTopApp;
import org.alaurie.jtop.ui.style.Glyph;
import org.alaurie.jtop.ui.style.Theme;
import org.alaurie.jtop.ui.style.UiFormatter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Responsive btop-inspired JVM Dashboard implementing the deep View seam.
public class DashboardView implements View {

    private final TableState threadTableState = new TableState();
    private final TableState poolTableState = new TableState();
    private final TableState gcTableState = new TableState();

    public DashboardView() {
        poolTableState.select(1);
        gcTableState.select(0);
    }

    @Override
    public boolean handleKey(KeyEvent keyEvent, ViewContext context, JTopApp app) {
        if (keyEvent.isChar('s')) {
            app.switchView(JTopApp.ViewState.THREADS);
            app.getThreadView().cycleSort();
            return true;
        } else if (keyEvent.isChar('v')) {
            app.switchView(JTopApp.ViewState.THREADS);
            app.getThreadView().toggleVirtualOnly();
            return true;
        }
        return false;
    }

    @Override
    public void render(Rect area, Buffer buffer, ViewContext context) {
        var metrics = context.currentMetrics();
        var history = context.currentHistory();
        var theme = context.currentTheme();

        var isCompactHeight = area.height() < 30;
        var isCompactWidth = area.width() < 100;

        var contentRows = Layout.vertical()
            .constraints(
                isCompactHeight ? Constraint.percentage(48) : Constraint.percentage(52),
                isCompactHeight ? Constraint.percentage(52) : Constraint.percentage(48)
            )
            .split(area);

        var topRow = Layout.horizontal()
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(contentRows.get(0));

        var bottomRow = Layout.horizontal()
            .constraints(Constraint.percentage(40), Constraint.percentage(60))
            .split(contentRows.get(1));

        renderCpuAndLoadBox(topRow.get(0), buffer, metrics, history, isCompactHeight, theme);
        renderMemoryOverviewBox(topRow.get(1), buffer, metrics, isCompactHeight, theme);
        renderGcCollectorsBox(bottomRow.get(0), buffer, metrics, theme);
        renderTopThreadsBox(bottomRow.get(1), buffer, metrics, isCompactWidth, theme);
    }

    private void renderCpuAndLoadBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, MetricHistory history, boolean isCompactHeight, Theme theme) {
        var procCpu = metrics != null ? metrics.processCpuLoad() : 0.0;
        var sysCpu = metrics != null ? metrics.systemCpuLoad() : 0.0;
        var highCpu = procCpu >= 80.0;

        var outerBlock = Block.builder()
            .title(" cpu and load ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(highCpu ? theme.getAlertColor() : Color.GRAY)
            .build();

        outerBlock.render(area, buffer);

        var inner = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(outerBlock.inner(area));

        var procGauge = LineGauge.builder()
            .percent((int) Math.min(100, Math.max(0, procCpu)))
            .label(String.format("Process  %4.1f%%", procCpu))
            .lineSet(LineGauge.THICK)
            .filledColor(UiFormatter.getCpuColor(procCpu, theme))
            .unfilledColor(Color.DARK_GRAY)
            .build();
        procGauge.render(inner.get(0), buffer);

        var sysGauge = LineGauge.builder()
            .percent((int) Math.min(100, Math.max(0, sysCpu)))
            .label(String.format("System   %4.1f%%", sysCpu))
            .lineSet(LineGauge.THICK)
            .filledColor(UiFormatter.getCpuColor(sysCpu, theme))
            .unfilledColor(Color.DARK_GRAY)
            .build();
        sysGauge.render(inner.get(1), buffer);

        var sparkBlock = Block.builder()
            .title(" load history ")
            .titleBottom("Last 5m                                                    Last 5m")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(Color.GRAY)
            .build();

        sparkBlock.render(inner.get(2), buffer);

        var sparkInner = sparkBlock.inner(inner.get(2));
        if (history != null && history.cpuHistory() != null && !history.cpuHistory().isEmpty()) {
            var cpuList = history.cpuHistory();
            var width = Math.max(1, sparkInner.width());

            var sparkData = new long[width];
            var listSize = cpuList.size();
            for (var i = 0; i < width; i++) {
                var srcIdx = listSize - width + i;
                sparkData[i] = srcIdx >= 0 ? cpuList.get(srcIdx).longValue() : 0;
            }

            var sparkline = Sparkline.builder()
                .data(sparkData)
                .max(100)
                .foreground(highCpu ? theme.getAlertColor() : theme.getHeaderColor())
                .build();
            sparkline.render(sparkInner, buffer);
        }
    }

    private void renderMemoryOverviewBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, boolean isCompactHeight, Theme theme) {
        var heapUsed = metrics != null ? metrics.heapUsed() : 0;
        var heapMax = metrics != null && metrics.heapMax() > 0 ? metrics.heapMax() : Math.max(1, heapUsed);
        var heapPct = (int) Math.min(100, (double) heapUsed / heapMax * 100.0);
        var highMem = heapPct >= 85;

        var outerBlock = Block.builder()
            .title(" memory overview & pools ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(highMem ? theme.getAlertColor() : Color.GRAY)
            .build();

        outerBlock.render(area, buffer);

        var inner = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(outerBlock.inner(area));

        var heapGauge = LineGauge.builder()
            .percent(heapPct)
            .label(String.format("Heap (%d%%)                              %s / %s", heapPct, UiFormatter.formatBytes(heapUsed), UiFormatter.formatBytes(heapMax)))
            .lineSet(LineGauge.THICK)
            .filledColor(UiFormatter.getMemColor(heapPct, theme))
            .unfilledColor(Color.DARK_GRAY)
            .build();
        heapGauge.render(inner.get(0), buffer);

        var nonHeapUsed = metrics != null ? metrics.nonHeapUsed() : 0;
        var nonHeapGauge = LineGauge.builder()
            .percent(50)
            .label(String.format("Non-Heap                                %s", UiFormatter.formatBytes(nonHeapUsed)))
            .lineSet(LineGauge.THICK)
            .filledColor(theme.getMemoryColor())
            .unfilledColor(Color.DARK_GRAY)
            .build();
        nonHeapGauge.render(inner.get(1), buffer);

        var poolBlock = Block.builder()
            .title(" memory pool breakdown ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(Color.GRAY)
            .build();

        var poolRows = new ArrayList<Row>();
        if (metrics != null && metrics.memoryPools() != null) {
            for (var pool : metrics.memoryPools()) {
                poolRows.add(Row.from(
                    Cell.from(pool.name()),
                    Cell.from(UiFormatter.formatBytes(pool.used())),
                    Cell.from(String.format("%.1f%%", pool.usagePercentage())),
                    Cell.from(pool.max() > 0 ? UiFormatter.formatBytes(pool.max()) : "N/A")
                ));
            }
        }

        var poolTable = Table.builder()
            .header(Row.from("Pool", "Used", "Usage%", "Max").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(poolRows)
            .widths(Constraint.percentage(45), Constraint.percentage(20), Constraint.percentage(15), Constraint.percentage(20))
            .highlightStyle(Style.create().bg(theme.getMemoryColor()).fg(Color.BLACK).addModifier(Modifier.BOLD))
            .block(poolBlock)
            .build();

        poolTable.render(inner.get(2), buffer, poolTableState);
    }

    private void renderGcCollectorsBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, Theme theme) {
        var gcOverhead = metrics != null ? metrics.gcCpuOverheadPct() : 0.0;
        var highGc = gcOverhead > 15.0;

        var block = Block.builder()
            .title(highGc ? " gc collectors (THRASHING DETECTED) " : " gc collectors ")
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(highGc ? theme.getAlertColor() : Color.GRAY)
            .build();

        var gcRows = new ArrayList<Row>();
        if (metrics != null && metrics.gcSnapshots() != null) {
            for (var gc : metrics.gcSnapshots()) {
                gcRows.add(Row.from(
                    Cell.from(gc.name()),
                    Cell.from(String.valueOf(gc.collectionCount())),
                    Cell.from(gc.collectionTimeMs() + "ms"),
                    Cell.from(gc.collectionTimeMs() + "ms")
                ));
            }
        }

        var gcTable = Table.builder()
            .header(Row.from("Collector", "Count", "Time (ms)", "Total Time").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(gcRows)
            .widths(Constraint.percentage(40), Constraint.percentage(20), Constraint.percentage(20), Constraint.percentage(20))
            .highlightStyle(Style.create().bg(theme.getGcColor()).fg(Color.BLACK).addModifier(Modifier.BOLD))
            .block(block)
            .build();

        gcTable.render(area, buffer, gcTableState);
    }

    private void renderTopThreadsBox(Rect area, Buffer buffer, JvmMetricsSnapshot metrics, boolean isCompactWidth, Theme theme) {
        var platformCount = metrics != null ? metrics.platformThreadCount() : 0;
        var virtualCount = metrics != null ? metrics.virtualThreadCount() : 0;
        var hasDeadlock = metrics != null && metrics.hasDeadlock();

        var alertIcon = Glyph.alertIcon();
        var block = Block.builder()
            .title(hasDeadlock
                ? String.format(" top thread activity %s DEADLOCK (P: %d | V: %d) ", alertIcon, platformCount, virtualCount)
                : String.format(" top thread activity (P: %d | V: %d) ", platformCount, virtualCount))
            .titleBottom(String.format("Total P/V threads, (P: %d / %d)                                Paging [< >]", platformCount, virtualCount))
            .borders(Borders.ALL)
            .borderType(BorderType.PLAIN)
            .borderColor(hasDeadlock ? theme.getAlertColor() : Color.GRAY)
            .build();

        var threads = metrics != null && metrics.threads() != null
            ? new ArrayList<>(metrics.threads())
            : new ArrayList<ThreadSnapshot>();

        threads.sort(Comparator.comparingDouble(ThreadSnapshot::cpuPercent).reversed());

        var innerArea = block.inner(area);
        var availableRows = Math.max(1, innerArea.height() - 2);
        var limit = Math.min(availableRows, threads.size());

        var rows = new ArrayList<Row>();
        for (var i = 0; i < limit; i++) {
            var t = threads.get(i);
            var typeStr = t.isVirtual() ? "V" : "P";
            var stateStyle = UiFormatter.getThreadStateStyle(t.state(), theme);
            var detailStr = t.stackTrace() != null && !t.stackTrace().isEmpty()
                ? UiFormatter.truncate(t.stackTrace().get(0), 20)
                : "Active";

            if (isCompactWidth) {
                rows.add(Row.from(
                    Cell.from(String.valueOf(t.threadId())),
                    Cell.from(UiFormatter.truncate(t.threadName(), 30)),
                    Cell.from(t.state().name()).style(stateStyle),
                    Cell.from(String.format("%.1f%%", t.cpuPercent())),
                    Cell.from(typeStr)
                ));
            } else {
                rows.add(Row.from(
                    Cell.from(String.valueOf(t.threadId())),
                    Cell.from(UiFormatter.truncate(t.threadName(), 25)),
                    Cell.from(t.state().name()).style(stateStyle),
                    Cell.from(String.format("%.1f%%", t.cpuPercent())),
                    Cell.from(typeStr),
                    Cell.from(detailStr)
                ));
            }
        }

        var table = isCompactWidth
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
}
