"""Per-session tool registry: cloud tools (server) + device tools (from the phone's manifest)."""
from __future__ import annotations

import logging
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

from .base import CloudTool, ToolSpec

log = logging.getLogger("zibi.registry")


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
                    "parameters": s.parameters,
                },
            }
            for s in self.specs()
        ]
