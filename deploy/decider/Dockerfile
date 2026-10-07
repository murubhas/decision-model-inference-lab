FROM pytorch/pytorch@sha256:c16f4c749e2d9e96878875cdf6cc45cddda1d1a36fddd371dd6f2360f1b6e2a2

ARG STRANDS_DECIDER_WHEEL_URL="https://files.pythonhosted.org/packages/5b/8a/b55c5a0c355914461820ccf1543379ef784f98e3d2783fd29c551c4079e4/strands_decider-0.1.0-py3-none-any.whl"
ARG STRANDS_DECIDER_WHEEL_SHA256="cd8ee1c26937aeb4f6548d920b30b17012bd642e862e2c9f37acf2e6c9d3feeb"

LABEL org.opencontainers.image.title="Strands Decider v19"
LABEL org.opencontainers.image.source="https://github.com/strands-labs/strands-decider"
LABEL org.opencontainers.image.revision="fe39fad9ec8ec355993f76b977348d915ec9cb65"
LABEL dev.decisionmodel.lab.model.revision="bb282d786bc251fd4e3068de3ada9ddbb38127cd"
LABEL dev.decisionmodel.lab.base-model.revision="b1485b2fa6dfa1287294f269f5fb618e03d52d7c"

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/opt/huggingface \
    HF_HUB_DISABLE_TELEMETRY=1

RUN python -m pip install --no-cache-dir \
      "${STRANDS_DECIDER_WHEEL_URL}#sha256=${STRANDS_DECIDER_WHEEL_SHA256}" \
    && python -m pip check

COPY prepare_model.py /tmp/prepare_model.py
RUN python /tmp/prepare_model.py \
    && python -m pip freeze > /opt/models/python-requirements.freeze.txt \
    && rm -rf /opt/models/*/.cache /opt/huggingface /root/.cache/pip /tmp/prepare_model.py \
    && groupadd --gid 10001 decider \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin decider

ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

USER 10001:10001
EXPOSE 8000

ENTRYPOINT ["strands-decider", "serve", "/opt/models/strands-decider-2B-hobson-v19", "--host", "0.0.0.0", "--port", "8000", "--device", "cuda", "--model-name", "strands-decider-2B-hobson-v19"]
