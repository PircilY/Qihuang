"""Review every expanded record against retained source evidence."""
import argparse
from collections import Counter
import json
from collect_expanded_sources import plain
from corpus_governance import ROOT, parse_article, redistribution_license, save_json, source_path, topic_review


def review():
    path = ROOT / "data/expanded-sources.json"
    ledger = json.loads(path.read_text(encoding="utf-8"))
    rows = []
    decisions = json.loads((ROOT / "docs/corpus-role-decisions.json").read_text(encoding="utf-8"))
    for record in ledger["records"]:
        record["title"] = plain(record["title"])
        text = source_path(record["fullTextPath"]).read_text(encoding="utf-8")
        result = topic_review(record, text)
        decision = decisions.get(record["documentId"])
        if decision:
            result["corpusRole"] = decision["corpusRole"]
            result["topicReview"]["sourceReviewDecision"] = decision
        article = parse_article(source_path(record["rawSourcePath"]).read_text(encoding="utf-8"))
        record.update(result)
        record["licenseEvidence"] = article["licenses"]
        record["licenseEvidenceSource"] = record["sourceUrl"] + " (retained fullTextXML)"
        record["licenseEvidenceAccessedAt"] = record.get("accessedAt", "")
        record["redistributionLicenseUrl"] = redistribution_license(article["licenses"])
        record["redistributionStatus"] = "permissive-verified" if record["redistributionLicenseUrl"] else "restricted-review-required"
        rows.append({"documentId": record["documentId"], "title": record["title"],
                     **result, "redistributionStatus": record["redistributionStatus"]})
    return ledger, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    ledger, rows = review()
    if args.check:
        actual = json.loads((ROOT / "data/expanded-sources.json").read_text(encoding="utf-8"))
        if actual != ledger:
            raise SystemExit("source review is stale")
    else:
        save_json(ROOT / "data/expanded-sources.json", ledger)
        save_json(ROOT / "docs/corpus-topic-review.json", {
            "method": "automatic-source-screen; not expert medical or legal certification",
            "roles": dict(Counter(row["corpusRole"] for row in rows)),
            "records": rows,
        })
    print(json.dumps({"records": len(rows), "roles": dict(Counter(row["corpusRole"] for row in rows)),
                      "redistribution": dict(Counter(row["redistributionStatus"] for row in rows))}))


if __name__ == "__main__":
    main()
