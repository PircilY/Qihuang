"""Check names-only dictionary entries against bilingual corpus titles."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICTIONARY = ROOT / "data/tcm-terminology.json"
CORPUS = ROOT / "backend/src/main/resources/data/expanded/knowledge-documents.json"
REPORT = ROOT / "docs/terminology-check.md"
CONFLICTS = ROOT / "data/terminology-conflicts.json"
LEDGER = ROOT / "data/expanded-sources.json"


def bilingual_titles(documents: list[dict]) -> list[str]:
    bilingual = []
    for document in documents:
        title = str(document.get("title", ""))
        translated = [t.get("text", "") for t in document.get("sourceTranslatedTitles", [])
                      if t.get("language") == "en"]
        if translated and re.search(r"[\u3400-\u9fff]", title):
            bilingual.append(title + " " + " ".join(translated))
            continue
        # With retained title-group metadata, abbreviations are not an English title.
        if "sourceTranslatedTitles" in document:
            continue
        if re.search(r"[\u3400-\u9fff]", title) and re.search(r"[A-Za-z]", title):
            bilingual.append(title)
    return bilingual


def check(entries: list[dict], documents: list[dict]) -> list[dict]:
    bilingual = bilingual_titles(documents)
    results = []
    for entry in entries:
        zh_names = [entry["zh"], *entry.get("zhAliases", [])]
        en_names = entry.get("en", [])
        evidence = [title for title in bilingual if any(name and name in title for name in zh_names)]
        verified = [title for title in evidence if any(
            re.search(r"(?<![A-Za-z0-9])" + re.escape(name) + r"(?![A-Za-z0-9])", title, re.I)
            for name in en_names if name)]
        results.append({"id": entry["id"], "zh": entry["zh"], "en": en_names,
                        "status": "无证据" if not evidence else "已验证" if verified else "冲突",
                        "evidenceCount": len(evidence), "verifiedCount": len(verified),
                        "conflictTitles": evidence if evidence and not verified else []})
    return results


def render(results: list[dict]) -> str:
    lines = ["# 术语词典自动一致性检查", "",
             "仅使用来源自带的中英文标题对（保留 JATS trans-title）或旧格式双语标题，不把中文标题里的 CMR、MAPK 等缩写当成英文标题。只有中文标题出现词典中文名、且同一文档英文标题出现英文/拉丁名才计为已验证；出现中文名但没有对应英文名计为冲突。无证据表示没有可用的双语标题。此规则是自动字符串检查，不等于人工核对；不自动改写词典或生成译文。",
             "", "|状态|条目数|", "|---|---:|"]
    for state in ("已验证", "无证据", "冲突"):
        lines.append(f"|{state}|{sum(item['status'] == state for item in results)}|")
    lines += ["", "|ID|中文名|英文/拉丁名|状态|双语标题中文命中数|同标题英文命中数|", "|---|---|---|---|---:|---:|"]
    for item in results:
        lines.append(f"|{item['id']}|{item['zh']}|{', '.join(item['en'])}|{item['status']}|{item['evidenceCount']}|{item['verifiedCount']}|")
    conflicts = [item for item in results if item["status"] == "冲突"]
    lines += ["", "## 冲突标题", ""]
    for item in conflicts:
        for title in item["conflictTitles"]:
            lines.append(f"- {item['id']} {item['zh']}: {title}")
    return "\n".join(lines) + "\n"


def main() -> None:
    entries = json.loads(DICTIONARY.read_text(encoding="utf-8"))["entries"]
    documents = json.loads(CORPUS.read_text(encoding="utf-8"))
    sources = {r["documentId"]: r for r in json.loads(LEDGER.read_text(encoding="utf-8"))["records"]}
    for document in documents:
        source = sources.get(document["id"], {})
        if "sourceTranslatedTitles" in source:
            document["sourceTranslatedTitles"] = source["sourceTranslatedTitles"]
    results = check(entries, documents)
    REPORT.write_text(render(results), encoding="utf-8")
    CONFLICTS.write_text(json.dumps({"schemaVersion": 1, "conflictingEntryIds": [
        item["id"] for item in results if item["status"] == "冲突"]}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    bilingual_count = len(bilingual_titles(documents))
    print(json.dumps({"entries": len(results), "bilingualTitles": bilingual_count,
        "statuses": {state: sum(item["status"] == state for item in results) for state in ("已验证", "无证据", "冲突")},
        "report": str(REPORT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
