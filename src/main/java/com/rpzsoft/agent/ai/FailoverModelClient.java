package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tries each configured provider in order. Failover only happens before the
 * first token/tool delta, so a partially streamed answer is never replayed.
 */
public class FailoverModelClient implements ModelClient {

    private final List<ModelClient> clients;

    public FailoverModelClient(ModelClient... clients) {
        this.clients = new ArrayList<ModelClient>(Arrays.asList(clients));
    }

    @Override
    public String provider() {
        StringBuilder sb = new StringBuilder();
        for (ModelClient client : clients) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(client.provider());
        }
        return sb.toString();
    }

    @Override
    public String model() {
        return clients.isEmpty() ? "none" : clients.get(0).model();
    }

    @Override
    public void stream(Json messages, Json tools, final ModelStream stream) throws Exception {
        Throwable lastError = null;
        for (int i = 0; i < clients.size(); i++) {
            final boolean last = i == clients.size() - 1;
            final AtomicBoolean emitted = new AtomicBoolean(false);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            final AtomicBoolean completed = new AtomicBoolean(false);

            ModelStream wrapper = new ModelStream() {
                @Override
                public void onDelta(String text) {
                    emitted.set(true);
                    stream.onDelta(text);
                }

                @Override
                public void onToolCallDelta(int index, String id, String name, String argsDelta) {
                    emitted.set(true);
                    stream.onToolCallDelta(index, id, name, argsDelta);
                }

                @Override
                public void onUsage(int promptTokens, int completionTokens) {
                    stream.onUsage(promptTokens, completionTokens);
                }

                @Override
                public void onUsage(int promptTokens, int completionTokens, int cachedTokens) {
                    stream.onUsage(promptTokens, completionTokens, cachedTokens);
                }

                @Override
                public boolean isCancelled() {
                    return stream.isCancelled();
                }

                @Override
                public void onFinishReason(String reason) {
                    stream.onFinishReason(reason);
                }

                @Override
                public void onComplete() {
                    completed.set(true);
                    stream.onComplete();
                }

                @Override
                public void onError(Throwable t) {
                    failure.set(t);
                }
            };

            clients.get(i).stream(messages, tools, wrapper);
            if (completed.get()) return;
            if (failure.get() == null) return;
            lastError = failure.get();
            if (emitted.get() || stream.isCancelled()) {
                stream.onError(lastError);
                return;
            }
            if (last) {
                stream.onError(lastError);
                return;
            }
        }
        if (lastError != null) stream.onError(lastError);
    }
}
