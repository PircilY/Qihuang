"""Verify manifest hashes, paths and licensed corpus membership in a delivery ZIP."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

from package_delivery import SECRET, allowed


def verify(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate archive entries")
        manifest = json.loads(archive.read("delivery-manifest.json"))
        expected = {row["path"] for row in manifest["files"]}
        if set(names) != expected | {"delivery-manifest.json", "delivery-corpus-isolation.json"}:
            raise ValueError("archive/manifest membership mismatch")
        for row in manifest["files"]:
            name = row["path"]
            parts = PurePosixPath(name)
            if parts.is_absolute() or ".." in parts.parts or "\\" in name or not allowed(name):
                raise ValueError(f"unsafe delivery path: {name}")
            data = archive.read(name)
            if len(data) != row["bytes"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise ValueError(f"manifest hash mismatch: {name}")
            if SECRET.search(data):
                raise ValueError(f"credential pattern: {name}")
        ledger = json.loads(archive.read("data/expanded-sources.json"))["records"]
        if any(row.get("redistributionStatus") != "permissive-verified"
               or row.get("corpusRole") == "excluded" for row in ledger):
            raise ValueError("non-permissive or excluded record in delivery")
        ids = {row["documentId"] for row in ledger}
        raw = {row[key] for row in ledger for key in ("fullTextPath", "rawSourcePath")}
        if {name for name in names if name.startswith("data/expanded-raw/")} != raw:
            raise ValueError("full text membership mismatch")
        chunks = json.loads(archive.read("backend/src/main/resources/data/expanded/knowledge-base.json"))
        documents = json.loads(archive.read("backend/src/main/resources/data/expanded/knowledge-documents.json"))
        if {chunk["documentId"] for chunk in chunks} != ids or {doc["id"] for doc in documents} != ids:
            raise ValueError("indexed document membership mismatch")
        isolation = json.loads(archive.read("delivery-corpus-isolation.json"))
        if isolation["indexedDocumentCount"] != len(ids):
            raise ValueError("isolation count mismatch")
    return {"passed": True, "files": len(expected), "indexedDocuments": len(ids),
            "chunks": len(chunks), "isolatedDocuments": len(isolation["excluded"]),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify(args.archive)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
