"""Build expanded knowledge files from the verified source ledger."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

from validate_expanded_sources import validate, ROOT


OUT = ROOT / "backend" / "src" / "main" / "resources" / "data" / "expanded"
CHUNK_SIZE = 620


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


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
        documents.append({
            "id": record["documentId"],
            "title": record["title"],
            "sourceType": record["documentType"],
            "publisher": record["sourceName"],
            "publicationYear": record.get("publicationYear", 0),
            "authority": record["sourceName"],
            "evidenceLevel": "未评级",
            "diseaseScope": record.get("topicTags", []),
            "fileName": record["fullTextPath"],
            "checksum": source_digest,
            "version": "expanded-v1",
            "status": "ACTIVE",
            "registeredAt": record.get("accessedAt", now),
            "licenseNote": f"{record.get('license', '')}; {record['licenseStatus']}",
            "description": record.get("abstractOrDescription", ""),
            "sourceUrl": record["sourceUrl"],
            "accessedAt": record.get("accessedAt", now),
        })
        for index, content in enumerate(chunks(raw), start=1):
            chunk_id = f"{record['documentId']}-C{index:04d}"
            knowledge_chunks.append({
                "id": chunk_id,
                "documentId": record["documentId"],
                "title": record["title"],
                "sourceType": record["documentType"],
                "publisher": record["sourceName"],
                "publicationYear": record.get("publicationYear", 0),
                "disease": "、".join(record.get("topicTags", [])),
                "syndromes": [], "formulas": [], "herbs": [],
                "pageStart": 0, "pageEnd": 0,
                "section": "全文正文",
                "evidenceLevel": "未评级",
                "keywords": record.get("topicTags", []),
                "content": content,
                "checksum": digest(content),
                "status": "ACTIVE",
                "version": "expanded-v1",
                "ingestedAt": record.get("accessedAt", now),
            })
    return documents, knowledge_chunks


if __name__ == "__main__":
    documents, knowledge_chunks = build()
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "knowledge-documents.json").write_text(json.dumps(documents, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "knowledge-base.json").write_text(json.dumps(knowledge_chunks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"documents": len(documents), "chunks": len(knowledge_chunks), "output": str(OUT)}, ensure_ascii=False))
