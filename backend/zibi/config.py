from __future__ import annotations

import os
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

import yaml

DEFAULT_MODELS_FILE = Path(__file__).resolve().parent.parent / "config" / "models.yaml"
DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent / "data"


@dataclass
class Settings:
    dev_token: str | None = None
    models_file: Path = DEFAULT_MODELS_FILE
    data_dir: Path = field(default_factory=lambda: Path(os.getenv("ZIBI_DATA_DIR", str(DEFAULT_DATA_DIR))))

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            dev_token=os.getenv("ZIBI_DEV_TOKEN") or None,
            models_file=Path(os.getenv("ZIBI_MODELS_FILE", str(DEFAULT_MODELS_FILE))),
            data_dir=Path(os.getenv("ZIBI_DATA_DIR", str(DEFAULT_DATA_DIR))),
        )


@dataclass
class ModelConfig:
    models: dict[str, str] = field(default_factory=dict)
    reasoning_effort: dict[str, str] = field(default_factory=dict)
    fallbacks: dict[str, list[str]] = field(default_factory=dict)  # role -> models to try if the main one fails


def load_model_config(path: Path) -> ModelConfig:
    data = yaml.safe_load(Path(path).read_text()) or {}
    cfg = ModelConfig(
        models=dict(data.get("models", {})),
        reasoning_effort=dict(data.get("reasoning_effort", {})),
    )
    for role, value in (data.get("fallbacks") or {}).items():
        cfg.fallbacks[role] = [value] if isinstance(value, str) else list(value or [])
    # ZIBI_MODEL_<ROLE> env vars override the file
    for role in list(cfg.models):
        override = os.getenv(f"ZIBI_MODEL_{role.upper()}")
        if override:
            cfg.models[role] = override
        fb = os.getenv(f"ZIBI_FALLBACK_{role.upper()}")
        if fb is not None:  # set to an empty string to turn the fallback off
            cfg.fallbacks[role] = [m.strip() for m in fb.split(",") if m.strip()]
    return cfg


# LiteLLM model prefix -> the environment variable that holds that provider's key
PROVIDER_KEYS = {
    "gemini": "GEMINI_API_KEY",
    "groq": "GROQ_API_KEY",
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
}


def all_model_names(cfg: ModelConfig) -> list[str]:
    return [*cfg.models.values(), *(m for models in cfg.fallbacks.values() for m in models)]


def missing_api_keys(model_names: Iterable[str]) -> list[str]:
    """Env vars needed by the configured models that are not set (so we can say so at startup)."""
    needed = {PROVIDER_KEYS[m.split("/", 1)[0]] for m in model_names if m.split("/", 1)[0] in PROVIDER_KEYS}
    return sorted(k for k in needed if not os.getenv(k))
