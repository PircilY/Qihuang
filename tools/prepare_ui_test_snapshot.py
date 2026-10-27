"""Copy permitted working sources for UI regression without locking a live JAR."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from package_delivery import allowed


ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / ".tmp" / "ui-test-snapshot"
EXCLUDED_TEST = "rag-service/tests/test_safety_rules_shared.py"


def main():
    if DESTINATION.exists():
        raise SystemExit("Snapshot already exists; use the existing isolated copy.")
    listed = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    ).decode("utf-8").split("\0")
    records = []
    excluded = 0
    for name in sorted(set(listed)):
        if not name:
            continue
        # Filter names before stat/copy/read: frozen inputs never enter the snapshot.
        if not allowed(name) or "holdout" in name.lower() or name == EXCLUDED_TEST:
            excluded += 1
            continue
        source = ROOT / name
        if not source.is_file():
            continue
        target = DESTINATION / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        records.append({"path": name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    (DESTINATION / ".tmp").mkdir(exist_ok=True)
    report = {"source": str(ROOT), "destination": str(DESTINATION),
              "files": records, "excludedBeforeRead": excluded,
              "excludedTest": EXCLUDED_TEST, "backendAssertionsModified": False}
    (DESTINATION / "snapshot-manifest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({"destination": str(DESTINATION), "copied": len(records),
                      "excludedBeforeRead": excluded}, ensure_ascii=False))


if __name__ == "__main__":
    main()
