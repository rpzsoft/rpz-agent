package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.ai.ToolOutput;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.sandbox.ExecResult;

/**
 * Runs a shell command in the session sandbox.
 */
public class BashTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 120000L;
    private static final long MAX_TIMEOUT_MS = 600000L;
    private static final int MAX_BYTES = 12000;

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a shell command in the session sandbox and return its combined output.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json command = new Json();
        command.put("type", "string");
        command.put("description", "Shell command to run");
        properties.put("command", command);
        Json timeout = new Json();
        timeout.put("type", "integer");
        timeout.put("description", "Timeout in milliseconds (default 120000)");
        properties.put("timeoutMs", timeout);
        Json required = new Json(true);
        required.add("command");
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) {
        String command = arguments.getString("command");
        if (command == null || command.trim().isEmpty()) return "ERROR: command is required";
        Integer requested = arguments.getInt("timeoutMs");
        long timeout = requested == null ? DEFAULT_TIMEOUT_MS : requested.longValue();
        if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) timeout = DEFAULT_TIMEOUT_MS;

        context.publish("tool.bash.start", "command", command);
        ExecResult result;
        try {
            result = context.sandboxes.exec(context.sessionId, command, timeout,
                    new java.util.function.BooleanSupplier() {
                        @Override
                        public boolean getAsBoolean() {
                            return context.aborted();
                        }
                    });
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
        context.changedFiles.size();

        StringBuilder sb = new StringBuilder();
        if (result.stdout != null && !result.stdout.isEmpty()) sb.append(result.stdout);
        if (result.stderr != null && !result.stderr.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(result.stderr);
        }
        sb.append("\n[exit ").append(result.exitCode).append(']');
        if (result.timedOut) sb.append(" (timed out)");
        if (result.aborted) sb.append(" (aborted)");
        return context.mask(ToolOutput.truncate(sb.toString(), MAX_BYTES, "both"));
    }
}
