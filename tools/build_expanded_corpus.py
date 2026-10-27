"""Build expanded knowledge files from the verified source ledger."""

from __future__ import annotations

import hashlib
import json
import re
import argparse
from datetime import datetime, timezone
from pathlib import Path

from validate_expanded_sources import validate, ROOT
from terminology_utils import extract_metadata


OUT = ROOT / "backend" / "src" / "main" / "resources" / "data" / "expanded"
CHUNK_SIZE = 620


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def metadata_confidence(values: dict[str, list[str]], title: str, description: str, content: str) -> dict[str, list[str]]:
    early = " ".join((title, description, content[:500])).casefold()
    full = (title + " " + content).casefold()
    return {field: [value for value in items if value and value.casefold() not in early
                    and full.count(value.casefold()) == 1]
            for field, items in values.items()}


def chunks(text: str) -> list[str]:
    normalized = re.sub(r"\s+", " ", text).strip()
    return [normalized[index:index + CHUNK_SIZE] for index in range(0, len(normalized), CHUNK_SIZE)]


def build() -> tuple[list[dict], list[dict]]:
    validation = validate()
    if validation["errors"]:
        raise ValueError("expanded source validation failed:\n" + "\n".join(validation["errors"]))
    ledger = json.loads((ROOT / "data" / "expanded-sources.json").read_text(encoding="utf-8"))
    documents: list[dict] = []
    knowledge_chunks: list[dict] = []
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    for record in ledger["records"]:
        raw = (ROOT / record["fullTextPath"]).read_text(encoding="utf-8").strip()
        source_digest = digest(raw)
        title_metadata = extract_metadata(record["title"])
        documents.append({
            "id": record["documentId"],
            "title": record["title"],
            "sourceType": record["documentType"],
            "publisher": record["sourceName"],
            "publicationYear": record.get("publicationYear", 0),
            "authority": record["sourceName"],
            "evidenceLevel": "未评级",
            "diseaseScope": list(dict.fromkeys(record.get("topicTags", []) + title_metadata["disease"])),
            "herbs": title_metadata["herbs"],
            "formulas": title_metadata["formulas"],
            "syndromes": title_metadata["syndromes"],
            "fileName": record["fullTextPath"],
            "checksum": source_digest,
            "version": "expanded-v1",
            "status": "ACTIVE",
            "registeredAt": record.get("accessedAt", now),
            "licenseNote": f"{record.get('license', '')}; {record['licenseStatus']}",
            "description": record.get("abstractOrDescription", ""),
            "sourceUrl": record["sourceUrl"],
            "accessedAt": record.get("accessedAt", now),
            "metadataSource": "来源台账+词典匹配" if any(title_metadata.values()) else "来源台账",
        })
        for index, content in enumerate(chunks(raw), start=1):
            chunk_id = f"{record['documentId']}-C{index:04d}"
            metadata = extract_metadata(record["title"] + "\n" + content)
            confidence = metadata_confidence(metadata, record["title"], record.get("abstractOrDescription", ""), content)
            disease_values = list(dict.fromkeys(record.get("topicTags", []) + title_metadata["disease"] + metadata["disease"]))
            knowledge_chunks.append({
                "id": chunk_id,
                "documentId": record["documentId"],
                "title": record["title"],
                "sourceType": record["documentType"],
                "publisher": record["sourceName"],
                "publicationYear": record.get("publicationYear", 0),
                "disease": "、".join(disease_values),
                "syndromes": metadata["syndromes"], "formulas": metadata["formulas"], "herbs": metadata["herbs"],
                "pageStart": 0, "pageEnd": 0,
                "section": "全文正文",
                "evidenceLevel": "未评级",
                "keywords": record.get("topicTags", []),
                "content": content,
                "checksum": digest(content),
                "status": "ACTIVE",
                "version": "expanded-v1",
                "ingestedAt": record.get("accessedAt", now),
                "metadataSource": "词典匹配" if any(metadata.values()) else "",
                "metadataConfidence": confidence,
            })
    return documents, knowledge_chunks


def check() -> dict:
    expected_documents, expected_chunks = build()
    documents_path = OUT / "knowledge-documents.json"
    chunks_path = OUT / "knowledge-base.json"
    actual_documents = json.loads(documents_path.read_text(encoding="utf-8"))
    actual_chunks = json.loads(chunks_path.read_text(encoding="utf-8"))
    if actual_documents != expected_documents or actual_chunks != expected_chunks:
        raise SystemExit("expanded corpus is stale; run tools/build_expanded_corpus.py")
    coverage = {}
    for field in ("herbs", "formulas", "syndromes"):
        document_hits = sum(bool(document.get(field)) for document in actual_documents)
        chunk_hits = sum(bool(chunk.get(field)) for chunk in actual_chunks)
        coverage[field] = {"documents": document_hits, "documentRate": round(document_hits / max(1, len(actual_documents)), 4),
                           "chunks": chunk_hits, "chunkRate": round(chunk_hits / max(1, len(actual_chunks)), 4)}
    return {"documents": len(actual_documents), "chunks": len(actual_chunks), "coverage": coverage, "status": "ok"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        print(json.dumps(check(), ensure_ascii=False))
        raise SystemExit(0)
    documents, knowledge_chunks = build()
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "knowledge-documents.json").write_text(json.dumps(documents, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "knowledge-base.json").write_text(json.dumps(knowledge_chunks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"documents": len(documents), "chunks": len(knowledge_chunks), "output": str(OUT)}, ensure_ascii=False))
