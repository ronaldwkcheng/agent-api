package com.ronald.agent.workflow;

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
     * Executes the workflow with the given input and returns the result.
     *
     * @param input the plain-text input to process; must not be null
     * @return the workflow result; never null
     * @throws WorkflowExhaustedException if the workflow is bounded, exhausts its budget
     *                                    without completing, and uses {@link ExhaustionPolicy#THROW}
     */
    T invoke(String input);
}
