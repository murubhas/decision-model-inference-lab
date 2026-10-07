# EKS Check-Specific Hybrid Follow-up - 2026-09-29

## Decision Summary

This follow-up implements and measures the recommendation from the first pilot:
route each semantic check to the model that performed best on that check instead
of treating the full question battery as one Jev-first decision.

The result supports Jev as a targeted inference optimization:

- Hybrid guardrail cost fell 44.02% per successful decision versus the LLM
  control while avoiding 28.13% of generative guardrail calls.
- The successful tool-call boundary used Jev without an LLM call, retained 100%
  action accuracy, reduced cost 99.12%, and reduced p50/p95 latency about 86%.
- Hybrid had no unsafe misses and no false blocks among successful decisions.
- Jev itself accounted for only 1.22% of hybrid provider cost; retained LLM
  checks dominate the remaining spend.

This is not a production-safety approval. All 34 labels are draft, this was one
repetition, and three observations failed the strict generated-JSON contract.

## What Changed

The original pilot sent every question and the full state to Jev, then sent the
same full request to the LLM whenever any Jev answer was uncertain. The new
router has a strict policy entry for every question:

| Check | Route | Projected state |
| --- | --- | --- |
| `prompt_injection` | Jev first | `message` |
| `in_scope` | LLM direct | `message`, `agent_scope` |
| `tool_intent_aligned` | Jev first | `user_request`, `tool_call` |
| `tool_arguments_safe` | Jev first | `user_request`, `tool_call` |
| `response_relevant` | LLM direct | `user_request`, `assistant_response` |
| `response_grounded` | LLM direct | `assistant_response`, `tool_evidence` |
| `sensitive_exposure` | Jev first | `assistant_response` |
| `policy_leakage` | Jev first | `assistant_response` |

Uncertain Jev-first IDs join the LLM-direct IDs in one LLM request. The runner
then merges answers in original question order. Raw provider calls remain
separate for cost and latency accounting. Missing routes or state keys fail
before a provider call.

## Run Identity

| Field | Value |
| --- | --- |
| Run ID | `jev-20260929T195807Z-eaff3870` |
| Build source SHA-256 | `5b05f0aa8ab334ce02c935ae60020b2161a65bfaf2485dbd607fb386581cbbda` |
| Benchmark image | `decision-inference-benchmark@sha256:2ac1b987c3e30d3af031aa7a56a1e5528f0e1300e1195c7f303fe9cf219517d2` |
| Corpus | 34 cases, 102 observations, 0 warmups, 1 repetition |
| Corpus SHA-256 | `5d6c0de9ec992488a3457ff2d279e624679233120fd8ee1b40b66a0abaabe4be` |
| Pricing SHA-256 | `6a83f52e72edaaace79dc38a8ee041c50892e2d2f4bbaca3d6226da97b3e6afc` |
| Observation SHA-256 | `297e03355ff6bf5966138f36a79eb6ca0fe8ae53063832f252380cd4b0e05b84` |
| Report SHA-256 | `dfb7a6d318d49edd517585bb50c966688fc62e380241639507ebbbf1475c9c16` |
| Raw local archive | `results/eks-check-specific-hybrid-2026-09-29/` (git-ignored) |
| Deployment ledger | Retained locally; account-specific cloud record excluded from the public repository |

CodeBuild reran all 79 tests before publishing the immutable images. The CLI
completed all observations and returned nonzero because three were errors:

| Case | Path | Error |
| --- | --- | --- |
| `input-block-out-of-scope-harmful` | LLM | `LlmProtocolException` |
| `input-block-out-of-scope-harmful` | Hybrid | `LlmProtocolException` |
| `tool-block-misaligned-domain` | Hybrid uncertainty fallback | `LlmProtocolException` |

The harness intentionally rejects malformed generated output and does not turn
it into a default verdict. The failed hybrid records cannot retain the earlier
successful Jev call, so reported hybrid cost is slightly understated.

## Corpus Results

| Metric | LLM | Jev | Check-specific hybrid |
| --- | ---: | ---: | ---: |
| Availability | 97.06% | 100.00% | 94.12% |
| Exact action accuracy | 96.97% | 79.41% | 90.63% |
| Unsafe-miss rate | 0.00% | 5.56% | 0.00% |
| False-block rate | 0.00% | 12.50% | 0.00% |
| Escalation rate | 3.03% | 14.71% | 9.38% |
| p50 latency | 1,141.71 ms | 145.45 ms | 1,104.15 ms |
| p95 latency | 1,481.90 ms | 208.02 ms | 1,563.17 ms |
| Cost / 1,000 successful decisions | $2.6828 | $0.0235 | $1.5019 |
| Savings vs LLM | baseline | 99.12% | 44.02% |
| Generative calls avoided | 0% | 100% | 28.13% |
| Confidence fallback rate | n/a | n/a | 3.13% of successful decisions |

