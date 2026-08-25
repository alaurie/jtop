package org.alaurie.jtop.ui.style;

/// Glyph provider supporting Unicode block characters and ASCII fallback.
public class Glyph {

    private static boolean useAsciiOnly = false;

    public static void setAsciiOnly(boolean asciiOnly) {
        useAsciiOnly = asciiOnly;
    }

    public static boolean isAsciiOnly() {
        return useAsciiOnly;
    }

    public static String fillChar() {
        return useAsciiOnly ? "#" : "█";
    }

    public static String emptyChar() {
        return useAsciiOnly ? "-" : "░";
    }

    public static String alertIcon() {
        return useAsciiOnly ? "[ALERT]" : "🚨";
    }

    public static String makeProgressBar(double pct, int width) {
        int filled = (int) Math.round(Math.min(100, Math.max(0, pct)) / 100.0 * width);
        String fillStr = fillChar();
        String emptyStr = emptyChar();
        return "[" + fillStr.repeat(filled) + emptyStr.repeat(Math.max(0, width - filled)) + "]";
    }

    public static String makeMultiSegmentBar(double usedPct, double committedPct, int width) {
        int usedWidth = (int) Math.round(Math.min(100, Math.max(0, usedPct)) / 100.0 * width);
        int committedWidth = (int) Math.round(Math.min(100, Math.max(0, committedPct)) / 100.0 * width);
        int uncommittedWidth = Math.max(0, width - committedWidth);
        int commOnlyWidth = Math.max(0, committedWidth - usedWidth);

        String usedChar = useAsciiOnly ? "#" : "█";
        String commChar = useAsciiOnly ? "=" : "▒";
        String emptyChar = useAsciiOnly ? "-" : "░";

        return "[" + usedChar.repeat(usedWidth) + commChar.repeat(commOnlyWidth) + emptyChar.repeat(uncommittedWidth) + "]";
    }
}
