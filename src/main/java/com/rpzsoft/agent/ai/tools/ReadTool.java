package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.ai.ToolOutput;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.file.Files;

/**
 * Reads a workspace file with a bounded line window.
 */
public class ReadTool implements Tool {

    private static final int DEFAULT_LINES = 400;
    private static final int MAX_BYTES = 122880;

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file from the workspace. Returns numbered lines.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "Relative file path");
        properties.put("path", path);
        Json start = new Json();
        start.put("type", "integer");
        start.put("description", "First line (1-based, default 1)");
        properties.put("startLine", start);
        Json lines = new Json();
        lines.put("type", "integer");
        lines.put("description", "Max lines to return (default 400)");
        properties.put("maxLines", lines);
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        Json required = new Json(true);
        required.add("path");
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) throws Exception {
        String relative = arguments.getString("path");
        if (relative == null || relative.trim().isEmpty()) return "ERROR: path is required";
        if (context.workspace.isDenied(relative)) return "ERROR: path is not accessible";
        File file = context.workspace.resolve(relative);
        if (file == null || !file.isFile()) return "ERROR: file not found: " + relative;
        if (file.length() > 2L * 1024 * 1024) return "ERROR: file is too large to read directly";

        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, "UTF-8");
        String[] all = text.split("\n", -1);

        int start = intArg(arguments, "startLine", 1);
        if (start < 1) start = 1;
        int max = intArg(arguments, "maxLines", DEFAULT_LINES);
        if (max <= 0) max = DEFAULT_LINES;
        int from = Math.min(start - 1, all.length);
        int to = Math.min(from + max, all.length);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append('\t').append(all[i]);
            if (i + 1 < to) sb.append('\n');
        }
        if (to < all.length) sb.append("\n… (").append(all.length - to).append(" more lines)");
        return context.mask(ToolOutput.truncate(sb.toString(), MAX_BYTES, "head"));
    }

    private static int intArg(Json arguments, String name, int fallback) {
        Integer value = arguments.getInt(name);
        return value == null ? fallback : value.intValue();
    }
}
