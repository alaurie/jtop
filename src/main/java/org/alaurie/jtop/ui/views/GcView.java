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
import dev.tamboui.widgets.sparkline.Sparkline;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import org.alaurie.jtop.model.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Detailed Garbage Collection and Memory Pool view with inline live GC tuning controls and full-width pause history sparklines.
 */
public class GcView {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final TableState gcTableState = new TableState();
    private final TableState poolTableState = new TableState();

    private boolean heapDumpOnOom = true;
    private int maxHeapFreeRatio = 70;

    public void toggleHeapDumpOnOom() {
        heapDumpOnOom = !heapDumpOnOom;
    }

    public void adjustMaxHeapFreeRatio(int delta) {
        maxHeapFreeRatio = Math.max(10, Math.min(100, maxHeapFreeRatio + delta));
    }

    public boolean isHeapDumpOnOom() {
        return heapDumpOnOom;
    }

    public int getMaxHeapFreeRatio() {
        return maxHeapFreeRatio;
    }

    public void render(Rect area, Buffer buffer, JvmProcess process, JvmMetricsSnapshot metrics, MetricHistory history) {
        List<Rect> mainSub = Layout.vertical()
            .spacing(1)
            .constraints(Constraint.length(3), Constraint.percentage(35), Constraint.percentage(65))
            .split(area);

        renderGcSummaryBanner(mainSub.get(0), buffer, metrics);

        List<Rect> topRow = Layout.horizontal()
            .spacing(1)
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(mainSub.get(1));

        renderGcCollectorsTable(topRow.get(0), buffer, metrics);
        renderGcPauseSparkline(topRow.get(1), buffer, history);

        List<Rect> bottomRow = Layout.horizontal()
            .spacing(1)
            .constraints(Constraint.percentage(60), Constraint.percentage(40))
            .split(mainSub.get(2));

        renderMemoryPoolsTable(bottomRow.get(0), buffer, metrics);
        renderBufferPoolsTable(bottomRow.get(1), buffer, metrics);
    }

