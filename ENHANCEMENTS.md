# Enhancement Proposals

Candidate next steps for the `:api` library, written against `main` at `e0fd8b0`. Already built,
so not repeated here: the six workflow patterns, MCP tool callbacks in `ReActWorkflow`,
`RagSubAgent`, chat memory, and `HumanReviewSubAgent`.

Each item gives the gap and a rough size: **S** (a builder option or small class), **M** (a new
class with tests and a guide in `api/docs/`), **L** (a new abstraction).

## 1. Missing agentic workflow patterns

| # | Pattern | Gap in the current implementation | Size |
|---|---|---|---|
| 1.1 | **Orchestrator–Workers** (fan-out decided at runtime) — done | `ParallelAgentOrchestrator` fans out to agents fixed at build time. `PlanAndExecuteWorkflow` runs its steps one after another. Neither lets an LLM split the input into N subtasks at runtime and run them in parallel. | M |
| 1.2 | **Voting / Ensemble** | Run the *same* agent N times (or across models), then pick by majority vote or a judge agent. The parallel workflow only fans out to *different* agents. | S–M |
| 1.3 | **Supervisor / Hierarchical** | A supervisor LLM keeps choosing which sub-agent to call next until it decides it is done. `ConditionalAgentRouter` routes only once. | M (S after 2.1) |
| 1.4 | **Handoff / Swarm** | Agents pass control to each other peer to peer over one shared context, with a cap on the number of handoffs, honouring `ExhaustionPolicy`. | M |
| 1.5 | **Guardrails** (decorator) | Input and output checks around any `SubAgent`, such as PII, schema or policy checks. It is the automated version of `HumanReviewSubAgent` and could reuse the `ReviewDecision` shape. | S–M |
| 1.6 | **Graph / State-machine workflow** | Named nodes plus conditional edges, with cycles allowed. This generalises all six current patterns (LangGraph-style). | L |
| 1.7 | **Corrective / Agentic RAG** | Grade the retrieved documents, then rewrite the query and search again if they are poor. Today `RagSubAgent` retrieves once. | M |

Built as `OrchestratorWorkersWorkflow<T>` (`com.ronald.agent.workflow`); see
[`api/docs/orchestrator-workers-workflow.md`](api/docs/orchestrator-workers-workflow.md). An
internal orchestrator agent produces a `Breakdown` of `Subtask`s from the input, one `worker`
`SubAgent` is invoked once per subtask concurrently (bounded by a per-subtask timeout and a
`BranchFailurePolicy`, reusing the fan-out/timeout technique from `ParallelAgentOrchestrator`), and
a typed synthesizer combines the results. A breakdown wider than `maxSubtasks` follows the same
`ExhaustionPolicy` convention as `PlanAndExecuteWorkflow`'s step budget.

## 2. Small extensions to existing classes

### 2.1 `SubAgent` / `AgenticWorkflow` as a `ToolCallback` adapter — done
Built as `AgentToolCallback` (`com.ronald.agent.tool`); see
[`api/docs/agent-tool-callback.md`](api/docs/agent-tool-callback.md). It wraps any sub-agent or
workflow as a Spring AI `ToolCallback` so `ReActWorkflow` can call it as a tool, which makes 1.3
(Supervisor) almost free and allows workflows to be nested. Open follow-up: `ReActWorkflow` calls
tools without a `ToolContext`, so `conversationId` does not yet reach nested agents.

### 2.2 `PlanAndExecuteWorkflow`
- **Re-planning:** run the planner again after each step, or only after a step fails, using the
  accumulated `stepResults`.
- **Step dependencies:** an optional `dependsOn` per step, so independent steps run in parallel
  on an `Executor`.

### 2.3 `ConditionalAgentRouter`
- **Confidence threshold:** a route below the threshold falls back to `defaultAgent`.
- **Multi-route (top-k) dispatch:** send the request to several routes and feed their results to
  an aggregator.

### 2.4 `ParallelAgentOrchestrator`
- **Quorum policy** next to `BranchFailurePolicy`: succeed once K of N branches return, and
  optionally cancel the rest.

### 2.5 `IterativeRefinementWorkflow`
- **Self-reflection mode:** the generator critiques its own draft, so no separate evaluator is
  needed.
- **Early stopping** when the evaluation score stops improving.

### 2.6 `RagSubAgent`
- **Query rewriting / multi-query** before the similarity search.
- **Metadata filter** expression taken from the context map.

### 2.7 Cross-cutting decorators
- `RetryingSubAgent`: retries transient model errors with backoff.
- `WorkflowListener` / Micrometer observation hook: step-level tracing across all workflows.

## 3. Suggested order

1. ~~**2.1 ToolCallback adapter.**~~ Done; it opens up 1.3.
2. ~~**1.1 Orchestrator–Workers.**~~ Done.
3. **1.5 Guardrails.** Small, decorator-shaped like `HumanReviewSubAgent`, and useful in
   production.
4. Then pick based on need: 1.2 / 2.2 / 1.7 / 1.6.

Per `CLAUDE.md`, each new pattern needs:
- stub-`SubAgent` tests that need no API key or network,
- a demo runner selected by `agent.demo`,
- a guide in `api/docs/`.
