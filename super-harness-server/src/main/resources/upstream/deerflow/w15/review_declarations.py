from __future__ import annotations
import re
import logging
logger = logging.getLogger(__name__)
from pathlib import Path
from dataclasses import dataclass
@dataclass(frozen=True)
class SecretRequirement:
    name: str
    optional: bool = False


_ENV_VAR_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

_PORTABLE_TOOL_ALIASES = {
    "Bash": "bash",
    "Edit": "str_replace",
    "Glob": "glob",
    "Grep": "grep",
    "Read": "read_file",
    "WebFetch": "web_fetch",
    "WebSearch": "web_search",
    "Write": "write_file",
}

def _normalize_unscoped_allowed_tool(tool_name: str) -> str:
    """Map known portable aliases while preserving unknown runtime names."""
    if "(" in tool_name or ")" in tool_name:
        return tool_name
    return _PORTABLE_TOOL_ALIASES.get(tool_name, tool_name)

def _split_portable_allowed_tools(raw: str, skill_file: Path) -> list[str]:
    """Split a portable scalar while preserving quoted parenthesized patterns."""
    tokens: list[str] = []
    current: list[str] = []
    depth = 0
    quote: str | None = None
    escaped = False

    for char in raw:
        if escaped:
            current.append(char)
            escaped = False
            continue
        if char == "\\":
            current.append(char)
            escaped = True
            continue
        if quote is not None:
            current.append(char)
            if char == quote:
                quote = None
            continue
        if char in {"'", '"'}:
            quote = char
            current.append(char)
            continue
        if char.isspace() and depth == 0:
            if current:
                tokens.append("".join(current))
                current = []
            continue
        if char == "(":
            depth += 1
        elif char == ")":
            if depth == 0:
                raise ValueError(f"allowed-tools in {skill_file} contains an unmatched closing parenthesis")
            depth -= 1
        current.append(char)

    if quote is not None:
        raise ValueError(f"allowed-tools in {skill_file} contains an unclosed quote")
    if depth:
        raise ValueError(f"allowed-tools in {skill_file} contains an unclosed parenthesized pattern")
    if current:
        tokens.append("".join(current))
    return tokens

def parse_allowed_tools(raw: object, skill_file: Path) -> tuple[str, ...] | None:
    """Parse the optional allowed-tools frontmatter field.

    Returns None when the field is omitted. Accepts the Agent Skills standard
    space-separated string or a YAML sequence of strings. Known portable client
    aliases normalize to DeerFlow runtime names. Unknown names and
    command-scoped patterns remain literal because DeerFlow does not inspect
    tool arguments. Returns an empty tuple for an explicit empty value. Raises
    ValueError for malformed values.
    """
    if raw is None:
        return None
    if isinstance(raw, str):
        raw = _split_portable_allowed_tools(raw, skill_file)
        normalize_tools = True
    elif not isinstance(raw, list):
        raise ValueError(f"allowed-tools in {skill_file} must be a space-separated string or list of strings")
    else:
        normalize_tools = False

    allowed_tools: list[str] = []
    for item in raw:
        if not isinstance(item, str):
            raise ValueError(f"allowed-tools in {skill_file} must contain only strings")
        tool_name = item.strip()
        if not tool_name:
            raise ValueError(f"allowed-tools in {skill_file} cannot contain empty tool names")
        allowed_tools.append(_normalize_unscoped_allowed_tool(tool_name) if normalize_tools else tool_name)
    return tuple(allowed_tools)

def parse_required_secrets(raw: object, skill_file: Path) -> tuple[SecretRequirement, ...]:
    """Parse the optional required-secrets frontmatter field (issue #3861).

    Accepts a YAML sequence whose items are either a string (the secret / env
    variable name) or a mapping (``{name, optional}``). Returns an empty tuple
    when the field is omitted. Entries whose name is missing or is not a valid
    environment-variable name are dropped with a warning, so one malformed
    declaration does not invalidate the whole skill. Raises ValueError only when
    the field is present but is not a list.
    """
    if raw is None:
        return ()
    if not isinstance(raw, list):
        raise ValueError(f"required-secrets in {skill_file} must be a list")

    secrets: list[SecretRequirement] = []
    seen: set[str] = set()
    for item in raw:
        if isinstance(item, str):
            name, optional = item.strip(), False
        elif isinstance(item, dict):
            name = str(item.get("name") or "").strip()
            optional = bool(item.get("optional", False))
        else:
            logger.warning("Ignoring malformed required-secrets entry in %s: %r", skill_file, item)
            continue

        if not _ENV_VAR_NAME_RE.match(name):
            logger.warning("Ignoring required-secrets entry with invalid env var name in %s: %r", skill_file, name)
            continue
        if name in seen:
            continue
        seen.add(name)
        secrets.append(SecretRequirement(name=name, optional=optional))
    return tuple(secrets)
