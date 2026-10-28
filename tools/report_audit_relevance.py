"""Publish separate code-fix and corpus-growth runs without modifying frozen labels."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PHASES = (
    ("before-fix", "c2da130 code, original corpus"),
    ("after-fix", "metadata fix only, original corpus"),
    ("after-safety-fix", "final safety and metadata fixes, original corpus"),
    ("after-corpus", "final fixes, governed expanded corpus"),
)


def main():
    labels = ROOT / "data/audit-relevance-cases.json"
    checksum = hashlib.sha256(labels.read_bytes()).hexdigest()
    runs = {}
    for phase, description in PHASES:
        result = json.loads((ROOT / f"runtime/audit-relevance-{phase}.json").read_text(encoding="utf-8"))
        if (result["labelsSha256"] != checksum or result["queryBridge"]
                or result["backend"] != "hash" or result["threshold"] != .9):
            raise ValueError(f"incomparable evaluation configuration: {phase}")
        result.pop("engineSource")
        runs[phase] = {"description": description, **result}
    before, fixed, expanded = (runs[key] for key in ("before-fix", "after-safety-fix", "after-corpus"))
    if before["corpusSha256"] != fixed["corpusSha256"]:
        raise ValueError("code comparison must use identical original corpus")
    payload = {"labelsSha256": checksum, "rules": "target document AND both source body anchors in Top3; exact indexed quote",
               "sourceSnapshot": "c2da130", "runs": runs}
    (ROOT / "docs/audit-relevance-results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# Independent retrieval audit", "",
        "24 positive source-nominated questions and 6 negative questions were frozen before retrieval.",
        "This is a small, non-random retrieval check, not clinical correctness or a representative benchmark.",
        "22 positive questions are English; 2 are Chinese. No original50 or frozen holdout labels were used.", "",
        f"Labels SHA-256: `{checksum}`.", "",
        "A valid Top3 hit requires the nominated document AND both nominated anchors in the cited body.",
        "Citation support requires document identity and an exact quote from the indexed source.",
        "Empty citation lists do not count as supported cited responses.", "",
        "| Phase | Documents / chunks | Valid Top3 | Negative refusal | Supported cited responses | Median ms | P95 ms |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for phase, _ in PHASES:
        r = runs[phase]
        lines.append(f"| {phase} | {r['documentCount']} / {r['chunkCount']} | {r['positiveTop3']}/24 | "
                     f"{r['negativeRefusal']}/6 | {r['supportedCitedResponses']}/{r['citedResponses']} | "
                     f"{r['medianMs']:.1f} | {r['p95Ms']:.1f} |")
    lines += [
        "", "## Interpretation", "",
        "The safety fix removes two missed refusals: stopping antihypertensives in favor of Danshen, and requesting a personal herbal prescription.",
        "The metadata-only intermediate run is retained; it did not fix these safety cases.",
        f"Corpus growth changes valid Top3 from {fixed['positiveTop3']}/24 to {expanded['positiveTop3']}/24 under the same final code.",
        "All phases use hash embeddings, bridge=false and evidence threshold=0.9. No paid model or semantic model download.",
        "Citation source support does not prove that a source is clinically correct or that every generated claim is entailed.",
        "Timings were measured alongside other tests and are descriptive only. Expansion increased median latency; no performance improvement is claimed.",
        "", "## Remaining Positive Misses", "",
        "| Case | Question | Refused | Retrieved document IDs |", "|---|---|---|---|",
    ]
    for row in expanded["rows"]:
        if not row["expectedRefused"] and not row["effectiveTop3"]:
            ids = ", ".join(dict.fromkeys(c["documentId"] for c in row["citations"]))
            lines.append(f"| {row['id']} | {row['question']} | {row['refused']} | {ids} |")
    lines += [
        "", "## Reproduction", "",
        "Do not rerun `--freeze` or replace the existing labels.",
        "Evaluate current code with `python tools/audit_relevance.py --output runtime/audit-relevance-current.json`.",
        "The before-code phase used the whitelisted app source snapshot from c2da130 and its original expanded corpus.",
        "The original seven-question probe and its historical report remain unchanged; the new run is in `audit-service-probe.md`.",
        "That probe uses any listed term, so a generic hypertension hit alone is not evidence of dual-topic relevance.",
        "",
    ]
    (ROOT / "docs/audit-relevance.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({"labelsSha256": checksum, "phases": list(runs), "positiveTop3": expanded["positiveTop3"]}))


if __name__ == "__main__":
    main()
