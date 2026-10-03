package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI-compatible /chat/completions client with SSE streaming, over Netty.
 */
public class OpenAiModelClient implements ModelClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiModelClient.class);

    private final String baseUri;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final long timeoutMs;

    public OpenAiModelClient(String baseUri, String apiKey, String model, int maxTokens) {
        this.baseUri = baseUri;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeoutMs = 180000L;
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void stream(Json messages, Json tools, final ModelStream stream) throws Exception {
        ensureSingleSystemMessage(messages);
        final Json payload = new Json();
        payload.put("model", model);
        payload.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        payload.put("stream", true);
        Json streamOptions = new Json();
        streamOptions.put("include_usage", true);
        payload.put("stream_options", streamOptions);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", maxTokens);

        final URI uri = new URI(baseUri);
        final String host = uri.getHost();
        final int port = uri.getPort() > 0 ? uri.getPort()
                : ("https".equals(uri.getScheme()) ? 443 : 80);
        final boolean ssl = "https".equals(uri.getScheme());
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "" : uri.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.endsWith("/chat/completions")) path = path + "/chat/completions";

        final byte[] body = payload.toString().getBytes(CharsetUtil.UTF_8);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final SseParser parser = new SseParser(stream);
        final EventLoopGroup group = new NioEventLoopGroup(1);

        try {
            final SslContext sslCtx = ssl ? SslContextBuilder.forClient().build() : null;
            final String fpath = path;
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (sslCtx != null) {
                                ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), host, port));
                            }
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpContentDecompressor());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpObject>() {
                                private int status;
                                private final StringBuilder errorBody = new StringBuilder();

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg)
                                        throws Exception {
                                    if (msg instanceof HttpResponse) {
                                        status = ((HttpResponse) msg).status().code();
                                    } else if (msg instanceof HttpContent) {
                                        String text = ((HttpContent) msg).content().toString(CharsetUtil.UTF_8);
                                        if (status >= 400) errorBody.append(text);
                                        else parser.feed(text);
                                        if (msg instanceof LastHttpContent) {
                                            if (status >= 400) {
                                                parser.failWith(status, errorBody.toString());
                                            } else {
                                                parser.finish();
                                            }
                                            done.countDown();
                                        }
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    if (status >= 400) parser.failWith(status, errorBody.toString());
                                    done.countDown();
                                    super.channelInactive(ctx);
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    error.compareAndSet(null, cause);
                                    done.countDown();
                                    ctx.close();
                                }
                            });
                        }
                    });

            final Channel ch = b.connect(host, port).sync().channel();
            // Poll for user cancellation so an in-flight stream can be closed promptly.
            final io.netty.util.concurrent.ScheduledFuture<?> cancelWatch =
                    group.scheduleAtFixedRate(new Runnable() {
                        @Override
                        public void run() {
                            if (parser.cancelled() && ch.isActive()) {
                                logger.info("cancellation detected, closing model stream to {}", host);
                                ch.close();
                            }
                        }
                    }, 200, 200, TimeUnit.MILLISECONDS);
            FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                    fpath, Unpooled.wrappedBuffer(body));
            req.headers().set(HttpHeaderNames.HOST, host);
            req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            req.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                req.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + apiKey);
            }
            ch.writeAndFlush(req);

            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                error.compareAndSet(null, ModelException.timeout());
            }
            logger.debug("model stream ended host={} cancelled={} error={}", host,
                    parser.cancelled(), error.get() == null ? "none" : error.get().getMessage());
            if (error.get() != null) {
                stream.onError(error.get() instanceof ModelException ? error.get()
                        : ModelException.connection(error.get()));
            }
        } catch (Throwable t) {
            logger.warn("model request failed: {}", t.getMessage());
            stream.onError(t instanceof ModelException ? t : ModelException.connection(t));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Strict chat templates (Llama family, some local servers) accept a system
     * message only as the very first one and raise otherwise. Demote any later
     * system message to a user message so the payload works everywhere.
     */
    static void ensureSingleSystemMessage(Json messages) {
        if (messages == null || !messages.isArray()) return;
        java.util.List<Object> list = messages.getArray();
        for (int i = 1; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof Json)) continue;
            Json m = (Json) o;
            if ("system".equals(m.getString("role"))) m.put("role", "user");
        }
    }

    /** Incremental SSE parser translating OpenAI deltas into ModelStream calls. */
    static class SseParser {
        private final ModelStream stream;
        private final StringBuilder buffer = new StringBuilder();
        private boolean completed;

        SseParser(ModelStream stream) {
            this.stream = stream;
        }

        boolean cancelled() {
            try {
                return stream.isCancelled();
            } catch (Exception e) {
                return false;
            }
        }

        void feed(String chunk) {
            buffer.append(chunk);
            int idx;
            while ((idx = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, idx);
                buffer.delete(0, idx + 1);
                handleLine(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
        }

        private void handleLine(String line) {
            if (!line.startsWith("data:")) return;
            String data = line.substring(5).trim();
            if (data.isEmpty()) return;
            if ("[DONE]".equals(data)) {
                finish();
                return;
            }
            try {
                Json event = Json.fromJson(data);
                Json usage = event.getJson("usage");
                if (usage != null) {
                    Integer p = usage.getInt("prompt_tokens");
                    Integer c = usage.getInt("completion_tokens");
                    Integer cached = null;
                    Json details = usage.getJson("prompt_tokens_details");
                    if (details != null) cached = details.getInt("cached_tokens");
                    if (cached == null) cached = usage.getInt("prompt_cache_hit_tokens");
                    stream.onUsage(p == null ? 0 : p, c == null ? 0 : c,
                            cached == null ? 0 : cached);
                }
                java.util.List<Json> choices = event.getJsonArray("choices");
                if (choices == null || choices.isEmpty()) return;
                Json choice = choices.get(0);
                String finish = choice.getString("finish_reason");
                if (finish != null) stream.onFinishReason(finish);
                Json delta = choice.getJson("delta");
                if (delta == null) return;
                String text = delta.getString("content");
                if (text != null && !text.isEmpty()) stream.onDelta(text);
                java.util.List<Json> tcs = delta.getJsonArray("tool_calls");
                if (tcs != null) {
                    for (Json tc : tcs) {
                        Integer index = tc.getInt("index");
                        String id = tc.getString("id");
                        Json fn = tc.getJson("function");
                        String name = fn == null ? null : fn.getString("name");
                        String args = fn == null ? null : fn.getString("arguments");
                        stream.onToolCallDelta(index == null ? 0 : index, id, name, args);
                    }
                }
            } catch (Exception e) {
                logger.debug("bad SSE line: {}", e.getMessage());
            }
        }

        void fail(String message) {
            if (!completed) {
                completed = true;
                stream.onError(new RuntimeException(message));
            }
        }

        /** Surfaces the provider's error body with an HTTP status and retryability. */
        void failWith(int status, String body) {
            String message = extractError(body);
            if (message == null || message.isEmpty()) message = "model API request failed";
            if (!completed) {
                completed = true;
                stream.onError(ModelException.http(status, message));
            }
        }

        private static String extractError(String body) {
            if (body == null) return null;
            try {
                Json parsed = Json.fromJson(body);
                Json error = parsed.getJson("error");
                if (error != null) {
                    String message = error.getString("message");
                    if (message != null) return truncate(message);
                }
            } catch (Exception ignore) {
                // body is not JSON
            }
            return truncate(body.trim());
        }

        private static String truncate(String text) {
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        }

        void finish() {
            if (!completed) {
                completed = true;
                stream.onComplete();
            }
        }
    }
}
