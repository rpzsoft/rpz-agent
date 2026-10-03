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
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AgentRunnerTest {

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
                new PermissionGate(), new ScriptedModelClient(), SecretMasker.NOOP, UsageSink.NOOP);
    }

    @After
    public void tearDown() {
        runner.shutdown();
        sandboxes.shutdown();
    }

    @Test
    public void runsToolLoopAndPersistsHistory() throws Exception {
        Json session = store.create(workspaceDir.getAbsolutePath(), "demo");
        final String id = session.getString("id");
        store.appendMessage(id, user(id, "create notes.txt with hello"));

        final CountDownLatch finished = new CountDownLatch(1);
        bus.subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String sessionId, Json event) {
                if (id.equals(sessionId) && "message.end".equals(event.getString("type"))) {
                    finished.countDown();
                }
            }
        });

        assertEquals("started", runner.submit(id, "user-1"));
        assertTrue("agent run did not finish", finished.await(60, TimeUnit.SECONDS));

        File written = new File(workspaceDir, "notes.txt");
        assertTrue(written.isFile());
        assertEquals("hello", new String(Files.readAllBytes(written.toPath()), "UTF-8"));

        List<Json> messages = store.messages(id);
        boolean sawTool = false;
        boolean sawAssistant = false;
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (Json m : messages) {
            assertTrue("duplicate message id " + m.getString("id"), ids.add(m.getString("id")));
            if ("tool".equals(m.getString("role")) && "write".equals(m.getString("name"))) {
                sawTool = true;
                assertTrue(m.getString("content").contains("wrote"));
            }
            if ("assistant".equals(m.getString("role")) && "done".equals(m.getString("content"))) {
                sawAssistant = true;
            }
        }
        assertTrue("tool message missing", sawTool);
        assertTrue("final assistant message missing", sawAssistant);

        Json updated = store.get(id);
        assertNotNull(updated.getJsonArray("checkpoints"));
        assertTrue(updated.getJsonArray("checkpoints").size() >= 1);
    }

    @Test
    public void abortBeforeSubmitIsRejectedForUnknownSession() {
        assertEquals("not-found", runner.submit("missing-session", "u"));
    }

    private static Json user(String sessionId, String text) {
        Json user = new Json();
        user.put("id", IdGen.uuid());
        user.put("sessionId", sessionId);
        user.put("role", "user");
        user.put("content", text);
        user.put("createdAt", System.currentTimeMillis());
        return user;
    }

    /** Writes a text file on the first turn, then answers. */
    static class ScriptedModelClient implements ModelClient {

        @Override
        public String provider() {
            return "scripted";
        }

        @Override
        public String model() {
            return "scripted";
        }

        @Override
        public void stream(Json messages, Json tools, ModelStream stream) {
            int assistants = 0;
            if (messages.isArray()) {
                for (Object o : messages.getArray()) {
                    if (o instanceof Json && "assistant".equals(((Json) o).getString("role"))) {
                        assistants++;
                    }
                }
            }
            if (assistants == 0) {
                Json args = new Json();
                args.put("path", "notes.txt");
                args.put("content", "hello");
                stream.onToolCallDelta(0, "call_1", "write", args.toString());
            } else {
                stream.onDelta("done");
            }
            stream.onUsage(10, 5);
            stream.onComplete();
        }
    }
}
