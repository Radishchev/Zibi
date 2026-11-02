"""Shows which models your API keys can use RIGHT NOW, and (with --ping) how fast they actually answer.

    python scripts/check_models.py                       # list models, check the names in models.yaml
    python scripts/check_models.py --ping                # time a tiny call to each configured model
    python scripts/check_models.py --ping gemini/gemini-3.1-flash-lite gemini/gemini-flash-lite-latest
                                                         # ...and also to these extra candidates
    python scripts/check_models.py --ping --effort minimal   # try a reasoning level on the LiteLLM calls

Each model is called twice in two ways: DIRECTLY against the provider's HTTP API, and through LiteLLM (what Zibi
uses). If the direct call is slow too, the model/service is the bottleneck. If only LiteLLM is slow, it's the library.
The first call includes connection set-up, so look at the second one. "thinking" is how many tokens the model spent
reasoning before it answered; for a trivial prompt that number should be small.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from zibi.config import DEFAULT_MODELS_FILE, all_model_names, load_model_config  # noqa: E402

# Groq sits behind a firewall that rejects Python's default User-Agent with a 403, so send a normal one.
USER_AGENT = "Mozilla/5.0 (compatible; zibi-check/1.0)"
PROMPT = "Reply with the single word OK."


def parse_gemini(payload: dict) -> list[str]:
    """Models that support generateContent, in LiteLLM naming (gemini/<name>)."""
    return sorted(
        "gemini/" + m["name"].removeprefix("models/")
        for m in payload.get("models", [])
        if "generateContent" in m.get("supportedGenerationMethods", [])
    )


def parse_groq(payload: dict) -> list[str]:
    return sorted("groq/" + m["id"] for m in payload.get("data", []))


def _request(url: str, headers: dict | None = None, body: dict | None = None, timeout: float = 60) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    h = {"User-Agent": USER_AGENT, **(headers or {})}
    if data is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def available() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    if key := os.getenv("GEMINI_API_KEY"):
        try:
            found["gemini"] = parse_gemini(_request(
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", {"x-goog-api-key": key}))
        except Exception as e:
            print(f"! couldn't list Gemini models: {e}")
    if key := os.getenv("GROQ_API_KEY"):
        try:
            found["groq"] = parse_groq(_request("https://api.groq.com/openai/v1/models", {"Authorization": f"Bearer {key}"}))
        except Exception as e:
            print(f"! couldn't list Groq models: {e}  (a 403 here with a valid key is usually the firewall; try --ping)")
    key = os.getenv("NVIDIA_NIM_API_KEY") or os.getenv("NVIDIA_API")
    if key:
        try:
            payload = _request("https://integrate.api.nvidia.com/v1/models", {"Authorization": f"Bearer {key}"})
            found["nvidia_nim"] = sorted("nvidia_nim/" + m["id"] for m in payload.get("data", []))
        except Exception as e:
            print(f"! couldn't list NVIDIA NIM models: {e}")
    return found


def _timed(fn) -> tuple[float, str]:
    t = time.perf_counter()
    try:
        note = fn()
    except urllib.error.HTTPError as e:
        note = f"FAILED HTTP {e.code}: {e.read()[:200]!r}"
    except Exception as e:
        note = "FAILED: " + str(e).replace("\n", " ")[:200]
    return time.perf_counter() - t, note


def direct_call(model: str) -> tuple[float, str]:
    provider, name = model.split("/", 1)

    def call() -> str:
        if provider == "gemini":
            data = _request(
                f"https://generativelanguage.googleapis.com/v1beta/models/{name}:generateContent",
                {"x-goog-api-key": os.environ["GEMINI_API_KEY"]},
                {"contents": [{"parts": [{"text": PROMPT}]}], "generationConfig": {"maxOutputTokens": 300}},
            )
            u = data.get("usageMetadata", {})
            return f"ok  prompt={u.get('promptTokenCount')} thinking={u.get('thoughtsTokenCount', 0)} answer={u.get('candidatesTokenCount')}"
        if provider == "groq":
            data = _request(
                "https://api.groq.com/openai/v1/chat/completions",
                {"Authorization": "Bearer " + os.environ["GROQ_API_KEY"]},
                {"model": name, "messages": [{"role": "user", "content": PROMPT}], "max_tokens": 300},
            )
            u = data.get("usage", {})
            return f"ok  prompt={u.get('prompt_tokens')} answer={u.get('completion_tokens')}"
        if provider == "nvidia_nim":
            key = os.environ.get("NVIDIA_NIM_API_KEY") or os.environ["NVIDIA_API"]
            data = _request(
                "https://integrate.api.nvidia.com/v1/chat/completions",
                {"Authorization": "Bearer " + key},
                {"model": name, "messages": [{"role": "user", "content": PROMPT}], "max_tokens": 300},
            )
            u = data.get("usage", {})
            return f"ok  prompt={u.get('prompt_tokens')} answer={u.get('completion_tokens')}"
        return "skipped (no direct test for this provider)"

    return _timed(call)


def litellm_call(model: str, effort: str | None) -> tuple[float, str]:
    def call() -> str:
        import litellm

        kwargs = {"model": model, "messages": [{"role": "user", "content": PROMPT}], "max_tokens": 300, "timeout": 60}
        if effort:
            kwargs["reasoning_effort"] = effort
        r = litellm.completion(**kwargs)
        u = r.usage
        thinking = getattr(getattr(u, "completion_tokens_details", None), "reasoning_tokens", None)
        return f"ok  prompt={u.prompt_tokens} thinking={thinking} answer={u.completion_tokens}"

    return _timed(call)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("models", nargs="*", help="extra models to test, e.g. gemini/gemini-3.1-flash-lite")
    ap.add_argument("--ping", action="store_true", help="time a real call to each model")
    ap.add_argument("--effort", help="reasoning_effort to use for the LiteLLM calls (default: models.yaml's value)")
    args = ap.parse_args()

    cfg = load_model_config(DEFAULT_MODELS_FILE)
    found = available()
    for provider, names in found.items():
        print(f"\n{provider}: {len(names)} models available to your key")
        for n in names:
            if any(w in n for w in ("flash", "pro", "gpt-oss")) and not any(w in n for w in ("image", "tts", "omni")):
                print("   ", n)

    targets = list(dict.fromkeys([*all_model_names(cfg), *args.models]))
    effort = args.effort or cfg.reasoning_effort.get("orchestrator")
    print("\nconfigured in models.yaml (plus any you named):")
    for name in targets:
        provider = name.split("/", 1)[0]
        status = "(not checked: no key set or listing failed)" if provider not in found else (
            "listed" if name in found[provider] else "NOT LISTED for your key")
        print(f"    {name}: {status}", flush=True)

    if args.ping:
        print(f"\ntiming (reasoning_effort for LiteLLM calls: {effort or 'not set'}):")
        for name in targets:
            if name.split("/", 1)[0] not in ("gemini", "groq", "nvidia_nim") or name.startswith("groq/whisper"):
                continue
            print(f"\n  {name}", flush=True)
            for run in (1, 2):
                secs, note = direct_call(name)
                print(f"    run {run}  direct   {secs:5.1f}s  {note}", flush=True)
            for run in (1, 2):
                secs, note = litellm_call(name, effort)
                print(f"    run {run}  litellm  {secs:5.1f}s  {note}", flush=True)
        print("\nRead it like this: the run-2 numbers are the real speed. Pick the model with a low direct time and a "
              "small 'thinking' count, and put it in config/models.yaml.")


if __name__ == "__main__":
    main()
