package com.rpzsoft.agent.cli;

import com.rpzsoft.agent.ai.AgentRunner;
import com.rpzsoft.agent.ai.ModelClient;
import com.rpzsoft.agent.ai.MockModelClient;
import com.rpzsoft.agent.ai.OpenAiModelClient;
import com.rpzsoft.agent.ai.PermissionEngine;
import com.rpzsoft.agent.ai.PermissionGate;
import com.rpzsoft.agent.ai.ToolRegistry;
import com.rpzsoft.agent.ai.tools.DefaultTools;
import com.rpzsoft.agent.common.ConfigRegistry;
import com.rpzsoft.agent.common.EventBus;
import com.rpzsoft.agent.common.LoggingUsageSink;
import com.rpzsoft.agent.common.SecretMasker;
import com.rpzsoft.agent.common.UsageSink;
import com.rpzsoft.agent.http.AgentHttpServer;
import com.rpzsoft.agent.sandbox.ProcessSandboxProvider;
import com.rpzsoft.agent.sandbox.SandboxManager;
import com.rpzsoft.agent.session.SessionStore;
import com.rpzsoft.agent.workspace.CheckpointService;
import com.rpzsoft.agent.workspace.FileWorkspace;
import com.rpzsoft.agent.workspace.WorkspaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;

/**
 * Entry point for the standalone agent: builds all ports from configuration and
 * serves the HTTP API (default {@code http://127.0.0.1:5011}).
 *
 * <pre>
 * java -jar agent.jar [--port N] [--host H] [--workspace DIR] [--data DIR] [--config FILE]
 * </pre>
 */
public final class AgentServer {

    private static final Logger logger = LoggerFactory.getLogger(AgentServer.class);

    private AgentServer() {
    }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a.help) {
            System.out.println("usage: agent [--port N] [--host H] [--workspace DIR] "
                    + "[--data DIR] [--config FILE]");
            return;
        }
        ConfigRegistry cfg = ConfigRegistry.getInstance();
        if (a.config != null) {
            File file = new File(a.config);
            if (!file.isFile()) throw new IllegalArgumentException("config file not found: " + a.config);
            FileInputStream in = new FileInputStream(file);
            try {
                cfg.load(in);
            } finally {
                in.close();
            }
        }

        File workspaceRoot = new File(a.workspace != null ? a.workspace
                : cfg.getProperty("rapps.agent.workspace", ".")).getAbsoluteFile();
        File dataRoot = new File(a.data != null ? a.data
                : cfg.getProperty("rapps.agent.data", "data")).getAbsoluteFile();
        String host = a.host != null ? a.host : cfg.getProperty("rapps.agent.http.host", "127.0.0.1");
        int port = a.port > 0 ? a.port
                : intValue(cfg.getProperty("rapps.agent.http.port"), 5011);
        String token = cfg.getProperty("rapps.agent.http.token", "");
        long idleMs = longValue(cfg.getProperty("rapps.agent.sandbox.idleMs"), 600000L);

        SessionStore store = new SessionStore.FileStore(new File(dataRoot, "sessions"));
        EventBus bus = new EventBus();
        SandboxManager sandboxes = new SandboxManager(new ProcessSandboxProvider(),
                new FileWorkspace.Local(workspaceRoot), bus, idleMs);
        CheckpointService checkpoints = new CheckpointService();
        ToolRegistry tools = DefaultTools.registry();
        PermissionEngine permissions = new PermissionEngine();
        PermissionGate gate = new PermissionGate();
        ModelClient model = buildModel(cfg);
        SecretMasker masker = SecretMasker.NOOP;
        UsageSink usage = new LoggingUsageSink();

        AgentRunner runner = new AgentRunner(store, new WorkspaceProvider.Local(workspaceRoot),
                sandboxes, bus, checkpoints, tools, permissions, gate, model, masker, usage);
        AgentHttpServer server = new AgentHttpServer(runner, store, bus, tools, model,
                workspaceRoot, token);
        server.start(host, port);

        final AgentHttpServer running = server;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                running.stop();
                sandboxes.shutdown();
                runner.shutdown();
            }
        }, "agent-shutdown"));

        logger.info("agent ready: http://{}:{}/ (workspace={}, provider={})",
                host, running.port(), workspaceRoot, model.provider());
        server.awaitTermination();
    }

    static ModelClient buildModel(ConfigRegistry cfg) {
        String provider = cfg.getProperty("rapps.agent.model.provider", "mock");
        if (provider == null || provider.trim().isEmpty() || "mock".equalsIgnoreCase(provider.trim())) {
            return new MockModelClient();
        }
        String baseUri = cfg.getProperty("rapps.agent.model.baseUrl", "https://api.openai.com/v1");
        String apiKey = cfg.getProperty("rapps.agent.model.apiKey", "");
        String name = cfg.getProperty("rapps.agent.model.name", "gpt-4o-mini");
        int maxTokens = intValue(cfg.getProperty("rapps.agent.model.maxTokens"), 8192);
        return new OpenAiModelClient(baseUri.trim(), apiKey, name.trim(), maxTokens);
    }

    private static int intValue(String value, int fallback) {
        try {
            return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longValue(String value, long fallback) {
        try {
            return value == null || value.trim().isEmpty() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class Args {
        int port = -1;
        String host;
        String workspace;
        String data;
        String config;
        boolean help;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    a.help = true;
                } else if ("--port".equals(arg) && i + 1 < args.length) {
                    a.port = intValue(args[++i], -1);
                } else if ("--host".equals(arg) && i + 1 < args.length) {
                    a.host = args[++i];
                } else if ("--workspace".equals(arg) && i + 1 < args.length) {
                    a.workspace = args[++i];
                } else if ("--data".equals(arg) && i + 1 < args.length) {
                    a.data = args[++i];
                } else if ("--config".equals(arg) && i + 1 < args.length) {
                    a.config = args[++i];
                }
            }
            return a;
        }
    }
}
