package com.rpzsoft.agent.ai;

/**
 * Trims tool output before it enters the conversation history. Full results
 * stay in the tool_calls collection for the UI/audit; only the copy sent to the
 * model is capped, keeping the head and the tail (where errors usually are).
 */
public final class ToolOutput {

    public static final int DEFAULT_CAP = 12000;

    private ToolOutput() {
    }

    public static String truncate(String text) {
        return truncate(text, DEFAULT_CAP, "both");
    }

    public static String truncate(String text, int max) {
        return truncate(text, max, "both");
    }

    /** direction: both | head | tail */
    public static String truncate(String text, int max, String direction) {
        if (text == null || text.length() <= max) return text;
        String marker = "… (" + (text.length() - max) + " chars omitted) …";
        if ("head".equalsIgnoreCase(direction)) {
            return text.substring(0, max) + "\n" + marker;
        }
        if ("tail".equalsIgnoreCase(direction)) {
            return marker + "\n" + text.substring(text.length() - max);
        }
        int head = (int) (max * 0.6);
        int tail = max - head;
        return text.substring(0, head)
                + "\n" + marker + "\n"
                + text.substring(text.length() - tail);
    }

    /** One-line replacement for old tool results no longer worth re-sending. */
    public static String summarize(String text) {
        if (text == null || text.isEmpty()) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("ERROR")) {
            String oneLine = trimmed.replace('\n', ' ');
            return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
        }
        return "ok (" + text.length() + " chars)";
    }
}
