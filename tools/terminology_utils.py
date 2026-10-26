"""Names-only terminology matching used while rebuilding the corpus."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTRIES = json.loads((ROOT / "data" / "tcm-terminology.json").read_text(encoding="utf-8"))["entries"]

_ALIASES: dict[str, dict[str, str]] = {"herb": {}, "formula": {}, "disease": {}, "syndrome": {}}
for _entry in ENTRIES:
    if _entry.get("kind") not in _ALIASES:
        continue
    for _alias in [_entry["zh"], *_entry.get("zhAliases", []), *_entry.get("en", [])]:
        if _alias:
            _ALIASES[_entry["kind"]][_alias.casefold()] = _entry["zh"]
_PATTERNS = {
    kind: re.compile("|".join(sorted((re.escape(alias) for alias in aliases), key=len, reverse=True)), re.I)
    for kind, aliases in _ALIASES.items() if aliases
}


def _contains(text: str, alias: str) -> bool:
    if not alias:
        return False
    folded_text, folded_alias = text.casefold(), alias.casefold()
    if folded_alias not in folded_text:
        return False
    if re.search(r"[A-Za-z0-9]", alias):
        return re.search(r"(?<![A-Za-z0-9])" + re.escape(alias) + r"(?![A-Za-z0-9])", text, re.I) is not None
    return True


def extract_metadata(text: str) -> dict[str, list[str]]:
    result = {"disease": [], "herbs": [], "formulas": [], "syndromes": []}
    for kind, pattern in _PATTERNS.items():
        target = {"herb": "herbs", "formula": "formulas", "disease": "disease", "syndrome": "syndromes"}[kind]
        for match in pattern.finditer(text):
            alias = match.group(0).casefold()
            # Latin aliases must be whole tokens; CJK aliases are already exact names.
            if re.search(r"[a-z0-9]", alias):
                start, end = match.span()
                if (start and text[start - 1].isalnum()) or (end < len(text) and text[end].isalnum()):
                    continue
            result[target].append(_ALIASES[kind][alias])
    return {key: list(dict.fromkeys(values)) for key, values in result.items()}
