package com.ronald.agent.workflow;

import java.util.Map;

/**
 * Core abstraction for agentic workflows in this application.
 * <p>
 * An {@code AgenticWorkflow} encapsulates one or more AI agents orchestrated to
 * transform a plain-text input into a typed result. Implementations may process
 * agents sequentially, in parallel, or in an iterative refinement loop.
 * </p>
 *
 * <h2>Exhaustion</h2>
 * <p>
 * Workflows bounded by a step or attempt budget ({@code ReActWorkflow},
 * {@code IterativeRefinementWorkflow}, {@code PlanAndExecuteWorkflow}) share one convention:
 * exceeding that budget throws {@link WorkflowExhaustedException}, which carries whatever
 * partial output was produced. A workflow never silently returns an incomplete result as
 * though it were a finished one. Callers who prefer the partial result can either read it
 * from the exception or configure {@link ExhaustionPolicy#RETURN_PARTIAL} on the builder.
 * </p>
 *
 * @param <T> the type of result produced by the workflow
 */
public interface AgenticWorkflow<T> {

    /**
     * Executes the workflow with the given input and no caller-supplied attributes.
     *
     * @param input the plain-text input to process; must not be null
     * @return the workflow result; never null
     * @throws WorkflowExhaustedException if the workflow is bounded, exhausts its budget
     *                                    without completing, and uses {@link ExhaustionPolicy#THROW}
     */
    default T invoke(String input) {
        return invoke(input, Map.of());
    }

    /**
     * Executes the workflow with the given input and a map of caller-supplied attributes.
     *
     * <p>Attributes seed the context every {@link com.ronald.agent.subagent.SubAgent} in this
     * workflow receives, before the workflow adds its own keys. They exist for values that
     * belong to the <em>call</em> rather than to the workflow's configuration &mdash;
     * {@link com.ronald.agent.subagent.SubAgent#CONVERSATION_ID} above all, which an advisor
     * customizer reads to scope chat memory to one conversation.</p>
     *
     * <p>A workflow's own keys take precedence: an attribute named {@code "input"} does not
     * displace the input, and an attribute colliding with a key the workflow computes is
     * overwritten by the computed value. Attributes are therefore additive and cannot
     * subvert a workflow's internal contract.</p>
     *
     * <p>This is the primitive operation &mdash; {@link #invoke(String)} delegates here with
     * an empty map.</p>
     *
     * @param input      the plain-text input to process; must not be null
     * @param attributes caller-supplied context entries; must not be null, may be empty
     * @return the workflow result; never null
     * @throws NullPointerException       if input or attributes is null
     * @throws WorkflowExhaustedException if the workflow is bounded, exhausts its budget
     *                                    without completing, and uses {@link ExhaustionPolicy#THROW}
     */
    T invoke(String input, Map<String, String> attributes);
}
