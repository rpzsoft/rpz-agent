package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.IdGen;

/**
 * One in-flight agent run for a session. One run per session at a time.
 */
public class AgentSession {

    public final String id = IdGen.uuid();
    /** Workspace directory this run operates on. */
    public final String directory;
    public final String sessionId;
    public final String userId;
    public volatile boolean aborted;
    public volatile boolean running = true;

    public AgentSession(String directory, String sessionId, String userId) {
        this.directory = directory;
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public void abort() {
        aborted = true;
    }
}
