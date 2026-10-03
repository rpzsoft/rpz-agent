package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.sandbox.ExecResult;

/**
 * Syntax-checks JavaScript files via `node --check` in the session sandbox.
 */
public class ValidationService {

    public static boolean isJavaScript(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }

    /** @return error text, or null when the file is valid/not checkable. */
    public static String checkJs(ToolContext ctx, String path) {
        if (!isJavaScript(path)) return null;
        try {
            ExecResult r = ctx.sandboxes.exec(ctx.sessionId,
                    "node --check " + quote(path), 15000L, null);
            if (r.ok()) return null;
            String err = (r.stderr == null ? "" : r.stderr) + (r.stdout == null ? "" : r.stdout);
            // plain .js files without "type": "module" cannot use import/export for --check
            if (err.contains("Cannot use import statement") || err.contains("Unexpected token 'export'")) {
                return null;
            }
            return err.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
