"""Offline format, duplicate and bidirectional alias-index checks (names only)."""
import argparse
import json
from pathlib import Path
from collections import defaultdict, Counter
ROOT = Path(__file__).resolve().parents[1]


def validate(payload):
    entries = payload["entries"]
    assert payload["schemaVersion"] == 1 and len(entries) >= 300
    ids, names, reverse = set(), set(), defaultdict(set)
    for entry in entries:
        assert entry["kind"] in {"herb", "formula", "syndrome", "disease", "examination", "drug_class"}
        assert set(entry) <= {"id", "kind", "zh", "zhAliases", "en", "sourceType", "sourceUrl",
                             "additionalSourceType", "additionalSourceUrl"}
        assert entry["id"] not in ids, "duplicate id"
        assert (entry["kind"], entry["zh"]) not in names, "duplicate canonical name"
        ids.add(entry["id"]); names.add((entry["kind"], entry["zh"]))
        assert entry["zh"] and any("\u3400" <= char <= "\u9fff" for char in entry["zh"])
        assert isinstance(entry["en"], list) and entry["en"]
        assert entry["sourceType"] and entry["sourceUrl"].startswith("https://")
        aliases = [entry["zh"], *entry["zhAliases"], *entry["en"]]
        assert all(isinstance(alias, str) and alias.strip() == alias and alias for alias in aliases)
        assert len({alias.lower() for alias in aliases}) == len(aliases), entry["id"] + " duplicate alias"
        for alias in aliases:
            reverse[alias.lower()].add(entry["id"])
    for entry in entries:
        for alias in [entry["zh"], *entry["zhAliases"], *entry["en"]]:
            assert entry["id"] in reverse[alias.lower()]
        for other in entries:
            if other["id"] in reverse[entry["zh"].lower()]:
                assert entry["zh"].lower() in {s.lower() for s in [other["zh"], *other["zhAliases"], *other["en"]]}
    return {"entries": len(entries), "categories": dict(Counter(e["kind"] for e in entries)),
            "ambiguousAliases": sum(len(ids) > 1 for ids in reverse.values())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(json.dumps(validate(json.loads((ROOT / "data/tcm-terminology.json").read_text("utf-8")))))
