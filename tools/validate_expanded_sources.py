"""Validate the expanded source ledger without changing project baselines."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from corpus_governance import indexed, ROLES, source_path, source_url, parse_article, redistribution_license


ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "data" / "expanded-sources.json"
MIN_TEXT_LENGTH = 500
ALLOWED_LANGUAGES = {"zh", "en"}
TCM_TERMS = (
    "中医", "中药", "针灸", "草药", "方剂", "证候", "辨证",
    "traditional chinese medicine", "chinese herbal", "herbal medicine",
    "acupuncture", "formula", "syndrome", "tanshinone", "salvia",
    "danshen", "astragalus", "tongxinluo", "shensong", "qili",
)
CARDIO_TERMS = (
    "心血管", "冠心病", "心力衰竭", "高血压", "心脏", "心肌", "心律",
    "cardiovascular", "coronary", "heart failure", "hypertension", "cardiac",
    "myocardial", "arrhythmia", "ischemic", "angina", "atherosclerosis",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(ledger_path: Path = LEDGER, root: Path = ROOT) -> dict[str, object]:
    payload = json.loads(ledger_path.read_text(encoding="utf-8"))
    records = payload.get("records", [])
    errors: list[str] = []
    ids: set[str] = set()
    keys: set[tuple[str, str]] = set()
    text_hashes: set[str] = set()
    counts = {"zh": 0, "en": 0}
    roles = {"core": 0, "background": 0, "excluded": 0}
    topic_counts = {
        "tcm_and_cardiovascular": 0,
        "cardiovascular_only": 0,
        "tcm_only": 0,
        "neither": 0,
    }
    topic_review: list[dict[str, object]] = []
    for record in records:
        document_id = str(record.get("documentId", "")).strip()
        url = str(record.get("sourceUrl", "")).strip()
        try:
            raw_path = source_path(str(record.get("fullTextPath", "")), root)
        except ValueError as error:
            errors.append(f"unsafe source path: {document_id}: {error}")
            continue
        language = record.get("language")
        if not document_id or document_id in ids:
            errors.append(f"duplicate or missing documentId: {document_id}")
        ids.add(document_id)
        if not source_url(url):
            errors.append(f"missing source URL: {document_id}")
        if language not in ALLOWED_LANGUAGES:
            errors.append(f"unsupported language: {document_id}")
        else:
            counts[language] += 1
        if record.get("licenseStatus") != "verified" or record.get("verificationStatus") != "verified-full-text":
            errors.append(f"unverified license/full text: {document_id}")
        role = record.get("corpusRole", "background")
        if role not in ROLES:
            errors.append(f"invalid corpusRole: {document_id}")
        else:
            roles[role] += 1
        if record.get("inclusionDecision") not in {"include", "exclude"}:
            errors.append(f"invalid inclusion decision: {document_id}")
        if not raw_path.is_file():
            errors.append(f"missing full text: {document_id} -> {raw_path}")
            continue
        text = raw_path.read_text(encoding="utf-8", errors="strict").strip()
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if len(text) < MIN_TEXT_LENGTH:
            errors.append(f"full text too short: {document_id}")
        searchable = " ".join((str(record.get("title", "")), str(record.get("abstractOrDescription", "")), text))
        folded = searchable.casefold()
        metadata_folded = " ".join((str(record.get("title", "")), str(record.get("abstractOrDescription", "")))).casefold()
        has_tcm = any(term.casefold() in folded for term in TCM_TERMS)
        has_cardio = any(term.casefold() in folded for term in CARDIO_TERMS)
        metadata_has_tcm = any(term.casefold() in metadata_folded for term in TCM_TERMS)
        metadata_has_cardio = any(term.casefold() in metadata_folded for term in CARDIO_TERMS)
        if not has_tcm and not has_cardio:
            errors.append(f"topic terms not found in full text: {document_id}")
        if metadata_has_tcm and metadata_has_cardio:
            topic_class = "tcm_and_cardiovascular"
        elif metadata_has_cardio:
            topic_class = "cardiovascular_only"
        elif metadata_has_tcm:
            topic_class = "tcm_only"
        else:
            topic_class = "neither"
        topic_counts[topic_class] += 1
        topic_review.append({
            "documentId": document_id,
            "language": language,
            "classification": topic_class,
            "manualReviewRequired": topic_class != "tcm_and_cardiovascular",
        })
        if digest in text_hashes:
            errors.append(f"duplicate full text hash: {document_id}")
        text_hashes.add(digest)
        if record.get("sourceChecksum") != digest:
            errors.append(f"source checksum mismatch: {document_id}")
        try:
            xml_path = source_path(record["rawSourcePath"], root)
            xml = xml_path.read_text(encoding="utf-8")
            article = parse_article(xml)
            if record.get("rawSourceChecksum") and hashlib.sha256(xml.encode("utf-8")).hexdigest() != record["rawSourceChecksum"]:
                errors.append(f"raw XML checksum mismatch: {document_id}")
            if record.get("redistributionStatus") == "permissive-verified" and not redistribution_license(article["licenses"]):
                errors.append(f"redistribution license proof missing: {document_id}")
        except (OSError, ValueError, KeyError) as error:
            errors.append(f"invalid raw source: {document_id}: {error}")
        for key in (("pmcid", str(record.get("pmcid", "")).strip().casefold()),
                    ("doi", str(record.get("doi", "")).strip().casefold()),
                    ("pmid", str(record.get("pmid", "")).strip()), ("sourceUrl", url)):
            if key[1] and key in keys:
                errors.append(f"duplicate {key[0]}: {document_id}")
            if key[1]:
                keys.add(key)
    return {
        "total": len(records),
        "languageCounts": counts,
        "roleCounts": roles,
        "indexedCount": sum(indexed(record) for record in records),
        "topicCounts": topic_counts,
        "manualReviewCount": sum(1 for item in topic_review if item["manualReviewRequired"]),
        "topicReview": topic_review,
        "errors": errors,
    }


if __name__ == "__main__":
    result = validate()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(1 if result["errors"] else 0)
