#!/usr/bin/env python3
"""Deterministic archive of tracked source files, never reads excluded files."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

BLOCKED = {".git", ".venv", "venv", ".rag-deps", "target", "runtime", ".tmp", "__pycache__", ".pytest_cache", "node_modules"}
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
    return not any(part in BLOCKED for part in path.parts) and "holdout" not in path.name.lower() and not path.name.startswith(".env") and path.suffix.lower() not in {".log", ".pyc", ".jar", ".docx", ".bin", ".safetensors", ".pt"} and ui_evidence_allowed(path)


def build(root, output):
    listed = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
    names = sorted(name for name in listed if name and allowed(name))
    records = []
    screenshot_bytes = {"before": 0, "after": 0}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in names:
            source = root / name
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
            records.append({"path":name, "bytes":len(data), "sha256":hashlib.sha256(data).hexdigest()})
            info = zipfile.ZipInfo(name, date_time=(2026,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
        info = zipfile.ZipInfo("delivery-manifest.json", date_time=(2026,1,1,0,0,0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info,json.dumps({"files":records,"excluded":"runtime/dependencies/build output/frozen holdout/untracked user files"},ensure_ascii=False,indent=2))
    return {"archive":str(output.resolve()),"files":len(records),"bytes":output.stat().st_size,"sha256":hashlib.sha256(output.read_bytes()).hexdigest(),"ui_screenshot_bytes":screenshot_bytes}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,default=Path("runtime/delivery/qihuang-rag.zip"))
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    print(json.dumps(build(root,args.output.resolve()),ensure_ascii=False,indent=2))

if __name__ == "__main__":
    main()
