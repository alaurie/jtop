package org.alaurie.jtop.ui.views;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.style.Modifier;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.gauge.LineGauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import org.alaurie.jtop.model.FrameworkInfo;
import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.model.JvmProcess;
import org.alaurie.jtop.ui.style.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * View inspecting Spring Boot, Quarkus, HikariCP, and Agroal framework telemetry with live interactive logger level tuning.
 */
public class FrameworkView {

    private final TableState logTableState = new TableState();
    private final Map<String, String> loggerLevels = new HashMap<>();
    private final List<String> categories = List.of(
        "org.springframework.web",
        "org.hibernate.SQL",
        "io.quarkus.http",
        "com.zaxxer.hikari"
    );

    public FrameworkView() {
        logTableState.select(0);
        loggerLevels.put("org.springframework.web", "INFO");
        loggerLevels.put("org.hibernate.SQL", "WARN");
        loggerLevels.put("io.quarkus.http", "INFO");
        loggerLevels.put("com.zaxxer.hikari", "INFO");
    }

    public void moveSelectionUp() {
        logTableState.selectPrevious();
    }

    public void moveSelectionDown() {
        logTableState.selectNext(categories.size());
    }

    public int getCategoryCount() {
        return categories.size();
    }

    public String cycleSelectedLogLevel() {
        Integer sel = logTableState.selected();
        if (sel != null && sel >= 0 && sel < categories.size()) {
            String cat = categories.get(sel);
            String current = loggerLevels.getOrDefault(cat, "INFO");
            String next = switch (current) {
                case "INFO" -> "DEBUG";
                case "DEBUG" -> "TRACE";
                case "TRACE" -> "WARN";
                case "WARN" -> "ERROR";
                default -> "INFO";
            };
            loggerLevels.put(cat, next);
            return cat + " -> " + next;
        }
        return null;
    }

    public void render(Rect area, Buffer buffer, JvmProcess process, JvmMetricsSnapshot metrics, Theme theme) {
        FrameworkInfo fw = metrics != null ? metrics.frameworkInfo() : null;

        List<Rect> contentRows = Layout.vertical()
            .spacing(1)
            .constraints(Constraint.length(3), Constraint.percentage(45), Constraint.percentage(55))
            .split(area);

        renderFrameworkBanner(contentRows.get(0), buffer, fw, theme);

        List<Rect> topRow = Layout.horizontal()
            .spacing(1)
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(contentRows.get(1));

        renderDbPoolBox(topRow.get(0), buffer, fw, theme);
        renderHttpThreadPoolBox(topRow.get(1), buffer, fw, theme);

        renderLogLevelsTableBox(contentRows.get(2), buffer, fw, theme);
    }

    private void renderFrameworkBanner(Rect area, Buffer buffer, FrameworkInfo fw, Theme theme) {
        String type = fw != null && fw.isDetected() ? fw.frameworkType() + " " + fw.version() : "Vanilla JVM";
        Block block = Block.builder()
            .title(" framework auto-discovery & actuator ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(theme.getHeaderColor())
            .build();

        String line = String.format(" Detected Framework: [%s]  |  HikariCP / Agroal JMX: %s  |  [Enter] Cycle Selected Logger Level",
            type, fw != null && fw.isDetected() ? "CONNECTED" : "AVAILABLE");

        Paragraph para = Paragraph.builder()
            .text(line)
            .block(block)
            .style(Style.create().fg(Color.WHITE))
            .build();

        para.render(area, buffer);
    }

    private void renderDbPoolBox(Rect area, Buffer buffer, FrameworkInfo fw, Theme theme) {
        Block block = Block.builder()
            .title(" database connection pool (hikaricp / agroal) ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.CYAN)
            .build();

        block.render(area, buffer);

        List<Rect> inner = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(block.inner(area));

        int active = fw != null ? fw.activeDbConnections() : 0;
        int max = fw != null && fw.maxDbConnections() > 0 ? fw.maxDbConnections() : Math.max(1, active);
        int pct = (int) Math.min(100, (double) active / max * 100.0);

        LineGauge activeGauge = LineGauge.builder()
            .percent(pct)
            .label(String.format("Active DB Connections: %d / %d (%d%%)", active, max, pct))
            .lineSet(LineGauge.THICK)
            .filledColor(pct > 80 ? Color.RED : Color.CYAN)
            .unfilledColor(Color.DARK_GRAY)
            .build();
        activeGauge.render(inner.get(0), buffer);

        int waiting = fw != null ? fw.waitingDbThreads() : 0;
        LineGauge waitingGauge = LineGauge.builder()
            .percent(waiting > 0 ? 100 : 0)
            .label(String.format("Threads Awaiting Connection: %d", waiting))
            .lineSet(LineGauge.THICK)
            .filledColor(waiting > 0 ? Color.RED : Color.GREEN)
            .unfilledColor(Color.DARK_GRAY)
            .build();
        waitingGauge.render(inner.get(1), buffer);
    }

    private void renderHttpThreadPoolBox(Rect area, Buffer buffer, FrameworkInfo fw, Theme theme) {
        Block block = Block.builder()
            .title(" http / event loop worker thread pool ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.MAGENTA)
            .build();

        block.render(area, buffer);

        List<Rect> inner = Layout.vertical()
            .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(block.inner(area));

        int activeHttp = fw != null ? fw.activeHttpThreads() : 0;
        int maxHttp = fw != null && fw.maxHttpThreads() > 0 ? fw.maxHttpThreads() : Math.max(1, activeHttp);
        int pct = (int) Math.min(100, (double) activeHttp / maxHttp * 100.0);

        LineGauge httpGauge = LineGauge.builder()
            .percent(pct)
            .label(String.format("Active Worker Threads: %d / %d", activeHttp, maxHttp))
            .lineSet(LineGauge.THICK)
            .filledColor(Color.MAGENTA)
            .unfilledColor(Color.DARK_GRAY)
            .build();
        httpGauge.render(inner.get(0), buffer);
    }

    private void renderLogLevelsTableBox(Rect area, Buffer buffer, FrameworkInfo fw, Theme theme) {
        Block block = Block.builder()
            .title(" live package logger levels [Enter to cycle level] ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.YELLOW)
            .build();

        List<Row> rows = new ArrayList<>();
        for (String cat : categories) {
            String lvl = loggerLevels.getOrDefault(cat, "INFO");
            Style lvlStyle = getLogLevelStyle(lvl);

            rows.add(Row.from(
                Cell.from(cat),
                Cell.from(lvl).style(lvlStyle)
            ));
        }

        Table table = Table.builder()
            .header(Row.from("Logger Category", "Current Level").style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD)))
            .rows(rows)
            .widths(Constraint.percentage(60), Constraint.percentage(40))
            .highlightStyle(Style.create().bg(Color.BLUE).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
            .highlightSymbol("> ")
            .block(block)
            .build();

        table.render(area, buffer, logTableState);
    }

    private Style getLogLevelStyle(String lvl) {
        return switch (lvl) {
            case "TRACE" -> Style.create().fg(Color.MAGENTA);
            case "DEBUG" -> Style.create().fg(Color.CYAN);
            case "WARN" -> Style.create().fg(Color.YELLOW);
            case "ERROR" -> Style.create().bg(Color.RED).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD);
            default -> Style.create().fg(Color.GREEN);
        };
    }
}
