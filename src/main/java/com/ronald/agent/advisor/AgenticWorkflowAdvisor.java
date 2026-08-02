package com.ronald.agent.advisor;

import com.ronald.agent.workflow.AgenticWorkflow;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * Spring AI {@link BaseAdvisor} that pre-processes chat requests by running an
 * {@link AgenticWorkflow} on the user's message and augmenting the prompt with the result.
 * <p>
 * The workflow is invoked in the {@link #before} phase; the response is passed through unchanged.
 * </p>
 */
public class AgenticWorkflowAdvisor implements BaseAdvisor {

    // Set default order to be after all built-in advisors,
    // but before any custom advisors that don't specify an order
    private static final int DEFAULT_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1;

    private final AgenticWorkflow<String> agenticWorkflow;
    private final int order;

    /**
     * Creates an advisor with the given workflow and the default precedence order.
     *
     * @param agenticWorkflow the workflow to invoke before each chat request; must not be null
     */
    public AgenticWorkflowAdvisor(AgenticWorkflow<String> agenticWorkflow) {
        this(agenticWorkflow, DEFAULT_ORDER);
    }

    /**
     * Creates an advisor with an explicit precedence order.
     *
     * @param agenticWorkflow the workflow to invoke before each chat request; must not be null
     * @param order           the advisor execution order (lower values run first)
     */
    public AgenticWorkflowAdvisor(AgenticWorkflow<String> agenticWorkflow, int order) {
        this.agenticWorkflow = Objects.requireNonNull(agenticWorkflow, "agenticWorkflow must not be null");
        this.order = order;
    }

    /**
     * Intercepts the outgoing chat request, invokes the agentic workflow on the user message,
     * and augments the prompt with the workflow result if one is produced.
     * If the user message is empty the request is forwarded unchanged.
     *
     * @param request the original chat client request
     * @param chain   the advisor chain to continue after this advisor
     * @return the (possibly augmented) chat client request
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // Safely extract the original message to avoid NullPointerExceptions
        String originalMessage = "";
        if (request.prompt() != null && request.prompt().getUserMessage() != null) {
            originalMessage = request.prompt().getUserMessage().getText();
        }

        // Skip processing if there is no user input
        if (!StringUtils.hasText(originalMessage)) {
            return request;
        }

        // Invoke the agentic workflow
        String workflowResult = agenticWorkflow.invoke(originalMessage);

        // Only augment the message if the workflow actually returned something useful
        if (StringUtils.hasText(workflowResult)) {
            String augmentedMessage = originalMessage + "\n\n" + workflowResult;
            return request.mutate()
                    .prompt(request.prompt().augmentUserMessage(augmentedMessage))
                    .build();
        }

        return request;
    }

    /**
     * Passes the chat response through without modification.
     *
     * @param response the chat client response
     * @param chain    the advisor chain (unused in this implementation)
     * @return the unchanged response
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // No modifications needed for the response in this workflow advisor
        return response;
    }

    /**
     * Returns the execution order of this advisor.
     *
     * @return the order value
     */
    @Override
    public int getOrder() {
        return this.order;
    }
}