package com.rpzsoft.agent.http;

import com.rpzsoft.agent.ai.AgentRunner;
import com.rpzsoft.agent.ai.ModelClient;
import com.rpzsoft.agent.ai.Tool;
import com.rpzsoft.agent.ai.ToolRegistry;
import com.rpzsoft.agent.common.ConfigRegistry;
import com.rpzsoft.agent.common.EventBus;
import com.rpzsoft.agent.common.IdGen;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.session.SessionStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;

/**
 * Minimal opencode-style HTTP/1.1 + SSE server for the standalone agent.
 * Routes are listed in PLAN.md; JSON is rendered with {@link Json}.
 */
public class AgentHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(AgentHttpServer.class);
    private static final String VERSION = "1.0-SNAPSHOT";

    private final AgentRunner runner;
    private final SessionStore store;
    private final EventBus bus;
    private final ToolRegistry tools;
    private final ModelClient model;
    private final File workspaceRoot;
    private final String token;
    /** Optional external UI directory; when null the classpath `/ui` is served. */
    private final File uiDir;

    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel channel;
    private final long startedAt = System.currentTimeMillis();

    public AgentHttpServer(AgentRunner runner, SessionStore store, EventBus bus, ToolRegistry tools,
                           ModelClient model, File workspaceRoot, String token) {
        this.runner = runner;
        this.store = store;
        this.bus = bus;
        this.tools = tools;
        this.model = model;
        this.workspaceRoot = workspaceRoot;
        this.token = token == null ? "" : token.trim();
        String uiProp = ConfigRegistry.getInstance().getProperty("rapps.agent.ui.dir", "");
        File configured = uiProp == null || uiProp.trim().isEmpty() ? null : new File(uiProp.trim());
        this.uiDir = configured != null && configured.isDirectory() ? configured.getAbsoluteFile() : null;
    }

    public synchronized void start(String host, int port) throws Exception {
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(1 << 20));
                        ch.pipeline().addLast(new HttpHandler());
                    }
                });
        channel = bootstrap.bind(host, port).sync().channel();
        logger.info("agent http listening on {}:{}", host, port());
    }

    public int port() {
        if (channel == null) return -1;
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public void awaitTermination() throws InterruptedException {
        if (channel != null) channel.closeFuture().sync();
    }

    /** Exposed for embedded-channel route tests. */
    io.netty.channel.ChannelHandler newChannelHandler() {
        return new HttpHandler();
    }

    public synchronized void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (boss != null) boss.shutdownGracefully();
        if (workers != null) workers.shutdownGracefully();
    }

    private final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private EventBus.Listener sseListener;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
            QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
            String path = decoder.path();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            HttpMethod method = req.method();

            // public UI assets (index/css/js) are served without auth; the API is not
            if (serveStatic(ctx, req, path)) return;

            if (!authorized(req, decoder)) {
                sendError(ctx, req, HttpResponseStatus.UNAUTHORIZED, "unauthorized");
                return;
            }

            if ("/health".equals(path)) {
                sendJson(ctx, req, HttpResponseStatus.OK, json("status", "ok",
                        "uptimeMs", System.currentTimeMillis() - startedAt));
                return;
            }
            if ("/app".equals(path)) {
                handleApp(ctx, req);
                return;
            }
            if ("/config".equals(path)) {
                handleConfig(ctx, req);
                return;
            }
            if ("/event".equals(path)) {
                handleSse(ctx, req);
                return;
            }
            if ("/session".equals(path)) {
                if (method == HttpMethod.GET) {
                    handleListSessions(ctx, req);
                } else if (method == HttpMethod.POST) {
                    handleCreateSession(ctx, req);
                } else {
                    methodNotAllowed(ctx, req);
                }
                return;
            }

            String[] parts = path.split("/");
            if (parts.length >= 3 && "session".equals(parts[1])) {
                String sessionId = parts[2];
                if (parts.length == 3) {
                    if (method == HttpMethod.GET) {
                        handleGetSession(ctx, req, sessionId);
                    } else if (method == HttpMethod.DELETE) {
                        handleDeleteSession(ctx, req, sessionId);
                    } else {
                        methodNotAllowed(ctx, req);
                    }
                    return;
                }
                String action = parts[3];
                if ("message".equals(action) && parts.length == 4) {
                    if (method == HttpMethod.GET) {
                        handleListMessages(ctx, req, sessionId);
                    } else if (method == HttpMethod.POST) {
                        handlePostMessage(ctx, req, sessionId);
                    } else {
                        methodNotAllowed(ctx, req);
                    }
                    return;
                }
                if ("abort".equals(action) && method == HttpMethod.POST) {
                    runner.abort(sessionId);
                    sendJson(ctx, req, HttpResponseStatus.OK, json("status", "aborted",
                            "sessionId", sessionId));
                    return;
                }
                if ("compact".equals(action) && method == HttpMethod.POST) {
                    Json body = body(req);
                    String status = runner.compact(sessionId, body.getString("userId"));
                    sendJson(ctx, req, "started".equals(status) ? HttpResponseStatus.ACCEPTED
                            : HttpResponseStatus.CONFLICT, json("status", status, "sessionId", sessionId));
                    return;
                }
                if ("revert".equals(action) && method == HttpMethod.POST) {
                    Json body = body(req);
                    String messageId = body.getString("messageId");
                    if (messageId == null || messageId.trim().isEmpty()) {
                        sendError(ctx, req, HttpResponseStatus.BAD_REQUEST, "messageId is required");
                        return;
                    }
                    Json result = runner.revert(sessionId, messageId, body.getString("userId"));
                    String status = result.getString("status");
                    HttpResponseStatus code = "reverted".equals(status) ? HttpResponseStatus.OK
                            : "not-found".equals(status) ? HttpResponseStatus.NOT_FOUND
                            : HttpResponseStatus.CONFLICT;
                    sendJson(ctx, req, code, result);
                    return;
                }
                if ("permission".equals(action) && parts.length == 4 && method == HttpMethod.GET) {
                    Json out = new Json(true);
                    for (Json request : runner.pendingPermissions(sessionId)) out.add(request);
                    sendJson(ctx, req, HttpResponseStatus.OK, out);
                    return;
                }
                if ("permission".equals(action) && parts.length == 5 && method == HttpMethod.POST) {
                    String permissionId = parts[4];
                    Json body = body(req);
                    Boolean allow = body.getBoolean("allow");
                    boolean resolved = runner.resolvePermission(permissionId,
                            allow != null && allow.booleanValue());
                    sendJson(ctx, req, HttpResponseStatus.OK, json("resolved", Boolean.valueOf(resolved)));
                    return;
                }
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "not found");
                return;
            }

            sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "not found");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (sseListener != null) {
                bus.unsubscribe(sseListener);
                sseListener = null;
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("http connection error: {}", cause.getMessage());
            ctx.close();
        }

        private void handleApp(ChannelHandlerContext ctx, FullHttpRequest req) {
            Json out = json("name", "agent", "version", VERSION,
                    "cwd", workspaceRoot.getAbsolutePath(),
                    "model", model.model(), "provider", model.provider());
            Json list = new Json(true);
            for (Tool tool : tools.all()) list.add(tool.name());
            out.put("tools", list);
            sendJson(ctx, req, HttpResponseStatus.OK, out);
        }

        private void handleConfig(ChannelHandlerContext ctx, FullHttpRequest req) {
            ConfigRegistry cfg = ConfigRegistry.getInstance();
            Json values = new Json();
            for (String key : cfg.stringPropertyNames()) {
                if (key.startsWith("rapps.agent.")) values.put(key, cfg.getProperty(key));
            }
            Json out = json("provider", model.provider(), "model", model.model(),
                    "cwd", workspaceRoot.getAbsolutePath());
            out.put("tools", Integer.valueOf(tools.size()));
            out.put("config", values);
            sendJson(ctx, req, HttpResponseStatus.OK, out);
        }

        private void handleListSessions(ChannelHandlerContext ctx, FullHttpRequest req) {
            Json out = new Json(true);
            for (Json record : store.list()) out.add(record);
            sendJson(ctx, req, HttpResponseStatus.OK, out);
        }

        private void handleCreateSession(ChannelHandlerContext ctx, FullHttpRequest req) {
            Json body = body(req);
            Json record = store.create(body.getString("directory"), body.getString("title"));
            bus.publish(record.getString("id"), event("session.created", "sessionId",
                    record.getString("id"), "title", record.getString("title")));
            sendJson(ctx, req, HttpResponseStatus.CREATED, record);
        }

        private void handleGetSession(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
            Json record = store.get(id);
            if (record == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "session not found");
                return;
            }
            sendJson(ctx, req, HttpResponseStatus.OK, record);
        }

        private void handleDeleteSession(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
            Json record = store.get(id);
            if (record == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "session not found");
                return;
            }
            runner.abort(id);
            store.delete(id);
            bus.publish(id, event("session.deleted", "sessionId", id));
            sendJson(ctx, req, HttpResponseStatus.OK, json("status", "deleted", "sessionId", id));
        }

        private void handleListMessages(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
            if (store.get(id) == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "session not found");
                return;
            }
            Json out = new Json(true);
            for (Json message : store.messages(id)) out.add(message);
            sendJson(ctx, req, HttpResponseStatus.OK, out);
        }

        private void handlePostMessage(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
            if (store.get(id) == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "session not found");
                return;
            }
            Json body = body(req);
            String text = body.getString("text");
            if (text == null || text.trim().isEmpty()) {
                sendError(ctx, req, HttpResponseStatus.BAD_REQUEST, "text is required");
                return;
            }
            Json user = new Json();
            user.put("id", IdGen.uuid());
            user.put("sessionId", id);
            user.put("role", "user");
            user.put("content", text);
            user.put("createdAt", System.currentTimeMillis());
            store.appendMessage(id, user);

            String status = runner.submit(id, body.getString("userId"));
            HttpResponseStatus code = "started".equals(status) ? HttpResponseStatus.ACCEPTED
                    : "not-found".equals(status) ? HttpResponseStatus.NOT_FOUND
                    : HttpResponseStatus.CONFLICT;
            sendJson(ctx, req, code, json("status", status, "sessionId", id));
        }

        private void handleSse(ChannelHandlerContext ctx, FullHttpRequest req) {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8");
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            HttpUtil.setTransferEncodingChunked(response, true);
            ctx.writeAndFlush(response);

            final Channel ch = ctx.channel();
            sseListener = new EventBus.Listener() {
                @Override
                public void onEvent(String sessionId, Json event) {
                    if (!ch.isActive()) return;
                    if (event.get("sessionId") == null && sessionId != null) {
                        event.put("sessionId", sessionId);
                    }
                    String data = "data: " + event.toString() + "\n\n";
                    ch.writeAndFlush(new DefaultHttpContent(
                            Unpooled.copiedBuffer(data, CharsetUtil.UTF_8)));
                }
            };
            bus.subscribe(sseListener);
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(": connected\n\n",
                    CharsetUtil.UTF_8)));
        }

        private boolean authorized(FullHttpRequest req, QueryStringDecoder decoder) {
            if (token.isEmpty()) return true;
            String header = req.headers().get(HttpHeaderNames.AUTHORIZATION);
            if (header != null && header.equals("Bearer " + token)) return true;
            List<String> query = decoder.parameters().get("access_token");
            return query != null && !query.isEmpty() && token.equals(query.get(0));
        }

        private Json body(FullHttpRequest req) {
            String text = req.content().toString(CharsetUtil.UTF_8);
            if (text == null || text.trim().isEmpty()) return new Json();
            try {
                Json parsed = Json.fromJson(text);
                return parsed.isArray() ? new Json() : parsed;
            } catch (Exception e) {
                return new Json();
            }
        }

        private void methodNotAllowed(ChannelHandlerContext ctx, FullHttpRequest req) {
            sendError(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed");
        }
    }

    /**
     * Serves the bundled UI (or an external {@code rapps.agent.ui.dir}). Returns
     * false when the request does not name an existing asset so normal API
     * routing can continue.
     */
    private boolean serveStatic(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        boolean head = HttpMethod.HEAD.equals(req.method());
        if (!HttpMethod.GET.equals(req.method()) && !head) return false;
        String rel = "/".equals(path) || path.isEmpty() ? "index.html" : path.substring(1);
        if (rel.indexOf("..") >= 0 || rel.startsWith("/") || rel.indexOf('\0') >= 0) {
            sendError(ctx, req, HttpResponseStatus.FORBIDDEN, "forbidden");
            return true;
        }
        byte[] data = readStatic(rel);
        if (data == null) return false;
        sendBytes(ctx, req, HttpResponseStatus.OK, contentType(rel), data, head);
        return true;
    }

    private byte[] readStatic(String rel) {
        if (uiDir != null) {
            try {
                File file = new File(uiDir, rel);
                if (file.isFile() && file.getCanonicalPath().startsWith(uiDir.getCanonicalPath())) {
                    return Files.readAllBytes(file.toPath());
                }
            } catch (Exception ignore) {
                // fall through to the classpath
            }
        }
        InputStream in = AgentHttpServer.class.getResourceAsStream("/ui/" + rel);
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private static String contentType(String rel) {
        if (rel.endsWith(".html")) return "text/html; charset=utf-8";
        if (rel.endsWith(".css")) return "text/css; charset=utf-8";
        if (rel.endsWith(".js") || rel.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (rel.endsWith(".json") || rel.endsWith(".map")) return "application/json; charset=utf-8";
        if (rel.endsWith(".svg")) return "image/svg+xml";
        if (rel.endsWith(".png")) return "image/png";
        if (rel.endsWith(".ico")) return "image/x-icon";
        if (rel.endsWith(".woff2")) return "font/woff2";
        if (rel.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static Json json(Object... kv) {
        return new Json(kv);
    }

    private static Json event(String type, Object... kv) {
        Json e = new Json();
        e.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) e.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return e;
    }

    private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                                 HttpResponseStatus status, Json body) {
        byte[] bytes = body.toString().getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendBytes(ChannelHandlerContext ctx, FullHttpRequest req,
                                  HttpResponseStatus status, String contentType, byte[] bytes,
                                  boolean headOnly) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                headOnly ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendError(ChannelHandlerContext ctx, FullHttpRequest req,
                                  HttpResponseStatus status, String message) {
        sendJson(ctx, req, status, json("error", message, "status", Integer.valueOf(status.code())));
    }
}
