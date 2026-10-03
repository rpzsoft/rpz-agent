package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.Json;

/**
 * A capability the model can invoke. Implementations receive a per-run
 * {@link ToolContext} and return text for the model.
 */
public interface Tool {

    String name();

    String description();

    Json parameters();

    String execute(ToolContext context, Json arguments) throws Exception;
}
