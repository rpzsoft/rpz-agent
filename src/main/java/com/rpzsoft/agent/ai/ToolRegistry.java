package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the tools exposed to the model and renders their OpenAI function
 * schemas. Tools are registered by the embedder (or a defaults installer).
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> all() {
        return new ArrayList<Tool>(tools.values());
    }

    public int size() {
        return tools.size();
    }

    public Json toolSchemas() {
        Json out = new Json(true);
        for (Tool tool : tools.values()) {
            Json fn = new Json();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.parameters());
            Json entry = new Json();
            entry.put("type", "function");
            entry.put("function", fn);
            out.add(entry);
        }
        return out;
    }
}
