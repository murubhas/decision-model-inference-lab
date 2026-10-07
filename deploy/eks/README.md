# EKS Deployment Templates

These manifests are public templates for the JVM A2A agent, optional Strands
Decider service, and suspended benchmark Jobs. They contain no live account,
cluster, endpoint, image, role, subnet, security-group, or credential values.

The manifests use `${NAME}` placeholders. Render them with environment-specific
values before applying them; do not commit rendered output.

## Prerequisites

- AWS CLI authenticated to the intended account.
- `kubectl` configured for an EKS cluster.
- `envsubst` and Kustomize support (`kubectl kustomize` is sufficient).
- An EKS node for the JVM workload with label `role=inference-system`.
- For Strands Decider, a GPU node with label `role=decision-model`, taint
  `workload=decision-model:NoSchedule`, and the NVIDIA device plugin.
- Three immutable images produced by `deploy/codebuild/build_images.py` or an
  equivalent pipeline.

Set deployment values in the shell that performs the render:

```shell
export AWS_PROFILE=replace-with-profile
export AWS_REGION=us-east-2
export AWS_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
export EKS_CLUSTER_NAME=replace-with-cluster
export BEDROCK_VPCE_ID=vpce-replace-me
export BEDROCK_ENDPOINT_OVERRIDE=https://replace-with-private-bedrock-endpoint

export AGENT_IMAGE=replace-with-agent-image-at-digest
export BENCHMARK_IMAGE=replace-with-benchmark-image-at-digest
export DECIDER_IMAGE=replace-with-decider-image-at-digest

export APP_MCP_URL=https://mcp.example.com/mcp
export OIDC_AUTH_SERVER_URL=https://id.example.com/realms/decision-model-lab
export OIDC_CLIENT_ID=decision-inference-agent
```

`AWS_PROFILE` is optional when another standard AWS credential source is active.

## Runtime Secrets

Create an ignored `deploy/eks/secret.env` containing these keys:

```text
TYPESAFE_API_KEY=replace-with-typesafe-key
MCP_CLIENT_SECRET=replace-with-oidc-client-secret
```

Load it without checking a Secret manifest into Git:

```shell
kubectl --namespace model-serving create secret generic decision-inference-agent-secret \
  --from-env-file=deploy/eks/secret.env \
  --dry-run=client -o yaml | kubectl apply -f -
```

The checked-in `secret.example.yaml` is documentation only and contains no
usable credential.

## Bedrock Pod Identity

Render the least-privilege policy and trust templates into a temporary directory:

```shell
mkdir -p /tmp/decision-model-inference-lab
envsubst < deploy/eks/iam/trust-policy.json \
  > /tmp/decision-model-inference-lab/trust-policy.json
envsubst < deploy/eks/iam/bedrock-policy.json \
  > /tmp/decision-model-inference-lab/bedrock-policy.json
envsubst < deploy/eks/iam/bedrock-vpc-endpoint-policy.json \
  > /tmp/decision-model-inference-lab/bedrock-vpc-endpoint-policy.json
```

The application role policy requires `aws:SourceVpce`, which prevents the agent
from silently falling back to the public Bedrock Runtime endpoint. Review any
shared VPC endpoint policy before updating it; the template intentionally
contains only this application's statement.

Create or update the role and Pod Identity association with your normal
infrastructure workflow. The expected Kubernetes service account is
`model-serving/decision-inference-agent`.

## Deploy The Agent

Review the rendered output before applying:

```shell
kubectl kustomize deploy/eks | envsubst \
  > /tmp/decision-model-inference-lab/agent.yaml
kubectl apply --dry-run=server \
  -f /tmp/decision-model-inference-lab/agent.yaml
kubectl apply -f /tmp/decision-model-inference-lab/agent.yaml
kubectl --namespace model-serving rollout status \
  deployment/decision-inference-agent
```

The default is one replica, `HYBRID` routing, and `SHADOW` enforcement. Inbound
A2A authentication remains disabled in this experiment and must be added before
any production exposure.

## Deploy Strands Decider

The Decider is an internal ClusterIP service with a one-GPU request and a
NetworkPolicy that accepts traffic only from this experiment's pods:

```shell
envsubst < deploy/eks/strands-decider.yaml \
  > /tmp/decision-model-inference-lab/strands-decider.yaml
kubectl apply --dry-run=server \
  -f /tmp/decision-model-inference-lab/strands-decider.yaml
kubectl apply -f /tmp/decision-model-inference-lab/strands-decider.yaml
kubectl --namespace model-serving rollout status deployment/strands-decider
```

The Spot node-group and launch-template JSON files are templates. Render them
only after setting `EKS_NODE_ROLE_ARN`, `SUBNET_ID_1..3`, and
`SECURITY_GROUP_ID`. They preserve `auto-delete=no` as an explicit lifecycle
tag; review that value against your own cleanup automation.

## Run A Paid Benchmark

The benchmark Jobs are suspended by default and require explicit CLI flags for
paid providers and draft labels. Create the pricing ConfigMap and result PVC,
render the selected Job, inspect it, and only then remove suspension.

```shell
kubectl --namespace model-serving create configmap decision-inference-benchmark-repro3-pricing \
  --from-file=datasets/provider-pricing.g5-spot-use2a-2026-10-01.json \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f deploy/eks/benchmark-repro3-results-pvc.yaml
envsubst < deploy/eks/benchmark-repro3-job.yaml \
  > /tmp/decision-model-inference-lab/benchmark-repro3-job.yaml
kubectl apply --dry-run=server \
  -f /tmp/decision-model-inference-lab/benchmark-repro3-job.yaml
kubectl apply -f /tmp/decision-model-inference-lab/benchmark-repro3-job.yaml
kubectl --namespace model-serving patch job decision-inference-benchmark-repro3 \
  --type=merge --patch '{"spec":{"suspend":false}}'
```

Provider calls and GPU capacity cost money. Keep the warmup/repetition settings,
draft-label gate, pricing snapshot, image digests, and corpus hash with every
published result.

## Validation And Cleanup

```shell
kubectl --namespace model-serving get pods,services
kubectl --namespace model-serving port-forward service/decision-inference-agent 8080:8080
curl --fail http://127.0.0.1:8080/q/health/ready
curl --fail http://127.0.0.1:8080/.well-known/agent-card.json
```

Benchmark Jobs, result PVCs, temporary ConfigMaps, GPU workloads, node groups,
ECR images, IAM roles, and VPC endpoint statements have independent lifecycles.
Remove them explicitly when the experiment no longer needs them.
