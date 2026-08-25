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
import org.alaurie.jtop.model.JvmRuntimeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// View inspecting JVM Flags, System Specs, File Descriptors, Class Loading, and System Properties with dynamic height allocation.
public class JvmInfoView {

    private final TableState flagsTableState = new TableState();
    private final TableState propsTableState = new TableState();

    public void render(Rect area, Buffer buffer, JvmProcess process, JvmMetricsSnapshot metrics) {
        JvmRuntimeInfo info = metrics != null ? metrics.runtimeInfo() : null;

        // Content split: Top 9-line summary boxes, Bottom 100% remaining space for Flags & System Properties
        List<Rect> contentRows = Layout.vertical()
            .spacing(1)
            .constraints(Constraint.length(9), Constraint.fill())
            .split(area);

        List<Rect> topRow = Layout.horizontal()
            .spacing(1)
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(contentRows.get(0));

        renderRuntimeSummaryBox(topRow.get(0), buffer, process, info);
        renderOsAndFileDescriptorsBox(topRow.get(1), buffer, info);

        List<Rect> bottomRow = Layout.horizontal()
            .spacing(1)
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(contentRows.get(1));

        renderFlagsTableBox(bottomRow.get(0), buffer, info);
        renderSysPropsTableBox(bottomRow.get(1), buffer, info);
    }

    private void renderRuntimeSummaryBox(Rect area, Buffer buffer, JvmProcess process, JvmRuntimeInfo info) {
        String vendorBadge = info != null ? info.detectVendorType().getDisplayName() : "OpenJDK";
        Block block = Block.builder()
            .title(" jvm runtime [" + vendorBadge + "] ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.CYAN)
            .build();

        String vmName = info != null ? info.vmName() : "Java Virtual Machine";
        String vmVendor = info != null ? info.vmVendor() : "Oracle/OpenJDK";
        String javaVer = info != null ? info.javaVersion() : "Java 25";
        String uptimeStr = info != null ? info.formattedUptime() : "00s";
        long pid = process != null ? process.pid() : 0;
        long loadedClasses = info != null ? info.loadedClassCount() : 0;
        String compilerName = info != null ? info.compilerName() : "HotSpot JIT";
        long compilationTimeMs = info != null ? info.totalCompilationTimeMs() : 0;

        String text = String.format("""
             PID / Vendor:      %d [%s]
             VM Name:           %s
             Vendor Details:    %s
             Java Spec:         %s
             JVM Uptime:        %s
             Active Classes:    %,d
             JIT Compiler:      %s (%,d ms)
            """, pid, vendorBadge, vmName, vmVendor, javaVer, uptimeStr, loadedClasses, compilerName, compilationTimeMs);

        Paragraph para = Paragraph.builder()
            .text(text)
            .block(block)
            .style(Style.create().fg(Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private void renderOsAndFileDescriptorsBox(Rect area, Buffer buffer, JvmRuntimeInfo info) {
        Block block = Block.builder()
            .title(" os, host ram & file descriptors ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.MAGENTA)
            .build();

        String osName = info != null ? info.osName() + " (" + info.osArch() + ")" : "Linux";
        int cores = info != null ? info.availableProcessors() : 1;
        long openFds = info != null ? info.openFileDescriptors() : 0;
        long maxFds = info != null ? info.maxFileDescriptors() : 0;
        long commVirt = info != null ? info.committedVirtualMemoryBytes() : 0;
        long totalPhys = info != null ? info.totalPhysicalMemoryBytes() : 0;
        long freePhys = info != null ? info.freePhysicalMemoryBytes() : 0;
        long totalSwap = info != null ? info.totalSwapSpaceBytes() : 0;

        String fdStr = maxFds > 0 ? String.format("%,d / %,d", openFds, maxFds) : String.format("%,d", openFds);

        String text = String.format("""
             Host OS / Arch:    %s
             CPU Cores:         %d
             File Descriptors:  %s
             Committed Virtual: %s
             Physical Host RAM: %s / %s (Free)
             System Swap Space: %s
            """, osName, cores, fdStr, formatBytes(commVirt), formatBytes(totalPhys), formatBytes(freePhys), formatBytes(totalSwap));

        Paragraph para = Paragraph.builder()
            .text(text)
            .block(block)
            .style(Style.create().fg(Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private void renderFlagsTableBox(Rect area, Buffer buffer, JvmRuntimeInfo info) {
        Block block = Block.builder()
            .title(" jvm launch arguments & flags ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.YELLOW)
            .build();

        List<Row> rows = new ArrayList<>();
        if (info != null && info.inputArguments() != null) {
            int idx = 1;
            for (String arg : info.inputArguments()) {
                rows.add(Row.from(
                    Cell.from("#" + idx++),
                    Cell.from(arg)
                ));
            }
        }

        Table table = Table.builder()
            .header(Row.from("Index", "JVM Launch Flag").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(rows)
            .widths(Constraint.length(8), Constraint.percentage(92))
            .block(block)
            .build();

        table.render(area, buffer, flagsTableState);
    }

    private void renderSysPropsTableBox(Rect area, Buffer buffer, JvmRuntimeInfo info) {
        Block block = Block.builder()
            .title(" system properties ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.GREEN)
            .build();

        List<Row> rows = new ArrayList<>();
        if (info != null && info.systemProperties() != null) {
            for (Map.Entry<String, String> entry : info.systemProperties().entrySet()) {
                rows.add(Row.from(
                    Cell.from(entry.getKey()),
                    Cell.from(truncate(entry.getValue(), 35))
                ));
            }
        }

        Table table = Table.builder()
            .header(Row.from("Property Key", "Value").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(rows)
            .widths(Constraint.percentage(45), Constraint.percentage(55))
            .block(block)
            .build();

        table.render(area, buffer, propsTableState);
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "N/A";
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
