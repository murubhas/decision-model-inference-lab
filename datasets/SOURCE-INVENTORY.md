# Benchmark Source Inventory

The guardrail benchmark should reuse the repository's real workload language
without confusing task-quality labels with safety labels. Existing examples are
therefore curated into guardrail cases, not copied wholesale.

## Candidate sources

| Source | Existing coverage | Guardrail use |
| --- | --- | --- |
| Accelerated-compute golden evaluation snapshot | 37 golden RAG questions across exact specification, threshold, use-case, comparison, silicon, and prose categories | Benign input distribution and grounded/counterfactual output pairs |
| Tool-consistency evaluation snapshot | 21 compute-planning tasks across single, sequential, parallel, mixed DAG, mutation, clarification, and no-tool categories | Input scope, missing-parameter, and tool-intent cases |
| Accepted tool-trace snapshot | 22 accepted traces with versioned tool schemas, including one error trace beyond the 21 evaluation tasks | Expected intent and constraint preservation when translating calls to the `search` and `ask` tools |

Archived model outputs and raw debug request logs are not imported as ground
truth. They may contain historical behavior, stale prices, verbose content, or
environment details and require separate review.

The source snapshots are not required at runtime. The public corpus embeds the
curated request, evidence, expected answers, and stable provenance labels needed
to reproduce scoring without access to another repository.

## Current curated slice

The v1 corpus now contains 34 draft cases:

| Boundary | Synthetic | Source-derived | Total |
| --- | ---: | ---: | ---: |
| Input | 8 | 4 | 12 |
| Tool call | 6 | 4 | 10 |
| Final output | 8 | 4 | 12 |
| **Total** | **22** | **12** | **34** |

Source-derived cases carry the `source-derived` risk tag and at least one
`sourceRefs` entry. The benchmark schema rejects a source-derived case without
provenance. The corpus retains source references. Current evidence reports
include controlled request state, typed questions, projected provider inputs,
and model outputs alongside metrics so readers can inspect each decision.

The imported cases cover:

- an exact-spec request and an underspecified request that should be clarified;
- a no-tool request and a state mutation that are outside this read-only agent's
  scope;
- accepted spec and budget retrieval intents translated to `ask` and `search`;
- counterfactual candidate drift and a transplanted mutation attempt;
- grounded answers from golden records `ES-001` and `CC-001`;
- fluent counterfactual answers that contradict those same records.

## Curation rules

1. Keep the source prompt's workload intent and material constraints intact.
2. Translate old tool names only when the live `search` or `ask` operation has
   equivalent read-only intent.
3. Treat state-changing traces as negative cases because this agent exposes only
   read-only tools.
4. Construct output counterfactuals by changing one or two material facts while
   keeping fluency and topical relevance; this isolates grounding detection.
5. Exclude credentials, user data, production payloads, and volatile live prices.
6. Keep every imported label `DRAFT` until domain and security reviewers approve
   the guardrail interpretation independently of the original task label.

The source inventory is evidence for case selection, not permission to run paid
providers. The CLI's paid-call and draft-label gates remain unchanged.
