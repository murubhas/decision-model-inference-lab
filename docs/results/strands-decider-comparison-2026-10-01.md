# Strands Decider Comparison Extension

Date: 2026-10-01
Status: three-repetition EKS A10G Spot comparison complete; provider-specific calibration pending

## Question

Can the managed TypeSafe Jev decision path be replaced by a self-hosted,
open-weight Strands Decider service while preserving guardrail quality and
improving privacy, latency, or total inference cost?

The answer is not assumed. Strands is promising, but self-hosting replaces a
small API bill with GPU capacity and model operations. This comparison therefore
measures quality and latency, and it charges idle capacity instead of reporting
only busy GPU milliseconds.

## Controlled comparison

The existing 34 draft cases, question wording, state projection, action policy,
thresholds, Bedrock model, and path-order rotation remain fixed. The comparison
adds two paths:

| Path | Decision provider | LLM use |
| --- | --- | --- |
| `LLM` | None | All questions |
| `JEV` | Managed TypeSafe Jev | None |
| `HYBRID` | Managed Jev on the fixed decision-model-first checks | LLM-direct and uncertain questions |
| `DECIDER` | Self-hosted Strands Decider v19 | None |
| `HYBRID_DECIDER` | Self-hosted Strands on that same fixed table | LLM-direct and uncertain questions |

The table was selected from Jev pilot evidence. Reusing it for Strands is a
controlled-variable choice, not a claim that it is Strands-optimal. The final
reproducibility run contains 510 measured observations: 34 cases times five
paths times three repetitions, after one excluded 170-observation warmup.

## Implemented and verified

- A separate declarative Quarkus REST adapter maps Strands `noul`, `choice`, and
  `score` responses into the shared Java contract.
- The runtime supports direct and hybrid-Decider paths with distinct provenance,
  uncertainty reasons, and provider-outage fallback.
- The exact `strands-decider==0.1.0` wheel, v19 checkpoint, Qwen base snapshot,
  and CUDA base image are pinned for the server image.
- CodeBuild can publish the agent, benchmark, and model server under one source
  tag and three immutable ECR digests.
- The EKS workload is one internal ClusterIP GPU service with no Ingress,
  a restrictive NetworkPolicy, non-root execution, and offline model loading.
- Report schema 3.0 includes direct/hybrid quality, latency, fallback, managed-
  provider cost, active GPU cost, provisioned-window cost, utilization, and
  throughput.
- The nine-module Maven package build passes all 83 tests. Corpus and pricing
  validation passes for 34 draft cases.

## Measured local quality canary

The published v19 model was served on this arm64 Apple-silicon host with the MPS
backend. The unchanged corpus ran once for warm-up and three measured times:
`102/102` measured decisions succeeded, and all three outputs for every case
were identical. This is useful quality and API evidence. It is not the target
EKS A10G latency or cost measurement.

The evidence JSON was produced by the common benchmark reporter, so its
self-hosted cost fields mechanically combine local MPS latency with the catalog's
`g5.xlarge` hourly rate. Those fields are counterfactual and must not be used as
measured economics. The cost forecast below and the future EKS report are the
appropriate cost views.

The self-contained schema 3.0 evidence report includes every input, projected
request, typed output, latency, and aggregate metric:

- [`strands-decider-local-mps-evidence-2026-10-01.json`](strands-decider-local-mps-evidence-2026-10-01.json)
- SHA-256: `64afcd29bd98b83d026e25036ae559de1c7474950a78455a09711a6d15a9facf`
- Size: `544,458` bytes
- Run ID: `strands-local-mps-20261001`

### Direct-path comparison

The LLM and Jev rows are the 2026-09-30 EKS run. The Strands row uses the same
34 cases with three deterministic measured repetitions on MPS. Quality is
comparable by case; latency is shown with its different execution environment
and must not be treated as an A10G result.

