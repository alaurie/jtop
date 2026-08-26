package org.alaurie.jtop.ui.style;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;

/// Centralized UI formatting engine handling byte formatting, text truncation, and theme-aware color styles.
public class UiFormatter {

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    public static String truncate(String val, int max) {
        if (val == null) return "";
        if (val.length() <= max) return val;
        return val.substring(0, max - 3) + "...";
    }

    public static String makeProgressBar(double pct, int width) {
        return Glyph.makeProgressBar(pct, width);
    }

    public static String makeMultiSegmentBar(double usedPct, double committedPct, int width) {
        return Glyph.makeMultiSegmentBar(usedPct, committedPct, width);
    }

    public static Color getCpuColor(double pct, Theme theme) {
        if (pct >= 80.0) return theme.getAlertColor();
        if (pct >= 50.0) return Color.YELLOW;
        return theme.getHeaderColor();
    }

    public static Color getMemColor(int pct, Theme theme) {
        if (pct >= 85) return theme.getAlertColor();
        if (pct >= 65) return Color.YELLOW;
        return theme.getThreadsColor();
    }

    public static Style getThreadStateStyle(Thread.State state, Theme theme) {
        return switch (state) {
            case RUNNABLE -> Style.create().fg(theme.getThreadsColor());
            case WAITING -> Style.create().fg(Color.GRAY);
            case TIMED_WAITING -> Style.create().fg(theme.getHeaderColor());
            case BLOCKED -> Style.create().bg(theme.getAlertColor()).fg(Color.BRIGHT_WHITE).addModifier(Modifier.BOLD);
            default -> Style.create().fg(Color.GRAY);
        };
    }
}
