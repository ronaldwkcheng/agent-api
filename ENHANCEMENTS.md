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
| 1.2 | **Voting / Ensemble** — done | Run the *same* agent N times (or across models), then pick by majority vote or a judge agent. The parallel workflow only fans out to *different* agents. | S–M |
| 1.3 | **Supervisor / Hierarchical** | A supervisor LLM keeps choosing which sub-agent to call next until it decides it is done. `ConditionalAgentRouter` routes only once. | M (S after 2.1) |
| 1.4 | **Handoff / Swarm** | Agents pass control to each other peer to peer over one shared context, with a cap on the number of handoffs, honouring `ExhaustionPolicy`. | M |
| 1.5 | **Guardrails** (decorator) — done | Input and output checks around any `SubAgent`, such as PII, schema or policy checks. It is the automated version of `HumanReviewSubAgent` and could reuse the `ReviewDecision` shape. | S–M |
| 1.6 | **Graph / State-machine workflow** | Named nodes plus conditional edges, with cycles allowed. This generalises all six current patterns (LangGraph-style). | L |
| 1.7 | **Corrective / Agentic RAG** — done | Grade the retrieved documents, then rewrite the query and search again if they are poor. Today `RagSubAgent` retrieves once. | M |

Built as `OrchestratorWorkersWorkflow<T>` (`com.ronald.agent.workflow`); see
[`api/docs/orchestrator-workers-workflow.md`](api/docs/orchestrator-workers-workflow.md). An
internal orchestrator agent produces a `Breakdown` of `Subtask`s from the input, one `worker`
`SubAgent` is invoked once per subtask concurrently (bounded by a per-subtask timeout and a
`BranchFailurePolicy`, reusing the fan-out/timeout technique from `ParallelAgentOrchestrator`), and
a typed synthesizer combines the results. A breakdown wider than `maxSubtasks` follows the same
`ExhaustionPolicy` convention as `PlanAndExecuteWorkflow`'s step budget.

1.5 is built as `GuardrailSubAgent` (`com.ronald.agent.subagent.guardrail`); see
[`api/docs/guardrail-sub-agent.md`](api/docs/guardrail-sub-agent.md). It wraps any
`SubAgent<String>` with ordered input and output `Guardrail`s whose `GuardrailDecision` mirrors
`ReviewDecision` (pass / rewrite / retry / block). Output retries follow the `ExhaustionPolicy`
convention. `Guardrails` ships length and pattern block/redact checks; `PromptGuardrail` judges a
plain-language policy with a model. Open follow-up: a typed (non-`String`) variant, e.g. a schema
check on an entity-returning agent.

1.2 is built as `VotingAgentOrchestrator<T>` (`com.ronald.agent.workflow`); see
[`api/docs/voting-agent-orchestrator.md`](api/docs/voting-agent-orchestrator.md). A candidate list
is built with `addCandidate(agent)` (ensemble: a different agent, run once) and
`addCandidate(agent, times)` (the same agent, run `times` times) — the same mechanism covers both
bullet points of the gap. Every sample is bounded by a per-sample timeout and a
`BranchFailurePolicy`, reusing the fan-out/timeout technique from `ParallelAgentOrchestrator`,
except `DEGRADE` drops a failed sample from the vote entirely rather than giving it a placeholder
(a placeholder must never win a vote or be read by the judge as a real candidate). Aggregation is
either `majorityVote()` (candidates grouped by exact text after an overridable normalizer, ties go
to the first-seen group, no further model call — requires `T` = `String`) or `judge(SubAgent<T>)`
(every distinct candidate, annotated with how many samples agreed, handed to a typed judge agent).
Open follow-up: no semantic/embedding-based clustering for the vote, only exact (normalized) text
match — documented as a gotcha favoring `judge(...)` for free-form text.

1.7 is built as `CorrectiveRagWorkflow` (`com.ronald.agent.subagent.rag`); see
[`api/docs/corrective-rag-workflow.md`](api/docs/corrective-rag-workflow.md). It wraps a
`RagSubAgent` with a grade-and-retry loop: an internal grader reads the question and the
formatted passages and grades them `SUFFICIENT` or `INSUFFICIENT`; on `INSUFFICIENT` an internal
rewriter drafts a better search query and retrieval runs again, bounded by `maxAttempts` and the
`ExhaustionPolicy` convention. Empty retrieval is graded `INSUFFICIENT` without a model call,
mirroring `RagSubAgent`'s own no-documents short-circuit, and the final answer always reuses the
last attempt's already-retrieved passages via a new `RagSubAgent.generate(Map, List<Document>)`
method rather than searching again. Open follow-up: the grader judges the whole retrieved set at
once, not passage-by-passage.