| Direct path | Environment | Availability | Exact action | Expected BLOCK not BLOCK | False block | Escalated | Mean Brier | p50 / p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Bedrock LLM | EKS / Bedrock | 97.06% | 93.94% | 0% | 0% | 6.06% | 0.0502 | 1,065 / 2,009 ms |
| Managed Jev | EKS / TypeSafe | 100.00% | 82.35% | 0% | 12.50% | 11.76% | 0.0988 | 145 / 244 ms |
| Strands Decider | Local MPS | 100.00% | 44.12% | 77.78% | 0% | 55.88% | 0.0952 | 274 / 461 ms |

The Strands action result is cautious rather than permissive. Of 18 expected
blocks, it blocked 4, escalated 14, and allowed none. Of 16 expected allows, it
allowed 11, escalated 5, and blocked none. The scorer's strict
`unsafeMissRate` counts every `ESCALATE` in place of an expected `BLOCK` as a
miss; it does not mean those cases were allowed to execute.

The important finding is threshold transfer. Strands' mean Brier score is close
to Jev's, but many probabilities land between the current `0.20` and `0.80`
clear-confidence thresholds. A policy calibrated for Jev therefore makes
Strands indecisive even when its underlying yes/no ranking is useful.

Per-check results reinforce that this is not a simple winner/loser result:

- Strands exceeded direct Jev on `in_scope` accuracy (`91.67%` vs `75.00%`),
  response relevance (`75.00%` vs `41.67%`), and grounding (`83.33%` vs
  `75.00%`). Those checks are currently routed directly to the LLM.
- It matched the small direct corpus on sensitive exposure and policy leakage
  (`100%` accuracy for both).
- It was weaker on the checks currently assigned decision-model-first:
  prompt-injection recall was `50%` versus Jev's `100%`; tool-intent accuracy
  was `90%` versus `100%`; tool-argument safety was `80%` versus `100%`.

Under the frozen Jev-derived hybrid policy, 13 of 34 Strands cases (`38.24%`)
contain at least one uncertain decision-model-first answer. Five of the ten tool
cases would newly require the LLM. Assuming successful calls, the fixed hybrid
would therefore use Bedrock for 29 of 34 cases and avoid only `14.71%` of
generative calls, versus the measured Jev hybrid's `29.03%` avoidance. This is
a projection from recorded typed answers, not a live Hybrid-Decider result.

It also exposes the next experiment: a Strands-specific route table may move
scope, relevance, or grounding to Strands while keeping prompt injection on the
LLM or a dedicated security control. That optimization must be measured after
the fixed-table comparison; it cannot be inferred into production.

## Measured EKS A10G Spot comparison

The reproducibility run is complete. The exact CodeBuild image ran on one
`g5.xlarge` Spot node in `us-east-2a` with one NVIDIA A10G. The benchmark client
ran on a separate temporary CPU worker, so its Java work did not contend with
the model server. The run kept the same 34 draft cases, fixed Jev-derived route
table, thresholds, action policy, Bedrock model, image digests, corpus digest,
and `$0.5321/hour` Spot catalog as the protected one-run comparison.

Self-contained evidence:

- [`strands-decider-eks-reproducibility-evidence-2026-10-01.json`](strands-decider-eks-reproducibility-evidence-2026-10-01.json)
- SHA-256: `d0104a8ea48f6c0a8b2f4fe0eca1be2af38c4ad9a3fbb69ad045afd12e062d23`
- Size: `2,783,092` bytes
- Run ID: `jev-20261002T005110Z-ae8148e2`
- Observations: `170` excluded warmups plus `510` measured
- Pricing catalog: `decision-model-guardrail-usd-2026-10-01-g5-spot-use2a`
- Pricing digest: `2e066790bd0a5d860530fe04849eb707b69c16499994eaf5be12f53f13004df4`

### Aggregate result

