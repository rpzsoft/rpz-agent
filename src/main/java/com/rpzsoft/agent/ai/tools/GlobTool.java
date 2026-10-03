package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds workspace files matching a glob pattern.
 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find workspace files matching a glob pattern, e.g. **/*.js.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json pattern = new Json();
        pattern.put("type", "string");
        pattern.put("description", "Glob pattern, e.g. **/*.css");
        properties.put("pattern", pattern);
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "Base directory (default workspace root)");
        properties.put("path", path);
        Json required = new Json(true);
        required.add("pattern");
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) throws Exception {
        String pattern = arguments.getString("pattern");
        if (pattern == null || pattern.trim().isEmpty()) return "ERROR: pattern is required";
        String base = arguments.getString("path");
        File root = base == null || base.trim().isEmpty()
                ? context.workspace.dir() : context.workspace.resolve(base);
        if (root == null || !root.isDirectory()) return "ERROR: directory not found: " + base;

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim());
        Path basePath = root.toPath();
        List<String> matches = new ArrayList<String>();
        java.util.stream.Stream<Path> stream = Files.walk(basePath);
        try {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (matches.size() >= MAX_RESULTS) break;
                if (!Files.isRegularFile(path)) continue;
                Path relative = basePath.relativize(path);
                if (!matcher.matches(relative)) continue;
                String display = relative.toString().replace('\\', '/');
                if (context.workspace.isDenied(display)) continue;
                matches.add(display);
            }
        } finally {
            stream.close();
        }
        if (matches.isEmpty()) return "(no matches)";
        Collections.sort(matches);
        StringBuilder sb = new StringBuilder();
        for (String match : matches) sb.append(match).append('\n');
        if (matches.size() >= MAX_RESULTS) sb.append("… (truncated)");
        return sb.toString();
    }
}
