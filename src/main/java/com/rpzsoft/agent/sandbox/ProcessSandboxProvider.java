package com.rpzsoft.agent.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Local sandbox: runs commands in the workspace directory with `/bin/sh`.
 * Docker/Firecracker providers can be plugged in through {@link SandboxProvider}.
 */
public class ProcessSandboxProvider implements SandboxProvider {

    private static final Logger logger = LoggerFactory.getLogger(ProcessSandboxProvider.class);

    @Override
    public String name() {
        return "process";
    }

    @Override
    public Sandbox start(Sandbox sandbox, LogSink log) throws Exception {
        sandbox.setState(Sandbox.State.RUNNING);
        sandbox.touch();
        log.state("running");
        return sandbox;
    }

    @Override
    public void stop(Sandbox sandbox) throws Exception {
        Process p = sandbox.getProcess();
        if (p != null) {
            p.destroy();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            sandbox.setProcess(null);
        }
        sandbox.setState(Sandbox.State.STOPPED);
    }

    @Override
    public ExecResult exec(Sandbox sandbox, String command, long timeoutMs,
                           java.util.function.BooleanSupplier aborted) throws Exception {
        ExecResult result = new ExecResult();
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.directory(sandbox.getDir());
        pb.environment().putAll(sandbox.getEnv());
        Process p = pb.start();
        p.getOutputStream().close();
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        Thread to = reader(p.getInputStream(), out);
        Thread te = reader(p.getErrorStream(), err);
        to.start();
        te.start();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean finished = false;
        while (System.currentTimeMillis() < deadline) {
            if (p.waitFor(200, TimeUnit.MILLISECONDS)) {
                finished = true;
                break;
            }
            if (aborted != null && aborted.getAsBoolean()) {
                result.aborted = true;
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
                break;
            }
        }
        if (!finished && !result.aborted) {
            result.timedOut = true;
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
        to.join(2000);
        te.join(2000);
        result.stdout = out.toString();
        result.stderr = err.toString();
        result.exitCode = finished ? p.exitValue() : -1;
        sandbox.touch();
        return result;
    }

    private static Thread reader(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sb.append(SandboxProvider.readStream(in));
                } catch (Exception ignore) {
                }
            }
        });
        t.setDaemon(true);
        return t;
    }
}
