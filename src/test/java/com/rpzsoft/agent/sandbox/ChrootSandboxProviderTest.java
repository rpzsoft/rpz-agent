package com.rpzsoft.agent.sandbox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChrootSandboxProviderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static ProcMode mode(String value) {
        return ProcMode.from(value);
    }

    @Test
    public void parsesConfigValues() {
        assertEquals(ProcMode.DENY, mode(null));
        assertEquals(ProcMode.DENY, mode(""));
        assertEquals(ProcMode.DENY, mode("deny"));
        assertEquals(ProcMode.NAMESPACE, mode("namespace"));
        assertEquals(ProcMode.NAMESPACE, mode("own"));
        assertEquals(ProcMode.HOST, mode("host"));
        assertEquals(ProcMode.OFF, mode("off"));
        assertEquals(ProcMode.OFF, mode("none"));
        assertEquals(ProcMode.OFF, mode("process"));
        assertEquals(ProcMode.BIND, mode("bind:1234"));
        assertEquals(ProcMode.DENY, mode("bind:abc"));   // invalid pid -> safe default
        assertEquals(ProcMode.DENY, mode("nonsense"));    // unknown -> safe default
    }

    @Test
    public void buildCommandWrapsEachMode() {
        String dir = tmp.getRoot().getAbsolutePath();

        ChrootSandboxProvider off = new ChrootSandboxProvider(ProcMode.OFF, 0, true);
        assertTrue(off.buildCommand(dir, "echo hi").contains("/bin/sh -c 'echo hi'"));

        ChrootSandboxProvider deny = new ChrootSandboxProvider(ProcMode.DENY, 0, true);
        String denyCmd = deny.buildCommand(dir, "ls -la");
        assertTrue(denyCmd.contains("unshare --mount chroot"));
        assertTrue(denyCmd.contains("mount -t tmpfs none /proc"));
        assertTrue(denyCmd.contains("ls -la"));

        ChrootSandboxProvider ns = new ChrootSandboxProvider(ProcMode.NAMESPACE, 0, true);
        String nsCmd = ns.buildCommand(dir, "ps");
        assertTrue(nsCmd.contains("unshare --mount --pid --fork chroot"));
        assertTrue(nsCmd.contains("mount -t proc proc /proc"));

        ChrootSandboxProvider host = new ChrootSandboxProvider(ProcMode.HOST, 0, true);
        assertTrue(host.buildCommand(dir, "ps").contains("mount --bind /proc"));

        ChrootSandboxProvider bind = new ChrootSandboxProvider(ProcMode.BIND, 4321, true);
        String bindCmd = bind.buildCommand(dir, "cat /proc/4321/status");
        assertTrue(bindCmd.contains("mount --bind /proc/4321 /proc/4321"));
    }

    @Test
    public void buildCommandEscapesSingleQuotes() {
        ChrootSandboxProvider p = new ChrootSandboxProvider(ProcMode.OFF, 0, true);
        String cmd = p.buildCommand("/tmp/w", "echo 'hi'");
        // embedded quotes are escaped with the canonical '\'' marker
        assertTrue("expected escaped-quote marker in: " + cmd, cmd.contains("'\\''"));
    }

    @Test
    public void runsQuotedCommandInOffMode() throws Exception {
        File ws = tmp.newFolder("ws");
        ChrootSandboxProvider p = new ChrootSandboxProvider(ProcMode.OFF, 0, true);
        Sandbox sandbox = new Sandbox("test", ws);
        p.start(sandbox, new SandboxProvider.LogSink() {
            @Override public void log(String stream, String line) { }
            @Override public void state(String state) { }
        });
        ExecResult r = p.exec(sandbox, "printf '%s' \"it's\"", 15000, null);
        assertTrue("expected quoted text in output, got: " + r.output(), r.stdout.contains("it's"));
    }

    @Test
    public void runsCommandInOffMode() throws Exception {
        File ws = tmp.newFolder("ws");
        ChrootSandboxProvider p = new ChrootSandboxProvider(ProcMode.OFF, 0, true);
        Sandbox sandbox = new Sandbox("test", ws);
        p.start(sandbox, new SandboxProvider.LogSink() {
            @Override public void log(String stream, String line) { }
            @Override public void state(String state) { }
        });
        ExecResult r = p.exec(sandbox, "echo hello-from-sandbox", 15000, null);
        assertTrue("expected stdout, got: " + r.output(), r.stdout.contains("hello-from-sandbox"));
        assertEquals(0, r.exitCode);
    }
}
