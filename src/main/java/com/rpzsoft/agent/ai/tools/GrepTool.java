package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches workspace text files with a regular expression.
 */
public class GrepTool implements Tool {

    private static final int MAX_MATCHES = 200;
    private static final int MAX_LINE_CHARS = 200;
    private static final long MAX_FILE_BYTES = 1024 * 1024;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search workspace files with a regular expression and return file:line matches.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json pattern = new Json();
        pattern.put("type", "string");
        pattern.put("description", "Java regular expression");
        properties.put("pattern", pattern);
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "File or directory to search (default workspace root)");
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
        String patternText = arguments.getString("pattern");
        if (patternText == null || patternText.trim().isEmpty()) return "ERROR: pattern is required";
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (Exception e) {
            return "ERROR: invalid regex: " + e.getMessage();
        }
        String base = arguments.getString("path");
        File root = base == null || base.trim().isEmpty()
                ? context.workspace.dir() : context.workspace.resolve(base);
        if (root == null || !root.exists()) return "ERROR: path not found: " + base;

        List<String> files = new ArrayList<String>();
        if (root.isFile()) {
            files.add(root.getAbsolutePath());
        } else {
            java.util.stream.Stream<Path> stream = Files.walk(root.toPath());
            try {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(path)) continue;
                    String display = context.workspace.dir().toPath().relativize(path)
                            .toString().replace('\\', '/');
                    if (context.workspace.isDenied(display)) continue;
                    files.add(path.toString());
                    if (files.size() > 2000) break;
                }
            } finally {
                stream.close();
            }
        }

        StringBuilder sb = new StringBuilder();
        int matches = 0;
        for (String file : files) {
            File candidate = new File(file);
            if (candidate.length() > MAX_FILE_BYTES) continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(candidate.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (!matcher.find()) continue;
                if (matches++ >= MAX_MATCHES) {
                    sb.append("… (truncated)");
                    return sb.toString();
                }
                String rel = context.workspace.dir().toPath()
                        .relativize(candidate.toPath()).toString().replace('\\', '/');
                String line = lines.get(i).trim();
                if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + "…";
                sb.append(rel).append(':').append(i + 1).append(": ").append(line).append('\n');
            }
        }
        return matches == 0 ? "(no matches)" : sb.toString();
    }
}
