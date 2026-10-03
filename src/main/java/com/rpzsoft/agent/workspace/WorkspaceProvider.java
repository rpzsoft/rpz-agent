package com.rpzsoft.agent.workspace;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@link FileWorkspace} for a session's directory. A single agent
 * process commonly serves several session directories; an embedder with one
 * workspace can just return the same instance.
 */
public interface WorkspaceProvider {

    FileWorkspace get(String directory);

    /** Default provider rooted at a base directory with a per-directory cache. */
    class Local implements WorkspaceProvider {

        private final File base;
        private final Map<String, FileWorkspace> cache = new ConcurrentHashMap<String, FileWorkspace>();

        public Local(File base) {
            this.base = base == null ? new File(".").getAbsoluteFile() : base.getAbsoluteFile();
        }

        public File base() {
            return base;
        }

        @Override
        public FileWorkspace get(String directory) {
            String key = directory == null ? "" : directory.trim();
            FileWorkspace existing = cache.get(key);
            if (existing != null) return existing;
            File dir = key.isEmpty() ? base : new File(key);
            if (!dir.isAbsolute()) dir = new File(base, key);
            FileWorkspace workspace = new FileWorkspace.Local(dir);
            FileWorkspace raced = cache.putIfAbsent(key, workspace);
            return raced == null ? workspace : raced;
        }
    }
}
