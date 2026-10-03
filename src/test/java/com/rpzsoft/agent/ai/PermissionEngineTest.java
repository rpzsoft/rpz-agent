package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PermissionEngineTest {

    @Test
    public void defaultsAllowSafeToolsAndAskForBash() {
        PermissionEngine engine = new PermissionEngine();
        assertEquals(PermissionEngine.Mode.ALLOW, engine.check("read", new Json()));
        assertEquals(PermissionEngine.Mode.ALLOW, engine.check("write", new Json()));
        assertEquals(PermissionEngine.Mode.ASK, engine.check("bash", new Json()));
    }

    @Test
    public void unknownToolsDefaultToAllow() {
        PermissionEngine engine = new PermissionEngine();
        assertEquals(PermissionEngine.Mode.ALLOW, engine.check("some_mcp_tool", new Json()));
    }

    @Test
    public void explicitModeWins() {
        PermissionEngine engine = new PermissionEngine();
        engine.set("bash", PermissionEngine.Mode.DENY);
        assertEquals(PermissionEngine.Mode.DENY, engine.check("bash", new Json()));
    }

    @Test
    public void ensureDoesNotOverrideExplicitMode() {
        PermissionEngine engine = new PermissionEngine();
        engine.set("custom", PermissionEngine.Mode.DENY);
        engine.ensure("custom", PermissionEngine.Mode.ASK);
        assertEquals(PermissionEngine.Mode.DENY, engine.check("custom", new Json()));
    }
}
