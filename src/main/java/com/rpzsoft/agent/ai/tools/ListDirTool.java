package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Lists a directory inside the workspace as a compact tree listing.
 */
public class ListDirTool implements Tool {

    private static final int MAX_ENTRIES = 200;

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "List files and directories at a workspace path (default: the root).";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "Relative directory path (default '.')");
        properties.put("path", path);
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) {
        String relative = arguments.getString("path");
        File dir = relative == null || relative.trim().isEmpty()
                ? context.workspace.dir()
                : context.workspace.resolve(relative);
        if (dir == null || !dir.isDirectory()) {
            return "ERROR: directory not found: " + relative;
        }
        if (context.workspace.isDenied(relative == null ? "" : relative)) {
            return "ERROR: path is not accessible";
        }
        File[] files = dir.listFiles();
        if (files == null) return "(empty)";
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (File file : files) {
            String path = relative == null || relative.trim().isEmpty()
                    ? file.getName() : relative + "/" + file.getName();
            if (context.workspace.isDenied(path)) continue;
            if (count++ >= MAX_ENTRIES) {
                sb.append("… (truncated)");
                break;
            }
            sb.append(file.isDirectory() ? "[dir]  " : "[file] ");
            sb.append(file.getName());
            if (file.isFile()) sb.append("  (").append(file.length()).append(" bytes)");
            sb.append('\n');
        }
        return count == 0 ? "(empty)" : sb.toString();
    }
}
