package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;

/**
 * Provider-agnostic chat completion client with streaming and tool calls.
 */
public interface ModelClient {

    String provider();

    String model();

    /**
     * @param messages OpenAI-style message array (with tool_calls / tool_call_id)
     * @param tools    OpenAI-style function schemas (may be an empty array)
     */
    void stream(Json messages, Json tools, ModelStream stream) throws Exception;
}
