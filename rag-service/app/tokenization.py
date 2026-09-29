from __future__ import annotations

import re


_ASCII_WORD = re.compile(r"[a-z0-9][a-z0-9._-]*")
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")


def tokenize(text: str) -> list[str]:
    """Create lightweight lexical tokens suitable for short Chinese medical text."""
    normalized = (text or "").lower()
    tokens = _ASCII_WORD.findall(normalized)
    characters = _CJK.findall(normalized)
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
