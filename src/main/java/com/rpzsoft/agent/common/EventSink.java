package com.rpzsoft.agent.common;

/**
 * Sink for agent events (message deltas, tool lifecycle, permissions). The HTTP
 * layer fans these out to SSE clients; embedders can ignore them.
 */
public interface EventSink {

    void publish(String sessionId, Json event);

    EventSink NOOP = new EventSink() {
        @Override
        public void publish(String sessionId, Json event) {
            // drop
        }
    };
}
