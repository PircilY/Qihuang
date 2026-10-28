from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from functools import lru_cache
from pathlib import Path
from typing import Any


_REQUIRED_LISTS = {
    "emergency": ("symptom_terms", "critical_symptom_terms", "time_severity_help_terms",
                  "negation_terms", "history_terms", "knowledge_intent_terms", "current_person_terms"),
    "personalized": ("person_terms", "medication_action_terms", "dose_or_adjustment_terms",
                     "whitelist_patterns"),
}
_REQUIRED_EVIDENCE_RULES = (
    "pregnancy_request", "pregnancy_evidence", "blood_pressure", "range_request", "age_group",
    "range_evidence", "age_range_evidence", "condition_safety_request", "condition_safety_evidence",
    "herb_drug_interaction_request", "herb_drug_interaction_evidence",
    "anticoagulant_adjustment_request", "anticoagulant_adjustment_evidence",
)


def _rules_path() -> Path:
    import os

    configured = os.environ.get("SAFETY_RULES_PATH")
    if configured:
        configured_path = Path(configured)
        if not configured_path.is_file():
            raise RuntimeError(f"Safety rules startup failure: configured file is missing: {configured_path}")
        return configured_path.resolve()
    candidates = [
        Path(__file__).resolve().parents[2] / "safety-rules.json",
        Path.cwd() / "safety-rules.json",
    ]
    for path in candidates:
        if path.is_file():
            return path.resolve()
    raise RuntimeError("Safety rules startup failure: safety-rules.json is missing")


def _normalize(value: str, mapping: dict[str, str] | None = None) -> str:
    normalized = unicodedata.normalize("NFKC", value or "").casefold()
    if mapping:
        normalized = "".join(mapping.get(char, char) for char in normalized)
    output: list[str] = []
    for index, char in enumerate(normalized):
        category = unicodedata.category(char)
        if char.isspace():
            previous = normalized[index - 1] if index else ""
            following = normalized[index + 1] if index + 1 < len(normalized) else ""
            if previous.isascii() and previous.isalnum() and following.isascii() and following.isalnum():
                output.append(" ")
            continue
        if category.startswith(("P", "S")):
            previous = normalized[index - 1] if index else ""
            following = normalized[index + 1] if index + 1 < len(normalized) else ""
            ascii_separator = (ord(char) < 128 and previous.isascii() and previous.isalnum()
                               and following.isascii() and following.isalnum())
            if ascii_separator and output and output[-1] != " ":
                output.append(" ")
            continue
        output.append(char)
    return "".join(output).strip()


def _load_rules(path: Path | None = None) -> tuple[dict[str, Any], Path, str]:
    path = path.resolve() if path is not None else _rules_path()
    try:
        if not path.is_file():
            raise RuntimeError(f"Safety rules startup failure: configured file is missing: {path}")
        raw = path.read_bytes()
        rules = json.loads(raw.decode("utf-8"))
        if not isinstance(rules, dict):
            raise ValueError("root must be an object")
        normalization = rules.get("normalization")
        mapping = normalization.get("traditional_to_simplified") if isinstance(normalization, dict) else None
        if not isinstance(mapping, dict) or not mapping or any(
                not isinstance(key, str) or len(key) != 1 or not isinstance(value, str) or len(value) != 1
                for key, value in mapping.items()):
            raise ValueError("normalization.traditional_to_simplified must be a non-empty character map")
        for section, required_keys in _REQUIRED_LISTS.items():
            value = rules.get(section)
            if not isinstance(value, dict):
                raise ValueError(f"{section} must be an object")
            for key in required_keys:
                terms = value.get(key)
                if not isinstance(terms, list) or not terms or any(not isinstance(item, str) or not item.strip() for item in terms):
                    raise ValueError(f"{section}.{key} must be a non-empty string list")
        threshold = rules["emergency"].get("score_threshold")
        if not isinstance(threshold, int) or threshold <= 0:
            raise ValueError("emergency.score_threshold must be a positive integer")
        for pattern in rules["personalized"]["whitelist_patterns"]:
            re.compile(pattern, re.I)
        evidence_rules = rules.get("evidence_coverage")
        if not isinstance(evidence_rules, dict):
            raise ValueError("evidence_coverage must be an object")
        for name in _REQUIRED_EVIDENCE_RULES:
            pattern = evidence_rules.get(name)
            if not isinstance(pattern, str) or not pattern.strip():
                raise ValueError(f"evidence_coverage.{name} must be a non-empty pattern")
            re.compile(pattern, re.I | re.S)
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError, AttributeError) as error:
        raise RuntimeError(f"Safety rules startup failure: invalid safety-rules.json at {path}: {error}") from error
    return rules, path, hashlib.sha256(raw).hexdigest()


