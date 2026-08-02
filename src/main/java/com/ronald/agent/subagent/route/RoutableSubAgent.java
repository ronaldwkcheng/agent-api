package com.ronald.agent.subagent.route;

import com.ronald.agent.subagent.SubAgent;

/**
 * An extension of SubAgent that supports routing based on a route key.
 * Routable sub-agents are used in conditional routing workflows where the execution
 * path is determined by classifying the input and matching it to a route.
 */
public interface RoutableSubAgent<T> extends SubAgent<T> {

    /**
     * Returns the route key that identifies this sub-agent's category.
     * The route key is used for classification and dispatching in routing logic.
     *
     * @return the route key string
     */
    String getRouteKey();

    /**
     * Returns the output key, which is null by default for routable sub-agents.
     *
     * @return null
     */
    default String getOutputKey() {
        return null;
    }
}
