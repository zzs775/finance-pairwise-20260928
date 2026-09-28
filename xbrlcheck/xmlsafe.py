"""Hardened XML reader: no external entities, bounded depth/elements/text."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from xml.parsers import expat

from .errors import Location
from .errors import XBRLCheckError
from .limits import Limits


@dataclass
class SafeElement:
    """Minimal DOM node retaining source line/column and document order."""

    name: str
    attrs: list[tuple[str, str]]
    children: list["SafeElement"] = field(default_factory=list)
    text_parts: list[str] = field(default_factory=list)
    line: int = 0
    column: int = 0

    def local_name(self) -> str:
        name = self.name
        if "}" in name:
            name = name.split("}", 1)[1]
        if ":" in name:
            name = name.rsplit(":", 1)[1]
        return name

    def attr(self, name: str, default: str | None = None) -> str | None:
        for key, value in self.attrs:
            key_local = key
            if "}" in key_local:
                key_local = key_local.split("}", 1)[1]
            if ":" in key_local:
                key_local = key_local.rsplit(":", 1)[1]
            if key_local == name:
                return value
        return default

    @property
    def text(self) -> str:
        return "".join(self.text_parts)

    def find_all(self, name: str) -> list["SafeElement"]:
        """All descendant elements with the given local name, in document order."""
        found: list[SafeElement] = []
        stack = list(reversed(self.children))
        while stack:
            node = stack.pop()
            if node.local_name() == name:
                found.append(node)
            stack.extend(reversed(node.children))
        return found

    def direct_children(self, name: str | None = None) -> list["SafeElement"]:
        if name is None:
            return list(self.children)
        return [child for child in self.children if child.local_name() == name]


@dataclass
class SafeDocument:
    root: SafeElement
    source: str

    def location(self, element: SafeElement) -> Location:
        return Location(self.source, element.line, element.column)


def parse_file(path: str, limits: Limits | None = None) -> SafeDocument:
    """Parse a local XML file into a safe element tree."""
    limits = limits or Limits()
    size = os.path.getsize(path)
    if size > limits.max_document_bytes:
        raise XBRLCheckError(
            "E001",
            f"document size {size} exceeds limit {limits.max_document_bytes} bytes",
            Location(path),
        )
    with open(path, "rb") as handle:
        data = handle.read(limits.max_document_bytes + 1)
    if len(data) > limits.max_document_bytes:
        raise XBRLCheckError(
            "E001",
            f"document exceeds limit {limits.max_document_bytes} bytes",
            Location(path),
        )
    return parse_bytes(data, path, limits)


def parse_bytes(data: bytes | str, source: str = "<input>", limits: Limits | None = None) -> SafeDocument:
    """Parse XML bytes/string with entities disabled and resource limits enforced."""
    limits = limits or Limits()
    if isinstance(data, str):
        data = data.encode("utf-8")
    if len(data) > limits.max_document_bytes:
        raise XBRLCheckError(
            "E001",
            f"document size {len(data)} exceeds limit {limits.max_document_bytes} bytes",
            Location(source),
        )

    parser = expat.ParserCreate(namespace_separator=None)
    stack: list[SafeElement] = []
    root: list[SafeElement] = []
    counters = {"elements": 0, "text": 0}

    def reject_doctype(doctype_name: str, system_id: str | None, public_id: str | None, has_internal: bool) -> None:
        raise XBRLCheckError(
            "E002",
            "DOCTYPE declarations are forbidden (external entities disabled)",
            Location(source, parser.CurrentLineNumber, parser.CurrentColumnNumber),
        )

    def start_element(name: str, attrs) -> None:
        depth = len(stack) + 1
        if depth > limits.max_depth:
            raise XBRLCheckError(
                "E001",
                f"element nesting depth {depth} exceeds limit {limits.max_depth}",
                Location(source, parser.CurrentLineNumber, parser.CurrentColumnNumber),
            )
        counters["elements"] += 1
        if counters["elements"] > limits.max_elements:
            raise XBRLCheckError(
                "E001",
                f"element count exceeds limit {limits.max_elements}",
                Location(source, parser.CurrentLineNumber, parser.CurrentColumnNumber),
            )
        element = SafeElement(
            name=name,
            attrs=list(attrs.items()),
            line=parser.CurrentLineNumber,
            column=parser.CurrentColumnNumber,
        )
        if stack:
            stack[-1].children.append(element)
        else:
            root.append(element)
        stack.append(element)

    def end_element(name: str) -> None:
        stack.pop()

    def characters(data_piece: str) -> None:
        if not stack:
            return
        counters["text"] += len(data_piece)
        if counters["text"] > limits.max_text_length:
            raise XBRLCheckError(
                "E001",
                f"total text length exceeds limit {limits.max_text_length}",
                Location(source, parser.CurrentLineNumber, parser.CurrentColumnNumber),
            )
        current = sum(len(part) for part in stack[-1].text_parts)
        if current + len(data_piece) > limits.max_text_length:
            raise XBRLCheckError(
                "E001",
                f"element text length exceeds limit {limits.max_text_length}",
                Location(source, parser.CurrentLineNumber, parser.CurrentColumnNumber),
            )
        stack[-1].text_parts.append(data_piece)

    parser.StartDoctypeDeclHandler = reject_doctype
    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element
    parser.CharacterDataHandler = characters
    # Defence in depth: never resolve external general/parameter entities.
    parser.ExternalEntityRefHandler = lambda *args: 0

    try:
        parser.Parse(data, True)
    except XBRLCheckError:
        raise
    except expat.ExpatError as exc:
        raise XBRLCheckError(
            "E003",
            f"malformed XML: {exc}",
            Location(source, parser.CurrentLineNumber, parser.CurrentColumnNumber),
        ) from exc
    if not root:
        raise XBRLCheckError("E003", "document contains no root element", Location(source))
    return SafeDocument(root=root[0], source=source)
