package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.ConfigRegistry;

/**
 * Central tool-output policy. All caps are configurable so operators can tune
 * token usage vs. agent information without code changes.
 */
public class ToolLimits {

    private static volatile ToolLimits instance;

    public static ToolLimits get() {
        ToolLimits local = instance;
        if (local == null) {
            synchronized (ToolLimits.class) {
                local = instance;
                if (local == null) {
                    local = new ToolLimits(ConfigRegistry.getInstance());
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Forget the cached instance (used after config changes and in tests). */
    public static synchronized void reset() {
        instance = null;
    }

    private final ConfigRegistry cfg;

    ToolLimits(ConfigRegistry cfg) {
        this.cfg = cfg;
    }

    public int readWindowLines() {
        return intValue("rapps.agent.tools.read.windowLines", 400);
    }

    public int readMaxBytes() {
        return intValue("rapps.agent.tools.read.maxBytes", 120 * 1024);
    }

    public int bashMaxBytes() {
        return intValue("rapps.agent.tools.bash.maxBytes", 12000);
    }

    public boolean bashSpill() {
        return booleanValue("rapps.agent.tools.bash.spill", true);
    }

    public int bashSpillInlineBytes() {
        return intValue("rapps.agent.tools.bash.spillInlineBytes", 4000);
    }

    public int bashRetention() {
        return intValue("rapps.agent.tools.bash.retention", 20);
    }

    public int historyToolBytes() {
        return intValue("rapps.agent.history.toolBytes", 12000);
    }

    public int grepMaxResults() {
        return intValue("rapps.agent.tools.grep.maxResults", 100);
    }

    public int grepLineChars() {
        return intValue("rapps.agent.tools.grep.maxLineChars", 160);
    }

    public int globMaxResults() {
        return intValue("rapps.agent.tools.glob.maxResults", 120);
    }

    public int listMaxEntries() {
        return intValue("rapps.agent.tools.list.maxEntries", 200);
    }

    public String direction() {
        return cfg.getProperty("rapps.agent.tools.truncateDirection", "both");
    }

    private int intValue(String key, int fallback) {
        try {
            String value = cfg.getProperty(key);
            return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean booleanValue(String key, boolean fallback) {
        String value = cfg.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
