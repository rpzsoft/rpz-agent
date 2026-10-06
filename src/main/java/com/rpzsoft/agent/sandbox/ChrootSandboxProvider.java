package com.rpzsoft.agent.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Local sandbox that runs each command inside a {@code chroot} of the session
 * workspace, so the shell cannot escape to {@code /home} or the host filesystem.
 *
 * <p>Beyond the root, {@link ProcMode} controls what the shell sees under
 * {@code /proc} (i.e. whether it can observe running processes). The default is
 * {@link ProcMode#DENY} (empty /proc). Containment needs root/capabilities (or
 * permitted unprivileged user namespaces); if the environment cannot contain,
 * the provider transparently falls back to {@link ProcMode#OFF} and logs a
 * warning so the agent keeps working rather than failing every command.</p>
 */
public class ChrootSandboxProvider implements SandboxProvider {

    private static final Logger logger = LoggerFactory.getLogger(ChrootSandboxProvider.class);

    private final ProcMode mode;
    private final int bindPid;
    private final ProcMode effective;

    public ChrootSandboxProvider(String modeValue) {
        this(ProcMode.from(modeValue), 0, false);
    }

    public ChrootSandboxProvider(ProcMode mode) {
        this(mode, 0, false);
    }

    public ChrootSandboxProvider(ProcMode mode, int bindPid) {
        this(mode, bindPid, false);
    }

    /** All-args constructor; {@code skipProbe} bypasses the capability check (tests). */
    ChrootSandboxProvider(ProcMode mode, int bindPid, boolean skipProbe) {
        this.mode = mode;
        this.bindPid = bindPid;
        this.effective = skipProbe ? mode : resolveEffective(mode);
    }

    /** The mode actually in effect (may be {@link #OFF} if containment is unavailable). */
    public ProcMode effective() {
        return effective;
    }

    private ProcMode resolveEffective(ProcMode mode) {
        if (mode == ProcMode.OFF) return mode;
        if (!canContain(mode)) {
            logger.warn("sandbox containment unavailable; falling back to OFF (no chroot) for mode={}",
                    mode);
            return ProcMode.OFF;
        }
        return mode;
    }

    @Override
    public String name() {
        return "chroot:" + effective;
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
        String wrapper = buildCommand(sandbox.getDir().getAbsolutePath(), command);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", wrapper);
        pb.directory(sandbox.getDir());
        Process p = pb.start();
        sandbox.setProcess(p);
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

    /**
     * Builds the shell wrapper that roots the command in {@code dir} and applies
     * the configured /proc policy. The returned string is safe to pass to
     * {@code /bin/sh -c} (all embedded quotes are escaped).
     */
    String buildCommand(String dir, String command) {
        String d = quote(dir);
        switch (effective) {
            case OFF:
                return "/bin/sh -c " + quote(command);
            case HOST:
                return "mount --bind /proc " + d + "/proc 2>/dev/null; chroot " + d
                        + " /bin/sh -c " + quote(command);
            case DENY:
                return "unshare --mount chroot " + d + " /bin/sh -c "
                        + quote("mount -t tmpfs none /proc 2>/dev/null; " + command);
            case NAMESPACE:
                return "unshare --mount --pid --fork chroot " + d + " /bin/sh -c "
                        + quote("mount -t proc proc /proc 2>/dev/null; " + command);
            case BIND:
                return "unshare --mount chroot " + d + " /bin/sh -c "
                        + quote("mount -t tmpfs none /proc 2>/dev/null; mount --bind /proc/"
                                + bindPid + " /proc/" + bindPid + " 2>/dev/null; " + command);
            default:
                return "/bin/sh -c " + quote(command);
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Probes whether the host can actually apply containment for this mode. */
    private static boolean canContain(ProcMode mode) {
        try {
            if (mode == ProcMode.HOST) {
                if (!hasBin("mount") || !hasBin("chroot")) return false;
                Process p = new ProcessBuilder("/bin/sh", "-c",
                        "mount --bind /tmp /tmp 2>/dev/null && umount /tmp 2>/dev/null && true")
                        .start();
                return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
            if (!hasBin("unshare") || !hasBin("chroot")) return false;
            Process p = new ProcessBuilder("/bin/sh", "-c",
                    "unshare --mount --fork true 2>/dev/null && true").start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static boolean hasBin(String name) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute()) return true;
        }
        return false;
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
