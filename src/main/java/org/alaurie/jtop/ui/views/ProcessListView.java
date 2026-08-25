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
import org.alaurie.jtop.model.JvmProcess;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/// Btop-style View for discovering and selecting local or containerized JVM processes.
public class ProcessListView {

    private final TableState tableState = new TableState();
    private String filterQuery = "";
    private boolean isFiltering = false;
    private List<JvmProcess> allProcesses = new ArrayList<>();
    private List<JvmProcess> filteredProcesses = new ArrayList<>();

    public ProcessListView() {
        tableState.select(0);
    }

    public void updateProcesses(List<JvmProcess> processes) {
        this.allProcesses = processes;
        applyFilter();
    }

    private void applyFilter() {
        if (filterQuery.isBlank()) {
            filteredProcesses = new ArrayList<>(allProcesses);
        } else {
            String lower = filterQuery.toLowerCase();
            filteredProcesses = allProcesses.stream()
                .filter(p -> String.valueOf(p.pid()).contains(lower)
                    || p.displayName().toLowerCase().contains(lower)
                    || p.mainClass().toLowerCase().contains(lower)
                    || p.containerName().toLowerCase().contains(lower))
                .collect(Collectors.toList());
        }
        if (tableState.selected() == null || tableState.selected() >= filteredProcesses.size()) {
            tableState.select(Math.max(0, filteredProcesses.size() - 1));
        }
    }

    public boolean isFiltering() {
        return isFiltering;
    }

    public void setFiltering(boolean filtering) {
        this.isFiltering = filtering;
    }

    public String getFilterQuery() {
        return filterQuery;
    }

    public void appendFilterChar(char c) {
        this.filterQuery += c;
        applyFilter();
    }

    public void backspaceFilter() {
        if (!filterQuery.isEmpty()) {
            filterQuery = filterQuery.substring(0, filterQuery.length() - 1);
            applyFilter();
        }
    }

    public void clearFilter() {
        filterQuery = "";
        isFiltering = false;
        applyFilter();
    }

    public void moveSelectionUp() {
        tableState.selectPrevious();
    }

    public void moveSelectionDown() {
        tableState.selectNext(filteredProcesses.size());
    }

    public JvmProcess getSelectedProcess() {
        Integer sel = tableState.selected();
        if (sel != null && sel >= 0 && sel < filteredProcesses.size()) {
            return filteredProcesses.get(sel);
        }
        return null;
    }

    public void render(Rect area, Buffer buffer) {
        List<Rect> chunks = Layout.vertical()
            .constraints(Constraint.length(2), Constraint.fill())
            .split(area);

        // Filter / Header Sub-Bar
        String headerTitle = isFiltering
            ? " process filter: " + filterQuery + "█ "
            : String.format(" discovered local & container jvm processes (%d) [/ Filter] [Enter Attach]", filteredProcesses.size());

        Paragraph headerPara = Paragraph.builder()
            .text(headerTitle)
            .style(Style.create().fg(isFiltering ? Color.YELLOW : Color.WHITE))
            .build();
        headerPara.render(chunks.get(0), buffer);

        // Table Rows
        List<Row> rows = new ArrayList<>();
        for (JvmProcess p : filteredProcesses) {
            String attachableStr = p.isAttachable() ? "Yes" : "No";
            Style rowStyle = p.isAttachable() ? Style.create().fg(Color.WHITE) : Style.create().fg(Color.GRAY);

            rows.add(Row.from(
                Cell.from(String.valueOf(p.pid())),
                Cell.from(truncate(p.displayName(), 35)),
                Cell.from(truncate(p.mainClass(), 25)),
                Cell.from(p.containerName()),
                Cell.from(p.jvmVersion()),
                Cell.from(attachableStr)
            ).style(rowStyle));
        }

        Row headerRow = Row.from("PID", "Display Name", "Main Class", "Container / Pod", "JVM Version", "Attachable")
            .style(Style.create().fg(Color.YELLOW).addModifier(Modifier.BOLD));

        Block tableBlock = Block.builder()
            .title(" local & container jvm processes ")
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.CYAN)
            .build();

        Table table = Table.builder()
            .header(headerRow)
            .rows(rows)
            .widths(
                Constraint.length(8),
                Constraint.percentage(32),
                Constraint.percentage(24),
                Constraint.percentage(18),
                Constraint.percentage(10),
                Constraint.length(8)
            )
            .highlightStyle(Style.create().bg(Color.BLUE).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD))
            .highlightSymbol("> ")
            .block(tableBlock)
            .build();

        table.render(chunks.get(1), buffer, tableState);
    }

    private String truncate(String val, int max) {
        if (val == null) return "";
        if (val.length() <= max) return val;
        return val.substring(0, max - 3) + "...";
    }
}