| Path | Availability | Exact action | Expected BLOCK not BLOCK | False block | Escalated | p50 / p95 | Cost / 1,000 successful decisions | Savings vs LLM | LLM calls avoided |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Bedrock LLM | 97.06% | 93.94% | 0% | 0% | 6.06% | 1,004 / 1,387 ms | `$2.6848` | control | control |
| Managed Jev | 100.00% | 79.41% | 5.56% | 12.50% | 14.71% | 127 / 182 ms | `$0.0235` | 99.12% | 100.00% |
| Jev Hybrid | 91.18% | 90.32% | 0% | 0% | 9.68% | 947 / 1,340 ms | `$1.4966` | 44.26% | 29.03% |
| Strands Decider | 100.00% | 44.12% | 77.78% | 0% | 55.88% | 175 / 184 ms | `$0.0259` active compute | 99.03% | 100.00% |
| Strands Hybrid | 92.16% | 94.68% | 0% | 0% | 5.32% | 1,020 / 1,456 ms | `$1.8824` | 29.89% | 15.96% |

All 20 measured failures were strict `LlmProtocolException` failures: 3 direct
LLM, 9 Jev Hybrid, and 8 Strands Hybrid. Managed Jev and direct Strands each
completed `102/102`; no decision-model protocol, serving, network, IAM, storage,
or PrivateLink failure occurred. Rates and quality percentages describe
successful observations where applicable.

The direct decision-model results were identical in all three measured
iterations. Jev stayed at 79.41% exact action and `$0.0235/1,000`; its p50 ranged
from 126 to 128 ms. Direct Strands stayed at 44.12% exact action; its p50 ranged
from 174.7 to 174.9 ms and p95 from 183.9 to 189.3 ms. Jev Hybrid stayed at
90.32% exact action, 29.03% generative-call avoidance, and 44.25% to 44.26%
savings. Strands Hybrid ranged from 90.63% to 96.77% exact action, 15.63% to
16.13% call avoidance, and 29.81% to 30.03% savings.

Across the three repetitions, 54 expected blocks on direct Strands produced 12
`BLOCK`, 42 `ESCALATE`, and no `ALLOW`; 48 expected allows produced 33 `ALLOW`,
15 `ESCALATE`, and no `BLOCK`. The scorer conservatively counts an escalation
instead of an expected block in `unsafeMissRate`. This result means the
transferred thresholds are too uncertain, not that unsafe requests were allowed.

The practical conclusion is unchanged but now reproducible: both hybrids
preserved zero observed expected-block misses and false blocks among successful
decisions, while the Jev-derived table saved more with Jev than with Strands.
The table is a controlled comparison, not a final Strands routing policy.

### Capacity economics

Across 196 successful Strands calls, the measured request window was 342.55
seconds and active model time was 31.51 seconds, or 9.20% observed utilization.
At `$0.5321/hour`:

| Capacity view | Measured value |
| --- | ---: |
| Direct Decider active compute / 1,000 decisions | `$0.0259` |
| Provisioned measured-window cost / 1,000 model calls | `$0.2583` |
| Active-compute floor / 1,000 model calls | `$0.0238` |
| Observed / saturated throughput | `0.57 / 6.22 calls/second` |

The temporary CPU-worker overlap lasted 1,082 seconds and cost an estimated
`$0.060592` at the recorded `$0.2016/hour` rate. The provisioned-window figure
is still not full lifecycle cost: it excludes GPU node provisioning, the 7.9 GB
image pull, model startup, post-run idle time, EBS, and shared cluster costs.
The protected node remains running for follow-up work, so its actual
infrastructure cost continues at the live Spot rate.

### Availability event before the valid run

The first EKS attempt is retained separately:

- [`strands-decider-eks-spot-availability-evidence-2026-10-01.json`](strands-decider-eks-spot-availability-evidence-2026-10-01.json)
- SHA-256: `aa66e2e0bfb960d16ca29d6222bcea0b0b3984d173a52da3f6b5e446f32fc975`
- Run ID: `jev-20261001T232908Z-3b4c51c4`

That is an operations result, not a model-quality result. The node-group request
accidentally carried `auto-delete=yes`; the account SpringClean Lambda set the
Auto Scaling group to desired zero at `2026-10-01T23:23:18Z`. Direct Decider
then failed `34/34`, while Hybrid Decider fell back to Bedrock as designed. The
rerun used `auto-delete=no`, verified on the EKS node group, Auto Scaling group,
source and effective launch templates, instance, and volume.

## What upstream evidence says

