from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml

DEFAULT_MODELS_FILE = Path(__file__).resolve().parent.parent / "config" / "models.yaml"


@dataclass
class Settings:
    dev_token: str | None = None
    models_file: Path = DEFAULT_MODELS_FILE

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            dev_token=os.getenv("ZIBI_DEV_TOKEN") or None,
            models_file=Path(os.getenv("ZIBI_MODELS_FILE", str(DEFAULT_MODELS_FILE))),
        )


@dataclass
class ModelConfig:
    models: dict[str, str] = field(default_factory=dict)
    reasoning_effort: dict[str, str] = field(default_factory=dict)


def load_model_config(path: Path) -> ModelConfig:
    data = yaml.safe_load(Path(path).read_text()) or {}
    cfg = ModelConfig(
        models=dict(data.get("models", {})),
        reasoning_effort=dict(data.get("reasoning_effort", {})),
    )
    # ZIBI_MODEL_<ROLE> env vars override the file
    for role in list(cfg.models):
        override = os.getenv(f"ZIBI_MODEL_{role.upper()}")
        if override:
            cfg.models[role] = override
    return cfg
