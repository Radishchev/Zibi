import json

import textwrap

from zibi.config import ModelConfig, all_model_names, load_model_config, missing_api_keys
from zibi.tools.base import Risk, ToolSpec
from zibi.tools.cloud import build_default_registry
from zibi.tools.registry import lenient_schema


def test_providers_get_lenient_schemas_but_we_keep_the_strict_ones():
    reg = build_default_registry().fork()
    reg.set_device_tools([ToolSpec(
        name="add_to_list", description="d", risk=Risk.WRITE,
        parameters={"type": "object", "properties": {"items": {"type": "array", "items": {"type": "string"}, "minItems": 1}},
                    "required": ["items"], "additionalProperties": False},
    )])
    sent = json.dumps(reg.llm_schemas())
    assert "minItems" not in sent and "maxItems" not in sent and "additionalProperties" not in sent
    assert "required" in sent and "items" in sent                        # useful guidance stays
    assert reg.get("add_to_list").parameters["properties"]["items"]["minItems"] == 1   # our validator still enforces it
    assert reg.get("ask_user").parameters["properties"]["options"]["maxItems"] == 4


def test_lenient_schema_does_not_modify_the_original():
    original = {"type": "object", "properties": {"a": {"type": "array", "minItems": 2, "items": {"type": "string"}}}}
    lenient_schema(original)
    assert original["properties"]["a"]["minItems"] == 2


def test_missing_keys_are_reported_for_the_configured_providers(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.setenv("GROQ_API_KEY", "x")
    names = ["gemini/gemini-3.8-flash", "groq/whisper-large-v3-turbo", "mystery/model"]
    assert missing_api_keys(names) == ["GEMINI_API_KEY"]
    monkeypatch.setenv("GEMINI_API_KEY", "y")
    assert missing_api_keys(names) == []


def test_fallbacks_load_from_yaml_and_count_toward_the_key_check(tmp_path, monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    monkeypatch.setenv("GEMINI_API_KEY", "y")
    f = tmp_path / "m.yaml"
    f.write_text(textwrap.dedent("""
        models: {orchestrator: gemini/gemini-3.8-flash}
        fallbacks: {orchestrator: [groq/openai/gpt-oss-120b]}
    """))
    cfg = load_model_config(f)
    assert cfg.fallbacks == {"orchestrator": ["groq/openai/gpt-oss-120b"]}
    assert missing_api_keys(all_model_names(cfg)) == ["GROQ_API_KEY"]  # the fallback needs its key too


def test_env_can_change_or_disable_the_fallback(tmp_path, monkeypatch):
    f = tmp_path / "m.yaml"
    f.write_text("models: {orchestrator: gemini/a}\nfallbacks: {orchestrator: [groq/b]}\n")
    monkeypatch.setenv("ZIBI_FALLBACK_ORCHESTRATOR", "")
    assert load_model_config(f).fallbacks["orchestrator"] == []
    monkeypatch.setenv("ZIBI_FALLBACK_ORCHESTRATOR", "groq/c, gemini/d")
    assert load_model_config(f).fallbacks["orchestrator"] == ["groq/c", "gemini/d"]
    monkeypatch.delenv("ZIBI_FALLBACK_ORCHESTRATOR")
    assert load_model_config(f).fallbacks["orchestrator"] == ["groq/b"]


def test_check_models_script_parses_provider_listings():
    import importlib.util
    import pathlib
    spec = importlib.util.spec_from_file_location("check_models", pathlib.Path(__file__).parent.parent / "scripts" / "check_models.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    gemini = {"models": [
        {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent", "countTokens"]},
        {"name": "models/text-embedding-9", "supportedGenerationMethods": ["embedContent"]},
    ]}
    assert mod.parse_gemini(gemini) == ["gemini/gemini-3.8-flash"]
    assert mod.parse_groq({"data": [{"id": "openai/gpt-oss-120b"}]}) == ["groq/openai/gpt-oss-120b"]
