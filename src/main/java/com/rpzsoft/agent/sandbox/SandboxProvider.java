package com.rpzsoft.agent.sandbox;

import java.io.File;

/**
 * Execution backend for project runtimes. Process (local dev), Docker and
 * Firecracker implementations share this lifecycle.
 */
public interface SandboxProvider {

    String name();

    Sandbox start(Sandbox sandbox, LogSink log) throws Exception;

    void stop(Sandbox sandbox) throws Exception;

    ExecResult exec(Sandbox sandbox, String command, long timeoutMs,
                    java.util.function.BooleanSupplier aborted) throws Exception;

    interface LogSink {
        void log(String stream, String line);

        void state(String state);
    }

    static String readStream(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[5011];
        int n;
        while ((n = in.read(buf)) >= 0) sb.append(new String(buf, 0, n, "UTF-8"));
        return sb.toString();
    }
}
