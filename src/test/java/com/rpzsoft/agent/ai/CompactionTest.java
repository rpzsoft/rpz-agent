package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.EventBus;
import com.rpzsoft.agent.common.IdGen;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.common.SecretMasker;
import com.rpzsoft.agent.common.UsageSink;
import com.rpzsoft.agent.ai.tools.DefaultTools;
import com.rpzsoft.agent.sandbox.ProcessSandboxProvider;
import com.rpzsoft.agent.sandbox.SandboxManager;
import com.rpzsoft.agent.session.SessionStore;
import com.rpzsoft.agent.workspace.CheckpointService;
import com.rpzsoft.agent.workspace.FileWorkspace;
import com.rpzsoft.agent.workspace.WorkspaceProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the user-triggered /compact flow: {@link AgentRunner#compact} folds
 * older history into a persisted summary message, and a follow-up compaction
 * (or a short history) reports nothing to compact.
 */
public class CompactionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File workspaceDir;
    private SessionStore store;
    private EventBus bus;
    private SandboxManager sandboxes;
    private AgentRunner runner;

    @Before
    public void setUp() throws Exception {
        workspaceDir = tmp.newFolder("workspace");
        File dataDir = tmp.newFolder("data");
        store = new SessionStore.FileStore(new File(dataDir, "sessions"));
        bus = new EventBus();
        sandboxes = new SandboxManager(new ProcessSandboxProvider(),
                new FileWorkspace.Local(workspaceDir), bus, 60000L);
        runner = new AgentRunner(store, new WorkspaceProvider.Local(workspaceDir), sandboxes, bus,
                new CheckpointService(), DefaultTools.registry(), new PermissionEngine(),
                new PermissionGate(), new SummaryModelClient(), SecretMasker.NOOP, UsageSink.NOOP);
    }

    @After
    public void tearDown() {
        runner.shutdown();
        sandboxes.shutdown();
    }

    @Test
    public void compactFoldsHistoryIntoSummary() throws Exception {
        Json session = store.create(workspaceDir.getAbsolutePath(), "demo");
        final String id = session.getString("id");

        // Seed a long history so there is something worth compacting.
        for (int i = 0; i < 40; i++) {
            store.appendMessage(id, message(id, i % 2 == 0 ? "user" : "assistant",
                    "turn " + i + " of a long conversation"));
        }

        final CountDownLatch done = new CountDownLatch(1);
        final Json[] result = new Json[1];
        bus.subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String sessionId, Json event) {
                if (id.equals(sessionId) && "compact.done".equals(event.getString("type"))) {
                    result[0] = event;
                    done.countDown();
                }
            }
        });

        assertEquals("started", runner.compact(id, "user-1"));
        assertTrue("compaction did not finish", done.await(30, TimeUnit.SECONDS));

        assertEquals("compacted", result[0].getString("status"));
        assertTrue("no messages were compacted", result[0].getInt("compactedMessages") > 0);
        assertTrue("model summary was not used", result[0].getBoolean("modelSummary"));

        List<Json> messages = store.messages(id);
        boolean sawSummary = false;
        for (Json m : messages) {
            if ("summary".equals(m.getString("role"))) {
                sawSummary = true;
                assertTrue("summary content is empty", m.getString("content").length() > 0);
                assertEquals("persisted compactedMessages mismatch",
                        result[0].getInt("compactedMessages"), m.getInt("compactedMessages"));
            }
        }
        assertTrue("no summary message was persisted", sawSummary);
    }

    @Test
    public void compactShortHistoryReportsNothingToCompact() throws Exception {
        Json session = store.create(workspaceDir.getAbsolutePath(), "demo");
        final String id = session.getString("id");
        for (int i = 0; i < 4; i++) {
            store.appendMessage(id, message(id, "user", "short turn " + i));
        }

        final CountDownLatch done = new CountDownLatch(1);
        final Json[] result = new Json[1];
        bus.subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String sessionId, Json event) {
                if (id.equals(sessionId) && "compact.done".equals(event.getString("type"))) {
                    result[0] = event;
                    done.countDown();
                }
            }
        });

        assertEquals("started", runner.compact(id, "user-1"));
        assertTrue("compaction did not finish", done.await(30, TimeUnit.SECONDS));

        assertEquals("nothing-to-compact", result[0].getString("status"));
        assertEquals("expected no messages compacted", 0, (int) result[0].getInt("compactedMessages"));
    }

    @Test
    public void planReturnsNullWhenHistoryEndsInSummaryMarker() {
        // After a compaction the history ends in a persisted summary; the next
        // compaction's plan() must see nothing left to fold.
        List<Json> history = new java.util.ArrayList<Json>();
        for (int i = 0; i < 20; i++) {
            history.add(message("id", i % 2 == 0 ? "user" : "assistant", "turn " + i));
        }
        Json marker = message("id", "summary", "Summary of earlier conversation.");
        history.add(marker);

        assertEquals("expected nothing to compact after a prior compaction",
                null, ContextBuilder.plan(history));
    }

    private static Json message(String sessionId, String role, String content) {
        Json m = new Json();
        m.put("id", IdGen.uuid());
        m.put("sessionId", sessionId);
        m.put("role", role);
        m.put("content", content);
        m.put("createdAt", System.currentTimeMillis());
        return m;
    }

    /** Always returns a summary text so the model-summary path is exercised. */
    static class SummaryModelClient implements ModelClient {

        @Override
        public String provider() {
            return "summary-test";
        }

        @Override
        public String model() {
            return "summary-test";
        }

        @Override
        public void stream(Json messages, Json tools, ModelStream stream) {
            stream.onDelta("Summarized the earlier turns into concise bullets.");
            stream.onUsage(5, 3);
            stream.onComplete();
        }
    }
}
