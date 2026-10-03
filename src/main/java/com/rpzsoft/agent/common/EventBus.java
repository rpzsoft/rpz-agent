package com.rpzsoft.agent.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process {@link EventSink} that fans events out to subscribers. The HTTP
 * layer subscribes SSE clients; embedders can subscribe for logging or tests.
 */
public class EventBus implements EventSink {

    /** Receives every published event. Implementations must not throw. */
    public interface Listener {
        void onEvent(String sessionId, Json event);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    public void subscribe(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    public int subscriberCount() {
        return listeners.size();
    }

    @Override
    public void publish(String sessionId, Json event) {
        if (event == null) return;
        for (Listener listener : listeners) {
            try {
                listener.onEvent(sessionId, event);
            } catch (Exception ignore) {
                // a broken subscriber must never break the agent run
            }
        }
    }
}
