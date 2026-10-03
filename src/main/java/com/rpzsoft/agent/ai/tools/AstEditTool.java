package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolContext;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Structural edit: replaces a whole balanced block (a `{ ... }` body or an
 * HTML element including nested same-name tags) identified by a start snippet.
 */
public class AstEditTool implements Tool {

    @Override
    public String name() {
        return "ast_edit";
    }

    @Override
    public String description() {
        return "Replace a balanced block. Provide a snippet that begins the block and its replacement; "
                + "supports `{...}` bodies and `<tag>...</tag>` elements.";
    }

    @Override
    public Json parameters() {
        Json properties = new Json();
        Json path = new Json();
        path.put("type", "string");
        path.put("description", "Relative file path");
        properties.put("path", path);
        Json start = new Json();
        start.put("type", "string");
        start.put("description", "Snippet at the block start, e.g. `function main(` or `<section id=\"hero\">`");
        properties.put("start", start);
        Json replacement = new Json();
        replacement.put("type", "string");
        replacement.put("description", "Replacement for the balanced block (including its braces/tags)");
        properties.put("replacement", replacement);
        Json required = new Json(true);
        required.add("path");
        required.add("start");
        required.add("replacement");
        Json schema = new Json();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(ToolContext context, Json arguments) throws Exception {
        String relative = arguments.getString("path");
        String start = arguments.getString("start");
        String replacement = arguments.getString("replacement");
        if (relative == null || start == null || replacement == null) {
            return "ERROR: path, start and replacement are required";
        }
        if (context.workspace.isDenied(relative)) return "ERROR: path is not accessible";
        File file = context.workspace.resolve(relative);
        if (file == null || !file.isFile()) return "ERROR: file not found: " + relative;

        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int anchor = text.indexOf(start);
        if (anchor < 0) return "ERROR: start snippet not found in " + relative;

        int brace = text.indexOf('{', anchor);
        int tag = text.indexOf('<', anchor);
        int begin;
        int end;
        if (brace >= 0 && (tag < 0 || brace < tag)) {
            begin = brace;
            end = matchBrace(text, brace);
        } else if (tag >= 0) {
            int nameEnd = tagNameEnd(text, tag);
            if (nameEnd < 0) return "ERROR: unsupported tag at start snippet";
            String name = text.substring(tag + 1, nameEnd);
            begin = tag;
            end = matchTag(text, begin, name);
        } else {
            return "ERROR: no brace or tag found after the start snippet";
        }
        if (end < 0) return "ERROR: could not find the end of the block (unbalanced)";

        String updated = text.substring(0, begin) + replacement + text.substring(end);
        Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
        context.changedFiles.add(relative);
        return "ast_edit replaced " + (end - begin) + " chars in " + relative;
    }

    private static int matchBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static int tagNameEnd(String text, int tag) {
        int i = tag + 1;
        if (i >= text.length() || !Character.isLetter(text.charAt(i))) return -1;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i))
                || text.charAt(i) == '-' || text.charAt(i) == '_')) {
            i++;
        }
        return i;
    }

    private static int matchTag(String text, int open, String name) {
        int depth = 1;
        int i = text.indexOf('>', open) + 1;
        String lower = name.toLowerCase();
        while (i > 0 && i < text.length()) {
            int next = text.indexOf('<', i);
            if (next < 0) return -1;
            if (next + 1 < text.length() && text.charAt(next + 1) == '/') {
                int end = text.indexOf('>', next);
                if (end < 0) return -1;
                String closing = text.substring(next + 2, end).trim().toLowerCase();
                if (closing.equals(lower)) {
                    depth--;
                    if (depth == 0) return end + 1;
                }
            } else {
                int nameEnd = tagNameEnd(text, next);
                if (nameEnd > 0 && text.substring(next + 1, nameEnd).toLowerCase().equals(lower)) {
                    int tagEnd = text.indexOf('>', nameEnd);
                    boolean selfClosing = tagEnd > 0 && text.charAt(tagEnd - 1) == '/';
                    if (!selfClosing) depth++;
                }
            }
            i = next + 1;
        }
        return -1;
    }
}
