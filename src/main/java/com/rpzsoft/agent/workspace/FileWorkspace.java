package com.rpzsoft.agent.workspace;

import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The agent's view of the files it edits: a root directory, one-level tree and
 * a deny list for secret/internal paths.
 */
public interface FileWorkspace {

    File dir();

    File resolve(String relative);

    boolean isDenied(String relative);

    Json tree();

    /** Default local implementation rooted at a directory. */
    class Local implements FileWorkspace {

        private final File root;
        private final List<String> denied;

        public Local(File root) {
            this(root, Arrays.asList(".env", ".git", ".agent", "node_modules"));
        }

        public Local(File root, List<String> denied) {
            this.root = root.getAbsoluteFile();
            this.denied = denied;
        }

        @Override
        public File dir() {
            return root;
        }

        @Override
        public boolean isDenied(String relative) {
            if (relative == null) return true;
            String path = relative.replace('\\', '/');
            for (String entry : denied) {
                if (path.equals(entry) || path.startsWith(entry + "/")
                        || path.endsWith("/" + entry) || path.contains("/" + entry + "/")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public File resolve(String relative) {
            if (relative == null || relative.trim().isEmpty()) return null;
            String path = relative.replace('\\', '/').trim();
            while (path.startsWith("/")) path = path.substring(1);
            if (path.contains("..")) return null;
            File file = new File(root, path);
            try {
                if (!file.getCanonicalPath().startsWith(root.getCanonicalPath())) return null;
            } catch (Exception e) {
                return null;
            }
            return file;
        }

        @Override
        public Json tree() {
            Json out = new Json(true);
            collect(root, "", out);
            return out;
        }

        private void collect(File directory, String prefix, Json out) {
            File[] files = directory.listFiles();
            if (files == null) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File file : files) {
                String relative = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
                if (isDenied(relative)) continue;
                Json node = new Json();
                node.put("name", file.getName());
                node.put("path", relative);
                if (file.isDirectory()) {
                    node.put("type", "dir");
                    Json children = new Json(true);
                    collect(file, relative, children);
                    node.put("children", children);
                } else {
                    node.put("type", "file");
                    node.put("size", file.length());
                }
                out.add(node);
            }
        }
    }
}
