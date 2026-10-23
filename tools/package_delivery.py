#!/usr/bin/env python3
"""Deterministic archive of tracked source files, never reads excluded files."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile

BLOCKED = {".git", ".venv", "venv", ".rag-deps", "target", "runtime", ".tmp", "__pycache__", ".pytest_cache", "node_modules"}

def allowed(name):
    path = PurePosixPath(name)
    return not any(part in BLOCKED for part in path.parts) and "holdout" not in path.name.lower() and not path.name.startswith(".env") and path.suffix.lower() not in {".log", ".pyc", ".jar", ".docx", ".bin", ".safetensors", ".pt"}


def build(root, output):
    listed = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
    names = sorted(name for name in listed if name and allowed(name))
    records = []
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in names:
            source = root / name
            if not source.is_file():
                raise ValueError(f"Tracked file missing: {name}")
            if source.stat().st_size > 20_000_000:
                raise ValueError(f"Unexpected large file: {name}")
            data = source.read_bytes()
            records.append({"path":name, "bytes":len(data), "sha256":hashlib.sha256(data).hexdigest()})
            info = zipfile.ZipInfo(name, date_time=(2026,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
        info = zipfile.ZipInfo("delivery-manifest.json", date_time=(2026,1,1,0,0,0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info,json.dumps({"files":records,"excluded":"runtime/dependencies/build output/frozen holdout/untracked user files"},ensure_ascii=False,indent=2))
    return {"archive":str(output.resolve()),"files":len(records),"bytes":output.stat().st_size,"sha256":hashlib.sha256(output.read_bytes()).hexdigest()}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,default=Path("runtime/delivery/qihuang-rag.zip"))
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    print(json.dumps(build(root,args.output.resolve()),ensure_ascii=False,indent=2))

if __name__ == "__main__":
    main()
