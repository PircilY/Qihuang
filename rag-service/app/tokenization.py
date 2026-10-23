from __future__ import annotations
import re

_ASCII_WORD = re.compile(r"[a-z0-9]{2,}")
_HAN_SEQUENCE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]{2,}")
_DOMAIN_ALIASES = {
    "三高": ("高血压", "糖尿病", "高血脂"), "心梗": ("心脏病发作",),
    "脑卒中": ("中风",), "血压偏高": ("高血压",),
}
_SYNONYMS = {"心力衰竭": ("心衰",), "心衰": ("心力衰竭",)}


def tokenize(text: str) -> list[str]:
    """Shared contiguous Han bigrams and ASCII terms, retaining term frequency."""
    normalized = (text or "").lower()
    features = [normalized]
    for alias, expansions in sorted(_DOMAIN_ALIASES.items()):
        if alias in normalized:
            features.extend(expansions)
    searchable = " ".join(features)
    tokens = _ASCII_WORD.findall(searchable)
    for sequence in _HAN_SEQUENCE.findall(searchable):
        tokens.extend(sequence[index:index + 2] for index in range(len(sequence) - 1))
    for term, related in sorted(_SYNONYMS.items()):
        if term in normalized:
            tokens.append(term)
            tokens.extend(related)
    return tokens
