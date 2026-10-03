package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.workspace.FileWorkspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextBuilderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void systemPromptIncludesAgentsMd() throws Exception {
        File dir = tmp.newFolder("ws");
        Files.write(new File(dir, "AGENTS.md").toPath(), "Be nice to users".getBytes("UTF-8"));
        Files.write(new File(dir, "a.txt").toPath(), "hello".getBytes("UTF-8"));

        ContextBuilder cb = new ContextBuilder(new FileWorkspace.Local(dir));
        Json messages = cb.build();

        assertTrue(messages.isArray());
        Json system = (Json) messages.getArray().get(0);
        assertEquals("system", system.getString("role"));
        assertTrue(system.getString("content").contains("Be nice to users"));
    }

    @Test
    public void appendContextAddsTreeToLastUser() throws Exception {
        File dir = tmp.newFolder("ws");
        Files.write(new File(dir, "a.txt").toPath(), "hello".getBytes("UTF-8"));

        ContextBuilder cb = new ContextBuilder(new FileWorkspace.Local(dir));
        Json messages = new Json(true);
        Json user = new Json();
        user.put("role", "user");
        user.put("content", "what is here?");
        messages.add(user);

        cb.appendContext(messages, Collections.<String>emptyList());

        assertTrue(user.getString("content").contains("a.txt"));
        assertTrue(user.getString("content").contains("untrusted"));
    }

    @Test
    public void providerFormatRepairsBadArguments() {
        Json stored = new Json();
        stored.put("role", "assistant");
        Json calls = new Json(true);
        Json call = new Json();
        call.put("id", "c1");
        call.put("name", "write");
        call.put("arguments", "this is not json");
        calls.add(call);
        stored.put("toolCalls", calls);

        Json converted = ContextBuilder.toProviderFormat(stored);
        Json fn = converted.getJsonArray("tool_calls").get(0).getJson("function");
        assertEquals("write", fn.getString("name"));
        assertEquals("{}", fn.getString("arguments"));
    }

    @Test
    public void planCompactsLongHistory() {
        List<Json> history = new ArrayList<Json>();
        for (int i = 0; i < 40; i++) {
            Json m = new Json();
            m.put("id", "m" + i);
            m.put("role", i % 2 == 0 ? "user" : "assistant");
            m.put("content", "message number " + i);
            history.add(m);
        }
        ContextBuilder.CompactResult plan = ContextBuilder.plan(history);
        assertNotNull(plan);
        assertTrue(plan.compactedMessages > 0);
        assertTrue(plan.summary().length() > 0);
        assertTrue(plan.tokensBefore >= plan.tokensAfter);
    }

    @Test
    public void compactionCutNeverLandsOnToolMessage() {
        List<Json> converted = new ArrayList<Json>();
        Json tool1 = new Json();
        tool1.put("role", "tool");
        tool1.put("content", "x");
        converted.add(tool1);
        Json tool2 = new Json();
        tool2.put("role", "tool");
        tool2.put("content", "y");
        converted.add(tool2);
        for (int i = 2; i < 14; i++) {
            Json m = new Json();
            m.put("role", "user");
            m.put("content", "u" + i);
            converted.add(m);
        }
        assertEquals(2, ContextBuilder.compactionCut(converted));
    }
}
