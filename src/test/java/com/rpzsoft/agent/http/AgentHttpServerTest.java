package com.rpzsoft.agent.http;

import com.rpzsoft.agent.ai.AgentRunner;
import com.rpzsoft.agent.ai.MockModelClient;
import com.rpzsoft.agent.ai.PermissionEngine;
import com.rpzsoft.agent.ai.PermissionGate;
import com.rpzsoft.agent.ai.tools.DefaultTools;
import com.rpzsoft.agent.common.EventBus;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.common.SecretMasker;
import com.rpzsoft.agent.common.UsageSink;
import com.rpzsoft.agent.sandbox.ProcessSandboxProvider;
import com.rpzsoft.agent.sandbox.SandboxManager;
import com.rpzsoft.agent.session.SessionStore;
import com.rpzsoft.agent.workspace.CheckpointService;
import com.rpzsoft.agent.workspace.FileWorkspace;
import com.rpzsoft.agent.workspace.WorkspaceProvider;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AgentHttpServerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SessionStore store;
    private SandboxManager sandboxes;
    private AgentRunner runner;
    private AgentHttpServer server;
    private EventBus bus;

    @Before
    public void setUp() throws Exception {
        File workspace = tmp.newFolder("workspace");
        File data = tmp.newFolder("data");
        store = new SessionStore.FileStore(new File(data, "sessions"));
        bus = new EventBus();
        sandboxes = new SandboxManager(new ProcessSandboxProvider(), new FileWorkspace.Local(workspace),
                bus, 60000L);
        runner = new AgentRunner(store, new WorkspaceProvider.Local(workspace), sandboxes, bus,
                new CheckpointService(), DefaultTools.registry(), new PermissionEngine(),
                new PermissionGate(), new MockModelClient(), SecretMasker.NOOP, UsageSink.NOOP);
        server = new AgentHttpServer(runner, store, bus, DefaultTools.registry(),
                new MockModelClient(), workspace, "");
    }

    @After
    public void tearDown() {
        runner.shutdown();
        sandboxes.shutdown();
    }

    @Test
    public void healthAndAppRoutes() {
        FullHttpResponse health = call(HttpMethod.GET, "/health", null);
        assertEquals(HttpResponseStatus.OK, health.status());
        assertTrue(content(health).contains("\"status\":\"ok\""));

        FullHttpResponse app = call(HttpMethod.GET, "/app", null);
        assertEquals(HttpResponseStatus.OK, app.status());
        assertTrue(content(app).contains("\"tools\""));

        FullHttpResponse config = call(HttpMethod.GET, "/config", null);
        assertEquals(HttpResponseStatus.OK, config.status());
        assertTrue(content(config).contains("rapps.agent."));
    }

    @Test
    public void sessionLifecycle() {
        FullHttpResponse created = call(HttpMethod.POST, "/session", "{\"title\":\"demo\"}");
        assertEquals(HttpResponseStatus.CREATED, created.status());
        String body = content(created);
        String id = Json.fromJson(body).getString("id");
        assertNotNull(id);

        FullHttpResponse fetched = call(HttpMethod.GET, "/session/" + id, null);
        assertEquals(HttpResponseStatus.OK, fetched.status());
        assertEquals(id, Json.fromJson(content(fetched)).getString("id"));

        FullHttpResponse listed = call(HttpMethod.GET, "/session", null);
        assertEquals(HttpResponseStatus.OK, listed.status());
        assertTrue(content(listed).contains(id));

        FullHttpResponse messages = call(HttpMethod.GET, "/session/" + id + "/message", null);
        assertEquals(HttpResponseStatus.OK, messages.status());
        assertEquals(0, Json.fromJson(content(messages)).size());

        FullHttpResponse permissions = call(HttpMethod.GET, "/session/" + id + "/permission", null);
        assertEquals(HttpResponseStatus.OK, permissions.status());

        FullHttpResponse deleted = call(HttpMethod.DELETE, "/session/" + id, null);
        assertEquals(HttpResponseStatus.OK, deleted.status());
        assertEquals(HttpResponseStatus.NOT_FOUND,
                call(HttpMethod.GET, "/session/" + id, null).status());
    }

    @Test
    public void postMessageStartsRun() throws Exception {
        FullHttpResponse created = call(HttpMethod.POST, "/session", "{\"title\":\"run\"}");
        final String id = Json.fromJson(content(created)).getString("id");

        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(1);
        bus.subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String sessionId, Json event) {
                if (id.equals(sessionId) && "message.end".equals(event.getString("type"))) {
                    finished.countDown();
                }
            }
        });

        FullHttpResponse posted = call(HttpMethod.POST, "/session/" + id + "/message",
                "{\"text\":\"build me a page\"}");
        assertEquals(HttpResponseStatus.ACCEPTED, posted.status());
        assertEquals("started", Json.fromJson(content(posted)).getString("status"));
        assertTrue("mock run did not finish", finished.await(60, java.util.concurrent.TimeUnit.SECONDS));

        FullHttpResponse missing = call(HttpMethod.POST, "/session/nope/message",
                "{\"text\":\"hi\"}");
        assertEquals(HttpResponseStatus.NOT_FOUND, missing.status());
    }

    @Test
    public void unknownRouteIs404() {
        assertEquals(HttpResponseStatus.NOT_FOUND, call(HttpMethod.GET, "/nope", null).status());
    }

    @Test
    public void servesBundledUiAssets() {
        FullHttpResponse index = call(HttpMethod.GET, "/", null);
        assertEquals(HttpResponseStatus.OK, index.status());
        assertTrue(index.headers().get(HttpHeaderNames.CONTENT_TYPE).startsWith("text/html"));
        assertTrue(content(index).contains("<!DOCTYPE html>"));
        assertTrue(content(index).contains("js/app.js"));

        FullHttpResponse js = call(HttpMethod.GET, "/js/app.js", null);
        assertEquals(HttpResponseStatus.OK, js.status());
        assertTrue(js.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("javascript"));

        FullHttpResponse css = call(HttpMethod.GET, "/css/app.css", null);
        assertEquals(HttpResponseStatus.OK, css.status());
        assertTrue(css.headers().get(HttpHeaderNames.CONTENT_TYPE).startsWith("text/css"));

        FullHttpResponse traversal = call(HttpMethod.GET, "/../pom.xml", null);
        assertEquals(HttpResponseStatus.FORBIDDEN, traversal.status());
    }

    private FullHttpResponse call(HttpMethod method, String uri, String body) {
        EmbeddedChannel channel = new EmbeddedChannel(server.newChannelHandler());
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri,
                body == null ? Unpooled.EMPTY_BUFFER : Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        channel.finishAndReleaseAll();
        return response;
    }

    private static String content(FullHttpResponse response) {
        return response.content().toString(CharsetUtil.UTF_8);
    }
}
