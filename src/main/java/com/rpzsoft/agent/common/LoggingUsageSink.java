package com.rpzsoft.agent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UsageSink} that writes model/tool activity to the agent log. Useful as
 * a default for the standalone server; embedders can substitute their own.
 */
public class LoggingUsageSink implements UsageSink {

    private static final Logger logger = LoggerFactory.getLogger(LoggingUsageSink.class);

    @Override
    public void onModelCall(String sessionId, String model, long promptTokens,
                            long completionTokens, long cachedTokens, long costMicros) {
        logger.info("model call session={} model={} prompt={} completion={} cached={} costMicros={}",
                sessionId, model, promptTokens, completionTokens, cachedTokens, costMicros);
    }

    @Override
    public void onToolCall(String sessionId, String tool, boolean ok) {
        logger.info("tool call session={} tool={} ok={}", sessionId, tool, ok);
    }
}
