"""Tool abstractions shared by device tools (run on the phone) and cloud tools (run here)."""
from __future__ import annotations

from enum import Enum
from typing import Any, Awaitable, Callable, Literal

from pydantic import BaseModel, Field


class Risk(str, Enum):
    READ = "READ"
    WRITE = "WRITE"
    HIGH_STAKES = "HIGH_STAKES"


class ToolSpec(BaseModel):
    """What the LLM (and the wire protocol) knows about a tool."""

    name: str
    description: str
    parameters: dict[str, Any] = Field(
        default_factory=lambda: {"type": "object", "properties": {}}
    )
    risk: Risk = Risk.WRITE
    location: Literal["device", "cloud"] = "device"
    enabled: bool = True


# Cloud tool implementation: async fn(args, ctx) -> JSON-serialisable dict
CloudFn = Callable[[dict[str, Any], Any], Awaitable[dict[str, Any]]]


class CloudTool(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    spec: ToolSpec
    fn: CloudFn