Strands Decider v19 is a 1.9B-parameter Qwen-based decision model. It replaces
the language-model head with a pointer head, performs one forward pass without a
text-decoding loop, and serves the System One request shape at
`POST /v1/systemone`. The published v19 results report 0.723 accuracy on 231
JevBench tasks, Brier score 0.342, expected calibration error 0.052, and 115 ms
median / 299 ms p95 per question on an RTX 3090. Those are upstream results,
not measurements from this guardrail corpus or the target A10G.

Sources:

- <https://github.com/strands-labs/strands-decider>
- <https://github.com/strands-labs/strands-decider/blob/main/docs/inference.md>

## Pre-run cost forecast (preserved)

This section records the hypothesis made before the target run. Use the measured
EKS section above for conclusions.

The pricing catalog pins one `g5.xlarge` in `us-east-2` at `$1.006/hour`. A
continuously provisioned node costs about `$734.38` for 730 hours before EBS,
EKS, observability, support, or data transfer.

Using upstream RTX 3090 latency only as a sizing hint:

| Assumption | Estimated GPU cost / 1,000 calls |
| --- | ---: |
| 115 ms active compute per call, no idle time | `$0.0321` |
| 299 ms active compute per call, no idle time | `$0.0836` |
| Dedicated node at 5 calls/second | `$0.0559` |
| Dedicated node at 1 call/second | `$0.2794` |
| Dedicated node at 0.1 call/second | `$2.7944` |

For context, the 2026-09-30 run measured `$2.6842` per 1,000 successful LLM
decisions and `$0.0235` per 1,000 Jev decisions. At list price, the dedicated
GPU needs only about `0.104` calls/second to beat that LLM control before quality
is considered. It would need about `11.89` calls/second to match the measured Jev
unit cost. That latter rate is faster than the upstream single-worker 115 ms
median implies, so a dedicated `g5.xlarge` is not expected to beat managed Jev
on unit cost in this small experiment.

These are forecasts, not benchmark results. Batching several questions over one
state, A10G performance, actual utilization, Spot pricing, a smaller serving
instance, or sharing the GPU with another compatible workload can change the
economics. The report preserves both active-compute and provisioned cost so
none of those effects are hidden.

## What the run answered

Strands is a stronger candidate when private in-VPC inference, open weights,
offline operation, customization, or predictable high-volume capacity matters
more than minimum unit cost. Managed Jev remains structurally attractive for
bursty or low-volume traffic because there is no idle accelerator to pay for.

1. Direct Strands did not meet the current action-policy bar because the
   Jev-derived confidence thresholds escalated 55.88% of cases.
2. Strands Hybrid preserved zero observed expected-block misses and false blocks
   among its 94 successful measured observations.
3. Strands Hybrid entered uncertainty fallback on 31.91% of successful cases.
4. Warm direct latency was stable: 175 ms aggregate p50 and 184 ms aggregate p95.
5. At 9.20% measured-window utilization, provisioned cost was `$0.2583` per
   1,000 model calls. Managed Jev remained cheaper at `$0.0235` per 1,000
   successful decisions without idle GPU capacity.

## Current verdict

**Keep Strands as an additional candidate; do not replace Jev in the current
hybrid table.** The architecture fit is excellent and the integration is small
because both providers expose typed decisions. Three EKS iterations confirmed
API stability and useful semantic signal, but the Jev-calibrated policy
escalates too often and misses one of the two prompt-injection positives at the
binary level.

Strands remains strategically interesting for private in-VPC inference, open
weights, offline operation, customization, and high sustained volume. The EKS
run confirms that it is a fast and inexpensive typed-decision service on A10G,
but it does not support replacing Jev under Jev's thresholds and routing table.
Against managed Jev it adds a GPU, model supply-chain work, capacity planning,
and a young `0.1.0` server with no built-in authentication.

The next experiment is provider-specific calibration and routing: keep prompt
injection and current tool checks away from Strands until approved evidence says
otherwise; test scope, relevance, and grounding as candidate Strands-first
checks. Repeat after label approval and provider-specific calibration before
enforcement. The protected EKS Decider service remains available for that
follow-up.
