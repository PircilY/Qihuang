from __future__ import annotations

import re
from dataclasses import dataclass


_MEDICATION = re.compile(r"降压药|药物|药品|用药|服药|吃药|药量|毫克|\bmg\b", re.I)
_DOSE_OR_AMOUNT = re.compile(
    r"剂量|药量|加到|增加|减少|加药|减药|加量|减量|调高|调低|"
    r"多少\s*(?:毫克|mg|片|粒|克)?|几\s*(?:毫克|mg|片|粒|克)|"
    r"每天.{0,8}(?:吃|服).{0,5}(?:片|粒|多少)|应该吃多少|该吃多少",
    re.I | re.S,
)
_ADJUSTMENT = re.compile(r"调整|加到|增加|减少|加药|减药|加量|减量|调高|调低", re.I)
_PERSONALIZED_CONTEXT = re.compile(
    r"我|我的|今天|现在|当前|现用|正在服用|患者|病人|应该|应当|该不该|能不能|是否可以",
    re.I,
)
_IMPLICIT_MEDICATION_DOSE = re.compile(
    r"每天.{0,8}(?:吃|服).{0,5}(?:几片|多少)|(?:应该|应当|该)吃多少", re.I | re.S
)

_PREGNANCY_REQUEST = re.compile(r"孕妇|妊娠|怀孕|孕期|pregnan", re.I)
_PREGNANCY_EVIDENCE = re.compile(
    r"(?=.*(?:孕妇|妊娠|怀孕|孕期|pregnan))"
    r"(?=.*(?:安全|风险|不良|禁忌|safe|safety|risk|adverse|harm))",
    re.I | re.S,
)
_BLOOD_PRESSURE = re.compile(r"血压|高血压|blood\s*pressure|hypertension", re.I)
_RANGE_REQUEST = re.compile(
    r"正常范围|正常值|标准范围|精确范围|正常区间|血压范围|正常数值|"
    r"normal\s*(?:range|value)|range",
    re.I,
)
_AGE_GROUP = re.compile(
    r"不同年龄|年龄分层|年龄段|年龄|儿童|孩子|老人|老年人|成人|"
    r"age\s*groups?|children|child|elderly|older\s*adults?",
    re.I,
)
_RANGE_EVIDENCE = re.compile(r"(?=.*(?:正常|范围|区间|normal|range))(?=.*\d{2,3})", re.I | re.S)
_AGE_RANGE_EVIDENCE = re.compile(
    r"(?=.*(?:不同年龄|年龄分层|年龄段|儿童|孩子|老人|老年人|成人|"
    r"age\s*groups?|children|child|elderly|older\s*adults?))"
    r"(?=.*(?:正常|范围|区间|normal|range))(?=.*\d{2,3})",
    re.I | re.S,
)
_CONDITION_SAFETY_REQUEST = re.compile(
    r"甘草.{0,16}(?:心脏|肾脏|心肾|heart|kidney).{0,16}(?:安全|风险|不良|safe|risk)|"
    r"(?:心脏|肾脏|心肾|heart|kidney).{0,16}甘草.{0,16}(?:安全|风险|不良|safe|risk)",
    re.I | re.S,
)
_CONDITION_SAFETY_EVIDENCE = re.compile(
    r"(?=.*(?:心脏|肾脏|心肾|heart|cardiac|kidney))"
    r"(?=.*(?:安全|风险|不良|严重|safe|risk|adverse|severe))",
    re.I | re.S,
)
_HERB_DRUG_INTERACTION_REQUEST = re.compile(
    r"甘草.{0,24}(?:相互作用|合用|interaction).{0,24}(?:皮质类固醇|激素|corticosteroid)|"
    r"(?:皮质类固醇|激素|corticosteroid).{0,24}甘草.{0,24}(?:相互作用|合用|interaction)",
    re.I | re.S,
)
_HERB_DRUG_INTERACTION_EVIDENCE = re.compile(
    r"(?=.*(?:甘草|licorice))(?=.*(?:皮质类固醇|corticosteroid))"
    r"(?=.*(?:相互作用|interaction|reported|报告))",
    re.I | re.S,
)

DOSE_REFUSAL = "现有公开文献无法支持个体化用药剂量调整建议（涉及个体化处方判断）。"
COVERAGE_REFUSAL = "现有知识库未包含足以支持问题所要求人群或属性的证据。"


@dataclass(frozen=True)
class EvidenceCoverage:
    covered: bool
    reason_code: str
    refusal_reason: str = ""


def is_individualized_dose_adjustment(question: str) -> bool:
    normalized = _normalize(question)
    implicit_dose = bool(_IMPLICIT_MEDICATION_DOSE.search(normalized))
    medication_mentioned = bool(_MEDICATION.search(normalized)) or implicit_dose
    dose_requested = bool(_DOSE_OR_AMOUNT.search(normalized))
    return medication_mentioned and dose_requested and bool(
        implicit_dose or _ADJUSTMENT.search(normalized) or _PERSONALIZED_CONTEXT.search(normalized)
    )


def assess_evidence_coverage(question: str, evidence_texts: list[str]) -> EvidenceCoverage:
    normalized_question = _normalize(question)
    evidence = _normalize(" ".join(evidence_texts or []))

    if is_individualized_dose_adjustment(normalized_question):
        return EvidenceCoverage(False, "individualized-dose-adjustment", DOSE_REFUSAL)

    # Population-specific claims need evidence for that population and its requested safety attribute.
    if _PREGNANCY_REQUEST.search(normalized_question) and not _PREGNANCY_EVIDENCE.search(evidence):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    asks_for_range = bool(_BLOOD_PRESSURE.search(normalized_question) and _RANGE_REQUEST.search(normalized_question))
    if asks_for_range:
        required_evidence = _AGE_RANGE_EVIDENCE if _AGE_GROUP.search(normalized_question) else _RANGE_EVIDENCE
        if not required_evidence.search(evidence):
            return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    if _CONDITION_SAFETY_REQUEST.search(normalized_question) and not _CONDITION_SAFETY_EVIDENCE.search(evidence):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    if _HERB_DRUG_INTERACTION_REQUEST.search(normalized_question) and not _HERB_DRUG_INTERACTION_EVIDENCE.search(evidence):
        return EvidenceCoverage(False, "population-attribute-uncovered", COVERAGE_REFUSAL)

    return EvidenceCoverage(True, "covered")


def _normalize(value: str) -> str:
    return re.sub(r"\s+", "", value or "").casefold()
