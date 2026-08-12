package com.ronald.agent.subagent.route;

import com.ronald.agent.workflow.ConditionalAgentRouter;

import java.util.Map;

/**
 * A simple fallback agent used in routing workflows when no specific route matches the input.
 * This agent provides a generic response indicating the detected category and suggests rephrasing.
 * It is typically used as the default agent in ConditionalAgentRouter.
 */
public class SimpleRouteFallbackAgent implements RoutableSubAgent<String> {
    /**
     * Returns the route key for this fallback agent.
     * Note: This is symbolic and not used for routing; the agent is registered as a default.
     *
     * @return "GENERAL"
     */
    @Override
    public String getRouteKey() {
        return "GENERAL"; // symbolic — used as the defaultAgent, not a registered route
    }

    /**
     * Executes the fallback logic by generating a response based on the detected route.
     *
     * @param context a map containing input data, including the "route" key with the detected category
     * @return a string response suggesting the user rephrase their request
     */
    @Override
    public String execute(Map<String, String> context) {
        String detected = context.getOrDefault(ConditionalAgentRouter.CTX_ROUTE, "UNKNOWN");
        return String.format(
                "I detected your request as '%s', but I can only help with the questions. Please rephrase and try again.", detected);
    }
}
