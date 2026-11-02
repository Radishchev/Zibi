"""Central confirmation policy. Tools never decide for themselves whether to ask first."""
from __future__ import annotations

from .tools.base import Risk, ToolSpec


def requires_confirmation(spec: ToolSpec) -> bool:
    return spec.risk == Risk.HIGH_STAKES
