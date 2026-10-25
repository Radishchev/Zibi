"""Shows which models your API keys can use RIGHT NOW and checks the ones in config/models.yaml.

    python scripts/check_models.py          # list available models and check the configured names
    python scripts/check_models.py --ping   # also make a tiny real call to each configured model

Model names change quickly and providers retire them. A name that is listed can still fail, so --ping is the
real test.
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from zibi.config import DEFAULT_MODELS_FILE, all_model_names, load_model_config  # noqa: E402


def parse_gemini(payload: dict) -> list[str]:
    """Models that support generateContent, in LiteLLM naming (gemini/<name>)."""
    return sorted(
        "gemini/" + m["name"].removeprefix("models/")
        for m in payload.get("models", [])
        if "generateContent" in m.get("supportedGenerationMethods", [])
    )


def parse_groq(payload: dict) -> list[str]:
    return sorted("groq/" + m["id"] for m in payload.get("data", []))


def fetch(url: str, headers: dict | None = None) -> dict:
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp)


def available() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    if key := os.getenv("GEMINI_API_KEY"):
        try:
            found["gemini"] = parse_gemini(fetch(f"https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key={key}"))
        except Exception as e:
            print(f"! couldn't list Gemini models: {e}")
    if key := os.getenv("GROQ_API_KEY"):
        try:
            found["groq"] = parse_groq(fetch("https://api.groq.com/openai/v1/models", {"Authorization": f"Bearer {key}"}))
        except Exception as e:
            print(f"! couldn't list Groq models: {e}")
    return found


def ping(model: str) -> str:
    import litellm

    try:
        litellm.completion(model=model, messages=[{"role": "user", "content": "Reply with OK"}], max_tokens=20)
        return "works"
    except Exception as e:
        return "FAILED: " + str(e).replace("\n", " ")[:200]


def main() -> None:
    cfg = load_model_config(DEFAULT_MODELS_FILE)
    found = available()
    for provider, names in found.items():
        print(f"\n{provider}: {len(names)} models available to your key")
        for n in names:
            if "flash" in n or "gpt-oss" in n or "pro" in n:
                print("   ", n)
    print("\nconfigured in models.yaml:")
    for name in dict.fromkeys(all_model_names(cfg)):
        provider = name.split("/", 1)[0]
        if provider not in found:
            status = "(not checked: no key set or listing failed)"
        else:
            status = "listed" if name in found[provider] else "NOT LISTED for your key"
        if "--ping" in sys.argv:
            status += "  | call: " + ping(name)
        print(f"    {name}: {status}")


if __name__ == "__main__":
    main()
