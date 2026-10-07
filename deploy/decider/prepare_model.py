from __future__ import annotations

import json
from pathlib import Path

from huggingface_hub import snapshot_download


DECIDER_REPOSITORY = "StrandsAgents/strands-decider-2B-hobson-v19"
DECIDER_REVISION = "bb282d786bc251fd4e3068de3ada9ddbb38127cd"
BASE_REPOSITORY = "Qwen/Qwen3.5-2B-Base"
BASE_REVISION = "b1485b2fa6dfa1287294f269f5fb618e03d52d7c"
MODEL_ROOT = Path("/opt/models")
DECIDER_PATH = MODEL_ROOT / "strands-decider-2B-hobson-v19"
BASE_PATH = MODEL_ROOT / "Qwen3.5-2B-Base"


def update_json(path: Path, field: str, value: str) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    document[field] = value
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=BASE_REPOSITORY,
        revision=BASE_REVISION,
        local_dir=BASE_PATH,
    )
    snapshot_download(
        repo_id=DECIDER_REPOSITORY,
        revision=DECIDER_REVISION,
        local_dir=DECIDER_PATH,
    )

    # The published adapter points at the mutable Hub name. Repoint both model
    # descriptors to the exact base snapshot baked into this image.
    update_json(DECIDER_PATH / "hobson_config.json", "base_model", str(BASE_PATH))
    update_json(
        DECIDER_PATH / "lora" / "adapter_config.json",
        "base_model_name_or_path",
        str(BASE_PATH),
    )

    provenance = {
        "strandsDeciderPackage": "0.1.0",
        "strandsDeciderRepository": DECIDER_REPOSITORY,
        "strandsDeciderRevision": DECIDER_REVISION,
        "baseRepository": BASE_REPOSITORY,
        "baseRevision": BASE_REVISION,
    }
    (MODEL_ROOT / "image-provenance.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
