"""Shared names-only dictionary and weighted query features.

Matching records candidates, never medical inference.  Both the Java and Python
retrievers consume the JSON resources in ``data/``; this module is deliberately
small so the scoring contract stays visible and testable.
"""
import json
import re
from pathlib import Path
from collections import Counter
from app.tokenization import tokenize

ROOT = Path(__file__).resolve().parents[2]
ENTRIES = json.loads((ROOT / "data/tcm-terminology.json").read_text("utf-8"))["entries"]
POLICY = json.loads((ROOT / "data/retrieval-stopwords.json").read_text("utf-8"))
STOP = set(POLICY["en"]) | set(POLICY["zh"])
STOP |= {term for word in POLICY["zh"] for term in tokenize(word)}
GENERIC_TOKENS = STOP


def is_generic(term: str) -> bool:
    return (term or "").casefold() in GENERIC_TOKENS


def contains(text, alias):
    text, alias = text.lower(), alias.lower()
    if re.search(r"[a-z0-9]", alias):
        return re.search(r"(?<![a-z0-9])" + re.escape(alias) + r"(?![a-z0-9])", text) is not None
    return alias in text


def matches(text):
    return [entry for entry in ENTRIES if any(contains(text, alias)
            for alias in [entry["zh"], *entry["zhAliases"], *entry["en"]])]


def query_features(question, enabled=False):
    original = tokenize(question)
    if not enabled:
        return Counter(original), []
    features = Counter()
    for term in original:
        features[term] += POLICY["genericWeight"] if is_generic(term) else 1.0
    expansions, extra = [], set()
    for entry in matches(question):
        zh_hit = any(contains(question, alias) for alias in [entry["zh"], *entry["zhAliases"]])
        aliases = entry["en"] if zh_hit else [entry["zh"], *entry["zhAliases"]]
        for alias in aliases:
            extra.update(tokenize(alias))
        expansions.append(entry["id"] + ":" + entry["zh"] + "↔" + "|".join(aliases)
                          + "（" + entry["sourceType"] + "）")
    for term in sorted(extra - set(original)):
        features[term] = POLICY["expansionWeight"] * (POLICY["genericWeight"] if is_generic(term) else 1.0)
    return features, expansions


def expanded_query(question: str, enabled: bool = False) -> str:
    """Return the original query plus low-priority aliases for vector search."""
    if not enabled:
        return question
    aliases = []
    for entry in matches(question):
        zh_hit = any(contains(question, alias) for alias in [entry["zh"], *entry["zhAliases"]])
        aliases.extend(entry["en"] if zh_hit else [entry["zh"], *entry["zhAliases"]])
    return " ".join([question, *aliases]) if aliases else question
