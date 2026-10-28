#!/usr/bin/env python3
"""Deterministic archive of tracked source files, never reads excluded files."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

BLOCKED = {".git", ".venv", "venv", ".rag-deps", "target", "runtime", ".tmp", "__pycache__", ".pytest_cache", "node_modules",
           ".cache", "models", "model-cache", "embedding-cache", "checkpoints"}
BLOCKED.update({".aws", ".ssh", ".codex", ".agents", "frozen", "holdout"})
SECRET = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                    rb"\bsk-[A-Za-z0-9_-]{24,}\b|\bAKIA[0-9A-Z]{16}\b")
UI_VIEWS = r"(overview|knowledge|workbench|evaluation|pipeline|comparison|model)"
UI_WIDTHS = r"(1440|1280|390|320)"


def ui_evidence_allowed(path):
    if path.parts[:2] != ("docs", "ui-redesign"):
        return True
    if len(path.parts) == 3:
        return path.name == "test-results.json"
    if len(path.parts) != 4 or path.parts[2] not in {"before", "after"}:
        return False
    stage = path.parts[2]
    if path.name == "manifest.json" or (stage == "after" and path.name == "audit-report.json"):
        return True
    theme = r"-(light|dark)" if stage == "after" else ""
    return bool(re.fullmatch(rf"{UI_VIEWS}-{UI_WIDTHS}{theme}\.jpg", path.name))

def allowed(name):
    path = PurePosixPath(name)
    return not any(part.casefold() in BLOCKED or "holdout" in part.casefold()
                   or part.casefold().startswith(".env") for part in path.parts) and path.suffix.lower() not in {".log", ".pyc", ".jar", ".docx", ".bin", ".safetensors", ".pt"} and ui_evidence_allowed(path)


def corpus_delivery(root):
    ledger_path = root / "data/expanded-sources.json"
    if not ledger_path.exists():
        return None
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    permitted = [r for r in ledger["records"] if r.get("redistributionStatus") == "permissive-verified"
                 and r.get("corpusRole", "background") != "excluded"
                 and r.get("inclusionDecision") == "include"]
    identifiers = {r["documentId"] for r in permitted}
    raw_paths = {r[key] for r in permitted for key in ("fullTextPath", "rawSourcePath")}
    excluded = [{"documentId": r["documentId"], "corpusRole": r.get("corpusRole"),
                 "redistributionStatus": r.get("redistributionStatus", "not-verified"),
                 "reason": "not in permissive, indexed delivery subset"}
                for r in ledger["records"] if r["documentId"] not in identifiers]
    return ledger, permitted, identifiers, raw_paths, excluded


def redact_restricted_quotes(value, restricted, inherited=None):
    if isinstance(value, list):
        return [redact_restricted_quotes(item, restricted, inherited) for item in value]
    if not isinstance(value, dict):
        return value
    document = value.get("documentId", inherited)
    return {key: ("[restricted source excerpt omitted from delivery]" if document in restricted
                   and key in {"quote", "content", "bodyEvidence", "supportingEvidence"}
                   else redact_restricted_quotes(item, restricted, document))
            for key, item in value.items()}


def build(root, output):
    listed = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
    names = sorted(name for name in listed if name and allowed(name))
    policy = corpus_delivery(root)
    if policy:
        ledger, permitted, identifiers, raw_paths, excluded = policy
        restricted = {r["documentId"] for r in ledger["records"]
                      if r.get("redistributionStatus") != "permissive-verified"}
        names = [name for name in names if not name.startswith("data/expanded-raw/") or name in raw_paths]
    records = []
    screenshot_bytes = {"before": 0, "after": 0}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in names:
            source = root / name
            if not source.resolve().is_relative_to(root.resolve()):
                raise ValueError(f"Tracked file escapes delivery root: {name}")
            if not source.is_file():
                raise ValueError(f"Tracked file missing: {name}")
            if source.stat().st_size > 20_000_000:
                raise ValueError(f"Unexpected large file: {name}")
            evidence = PurePosixPath(name)
            if evidence.parts[:2] == ("docs", "ui-redesign") and evidence.suffix == ".jpg":
                if source.stat().st_size > 300_000:
                    raise ValueError(f"UI screenshot exceeds 300 KB: {name}")
                screenshot_bytes[evidence.parts[2]] += source.stat().st_size
                limit = 3_000_000 if evidence.parts[2] == "before" else 6_000_000
                if screenshot_bytes[evidence.parts[2]] > limit:
                    raise ValueError(f"UI screenshots exceed stage budget: {evidence.parts[2]}")
            data = source.read_bytes()
            if policy:
                if name == "data/expanded-sources.json":
                    data = (json.dumps({**ledger, "records": permitted,
                            "deliveryPolicy": "permissive indexed subset; see delivery-corpus-isolation.json"},
                            ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                elif name in {"backend/src/main/resources/data/expanded/knowledge-base.json",
                              "backend/src/main/resources/data/expanded/knowledge-documents.json"}:
                    items = json.loads(data)
                    items = [item for item in items if item.get("documentId", item.get("id")) in identifiers]
                    data = (json.dumps(items, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                elif name == "docs/corpus-topic-review.json":
                    payload = json.loads(data)
                    payload["records"] = [row for row in payload["records"] if row["documentId"] in identifiers]
                    payload["roles"] = dict(Counter(row["corpusRole"] for row in payload["records"]))
                    payload["deliverySubset"] = True
                    data = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                if name.endswith(".json"):
                    payload = json.loads(data)
                    redacted = redact_restricted_quotes(payload, restricted)
                    if redacted != payload:
                        data = (json.dumps(redacted, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            if SECRET.search(data):
                raise ValueError(f"Potential credential in delivery source: {name}")
            records.append({"path":name, "bytes":len(data), "sha256":hashlib.sha256(data).hexdigest(),
                            "transformed": data != source.read_bytes()})
            info = zipfile.ZipInfo(name, date_time=(2026,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
        info = zipfile.ZipInfo("delivery-manifest.json", date_time=(2026,1,1,0,0,0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info,json.dumps({"files":records,"excluded":"runtime/dependencies/build output/frozen holdout/untracked user files"},ensure_ascii=False,indent=2))
        if policy:
            info = zipfile.ZipInfo("delivery-corpus-isolation.json", date_time=(2026,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, json.dumps({
                "indexedDocumentCount": len(permitted), "excluded": excluded,
                "policy": "Original workspace unchanged; only explicitly permissive indexed full texts redistributed.",
            }, ensure_ascii=False, indent=2))
    return {"archive":str(output.resolve()),"files":len(records),"bytes":output.stat().st_size,"sha256":hashlib.sha256(output.read_bytes()).hexdigest(),"ui_screenshot_bytes":screenshot_bytes}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,default=Path("runtime/delivery/qihuang-rag.zip"))
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    print(json.dumps(build(root,args.output.resolve()),ensure_ascii=False,indent=2))

if __name__ == "__main__":
    main()
