package org.alaurie.jtop.ui.style;

import dev.tamboui.style.Color;

/// Color palette themes for jtop terminal UI.
public enum Theme {
    BTOP("btop", Color.CYAN, Color.MAGENTA, Color.BLUE, Color.GREEN, Color.YELLOW, Color.RED),
    DRACULA("dracula", Color.indexed(141), Color.indexed(212), Color.indexed(117), Color.indexed(84), Color.indexed(228), Color.indexed(203)),
    NORD("nord", Color.indexed(110), Color.indexed(109), Color.indexed(108), Color.indexed(143), Color.indexed(222), Color.indexed(167)),
    SOLARIZED("solarized", Color.YELLOW, Color.MAGENTA, Color.CYAN, Color.GREEN, Color.BLUE, Color.RED);

    private final String name;
    private final Color headerColor;
    private final Color memoryColor;
    private final Color gcColor;
    private final Color threadsColor;
    private final Color highlightColor;
    private final Color alertColor;

    Theme(String name, Color headerColor, Color memoryColor, Color gcColor, Color threadsColor, Color highlightColor, Color alertColor) {
        this.name = name;
        this.headerColor = headerColor;
        this.memoryColor = memoryColor;
        this.gcColor = gcColor;
        this.threadsColor = threadsColor;
        this.highlightColor = highlightColor;
        this.alertColor = alertColor;
    }

    public String getName() { return name; }
    public Color getHeaderColor() { return headerColor; }
    public Color getMemoryColor() { return memoryColor; }
    public Color getGcColor() { return gcColor; }
    public Color getThreadsColor() { return threadsColor; }
    public Color getHighlightColor() { return highlightColor; }
    public Color getAlertColor() { return alertColor; }

    public Theme next() {
        Theme[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Theme fromName(String name) {
        if (name == null) return BTOP;
        for (Theme t : values()) {
            if (t.name.equalsIgnoreCase(name.trim())) return t;
        }
        return BTOP;
    }
}
