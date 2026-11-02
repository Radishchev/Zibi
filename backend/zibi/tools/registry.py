"""Per-session tool registry: cloud tools (server) + device tools (from the phone's manifest)."""
from __future__ import annotations

import logging
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

from .base import CloudTool, ToolSpec

log = logging.getLogger("zibi.registry")


# Providers (Groq, Gemini) validate tool calls themselves and answer a violation with a hard 400 error that we
# can't recover from. So we send them a lenient schema, and enforce the strict one ourselves, where a violation
# becomes feedback the model can act on.
_PROVIDER_UNSUPPORTED = {"additionalProperties", "minItems", "maxItems"}


def lenient_schema(schema: Any) -> Any:
    if isinstance(schema, dict):
        return {k: lenient_schema(v) for k, v in schema.items() if k not in _PROVIDER_UNSUPPORTED}
    if isinstance(schema, list):
        return [lenient_schema(v) for v in schema]
    return schema


class ToolRegistry:
    def __init__(self) -> None:
        self._cloud: dict[str, CloudTool] = {}
        self._device: dict[str, ToolSpec] = {}

    def register_cloud(self, tool: CloudTool) -> None:
        self._cloud[tool.spec.name] = tool

    def fork(self) -> "ToolRegistry":
        """Copy the cloud tools into a fresh registry for one session."""
        r = ToolRegistry()
        r._cloud = dict(self._cloud)
        return r

    def set_device_tools(self, specs: list[ToolSpec]) -> list[str]:
        """Replace the device tool set. Returns names that were rejected."""
        self._device = {}
        rejected: list[str] = []
        for spec in specs:
            spec = spec.model_copy(update={"location": "device"})
            try:
                Draft202012Validator.check_schema(spec.parameters)
            except SchemaError:
                log.warning("rejecting device tool %s: invalid JSON schema", spec.name)
                rejected.append(spec.name)
                continue
            if spec.name in self._cloud:
                log.warning("rejecting device tool %s: name clashes with cloud tool", spec.name)
                rejected.append(spec.name)
                continue
            self._device[spec.name] = spec
        return rejected

    def get(self, name: str) -> ToolSpec | None:
        if name in self._cloud:
            return self._cloud[name].spec
        return self._device.get(name)

    def cloud_tool(self, name: str) -> CloudTool | None:
        return self._cloud.get(name)

    def specs(self) -> list[ToolSpec]:
        all_specs = [t.spec for t in self._cloud.values()] + list(self._device.values())
        return [s for s in all_specs if s.enabled]

    def llm_schemas(self) -> list[dict[str, Any]]:
        """OpenAI-style function schemas (what LiteLLM / Groq expect)."""
        return [
            {
                "type": "function",
                "function": {
                    "name": s.name,
                    "description": s.description,
                    "parameters": lenient_schema(s.parameters),
                },
            }
            for s in self.specs()
        ]