The three hybrid action mismatches were unnecessary `ESCALATE` outcomes on
cases labeled `ALLOW`: one input clarification and two safe output
abstention/placeholder cases. There were no successful hybrid blocks of benign
cases and no successful hybrid allows of cases labeled `BLOCK`.

## Boundary Economics

The table uses successful observations because failed generated calls returned
no complete usage record.

| Boundary and path | Accuracy | p50 / p95 | Cost / 1,000 | Savings vs boundary LLM |
| --- | ---: | ---: | ---: | ---: |
| Input LLM | 90.91% | 1,053.61 / 2,157.91 ms | $2.2193 | baseline |
| Input hybrid | 90.91% | 1,151.75 / 2,282.23 ms | $1.6912 | 23.79% |
| Tool LLM | 100.00% | 1,084.32 / 1,190.89 ms | $2.3478 | baseline |
| Tool hybrid | 100.00% | 147.36 / 165.00 ms | $0.0205 | 99.12% |
| Output LLM | 100.00% | 1,235.50 / 1,481.90 ms | $3.3870 | baseline |
| Output hybrid | 83.33% | 1,184.30 / 1,563.17 ms | $2.4394 | 27.98% |

This shows exactly where Jev wins. Tool checks are bounded and independently
validated, so Jev removes the generative judge almost entirely. Input and output
retain nuanced LLM checks; state and question projection still save 23.79% and
27.98%, but serial Jev plus LLM execution does not improve tail latency.

Projection reduced mean Jev input tokens by 20.91% on input checks and 35.67%
on output checks relative to the full Jev path. It reduced mean LLM input tokens
by 17.93% and 20.51%, respectively, relative to the full LLM path.

## Cost Interpretation

The versioned catalog uses public list prices checked on 2026-09-28:

- Bedrock global Claude Sonnet 5.5: $2.00/M input and $10.00/M output tokens.
- Jev: $42/B input tokens, or $0.042/M.

TypeSafe did not publish an output-token rate on the cited pricing page. The
estimate treats Jev output as unbilled and requires invoice reconciliation.

Successful hybrid decisions cost an estimated `$0.048061496`: `$0.047474` for
23 Bedrock judge calls and `$0.000587496` for 32 Jev calls. At this corpus mix:

- one million semantic decisions project to about `$2,682.85` on LLM and
  `$1,501.92` on check-specific hybrid, a `$1,180.93` saving;
- an illustrative five checks per turn projects to `$5,904.63` saved per one
  million turns; and
- one million successful tool-boundary decisions project to about `$2,347.80`
  on LLM versus `$20.55` on hybrid.

These are linear scenarios, not traffic forecasts. Discounts, failed calls,
networking, taxes, infrastructure, and Jev output billing are excluded.

## Comparison With The Original Hybrid

The first whole-battery hybrid saved 65.08% and had a 161 ms p50, but inherited
two confidently wrong Jev false blocks. The check-specific hybrid saved 44.02%
and had a 1,104 ms p50, but reduced both false-block and unsafe-miss rates to
zero among successful decisions.

That is the intended trade: spend generative inference on checks where the
pilot showed Jev was weak, and preserve the decision-model optimization where
Jev was strong. The new 3.13% fallback rate excludes planned LLM-direct checks;
the more honest capacity measure is 28.13% generative-call avoidance.

## Live A2A Smoke

Before the corpus run, the deployed `HYBRID`/`SHADOW` agent completed the same
p5 versus p5e business request used in the original comparison. It made five
Jev calls and four semantic Bedrock calls across input, three tool calls, and
output:

- input routed only `in_scope` to the LLM;
- output routed only `response_relevant` and `response_grounded` to the LLM;
- one tool call stayed entirely on Jev and two uncertain tool-intent checks
  fell back to the LLM.

The 32.24-second smoke estimated `$0.043071234` for guardrails and `$0.052184`
for primary generation, or `$0.095255234` total provider cost. This single live
run verifies routing and state projection but is not a matched cost comparison.

## Infrastructure And Cleanup

One temporary on-demand `m7i.xlarge` overlapped the baseline node from
`19:56:11Z` to `20:11:46Z`, or 935 seconds. At `$0.2016/hour`, incremental
compute is estimated at `$0.05236`. The singleton MCP PDB initially prevented
managed drain; deleting only its old Pod let the unchanged Deployment recreate
one ready Pod on the remaining node.

The Job, reader Pod, PVC, and old node are gone. The node group is restored to
`min=1`, `max=1`, `desired=1`; the Jev agent and MCP server are each `1/1` ready.

## Decision

1. Keep the check-specific design and remain in `SHADOW`.
2. Treat tool intent and argument safety as the clearest direct-Jev business
   case after independent label approval and repeated measurements.
3. Keep scope, relevance, and grounding on the LLM for now; projection lowers
   their cost but does not remove generative latency or output variance.
4. Review the three strict-JSON failures and consider a bounded provider retry,
   while preserving the rule that malformed output never becomes a verdict.
5. Approve labels, freeze thresholds, repeat the run, and reconcile Bedrock CUR
   and the TypeSafe invoice before claiming audited production savings.
