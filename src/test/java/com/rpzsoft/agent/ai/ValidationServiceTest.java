package com.rpzsoft.agent.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValidationServiceTest {

    @Test
    public void detectsJavaScriptFiles() {
        assertTrue(ValidationService.isJavaScript("app.js"));
        assertTrue(ValidationService.isJavaScript("App.MJS"));
        assertFalse(ValidationService.isJavaScript("style.css"));
        assertFalse(ValidationService.isJavaScript(null));
    }

    @Test
    public void quotesForShell() {
        assertEquals("'a b.js'", ValidationService.quote("a b.js"));
        assertEquals("'it'\\''s.js'", ValidationService.quote("it's.js"));
    }
}
