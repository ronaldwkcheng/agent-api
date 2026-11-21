package com.ronald.agent.subagent.guardrail;

/**
 * Thrown when a {@link GuardrailDecision.Block} is returned for a step wrapped by
 * {@link GuardrailedSubAgent}.
 *
 * <p>Unchecked, and deliberately not caught by any workflow: a block means the content must not
 * be used, so it propagates out of {@code invoke} and no later step executes. The blocked content
 * is preserved on {@link #getBlockedContent()}.</p>
 */
public class GuardrailViolationException extends RuntimeException {

    private final String stepName;
    private final GuardrailStage stage;
    private final transient String blockedContent;

    /**
     * @param stepName       the label of the step whose content was blocked
     * @param stage          whether the block came from an input or an output guardrail
     * @param reason         the guardrail's stated reason
     * @param blockedContent the content that was blocked
     */
    public GuardrailViolationException(String stepName, GuardrailStage stage, String reason, String blockedContent) {
        super("Guardrail blocked " + stage.name().toLowerCase() + " for step '" + stepName + "': " + reason);
        this.stepName = stepName;
        this.stage = stage;
        this.blockedContent = blockedContent;
    }

    /**
     * @return the label of the step whose content was blocked
     */
    public String getStepName() {
        return stepName;
    }

    /**
     * @return whether the block came from an input or an output guardrail
     */
    public GuardrailStage getStage() {
        return stage;
    }

    /**
     * @return the content the guardrail blocked
     */
    public String getBlockedContent() {
        return blockedContent;
    }
}
