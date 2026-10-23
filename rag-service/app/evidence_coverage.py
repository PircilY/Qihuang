from __future__ import annotations

from dataclasses import dataclass

from app.safety_rules import is_individualized_request, matches_evidence_rule, normalize_text

DOSE_REFUSAL = "现有公开文献无法支持个体化用药剂量调整建议（涉及个体化处方判断）。"
COVERAGE_REFUSAL = "现有知识库未包含足以支持问题所要求人群或属性的证据。"


@dataclass(frozen=True)
class EvidenceCoverage:
    covered: bool
    reason_code: str
    refusal_reason: str = ""


def is_individualized_dose_adjustment(question: str) -> bool:
    return is_individualized_request(question)


def assess_evidence_coverage(question: str, evidence_texts: list[str]) -> EvidenceCoverage:
    normalized_question = normalize_text(question)
    evidence = [normalize_text(chunk) for chunk in (evidence_texts or [])]
    def covered(rule):
        return any(matches_evidence_rule(rule, chunk) for chunk in evidence)

    if is_individualized_dose_adjustment(question):
        return EvidenceCoverage(False, "individualized-dose-adjustment", DOSE_REFUSAL)

    # Population-specific claims need evidence for that population and its requested safety attribute.
    if matches_evidence_rule("pregnancy_request", normalized_question) and not covered("pregnancy_evidence"):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    asks_for_range = (matches_evidence_rule("blood_pressure", normalized_question)
                      and matches_evidence_rule("range_request", normalized_question))
    if asks_for_range:
        required_evidence = "age_range_evidence" if matches_evidence_rule("age_group", normalized_question) else "range_evidence"
        if not covered(required_evidence):
            return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    if (matches_evidence_rule("condition_safety_request", normalized_question)
            and not covered("condition_safety_evidence")):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    if (matches_evidence_rule("herb_drug_interaction_request", normalized_question)
            and not covered("herb_drug_interaction_evidence")):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    if (matches_evidence_rule("anticoagulant_adjustment_request", normalized_question)
            and not covered("anticoagulant_adjustment_evidence")):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    return EvidenceCoverage(True, "covered")