RULES, RULES_PATH, RULES_SHA256 = _load_rules()
_TRADITIONAL_MAP = RULES["normalization"]["traditional_to_simplified"]
_EVIDENCE_PATTERNS = {
    name: re.compile(pattern, re.I | re.S)
    for name, pattern in RULES["evidence_coverage"].items()
}
_WHITELIST_PATTERNS = tuple(
    re.compile(pattern, re.I) for pattern in RULES["personalized"]["whitelist_patterns"]
)


@lru_cache(maxsize=2048)
def normalize_text(value: str) -> str:
    return _normalize(value, _TRADITIONAL_MAP)


def matches_evidence_rule(name: str, normalized_text: str) -> bool:
    return _EVIDENCE_PATTERNS[name].search(normalized_text) is not None


def _contains(text: str, term: str) -> bool:
    normalized_term = normalize_text(term)
    if re.fullmatch(r"[a-z0-9]+", normalized_term):
        return re.search(rf"(?<![a-z0-9]){re.escape(normalized_term)}(?![a-z0-9])", text) is not None
    return normalized_term in text


def _matches_any(text: str, terms: list[str]) -> list[str]:
    return [term for term in terms if _contains(text, term)]


def _active_symptoms(question: str) -> list[str]:
    emergency = RULES["emergency"]
    normalized = normalize_text(question)
    active: list[str] = []
    for term in emergency["symptom_terms"]:
        normalized_term = normalize_text(term)
        if not _contains(normalized, term):
            continue
        start = normalized.find(normalized_term)
        while start >= 0:
            prefix = normalized[max(0, start - 12):start]
            suffix_start = start + len(normalized_term)
            suffix = normalized[suffix_start:suffix_start + 32]
            negated = any(_contains(prefix, negation) for negation in emergency["negation_terms"])
            negated = negated or any(_contains(suffix, negation) for negation in emergency["negation_terms"])
            historical = any(_contains(prefix, history) or _contains(suffix, history)
                             for history in emergency["history_terms"])
            if not negated and not historical:
                active.append(term)
                break
            start = normalized.find(normalized_term, start + len(normalized_term))
    return active


def emergency_score(question: str) -> int:
    normalized = normalize_text(question)
    emergency = RULES["emergency"]
    active = _active_symptoms(question)
    if not active:
        return 0
    if _matches_any(normalized, emergency["knowledge_intent_terms"]):
        return 0
    critical = [term for term in emergency["critical_symptom_terms"] if term in active]
    modifiers = _matches_any(normalized, emergency["time_severity_help_terms"])
    personal_now = _matches_any(normalized, emergency["current_person_terms"])
    return max(2 if critical else 0, len(active) + int(bool(modifiers or personal_now)))


def is_emergency(question: str) -> bool:
    return emergency_score(question) >= RULES["emergency"]["score_threshold"]


def is_individualized_request(question: str) -> bool:
    normalized = normalize_text(question)
    personalized = RULES["personalized"]
    has_person = bool(_matches_any(normalized, personalized["person_terms"]))
    has_action = bool(_matches_any(normalized, personalized["medication_action_terms"]))
    has_dose_or_adjustment = bool(_matches_any(normalized, personalized["dose_or_adjustment_terms"]))
    # Separated verb/object forms still request the same prohibited medication decision.
    explicit_plan = bool(re.search(
        r"停(?:掉|用|服|下)?[^药]{0,8}药"
        r"|(?:给|为|帮)(?:我|本人|患者|家人).{0,8}(?:开|制定).{0,8}(?:处方|药方|用药方案)"
        r"|(?:prescrib(?:e|ing)|(?:write|give).{0,8}(?:me|my)).{0,40}(?:prescription|medication|herb|formula)",
        normalized,
    ))
    return has_person and ((has_action and has_dose_or_adjustment) or explicit_plan)


def is_whitelisted_knowledge_question(question: str) -> bool:
    normalized = normalize_text(question)
    return any(pattern.search(normalized) for pattern in _WHITELIST_PATTERNS)


def classify_question(question: str) -> str:
    if is_emergency(question):
        return "safety_emergency"
    if is_individualized_request(question):
        return "individualized_refusal"
    if is_whitelisted_knowledge_question(question):
        return "knowledge"
    return "knowledge"
