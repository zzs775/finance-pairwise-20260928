"""Parse-time resource limits."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Limits:
    max_depth: int = 64
    max_elements: int = 100_000
    max_text_length: int = 1_000_000
    max_document_bytes: int = 20_000_000

