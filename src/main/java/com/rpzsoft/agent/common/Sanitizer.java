package com.rpzsoft.agent.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes server filesystem roots from text that is sent to clients (errors,
 * tool output, chat messages). Internal stack traces stay in the server log.
 */
public final class Sanitizer {

    private static final Map<String, String> ROOTS = new ConcurrentHashMap<String, String>();

    private Sanitizer() {
    }

    /** Replaces every occurrence of an absolute root path with a label. */
    public static void addRoot(String path, String replacement) {
        if (path == null || path.trim().isEmpty()) return;
        ROOTS.put(new File(path).getAbsolutePath(), replacement);
    }

    public static String clean(String text) {
        if (text == null || text.isEmpty()) return text;
        List<String> keys = new ArrayList<String>(ROOTS.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        String out = text;
        for (String root : keys) {
            out = out.replace(root, ROOTS.get(root));
        }
        return out;
    }

    /**
     * Client-safe representation of an unexpected failure. Raw exception text
     * (provider errors, stack details, paths) stays in the server log, never in
     * the assistant UI.
     */
    public static String publicError(Throwable error) {
        String message = error == null ? null : clean(error.getMessage());
        if (message != null && (message.contains("repository not found")
                || message.contains("RepositoryNotFound"))) {
            return "workspace checkpoint failed; the workspace was re-initialized, retry the request";
        }
        return "Something went wrong. Please try again.";
    }
}
