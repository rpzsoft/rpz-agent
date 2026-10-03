package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Replaces a unique snippet in an existing file.
 */
public class EditTool implements Tool {

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Replace an exact, uniquely occurring snippet in an existing file.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "Relative file path");
        properties.put("path", path);
        Json oldText = new Json();
        oldText.put("type", "string");
        oldText.put("description", "Exact text to replace (must occur once)");
        properties.put("oldText", oldText);
        Json newText = new Json();
        newText.put("type", "string");
        newText.put("description", "Replacement text");
        properties.put("newText", newText);
        Json required = new Json(true);
        required.add("path");
        required.add("oldText");
        required.add("newText");
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) throws Exception {
        String relative = arguments.getString("path");
        String oldText = arguments.getString("oldText");
        String newText = arguments.getString("newText");
        if (relative == null || oldText == null || newText == null) {
            return "ERROR: path, oldText and newText are required";
        }
        if (context.workspace.isDenied(relative)) return "ERROR: path is not accessible";
        File file = context.workspace.resolve(relative);
        if (file == null || !file.isFile()) return "ERROR: file not found: " + relative;

        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int first = text.indexOf(oldText);
        if (first < 0) return "ERROR: oldText not found in " + relative;
        if (text.indexOf(oldText, first + 1) >= 0) {
            return "ERROR: oldText occurs more than once; include more context to make it unique";
        }
        String updated = text.substring(0, first) + newText + text.substring(first + oldText.length());
        Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
        context.changedFiles.add(relative);
        return "edited " + relative;
    }
}
