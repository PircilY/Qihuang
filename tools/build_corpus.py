"""Build attributed excerpts and optionally verify them against cached source HTML."""

import argparse
import hashlib
import json
import re
from pathlib import Path

from source_text import extract_text

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "backend/src/main/resources/data"


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def normalize(text):
    return re.sub(r"\s+", " ", text).strip()


def build(cache_dir=None):
    sources = json.loads((ROOT / "data/sources.json").read_text(encoding="utf-8"))
    documents, chunks = [], []
    for source in sources:
        if cache_dir:
            visible = normalize(extract_text(cache_dir / source["cacheFile"]))
            for excerpt in source["excerpts"]:
                if normalize(excerpt["content"]) not in visible:
                    raise ValueError(f"Excerpt not found in source: {excerpt['id']}")
                if normalize(excerpt["section"]) not in visible:
                    raise ValueError(f"Section not found in source: {excerpt['id']}")
        shared = {key: source[key] for key in (
            "title", "sourceType", "publisher", "publicationYear", "version"
        )}
        shared.update(evidenceLevel="未评级", status="ACTIVE")
        documents.append({
            **shared, "id": source["id"], "authority": "公开机构原文",
            "diseaseScope": list(dict.fromkeys(e["disease"] for e in source["excerpts"])),
            "fileName": source["cacheFile"],
            "checksum": digest("\n\n".join(e["content"] for e in source["excerpts"])),
            "registeredAt": source["accessedAt"], "licenseNote": source["licenseNote"],
            "description": source["description"], "sourceUrl": source["sourceUrl"],
            "accessedAt": source["accessedAt"],
        })
        for excerpt in source["excerpts"]:
            chunks.append({
                **shared, **excerpt, "documentId": source["id"],
                "syndromes": [], "formulas": [], "herbs": excerpt.get("herbs", []),
                "pageStart": 0, "pageEnd": 0, "checksum": digest(excerpt["content"]),
                "ingestedAt": source["accessedAt"],
            })
    assert len({item["id"] for item in chunks}) == len(chunks)
    return {"knowledge-documents.json": documents, "knowledge-base.json": chunks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, payload in build(args.cache_dir).items():
        path = OUTPUT / name
        if args.check:
            if json.loads(path.read_text(encoding="utf-8")) != payload:
                raise ValueError(f"Corpus differs from source manifest: {name}")
        else:
            path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{name}: {len(payload)} records verified")


if __name__ == "__main__":
    main()
