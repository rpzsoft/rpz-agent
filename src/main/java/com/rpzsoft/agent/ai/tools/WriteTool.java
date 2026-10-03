package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.file.Files;

/**
 * Creates or fully rewrites a workspace file.
 */
public class WriteTool implements Tool {

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Create or fully rewrite a UTF-8 file in the workspace. Use edit for small changes.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "Relative file path");
        properties.put("path", path);
        Json content = new Json();
        content.put("type", "string");
        content.put("description", "Full file content");
        properties.put("content", content);
        Json required = new Json(true);
        required.add("path");
        required.add("content");
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) throws Exception {
        String relative = arguments.getString("path");
        String content = arguments.getString("content");
        if (relative == null || relative.trim().isEmpty()) return "ERROR: path is required";
        if (content == null) content = "";
        if (context.workspace.isDenied(relative)) return "ERROR: path is not accessible";
        File file = context.workspace.resolve(relative);
        if (file == null) return "ERROR: invalid path: " + relative;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return "ERROR: could not create directory for " + relative;
        }
        byte[] bytes = content.getBytes("UTF-8");
        Files.write(file.toPath(), bytes);
        context.changedFiles.add(relative);
        return "wrote " + bytes.length + " bytes to " + relative;
    }
}
