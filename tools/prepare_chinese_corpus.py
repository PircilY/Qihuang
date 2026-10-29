"""Restore source-authored Chinese metadata and report a bounded corpus increment."""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import json
from pathlib import Path
import re
import subprocess

from corpus_governance import ROOT, indexed, parse_article, save_json, source_path

LEDGER = ROOT / "data/expanded-sources.json"


def chinese(text: str) -> bool:
    return len(re.findall(r"[\u3400-\u9fff]", text)) >= 2


def restore_metadata(ledger: dict) -> tuple[dict, list[dict]]:
    result = copy.deepcopy(ledger)
    changes = []
    for record in result["records"]:
        if record["language"] != "zh":
            continue
        article = parse_article(source_path(record["rawSourcePath"]).read_text(encoding="utf-8"))
        # Restore only source-authored Chinese, never translate English metadata.
        titles = [article["title"], *[t["text"] for t in article["translatedTitles"]]]
        title = next((t for t in titles if chinese(t)), "")
        abstracts = [a for a in article["abstracts"] if len(re.findall(r"[\u3400-\u9fff]", a)) >= 20]
        if not title:
            continue
        old_title = record["title"]
        old_abstract = record.get("abstractOrDescription", "")
        if (title != old_title or (abstracts and abstracts[0] != old_abstract)
                or record.get("sourceTranslatedTitles") != article["translatedTitles"]):
            record.setdefault("sourceSearchTitle", old_title)
            record["title"] = title
            record["sourceTranslatedTitles"] = article["translatedTitles"]
            if abstracts:
                record["abstractOrDescription"] = abstracts[0]
            record["metadataProvenance"] = {
                "method": "retained-JATS-source-authored-Chinese",
                "titlePath": "front/article-meta/title-group",
                "abstractPath": "front/article-meta/abstract",
                "rawSourceChecksum": record.get("rawSourceChecksum"),
                "translatedByProject": False,
            }
            changes.append({"documentId": record["documentId"], "previousTitle": old_title,
                            "title": title, "chineseAbstractRestored": bool(abstracts)})
    return result, changes


def summary(ledger: dict, chunks: list[dict]) -> dict:
    records = [r for r in ledger["records"] if indexed(r)]
    zh = [r for r in records if r["language"] == "zh"]
    ids = {r["documentId"] for r in zh}
    return {
        "ledgerRecords": len(ledger["records"]),
        "indexedDocuments": len(records), "indexedChunks": len(chunks),
        "chineseDocuments": len(zh),
        "chineseChunks": sum(c["documentId"] in ids for c in chunks),
        "chineseRoles": dict(Counter(r["corpusRole"] for r in zh)),
        "chineseTitleDocuments": sum(chinese(r["title"]) for r in zh),
        "chineseAbstractDocuments": sum(chinese(r.get("abstractOrDescription", "")) for r in zh),
        "chineseTcmTitleDocuments": sum(bool(r.get("topicReview", {}).get("titleTcmTerms")) for r in zh),
        "chinesePermissiveDocuments": sum(r.get("redistributionStatus") == "permissive-verified" for r in zh),
    }


def git_json(ref: str, path: str) -> object:
    return json.loads(subprocess.check_output(["git", "show", f"{ref}:{path}"], cwd=ROOT).decode("utf-8"))


def report(before_ref: str, state_path: Path, metadata_changes: list[dict]):
    before = git_json(before_ref, "data/expanded-sources.json")
    before_chunks = git_json(before_ref, "backend/src/main/resources/data/expanded/knowledge-base.json")
    current = json.loads(LEDGER.read_text(encoding="utf-8"))
    current_chunks = json.loads((ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json").read_text(encoding="utf-8"))
    old_ids = {r["documentId"] for r in before["records"]}
    added = [r for r in current["records"] if r["documentId"] not in old_ids]
    state = json.loads(state_path.read_text(encoding="utf-8"))
    decisions = [{k: c[k] for k in ("pmcid", "title", "topics", "status", "reason", "documentId",
                                   "attemptedAt", "discoveredAt", "previousAttempts") if k in c}
                 for c in state["candidates"]]
    fields = ("documentId", "title", "language", "corpusRole", "collectionTopics",
              "sourceUrl", "sourceChecksum", "rawSourceChecksum", "accessedAt",
              "licenseEvidence", "redistributionStatus", "redistributionLicenseUrl",
              "noncommercialLicenseUrl", "usageScope", "topicReview")
    # Every restricted excerpt has an enclosing documentId for delivery redaction.
    payload = {
        "beforeCommit": before_ref, "batchId": state["batchId"],
        "before": summary(before, before_chunks), "after": summary(current, current_chunks),
        "addedDocuments": len(added), "addedLanguages": dict(Counter(r["language"] for r in added)),
        "addedRoles": dict(Counter(r["corpusRole"] for r in added)),
        "addedLicenses": dict(Counter(r["license"] for r in added)),
        "metadataChanges": metadata_changes,
        "records": [{k: r[k] for k in fields if k in r} for r in added],
        "queries": state["queries"], "candidateCount": len(decisions),
        "candidateStatuses": dict(Counter(c["status"] for c in decisions)),
        "candidates": decisions, "failures": state.get("failures", []),
        "limits": [
            "Chinese originals and source-authored abstracts only; no generated translations.",
            "Background TCM sources are not cardiovascular clinical evidence.",
            "Explicit BY-NC additions are noncommercial research only and excluded from general delivery.",
            "All evidence levels remain ungraded; source screening is not expert clinical review.",
            "PMC Chinese coverage is limited; general hypertension hits do not count as targeted TCM evidence.",
        ],
    }
    save_json(ROOT / "docs/chinese-corpus-increment.json", payload)
    before_path = ROOT / "runtime/chinese-corpus-before/knowledge-base.json"
    save_json(before_path, before_chunks)
    previous_indexed_ids = {c["documentId"] for c in before_chunks}
    save_json(ROOT / "runtime/chinese-corpus-metadata-only/knowledge-base.json",
              [c for c in current_chunks if c["documentId"] in previous_indexed_ids])
    print(json.dumps({k: payload[k] for k in ("before", "after", "addedDocuments", "addedRoles", "candidateStatuses")}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restore-metadata", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--before-ref", default="21a2457")
    parser.add_argument("--state", type=Path, default=ROOT / "runtime/corpus-collection/chinese-20261029.json")
    args = parser.parse_args()
    if not args.state.resolve().is_relative_to((ROOT / "runtime").resolve()):
        parser.error("collection state must stay inside runtime")
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    restored, changes = restore_metadata(ledger)
    if args.check and restored != ledger:
        raise SystemExit("Chinese source metadata is stale")
    if args.restore_metadata:
        save_json(LEDGER, restored)
        print(json.dumps({"restoredMetadata": len(changes)}, ensure_ascii=False))
    if args.report:
        before = git_json(args.before_ref, "data/expanded-sources.json")
        _, historical_changes = restore_metadata(before)
        report(args.before_ref, args.state, historical_changes)


if __name__ == "__main__":
    main()
