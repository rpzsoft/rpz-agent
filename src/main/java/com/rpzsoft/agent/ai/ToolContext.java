package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.EventSink;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.common.SecretMasker;
import com.rpzsoft.agent.sandbox.SandboxManager;
import com.rpzsoft.agent.workspace.FileWorkspace;

/**
 * Everything a tool needs for one session run. Built from ports so the tool
 * layer has no dependency on the host application.
 */
public class ToolContext {

    public final String sessionId;
    public final FileWorkspace workspace;
    public final SandboxManager sandboxes;
    public final EventSink events;
    public final AgentSession session;
    public final SecretMasker masker;
    public final java.util.Set<String> changedFiles = new java.util.LinkedHashSet<String>();
    public volatile String toolCallId;

    public ToolContext(String sessionId, FileWorkspace workspace, SandboxManager sandboxes,
                       EventSink events, AgentSession session, SecretMasker masker) {
        this.sessionId = sessionId;
        this.workspace = workspace;
        this.sandboxes = sandboxes;
        this.events = events == null ? EventSink.NOOP : events;
        this.session = session;
        this.masker = masker == null ? SecretMasker.NOOP : masker;
    }

    public String mask(String text) {
        return masker.mask(text);
    }

    public boolean aborted() {
        return session != null && session.aborted;
    }

    public void publish(String type, String... kv) {
        Json event = new Json();
        event.put("type", type);
        event.put("sessionId", sessionId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            event.put(kv[i], kv[i + 1]);
        }
        events.publish(sessionId, event);
    }
}
