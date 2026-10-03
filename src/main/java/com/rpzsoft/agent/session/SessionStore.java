package com.rpzsoft.agent.session;

import com.rpzsoft.agent.common.IdGen;
import com.rpzsoft.agent.common.Json;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for agent sessions and their message history.
 */
public interface SessionStore {

    Json create(String directory, String title);

    Json get(String id);

    List<Json> list();

    void delete(String id);

    void update(String id, Json patch);

    Json appendMessage(String id, Json message);

    /**
     * Replaces a stored message by its `id` (appends when the id is absent).
     * Unlike {@link #appendMessage} this is idempotent, so callers can refine a
     * message (e.g. add `durationMs`) without duplicating it.
     */
    Json updateMessage(String id, Json message);

    List<Json> messages(String id);

    /** Default file-backed implementation: one JSON document per session. */
    class FileStore implements SessionStore {

        private final File root;

        public FileStore(File root) {
            this.root = root;
            if (!root.exists() && !root.mkdirs()) {
                throw new IllegalStateException("cannot create session store " + root);
            }
        }

        private File file(String id) {
            return new File(root, id + ".json");
        }

        private synchronized Json read(String id) {
            File file = file(id);
            if (!file.isFile()) return null;
            try {
                return Json.fromJson(Files.readAllBytes(file.toPath()));
            } catch (Exception e) {
                return null;
            }
        }

        private synchronized void write(String id, Json record) {
            try {
                Files.write(file(id).toPath(), record.toString().getBytes("UTF-8"));
            } catch (Exception e) {
                throw new IllegalStateException("session store write failed", e);
            }
        }

        @Override
        public synchronized Json create(String directory, String title) {
            Json record = new Json();
            record.put("id", IdGen.uuid());
            record.put("title", title == null || title.trim().isEmpty() ? "New session" : title.trim());
            record.put("directory", directory);
            record.put("createdAt", System.currentTimeMillis());
            record.put("updatedAt", System.currentTimeMillis());
            record.put("messages", new Json(true));
            write(record.getString("id"), record);
            return record;
        }

        @Override
        public synchronized Json get(String id) {
            return read(id);
        }

        @Override
        public synchronized List<Json> list() {
            List<Json> out = new ArrayList<Json>();
            File[] files = root.listFiles();
            if (files == null) return out;
            for (File file : files) {
                if (!file.getName().endsWith(".json")) continue;
                Json record = read(file.getName().substring(0, file.getName().length() - 5));
                if (record != null) out.add(record);
            }
            return out;
        }

        @Override
        public synchronized void delete(String id) {
            file(id).delete();
        }

        @Override
        public synchronized void update(String id, Json patch) {
            Json record = read(id);
            if (record == null) return;
            if (patch != null) record.putAll(patch);
            record.put("updatedAt", System.currentTimeMillis());
            write(id, record);
        }

        @Override
        public synchronized Json appendMessage(String id, Json message) {
            Json record = read(id);
            if (record == null) return null;
            List<Json> messages = new ArrayList<Json>();
            List<Json> existing = record.getJsonArray("messages");
            if (existing != null) messages.addAll(existing);
            messages.add(message);
            Json array = new Json(true);
            for (Json item : messages) array.add(item);
            record.put("messages", array);
            record.put("updatedAt", System.currentTimeMillis());
            write(id, record);
            return message;
        }

        @Override
        public synchronized Json updateMessage(String id, Json message) {
            Json record = read(id);
            if (record == null) return null;
            String messageId = message == null ? null : message.getString("id");
            List<Json> messages = new ArrayList<Json>();
            List<Json> existing = record.getJsonArray("messages");
            boolean replaced = false;
            if (existing != null) {
                for (Json item : existing) {
                    if (!replaced && messageId != null && messageId.equals(item.getString("id"))) {
                        messages.add(message);
                        replaced = true;
                    } else {
                        messages.add(item);
                    }
                }
            }
            if (!replaced) messages.add(message);
            Json array = new Json(true);
            for (Json item : messages) array.add(item);
            record.put("messages", array);
            record.put("updatedAt", System.currentTimeMillis());
            write(id, record);
            return message;
        }

        @Override
        public synchronized List<Json> messages(String id) {
            Json record = read(id);
            List<Json> out = new ArrayList<Json>();
            if (record == null) return out;
            List<Json> existing = record.getJsonArray("messages");
            if (existing != null) out.addAll(existing);
            return out;
        }
    }
}
