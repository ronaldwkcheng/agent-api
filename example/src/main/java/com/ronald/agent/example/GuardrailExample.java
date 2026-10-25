package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.subagent.guardrail.Guardrail;
import com.ronald.agent.subagent.guardrail.GuardrailSubAgent;
import com.ronald.agent.subagent.guardrail.Guardrails;
import com.ronald.agent.subagent.guardrail.PromptGuardrail;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.SequentialAgentChain;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Demonstrates automated guardrails with {@link GuardrailSubAgent}.
 *
 * <p>The same summarise → draft chain as {@link SequentialWorkflowExample}, with the summary step
 * guarded on its way in and the reply guarded on its way out:</p>
 * <ul>
 *   <li><b>Input</b> &mdash; a prompt-injection pattern is blocked, then email addresses and card
 *       numbers are redacted, so no model in the chain ever sees them.</li>
 *   <li><b>Output</b> &mdash; a length limit, then a model-judged policy that the reply must not
 *       promise a refund. Both send the draft back with feedback in {@code {guardrailFeedback}}
 *       rather than failing the run.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GuardrailExample {

    private static final Pattern PROMPT_INJECTION =
            Pattern.compile("(?i)ignore (all |any )?(previous|prior|above) instructions");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    private final ChatClient chatClient;

    /**
     * Drafts a reply to a support complaint behind input and output guardrails.
     *
     * @param userComplaint the raw complaint text
     * @return a reply that passed every output guardrail
     * @throws com.ronald.agent.subagent.guardrail.GuardrailViolationException if a guardrail blocks
     */
    public String draftGuardedReply(String userComplaint) {

        // The input guardrails rewrite only the copy of the context this step receives. The chain
        // still holds the raw complaint under {input} for later steps, which is why the reply
        // step's template below deliberately uses only {summary}.
        GuardrailSubAgent summarizeStep = GuardrailSubAgent.builder()
                .delegate(DefaultPromptSubAgent.builder()
                        .chatClient(chatClient)
                        .outputKey("summary")
                        .promptTemplate("Summarize the following support request into 3 bullet points: {input}")
                        .build())
                .inputGuardrails(
                        Guardrails.blockMatching("prompt-injection", PROMPT_INJECTION,
                                "the request tries to override the assistant's instructions"),
                        Guardrails.redactMatching("email", EMAIL, "[email]"),
                        Guardrails.redactMatching("card-number", CARD_NUMBER, "[card number]"))
                .build();

        Guardrail noRefundPromises = PromptGuardrail.builder()
                .chatClient(chatClient)
                .name("no-refund-promises")
                .policy("The reply must not promise or confirm a refund, credit or any amount of money. "
                        + "It may say the billing team will review the charge.")
                .retryOnOutputViolation(true)
                .build();

        // Both guardrail keys are always present (empty on the first attempt), so the template can
        // reference them unconditionally.
        GuardrailSubAgent replyStep = GuardrailSubAgent.builder()
                .delegate(DefaultPromptSubAgent.builder()
                        .chatClient(chatClient)
                        .outputKey("reply")
                        .systemPrompt("You are a helpful customer support agent.")
                        .promptTemplate("""
                                Our internal summary of a customer's complaint is: {summary}

                                Draft a short, friendly reply letting them know we are working on it.

                                Previous draft (empty if this is the first): {previousOutput}
                                Feedback to address (empty if none): {guardrailFeedback}
                                """)
                        .build())
                .outputGuardrails(Guardrails.maxLength(600), noRefundPromises)
                .maxRetries(2)
                .build();

        AgenticWorkflow<String> workflow = SequentialAgentChain.<String>builder()
                .addAgents(summarizeStep, replyStep)
                .build();

        return workflow.invoke(userComplaint);
    }
}
