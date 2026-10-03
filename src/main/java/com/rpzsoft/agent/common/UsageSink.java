package com.rpzsoft.agent.common;

/**
 * Observability hook for model and tool activity. All callbacks default to
 * no-ops so embedders only implement what they need.
 */
public interface UsageSink {

    default void onModelCall(String sessionId, String model, long promptTokens,
                             long completionTokens, long cachedTokens, long costMicros) {
    }

    default void onToolCall(String sessionId, String tool, boolean ok) {
    }

    UsageSink NOOP = new UsageSink() {
    };
}
