package com.ronald.agent.workflow;

import com.ronald.agent.subagent.route.RoutableSubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.*;

/**
 * A router that conditionally dispatches requests to different RoutableSubAgents based on input classification.
 * The router uses a routing ChatClient to classify the input into categories and then routes to the appropriate agent.
 * If no matching route is found, it falls back to a default agent or response.
 *
 * <p>A fallback is mandatory. Classification is free-form LLM output, so no set of routes can be
 * exhaustive, and {@link AgenticWorkflow#invoke} must never return null — {@link Builder#build()}
 * therefore rejects a router with neither {@link Builder#defaultAgent} nor
 * {@link Builder#defaultResponse} configured.</p>
 *
 * @param <T> the type of the output returned by the router
 */
public class ConditionalAgentRouter<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(ConditionalAgentRouter.class);

    private static final String DEFAULT_ROUTING_PROMPT_TEMPLATE = """
            Classify the following user request into exactly one of these categories: {routes}.
            Respond with only the category name in uppercase, nothing else.
            User Request: {input}
            Category:
            """;

    private static final String CTX_INPUT = "input";
    public static final String CTX_ROUTE = "route";

    private final ChatClient routingClient;
    private final String routingPromptTemplate;

    // We now use RoutableSubAgent<T> directly, allowing the agent to handle its own structured outputs
    private final Map<String, RoutableSubAgent<T>> routes;
    private final String resolvedRoutesList;

    private final RoutableSubAgent<T> defaultAgent;
    private final T defaultResponse;

    private ConditionalAgentRouter(Builder<T> builder) {
        this.routingClient           = builder.routingClient;
        this.routingPromptTemplate   = builder.routingPromptTemplate;
        this.routes                  = Collections.unmodifiableMap(new LinkedHashMap<>(builder.routes));
        this.resolvedRoutesList      = String.join(", ", this.routes.keySet());
        this.defaultAgent            = builder.defaultAgent;
        this.defaultResponse         = builder.defaultResponse;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Invokes the routing logic by classifying the input and dispatching to the appropriate agent.
     * The method first uses the routing client to classify the input into a category, then looks up
     * the corresponding RoutableSubAgent. If no match is found, it falls back to the default agent
     * or default response.
     *
     * @param input the user input string to be routed
     * @return the result from the matched agent, or the configured fallback; never null
     * @throws NullPointerException  if input is null
     * @throws IllegalStateException if the dispatched agent returns null
     */
    @Override
    public T invoke(String input) {
        Objects.requireNonNull(input, "input must not be null");
        log.info("router_start routes=[{}] inputLength={}", resolvedRoutesList, input.length());

        // ── Step 1: Classify ────────────────────────────────────────────────
        PromptTemplate template = new PromptTemplate(routingPromptTemplate);
        Message message = template.createMessage(Map.of(
                "routes", resolvedRoutesList,
                "input", input
        ));

        String category = Optional.of(message)
                .map(msg -> routingClient.prompt()
                        .messages(msg)
                        .call()
                        .content())
                .map(String::trim)
                .map(String::toUpperCase)
                .orElse("");

        log.info("router_classified category={}", category);

        // ── Step 2: Build handler context ──────────────────────────────────
        Map<String, String> context = Map.of(CTX_INPUT, input, CTX_ROUTE, category);

        // ── Step 3: Dispatch ────────────────────────────────────────────────
        RoutableSubAgent<T> dispatchedAgent = routes.get(category);

        if (dispatchedAgent != null) {
            log.debug("router_dispatch agent={}", dispatchedAgent.getClass().getSimpleName());
            return requireResult(dispatchedAgent, context);
        }

        if (defaultAgent != null) {
            log.info("router_fallback_agent agent={} category={}", defaultAgent.getClass().getSimpleName(), category);
            return requireResult(defaultAgent, context);
        }

        log.info("router_fallback_response category={}", category);
        return defaultResponse;
    }

    /**
     * Executes an agent and enforces the non-null guarantee of {@link AgenticWorkflow#invoke}.
     *
     * @param agent   the agent to execute
     * @param context the handler context
     * @return the agent's result, never null
     * @throws IllegalStateException if the agent returns null
     */
    private T requireResult(RoutableSubAgent<T> agent, Map<String, String> context) {
        T result = agent.execute(context);
        if (result == null) {
            throw new IllegalStateException(
                    "RoutableSubAgent " + agent.getClass().getSimpleName()
                            + " (route '" + agent.getRouteKey() + "') returned null; "
                            + "AgenticWorkflow.invoke must never return null");
        }
        return result;
    }

    /**
     * Builder class for ConditionalAgentRouter.
     * Provides a fluent API to configure and build ConditionalAgentRouter instances.
     */
    public static final class Builder<T> {

        private ChatClient routingClient;
        private String routingPromptTemplate = DEFAULT_ROUTING_PROMPT_TEMPLATE;
        private final Map<String, RoutableSubAgent<T>> routes = new LinkedHashMap<>();
        private RoutableSubAgent<T> defaultAgent;
        private T defaultResponse;

        private Builder() {}

        /**
         * Sets the ChatClient used for routing classification.
         *
         * @param routingClient the ChatClient for routing
         * @return this Builder
         * @throws NullPointerException if routingClient is null
         */
        public Builder<T> routingClient(ChatClient routingClient) {
            this.routingClient = Objects.requireNonNull(routingClient, "routingClient must not be null");
            return this;
        }

        /**
         * Sets the prompt template used for classifying the input into categories.
         * The template must contain {input} and {routes} placeholders.
         *
         * @param routingPromptTemplate the prompt template string
         * @return this Builder
         * @throws NullPointerException if routingPromptTemplate is null
         */
        public Builder<T> routingPromptTemplate(String routingPromptTemplate) {
            this.routingPromptTemplate = Objects.requireNonNull(routingPromptTemplate, "routingPromptTemplate must not be null");
            return this;
        }

        /**
         * Adds a single RoutableSubAgent to the routing map using its route key.
         *
         * @param agent the RoutableSubAgent to add
         * @return this Builder
         * @throws NullPointerException if agent or its route key is null
         */
        public Builder<T> addRoute(RoutableSubAgent<T> agent) {
            Objects.requireNonNull(agent, "agent must not be null");
            String key = Objects.requireNonNull(agent.getRouteKey(), "RoutableSubAgent.getRouteKey() must not return null").trim().toUpperCase();
            this.routes.put(key, agent);
            return this;
        }

        /**
         * Adds multiple RoutableSubAgents to the routing map.
         *
         * @param agents the array of RoutableSubAgents to add
         * @return this Builder
         */
        public Builder<T> addRoutes(RoutableSubAgent<T>... agents) {
            return addRoutes(Arrays.asList(agents));
        }

        /**
         * Adds a list of RoutableSubAgents to the routing map.
         *
         * @param agents the list of RoutableSubAgents to add
         * @return this Builder
         */
        public Builder<T> addRoutes(List<? extends RoutableSubAgent<T>> agents) {
            agents.forEach(this::addRoute);
            return this;
        }

        /**
         * Sets the default agent to use when no route matches.
         *
         * @param defaultAgent the default RoutableSubAgent
         * @return this Builder
         * @throws NullPointerException if defaultAgent is null
         */
        public Builder<T> defaultAgent(RoutableSubAgent<T> defaultAgent) {
            this.defaultAgent = Objects.requireNonNull(defaultAgent, "defaultAgent must not be null");
            return this;
        }

        /**
         * Sets the default response string to use when no route matches and no default agent is set.
         *
         * @param defaultResponse the default response string
         * @return this Builder
         * @throws NullPointerException if defaultResponse is null
         */
        public Builder<T> defaultResponse(T defaultResponse) {
            this.defaultResponse = Objects.requireNonNull(defaultResponse, "defaultResponse must not be null");
            return this;
        }

        /**
         * Builds the ConditionalAgentRouter instance with the configured settings.
         *
         * @return the built ConditionalAgentRouter
         * @throws NullPointerException  if routingClient is null
         * @throws IllegalStateException if no routes are added, if the prompt template is invalid,
         *                               or if no fallback is configured
         */
        public ConditionalAgentRouter<T> build() {
            Objects.requireNonNull(routingClient, "A routingClient must be set.");
            if (routes.isEmpty()) {
                throw new IllegalStateException("At least one RoutableSubAgent must be added.");
            }
            if (!routingPromptTemplate.contains("{input}") || !routingPromptTemplate.contains("{routes}")) {
                throw new IllegalStateException("routingPromptTemplate must contain both {input} and {routes} placeholders.");
            }
            // The route key comes from free-form LLM output, so no set of routes is exhaustive.
            // Without a fallback an unrecognised category would return null, breaking the
            // never-null guarantee of AgenticWorkflow.invoke.
            if (defaultAgent == null && defaultResponse == null) {
                throw new IllegalStateException(
                        "A fallback is required: set defaultAgent(...) or defaultResponse(...). "
                                + "The router classifies with an LLM, so it must handle categories "
                                + "outside " + routes.keySet() + ".");
            }

            return new ConditionalAgentRouter<>(this);
        }
    }
}