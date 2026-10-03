package com.rpzsoft.agent.ai.tools;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Recursively walks a workspace, skipping VCS/dependency directories.
 */
public class FileWalker {

    private static final Set<String> SKIP = new HashSet<String>(Arrays.asList(
            ".git", "node_modules", "dist", ".env"));

    public interface Visitor {
        /** @return false to stop walking. */
        boolean file(File f, String rel);
    }

    public static void walk(File root, Visitor visitor) {
        walk(root, root, visitor);
    }

    private static boolean walk(File root, File dir, Visitor visitor) {
        File[] files = dir.listFiles();
        if (files == null) return true;
        Arrays.sort(files);
        for (File f : files) {
            if (SKIP.contains(f.getName())) continue;
            if (f.isDirectory()) {
                if (!walk(root, f, visitor)) return false;
            } else {
                String rel = root.toPath().relativize(f.toPath()).toString()
                        .replace(File.separatorChar, '/');
                if (!visitor.file(f, rel)) return false;
            }
        }
        return true;
    }
}
