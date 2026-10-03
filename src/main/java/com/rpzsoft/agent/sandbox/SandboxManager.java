package com.rpzsoft.agent.sandbox;

import com.rpzsoft.agent.common.EventSink;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.workspace.FileWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Supervises one sandbox per session, publishing state and log events.
 */
public class SandboxManager {

    private static final Logger logger = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProvider provider;
    private final FileWorkspace workspace;
    private final EventSink events;
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<String, Sandbox>();
    private final Map<String, FileWorkspace> boundWorkspaces =
            new ConcurrentHashMap<String, FileWorkspace>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
    private final long idleMillis;

    public SandboxManager(SandboxProvider provider, FileWorkspace workspace, EventSink events,
                          long idleMillis) {
        this.provider = provider;
        this.workspace = workspace;
        this.events = events == null ? EventSink.NOOP : events;
        this.idleMillis = idleMillis;
        reaper.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                reapIdle();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public Sandbox get(String sessionId) {
        return sandboxes.get(sessionId);
    }

    /** Binds a session to a workspace so sandboxes run in the session directory. */
    public void bind(String sessionId, FileWorkspace workspace) {
        if (sessionId != null && workspace != null) boundWorkspaces.put(sessionId, workspace);
    }

    public void unbind(String sessionId) {
        if (sessionId != null) boundWorkspaces.remove(sessionId);
    }

    private FileWorkspace workspaceFor(String sessionId) {
        FileWorkspace bound = boundWorkspaces.get(sessionId);
        return bound == null ? workspace : bound;
    }

    public synchronized Sandbox ensureStarted(String sessionId) throws Exception {
        Sandbox existing = sandboxes.get(sessionId);
        if (existing != null && existing.getState() == Sandbox.State.RUNNING) {
            existing.touch();
            return existing;
        }
        if (existing == null) {
            existing = new Sandbox(sessionId, workspaceFor(sessionId).dir());
            sandboxes.put(sessionId, existing);
        }
        return provider.start(existing, loggerFor(sessionId));
    }

    public synchronized void stop(String sessionId) throws Exception {
        Sandbox sandbox = sandboxes.get(sessionId);
        if (sandbox != null) provider.stop(sandbox);
    }

    public synchronized void remove(String sessionId) throws Exception {
        stop(sessionId);
        sandboxes.remove(sessionId);
    }

    public ExecResult exec(String sessionId, String command, long timeoutMs,
                           java.util.function.BooleanSupplier aborted) throws Exception {
        Sandbox sandbox = ensureStarted(sessionId);
        return provider.exec(sandbox, command, timeoutMs, aborted);
    }

    public String providerName() {
        return provider.name();
    }

    public void shutdown() {
        reaper.shutdownNow();
    }

    private SandboxProvider.LogSink loggerFor(final String sessionId) {
        return new SandboxProvider.LogSink() {
            @Override
            public void log(String stream, String line) {
                Json event = new Json();
                event.put("type", "sandbox.log");
                event.put("sessionId", sessionId);
                event.put("stream", stream);
                event.put("line", line);
                events.publish(sessionId, event);
            }

            @Override
            public void state(String state) {
                Json event = new Json();
                event.put("type", "sandbox.state");
                event.put("sessionId", sessionId);
                event.put("state", state);
                events.publish(sessionId, event);
            }
        };
    }

    private void reapIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Sandbox> entry : sandboxes.entrySet()) {
            Sandbox sandbox = entry.getValue();
            if (sandbox.isRunning() && now - sandbox.getLastActivity() > idleMillis) {
                logger.info("reaping idle sandbox {}", entry.getKey());
                try {
                    provider.stop(sandbox);
                } catch (Exception e) {
                    logger.warn("failed reaping {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }
    }
}