## 2. Small extensions to existing classes

### 2.1 `SubAgent` / `AgenticWorkflow` as a `ToolCallback` adapter — done
Built as `AgentToolCallback` (`com.ronald.agent.tool`); see
[`api/docs/agent-tool-callback.md`](api/docs/agent-tool-callback.md). It wraps any sub-agent or
workflow as a Spring AI `ToolCallback` so `ReActWorkflow` can call it as a tool, which makes 1.3
(Supervisor) almost free and allows workflows to be nested. Open follow-up: `ReActWorkflow` calls
tools without a `ToolContext`, so `conversationId` does not yet reach nested agents.

### 2.2 `PlanAndExecuteWorkflow`
- **Re-planning** — done. `replanMode(ReplanMode)` adds `AFTER_EACH_STEP` (ask the planner again
  after every step, using the accumulated `stepResults`) and `ON_FAILURE` (follow the plan as
  given; ask again, instead of propagating, only when a step throws). `maxSteps` becomes a total
  budget across every plan and replan together, and a budget that runs out mid-replan is handled
  like `IterativeRefinementWorkflow`'s unpassed draft — `THROW` carries every result produced so
  far as the partial result, `RETURN_PARTIAL` synthesizes over them. See
  [`api/docs/plan-and-execute-workflow.md`](api/docs/plan-and-execute-workflow.md). Open
  follow-up: **step dependencies** (below) are not built — re-planning only ever runs one step
  queue at a time, never fans steps out.
- **Step dependencies:** an optional `dependsOn` per step, so independent steps run in parallel
  on an `Executor`. Not built this round — it would need either a second, isolated `Step`/`Plan`
  pair (to avoid silently widening the default planner's structured-output schema for every
  existing caller) or an accepted behaviour change to the shared one.

### 2.3 `ConditionalAgentRouter`
- **Confidence threshold:** a route below the threshold falls back to `defaultAgent`.
- **Multi-route (top-k) dispatch:** send the request to several routes and feed their results to
  an aggregator.

### 2.4 `ParallelAgentOrchestrator` — done
- **Quorum policy** next to `BranchFailurePolicy`: `quorum(int)` (default: every branch) lets the
  workflow proceed to aggregation once that many branches have settled — succeeded, degraded, or
  timed out — without waiting on the rest, which contribute an `UNAVAILABLE - quorum was reached
  ...` placeholder instead. Requires `BranchFailurePolicy.DEGRADE` below the full branch count:
  `FAIL_FAST` must still propagate every failure, which stopping early cannot guarantee. See
  [`api/docs/parallel-agent-orchestrator.md`](api/docs/parallel-agent-orchestrator.md). Open
  follow-up: **"optionally cancel the rest"**, from the original gap, is not built — this
  workflow takes a plain `Executor`, not an `ExecutorService`, and `CompletableFuture.cancel()`
  cannot interrupt a branch already running regardless of executor type, so there is nothing a
  cancel option could do beyond what simply not waiting already achieves. Stragglers keep running
  to completion and their result is discarded, exactly like a branch that outlives
  `branchTimeout`.

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
3. ~~**1.5 Guardrails.**~~ Done.
4. ~~**1.2 Voting / Ensemble.**~~ Done.
5. ~~**1.7 Corrective / Agentic RAG.**~~ Done.
6. ~~**2.2 `PlanAndExecuteWorkflow` re-planning.**~~ Done (step dependencies left open).
7. ~~**2.4 `ParallelAgentOrchestrator` quorum.**~~ Done (cancellation not applicable — see above).
8. Then pick based on need: 1.6, or the step-dependencies half of 2.2.

Per `CLAUDE.md`, each new pattern needs:
- stub-`SubAgent` tests that need no API key or network,
- a demo runner selected by `agent.demo`,
- a guide in `api/docs/`.
