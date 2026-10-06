package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.ConfigRegistry;
import com.rpzsoft.agent.common.Json;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tool policy: allow, ask or deny. `bash` is the only default tool that can
 * escape the workspace, so it defaults to "ask" and can be overridden per
 * deployment. Extensions can register tools (and their fallback modes) while
 * agent runs are already in flight, so the map must stay thread-safe.
 *
 * Configured with `rpzag.permissions.&lt;tool&gt;`.
 */
public class PermissionEngine {

    public enum Mode {
        ALLOW, ASK, DENY
    }

    private final Map<String, Mode> modes = new ConcurrentHashMap<String, Mode>();

    public PermissionEngine() {
        allow("read");
        allow("write");
        allow("edit");
        allow("ast_edit");
        allow("glob");
        allow("grep");
        allow("list_dir");
        modes.put("bash", mode(config("bash"), Mode.ASK));
    }

    private void allow(String tool) {
        modes.put(tool, Mode.ALLOW);
    }

    private static String config(String tool) {
        return ConfigRegistry.getInstance().getProperty("rpzag.permissions." + tool);
    }

    private static Mode mode(String value, Mode fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Mode.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }

    public Mode check(String tool, Json args) {
        Mode m = modes.get(tool);
        if (m != null) return m;
        // tools registered after startup (MCP, custom) are governed by
        // rpzag.permissions.<tool>; unknown tools keep the permissive default
        return mode(config(tool), Mode.ALLOW);
    }

    /**
     * Registers a fallback mode for a tool without overriding an explicit or
     * configured mode. Extensions use this for custom command tools.
     */
    public void ensure(String tool, Mode fallback) {
        if (modes.containsKey(tool)) return;
        modes.put(tool, mode(config(tool), fallback));
    }

    public void set(String tool, Mode mode) {
        modes.put(tool, mode);
    }
}
