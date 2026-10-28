import json
from pathlib import Path

import time

import pytest

from app.safety_rules import (
    RULES_SHA256,
    RULES_PATH,
    _load_rules,
    classify_question,
    is_emergency,
    is_individualized_request,
    is_whitelisted_knowledge_question,
    normalize_text,
)


CASES = json.loads((Path(__file__).resolve().parents[2] / "tests" / "safety-rule-cases.json").read_text(encoding="utf-8"))
BLIND_CASES_PATH = Path(__file__).resolve().parents[2] / "tests" / "safety-blind-cases.json"
BLIND_CASES = json.loads(BLIND_CASES_PATH.read_text(encoding="utf-8"))


def test_shared_safety_rule_holdout_cases():
    assert len(CASES) >= 76
    for case in CASES:
        refused = is_emergency(case["question"]) or is_individualized_request(case["question"])
        assert refused is case["should_refuse"], case["id"]


def test_user_authored_blind_cases_when_present():
    for case in BLIND_CASES:
        refused = is_emergency(case["question"]) or is_individualized_request(case["question"])
        assert refused is case["should_refuse"], case["id"]


def test_emergency_intent_negation_history_and_normalization():
    assert classify_question("胸痛的原因有哪些？") == "knowledge"
    assert classify_question("我现在胸痛") == "safety_emergency"
    assert not is_emergency("没有胸痛")
    assert not is_emergency("以前胸痛过，现在已经好了")
    assert is_emergency("胸，口　痛，喘不上气")
    assert normalize_text(" Ｉ　ＨＡＶＥ，Chest Pain！ ") == "i have chest pain"


def test_personalized_refusal_precedes_whitelist_allowlist():
    question = "常见用量范围：我每天吃几片合适？"
    assert is_whitelisted_knowledge_question(question)
    assert is_individualized_request(question)
    assert classify_question(question) == "individualized_refusal"


@pytest.mark.parametrize("question", [
    "我有高血压，能停掉降压药只喝丹参吗？",
    "我有冠心病，请为我开一个中药处方。",
    "请帮患者制定用药方案。",
    "常见用量范围，请给我开一个处方。",
    "Can you prescribe herbs for me?",
    "Can you write me a herbal prescription?",
])
def test_explicit_medication_decisions_without_literal_dose(question):
    assert classify_question(question) == "individualized_refusal"


@pytest.mark.parametrize("question", [
    "黄芪和丹参的方剂研究有哪些？",
    "停掉降压药的风险有哪些研究？",
    "中药处方的历史有哪些文献？",
    "What research discusses herbal prescriptions?",
])
def test_impersonal_research_is_not_prescription_intent(question):
    assert not is_individualized_request(question)


@pytest.mark.parametrize("payload, message", [
    (b"{", "invalid safety-rules.json"),
    (b'{"normalization":{"traditional_to_simplified":{"x":"x"}},"emergency":{},"personalized":{}}',
     "symptom_terms must be a non-empty string list"),
])
def test_safety_rules_invalid_or_missing_required_values_fail_closed(tmp_path, payload, message):
    path = tmp_path / "safety-rules.json"
    path.write_bytes(payload)
    with pytest.raises(RuntimeError, match=message):
        _load_rules(path)


def test_missing_safety_rules_fail_with_explicit_startup_error(tmp_path):
    with pytest.raises(RuntimeError, match="Safety rules startup failure: configured file is missing"):
        _load_rules(tmp_path / "missing-safety-rules.json")


def test_safety_rules_sha256_is_exposed_by_health():
    import hashlib

    assert hashlib.sha256(RULES_PATH.read_bytes()).hexdigest() == RULES_SHA256


def test_safety_matcher_is_bounded_for_max_length_and_pathological_text():
    seed = "我现在胸痛；常见用量范围，我每天吃几片？!（）＂"
    text = (seed * 60)[:500]
    assert len(text) == 500
    started = time.perf_counter()
    for _ in range(100):
        is_emergency(text)
        is_individualized_request(text)
        is_whitelisted_knowledge_question(text)
    assert time.perf_counter() - started < 1.0
