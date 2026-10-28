"""Export bounded collection evidence and a precise incremental ledger diff."""
from collections import Counter
import json
import subprocess

from corpus_governance import ROOT, indexed, save_json


def main():
    before = json.loads(subprocess.check_output(
        ["git", "show", "c2da130:data/expanded-sources.json"], cwd=ROOT
    ).decode("utf-8"))
    current = json.loads((ROOT / "data/expanded-sources.json").read_text(encoding="utf-8"))
    state = json.loads((ROOT / "runtime/corpus-collection/state.json").read_text(encoding="utf-8"))
    old_ids = {r["documentId"] for r in before["records"]}
    records = current["records"]
    by_pmcid = {r["pmcid"]: r for r in records}
    added = [r for r in records if r["documentId"] not in old_ids]
    fields = ("documentId", "pmcid", "doi", "title", "language", "corpusRole", "sourceUrl",
              "sourceChecksum", "accessedAt", "licenseEvidence", "redistributionLicenseUrl",
              "redistributionStatus", "collectionTopics", "topicReview")
    payload = {
        "beforeCommit": "c2da130", "oldRecords": len(old_ids), "currentRecords": len(records),
        "addedRecords": len(added), "addedRoles": dict(Counter(r["corpusRole"] for r in added)),
        "addedLanguages": dict(Counter(r["language"] for r in added)),
        "addedLicenses": dict(Counter(r["redistributionStatus"] for r in added)),
        "indexedRecords": sum(indexed(r) for r in records),
        "records": [{k: r[k] for k in fields if k in r} for r in added],
        "isolation": [{**{k: r[k] for k in ("documentId", "title", "corpusRole", "redistributionStatus")},
                       "reason": r["topicReview"]["reason"],
                       "sourceReviewDecision": r["topicReview"].get("sourceReviewDecision")}
                      for r in records if r["corpusRole"] == "excluded"],
        "redistributionIsolationIds": [r["documentId"] for r in records
                                       if r["redistributionStatus"] != "permissive-verified"],
        "limits": [
            "Source-assisted topic screening, not expert clinical or legal certification.",
            "All 48 additions are original English full texts; no fabricated Chinese translations.",
            "Background documents remain searchable; excluded documents and originals remain in the ledger.",
            "Legacy nonpermissive texts stay in the existing workspace but not in the delivery archive.",
        ],
    }
    save_json(ROOT / "docs/corpus-increment.json", payload)
    candidates = []
    for candidate in state["candidates"]:
        record = by_pmcid.get(candidate["pmcid"])
        candidates.append({**{k: candidate[k] for k in (
            "pmcid", "title", "topics", "language", "status", "reason", "matches",
            "discoveredAt", "attemptedAt") if k in candidate},
            "ledgerDocumentId": record["documentId"] if record else None,
            "finalRole": record.get("corpusRole") if record else None})
    save_json(ROOT / "docs/corpus-candidates.json", {
        "batchId": state["batchId"], "updatedAt": state.get("updatedAt"),
        "queries": state["queries"], "count": len(candidates),
        "statuses": dict(Counter(c["status"] for c in candidates)),
        "failureNote": "Failed attempts remain recorded even if a ledger write recovered later; ledgerDocumentId is authoritative.",
        "candidates": candidates,
    })
    print(json.dumps({k: v for k, v in payload.items() if k not in (
        "records", "isolation", "redistributionIsolationIds", "limits")}))


if __name__ == "__main__":
    main()
