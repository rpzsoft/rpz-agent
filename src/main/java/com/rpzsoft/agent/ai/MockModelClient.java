package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline model used when {@code rapps.agent.model.provider=mock}. It makes one
 * deterministic tool call (list the workspace) and then replies with an echo of
 * the prompt, so the whole loop - context, tools, persistence, checkpoints -
 * can be exercised without an API key. It assumes nothing about the project.
 */
public class MockModelClient implements ModelClient {

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock";
    }

    @Override
    public void stream(Json messages, Json tools, ModelStream stream) throws Exception {
        int assistantCount = 0;
        String lastUser = "";
        List<Json> list = new ArrayList<Json>();
        if (messages.isArray()) {
            for (Object o : messages.getArray()) {
                if (o instanceof Json) list.add((Json) o);
            }
        }
        for (Json m : list) {
            String role = m.getString("role");
            if ("assistant".equals(role)) assistantCount++;
            if ("user".equals(role)) lastUser = m.getString("content");
        }

        int completionChars;
        if (assistantCount == 0 && listDirAvailable(tools)) {
            String args = "{\"path\":\".\"}";
            completionChars = args.length();
            stream.onToolCallDelta(0, "call_mock_" + System.nanoTime(), "list_dir", args);
        } else {
            String message = "Offline mock model (no provider configured). "
                    + (lastUser == null || lastUser.trim().isEmpty()
                    ? "Send a request and I will echo it."
                    : "You said: \"" + shorten(lastUser) + "\". ")
                    + "Set rapps.agent.model.provider to an OpenAI-compatible endpoint for real edits.";
            completionChars = message.length();
            text(stream, message);
        }
        stream.onUsage(estimateTokens(messages), Math.max(1, completionChars / 4));
        stream.onComplete();
    }

    private static boolean listDirAvailable(Json tools) {
        if (tools == null || !tools.isArray()) return false;
        for (Object o : tools.getArray()) {
            if (!(o instanceof Json)) continue;
            Json fn = ((Json) o).getJson("function");
            if (fn != null && "list_dir".equals(fn.getString("name"))) return true;
        }
        return false;
    }

    private static int estimateTokens(Json messages) {
        int chars = 0;
        if (messages.isArray()) {
            for (Object o : messages.getArray()) {
                if (o instanceof Json) {
                    String content = ((Json) o).getString("content");
                    if (content != null) chars += content.length();
                }
            }
        }
        return Math.max(1, chars / 4);
    }

    private static String shorten(String value) {
        if (value == null) return "";
        String first = value.trim().split("\\n")[0];
        return first.length() > 120 ? first.substring(0, 120) + "..." : first;
    }

    private static void text(ModelStream stream, String message) {
        int step = 12;
        for (int i = 0; i < message.length(); i += step) {
            stream.onDelta(message.substring(i, Math.min(message.length(), i + step)));
        }
    }
}
