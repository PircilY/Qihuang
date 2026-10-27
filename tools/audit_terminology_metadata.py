"""Deterministic low-confidence audit for dictionary-derived expanded tags."""
from __future__ import annotations

import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHUNKS = ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json"
DOCS = ROOT / "backend/src/main/resources/data/expanded/knowledge-documents.json"
REPORT = ROOT / "docs/knowledge-base.md"
FIELDS = ("herbs", "formulas", "syndromes", "disease")


def audit(chunks: list[dict], documents: list[dict], seed: int = 42, sample_size: int = 200) -> dict:
    by_id = {str(document["id"]): document for document in documents}
    candidates = [chunk for chunk in chunks if chunk.get("metadataSource") == "词典匹配"
                  and any(chunk.get(field) for field in FIELDS)]
    rng = random.Random(seed)
    sample = rng.sample(candidates, min(sample_size, len(candidates)))
    totals = {field: {"observed": 0, "low": 0, "sampledChunks": 0} for field in FIELDS}
    updates = []
    for chunk in sample:
        doc = by_id.get(str(chunk.get("documentId", "")), {})
        early = " ".join([str(doc.get("title", "")), str(doc.get("description", "")), str(chunk.get("title", "")), str(chunk.get("content", "")[:500])]).casefold()
        full = " ".join([str(chunk.get("title", "")), str(chunk.get("content", ""))]).casefold()
        confidence = {}
        for field in FIELDS:
            values = chunk.get(field, []) if isinstance(chunk.get(field, []), list) else str(chunk.get(field, "")).split("、")
            for value in values:
                if not value: continue
                totals[field]["observed"] += 1
                totals[field]["sampledChunks"] += 1
                early_match = str(value).casefold() in early
                occurrences = full.count(str(value).casefold())
                low = not early_match and occurrences == 1
                totals[field]["low"] += int(low)
                confidence[str(value)] = "low" if low else "normal"
        if confidence:
            updates.append({"id": chunk["id"], "confidence": confidence})
    for field, data in totals.items():
        data["lowRate"] = round(data["low"] / data["observed"], 4) if data["observed"] else None
    return {"seed": seed, "requestedSampleSize": sample_size, "sampledChunks": len(sample),
            "candidateChunks": len(candidates), "fields": totals, "sampleMetadata": updates}


def main() -> None:
    chunks = json.loads(CHUNKS.read_text("utf-8")); docs = json.loads(DOCS.read_text("utf-8"))
    result = audit(chunks, docs)
    lines = ["", "## 词典自动元数据低置信度抽样（固定种子42）", "",
             f"按固定种子从词典自动标记片段中抽取 {result['sampledChunks']} 条（请求200条；候选{result['candidateChunks']}条）。低置信规则：术语不在所属文档标题、摘要/描述或正文前500字符出现，且在所属片段标题+正文中恰好出现一次。此为自动规则代理，不等于人工误标率。",
             "", "|字段|标记数|低置信数|低置信比例|", "|---|---:|---:|---:|"]
    for field, data in result["fields"].items():
        rate = "无样本" if data["lowRate"] is None else f"{data['lowRate']:.2%}"
        lines.append(f"|{field}|{data['observed']}|{data['low']}|{rate}|")
    lines += ["", "重建器把符合上述规则的名称列入各片段 `metadataConfidence` 对象；Java/Python guarded 元数据加分默认忽略这些 low 名称。过滤阶段也不以这些字段拒绝候选。非低置信自动匹配仍不是人工核对。", ""]
    old = REPORT.read_text("utf-8") if REPORT.exists() else ""
    marker = "## 词典自动元数据低置信度抽样（固定种子42）"
    old = old.split(marker)[0].rstrip()
    REPORT.write_text(old + "\n" + "\n".join(lines), encoding="utf-8")
    print(json.dumps({"sampledChunks": result["sampledChunks"], "candidateChunks": result["candidateChunks"], "fields": result["fields"]}, ensure_ascii=False))


if __name__ == "__main__": main()