    private void renderGcSummaryBanner(Rect area, Buffer buffer, JvmMetricsSnapshot metrics) {
        Block block = Block.builder()
            .title(" gc allocation rates & inline tuning ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.BLUE)
            .build();

        double allocRate = metrics != null ? metrics.heapAllocationRateMbPerSec() : 0.0;
        double gcOverhead = metrics != null ? metrics.gcCpuOverheadPct() : 0.0;
        long totalGcTime = 0;
        long totalGcCount = 0;

        if (metrics != null && metrics.gcSnapshots() != null) {
            for (GcSnapshot gc : metrics.gcSnapshots()) {
                totalGcCount += gc.collectionCount();
                totalGcTime += gc.collectionTimeMs();
            }
        }

        double avgPause = totalGcCount > 0 ? (double) totalGcTime / totalGcCount : 0.0;

        String line = String.format(" Alloc: %5.1f MB/s │ GC Overhead: %4.1f%% │ HeapDumpOnOOM: [%s] [d] │ MaxFreeRatio: [%d%%] [+/-] │ [g] System.gc()",
            allocRate, gcOverhead, heapDumpOnOom ? "ON" : "OFF", maxHeapFreeRatio);

        Paragraph para = Paragraph.builder()
            .text(line)
            .block(block)
            .style(Style.create().fg(gcOverhead > 15.0 ? Color.RED : Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private void renderGcCollectorsTable(Rect area, Buffer buffer, JvmMetricsSnapshot metrics) {
        List<Row> gcRows = new ArrayList<>();
        if (metrics != null && metrics.gcSnapshots() != null) {
            for (GcSnapshot gc : metrics.gcSnapshots()) {
                gcRows.add(Row.from(
                    Cell.from(gc.name()),
                    Cell.from(String.valueOf(gc.collectionCount())),
                    Cell.from(gc.collectionTimeMs() + " ms"),
                    Cell.from(String.format("%.2f ms", gc.averagePauseMs())),
                    Cell.from(String.format("%.1f ms/s", gc.gcPauseRateMsPerSec()))
                ));
            }
        }

        Block gcTableBlock = Block.builder()
            .title(" gc collectors ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.BLUE)
            .build();

        Table gcTable = Table.builder()
            .header(Row.from("Collector Name", "Count", "Total Pause", "Avg Pause", "Pause Rate")
                .style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(gcRows)
            .widths(Constraint.percentage(30), Constraint.percentage(15), Constraint.percentage(20), Constraint.percentage(18), Constraint.percentage(17))
            .block(gcTableBlock)
            .build();

        gcTable.render(area, buffer, gcTableState);
    }

    private void renderGcPauseSparkline(Rect area, Buffer buffer, MetricHistory history) {
        Block block = Block.builder()
            .title(" gc pause time trend (ms) ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.BLUE)
            .build();

        block.render(area, buffer);
        Rect sparkInner = block.inner(area);

        if (history != null && history.gcTimeHistory() != null && !history.gcTimeHistory().isEmpty()) {
            List<Long> gcTimeList = history.gcTimeHistory();
            int width = Math.max(1, sparkInner.width());

            long[] sparkData = new long[width];
            int listSize = gcTimeList.size();
            for (int i = 0; i < width; i++) {
                int srcIdx = listSize - width + i;
                sparkData[i] = srcIdx >= 0 ? gcTimeList.get(srcIdx) : 0;
            }

            Sparkline sparkline = Sparkline.builder()
                .data(sparkData)
                .foreground(Color.BLUE)
                .build();
            sparkline.render(sparkInner, buffer);
        }
    }

    private void renderMemoryPoolsTable(Rect area, Buffer buffer, JvmMetricsSnapshot metrics) {
        List<Row> poolRows = new ArrayList<>();
        if (metrics != null && metrics.memoryPools() != null) {
            for (MemoryPoolSnapshot pool : metrics.memoryPools()) {
                String usageBar = makeProgressBar(pool.usagePercentage(), 15);
                poolRows.add(Row.from(
                    Cell.from(pool.name()),
                    Cell.from(formatBytes(pool.used())),
                    Cell.from(formatBytes(pool.committed())),
                    Cell.from(pool.max() > 0 ? formatBytes(pool.max()) : "N/A"),
                    Cell.from(String.format("%.1f%%", pool.usagePercentage())),
                    Cell.from(usageBar)
                ));
            }
        }

        Block poolTableBlock = Block.builder()
            .title(" memory pools telemetry matrix ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.CYAN)
            .build();

        Table poolTable = Table.builder()
            .header(Row.from("Pool Name", "Used", "Committed", "Max", "Usage %", "Visual Bar")
                .style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(poolRows)
            .widths(Constraint.percentage(30), Constraint.percentage(12), Constraint.percentage(12), Constraint.percentage(12), Constraint.percentage(12), Constraint.percentage(22))
            .block(poolTableBlock)
            .build();

        poolTable.render(area, buffer, poolTableState);
    }

    private void renderBufferPoolsTable(Rect area, Buffer buffer, JvmMetricsSnapshot metrics) {
        List<Row> rows = new ArrayList<>();
        if (metrics != null && metrics.bufferPools() != null) {
            for (BufferPoolSnapshot pool : metrics.bufferPools()) {
                rows.add(Row.from(
                    Cell.from(pool.name()),
                    Cell.from(String.valueOf(pool.count())),
                    Cell.from(formatBytes(pool.memoryUsed())),
                    Cell.from(pool.totalCapacity() > 0 ? formatBytes(pool.totalCapacity()) : "N/A")
                ));
            }
        }

        Block block = Block.builder()
            .title(" off-heap buffer pools ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.MAGENTA)
            .build();

        Table table = Table.builder()
            .header(Row.from("Pool", "Count", "Memory Used", "Capacity").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(rows)
            .widths(Constraint.percentage(30), Constraint.percentage(20), Constraint.percentage(25), Constraint.percentage(25))
            .block(block)
            .build();

        table.render(area, buffer, new TableState());
    }

    private String makeProgressBar(double pct, int width) {
        int filled = (int) Math.round(Math.min(100, Math.max(0, pct)) / 100.0 * width);
        return "[" + "█".repeat(filled) + "░".repeat(Math.max(0, width - filled)) + "]";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }
}
