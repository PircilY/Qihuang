from __future__ import annotations

import re


_ASCII_WORD = re.compile(r"[a-z0-9][a-z0-9._-]*")
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
_DOMAIN_ALIASES = {
    "三高": ("高血压", "糖尿病", "高血脂"),
    "心梗": ("心脏病发作",),
    "脑卒中": ("中风",),
    "血压偏高": ("高血压",),
}


def tokenize(text: str) -> list[str]:
    """Create lightweight lexical tokens suitable for short Chinese medical text."""
    normalized = (text or "").lower()
    feature_text = [normalized]
    feature_text.extend(
        expansion
        for alias, expansions in _DOMAIN_ALIASES.items()
        if alias in normalized
        for expansion in expansions
    )
    searchable = " ".join(feature_text)
    tokens = _ASCII_WORD.findall(searchable)
    characters = _CJK.findall(searchable)
    tokens.extend(characters)
    tokens.extend(
        "".join(characters[index : index + 2])
        for index in range(max(0, len(characters) - 1))
    )
    tokens.extend(
        "".join(characters[index : index + 3])
        for index in range(max(0, len(characters) - 2))
    )
    return tokens
