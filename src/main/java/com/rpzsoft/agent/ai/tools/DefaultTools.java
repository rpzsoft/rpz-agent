package com.rpzsoft.agent.ai.tools;

import com.rpzsoft.agent.ai.ToolRegistry;

/**
 * Installs the default coding tool set into a registry.
 */
public final class DefaultTools {

    private DefaultTools() {
    }

    public static ToolRegistry install(ToolRegistry registry) {
        registry.register(new ReadTool());
        registry.register(new WriteTool());
        registry.register(new EditTool());
        registry.register(new ListDirTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new BashTool());
        registry.register(new AstEditTool());
        return registry;
    }

    public static ToolRegistry registry() {
        return install(new ToolRegistry());
    }
}
