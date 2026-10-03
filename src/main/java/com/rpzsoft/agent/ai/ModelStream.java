package com.rpzsoft.agent.ai;

/**
 * Streaming callbacks from a model completion.
 */
public interface ModelStream {

    void onDelta(String text);

    void onToolCallDelta(int index, String id, String name, String argsDelta);

    void onUsage(int promptTokens, int completionTokens);

    /** Usage with provider cache accounting (cached tokens are billed cheaper). */
    default void onUsage(int promptTokens, int completionTokens, int cachedTokens) {
        onUsage(promptTokens, completionTokens);
    }

    /** Polled by streaming clients so a user abort can close the connection. */
    boolean isCancelled();

    /** Provider stop reason, e.g. "stop" or "length" when output was truncated. */
    default void onFinishReason(String reason) {
    }

    void onComplete();

    void onError(Throwable t);
}
