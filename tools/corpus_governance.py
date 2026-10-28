"""Source-ledger governance shared by collection, validation and delivery."""
from __future__ import annotations

import json
import html
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
TCM_TERMS = (
    "中医", "中药", "针灸", "草药", "方剂", "辨证", "黄芪", "丹参",
    "traditional chinese medicine", "chinese medicine", "chinese herbal",
    "herbal medicine", "acupuncture", "astragalus", "salvia miltiorrhiza",
    "danshen", "huangqi", "tongxinluo", "qiliqiangxin", "qishen",
    "shensong", "tanshinone", "qi deficiency", "blood stasis",
    "astragaloside", "ginsenoside", "decoction", "shengmai", "sheng mai",
    "gualou", "xiebai", "tcm", "chinese patent medicine",
    "shenge", "shenfu", "syndrome differentiation",
)
CARDIO_TERMS = (
    "心血管", "冠心病", "心力衰竭", "心衰", "高血压", "心脏", "心肌", "心律",
    "cardiovascular", "coronary", "heart failure", "hypertension",
    "cardiac", "myocardial", "arrhythmia", "angina", "atherosclerosis",
    "cardiomyopathy", "cardiometabolic", "antihypertensive", "hypertensive",
    "ventricular", "thrombosis",
    "cardiomyocyte", "cardio-cerebrovascular", "ischemic heart", "hypotensive",
)
ROLES = {"core", "background", "excluded"}


def source_path(value: str, root: Path = ROOT) -> Path:
    raw_root = (root / "data/expanded-raw").resolve()
    candidate = (root / value).resolve()
    if not candidate.is_relative_to(raw_root) or candidate == raw_root:
        raise ValueError("source path must stay inside data/expanded-raw")
    return candidate


def source_url(value: str) -> bool:
    try:
        parsed = urlsplit(value)
        return (parsed.scheme == "https" and bool(parsed.hostname)
                and not parsed.username and not parsed.password
                and not parsed.fragment)
    except ValueError:
        return False


def parse_article(xml: str) -> dict:
    if len(xml.encode("utf-8")) > 16_000_000 or "<!ENTITY" in xml.upper():
        raise ValueError("unsafe or oversized source XML")
    root = ET.fromstring(xml)
    for element in root.iter():
        element.tag = element.tag.rsplit("}", 1)[-1]
    body = root.find(".//body")
    if body is None:
        raise ValueError("full-text body missing; abstracts are not full text")
    sections = []
    for element in body.iter():
        if element.tag in {"title", "p"}:
            text = re.sub(r"\s+", " ", "".join(element.itertext())).strip()
            if text:
                sections.append(text)
    text = "\n\n".join(sections)
    if len(text) < 500:
        raise ValueError("full-text body too short")
    licenses = []
    for element in root.findall(".//permissions/license"):
        urls = [value for key, value in element.attrib.items() if key.endswith("href")]
        urls.extend(value for child in element.iter() for key, value in child.attrib.items()
                    if key.endswith("href"))
        statement = re.sub(r"\s+", " ", "".join(element.itertext())).strip()
        licenses.append({"urls": list(dict.fromkeys(urls)), "statement": statement})
    title = root.find(".//article-title")
    return {"text": text, "sections": sections, "licenses": licenses,
            "title": re.sub(r"\s+", " ", "".join(title.itertext())).strip() if title is not None else ""}


def redistribution_license(licenses: list[dict]) -> str | None:
    for license_record in licenses:
        for url in license_record["urls"]:
            parsed = urlsplit(url)
            try:
                safe = (parsed.scheme in {"http", "https"} and not parsed.username
                        and not parsed.password and parsed.port in {None, 80, 443}
                        and not parsed.query and not parsed.fragment)
            except ValueError:
                safe = False
            if not safe:
                continue
            if parsed.hostname in {"creativecommons.org", "www.creativecommons.org"}:
                if re.fullmatch(r"/licenses/by/(?:[1-4]\.0)/?", parsed.path):
                    return url
                if parsed.path.rstrip("/") == "/publicdomain/zero/1.0":
                    return url
        statement = license_record["statement"].casefold()
        if "public domain" in statement and "united states government" in statement:
            return "public-domain-us-government"
    return None


def topic_review(record: dict, text: str) -> dict:
    title = re.sub(r"<[^>]+>", "", html.unescape(record.get("title", "")))
    metadata = title + " " + re.sub(r"<[^>]+>", "", html.unescape(record.get("abstractOrDescription", "")))
    folded = metadata.casefold()
    tcm = [term for term in TCM_TERMS if term.casefold() in folded]
    cardio = [term for term in CARDIO_TERMS if term.casefold() in folded]
    title_tcm = [term for term in TCM_TERMS if term.casefold() in title.casefold()]
    title_cardio = [term for term in CARDIO_TERMS if term.casefold() in title.casefold()]
    body_tcm = [term for term in TCM_TERMS if term.casefold() in text.casefold()]
    body_cardio = [term for term in CARDIO_TERMS if term.casefold() in text.casefold()]
    notice = bool(re.search(r"\b(retraction|retracted|correction|corrigendum)\b", title, re.I))
    role = ("excluded" if notice else "core" if title_tcm and title_cardio
            else "background" if title_tcm or title_cardio else "excluded")
    missing_body = role == "core" and bool(text) and not (body_tcm and body_cardio)
    if missing_body:
        role = "excluded"
    evidence = []
    paragraphs = re.split(r"\n\s*\n|(?<=[。.!?])\s+", text)
    for terms in (body_tcm, body_cardio):
        paragraph = next((p for p in paragraphs
                          if any(term.casefold() in p.casefold() for term in terms)), None)
        if paragraph and paragraph[:700] not in evidence:
            evidence.append(paragraph[:700])
    return {"corpusRole": role, "topicReview": {
        "method": "source-title-dual-topic-and-body-screen-v2",
        "status": "automatic-screened-not-clinical-review",
        "metadataTcmTerms": tcm, "metadataCardioTerms": cardio,
        "titleTcmTerms": title_tcm, "titleCardioTerms": title_cardio,
        "bodyTcmTerms": body_tcm, "bodyCardioTerms": body_cardio,
        "bodyEvidence": evidence,
        "reason": "publication-notice-not-evidence" if notice else "missing-dual-topic-body"
        if missing_body else "dual-topic-title"
        if role == "core" else "single-topic-background"
        if role == "background" else "outside-project-scope",
    }}


def indexed(record: dict) -> bool:
    return record.get("corpusRole", "background") != "excluded" and record.get("inclusionDecision") == "include"


def save_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
