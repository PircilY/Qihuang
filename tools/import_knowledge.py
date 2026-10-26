"""Deterministic offline CSV/JSONL/TXT import and corpus audit. No external data is invented."""
from __future__ import annotations
import argparse
from collections import Counter
import csv
from datetime import date
from difflib import SequenceMatcher
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlparse
from terminology_utils import extract_metadata

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ("title", "sourceType", "publisher", "publicationYear", "licenseNote", "sourceUrl", "content")
STRUCTURED = ("chiefComplaint", "syndrome", "composition", "efficacy", "history", "treatment", "主诉", "证候", "方药组成", "疗效")
LABELS = {"chiefComplaint":"主诉", "syndrome":"证候", "composition":"方药组成", "efficacy":"疗效", "history":"病史", "treatment":"处置"}
ALIASES = {"标题":"title", "来源类型":"sourceType", "来源":"publisher", "出版方":"publisher", "年份":"publicationYear",
           "授权说明":"licenseNote", "来源链接":"sourceUrl", "正文":"content", "病证":"disease", "证据等级":"evidenceLevel",
           "章节":"section", "页码":"pageStart", "方剂":"formulas", "药物":"herbs", "证候标签":"syndromes"}


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def read_records(path):
    if path.suffix.lower() == ".csv":
        with path.open(encoding="utf-8-sig", newline="") as stream:
            return list(csv.DictReader(stream))
    if path.suffix.lower() in {".jsonl", ".ndjson"}:
        return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    if path.suffix.lower() == ".txt":
        header, separator, content = path.read_text(encoding="utf-8-sig").partition("\n---\n")
        if not separator:
            raise ValueError("TXT needs key: value metadata, a --- line, then body")
        record = {}
        for line in header.splitlines():
            key, colon, value = line.partition(":")
            if not colon:
                raise ValueError("TXT metadata needs key: value")
            record[key.strip()] = value.strip()
        record["content"] = content
        return [record]
    raise ValueError("Supported input formats: CSV / JSONL / TXT")


def tags(value):
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return [item.strip() for item in re.split(r"[|;；]", str(value)) if item.strip()]


def redact(text):
    text = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "[手机号已脱敏]", text)
    text = re.sub(r"(?<!\d)\d{17}[\dXx](?!\d)", "[证件号已脱敏]", text)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[邮箱已脱敏]", text)
    text = re.sub(r"((?:姓名|患者姓名|住址|联系电话)\s*[:：])[^\n，。;；]+", r"\1[已脱敏]", text)
    return text


def split_chunks(content):
    """Same 620-character paragraph packing and punctuation break as Java splitLongParagraph."""
    paragraphs = [re.sub(r"[ \t]+", " ", part).strip() for part in re.split(r"\n\s*\n", content.replace("\r\n", "\n").strip())]
    result, current = [], ""
    for paragraph in filter(None, paragraphs):
        if len(paragraph) > 620:
            if current:
                result.append(current); current = ""
            start = 0
            while start < len(paragraph):
                end = min(len(paragraph), start + 620)
                if end < len(paragraph):
                    punctuation = next((index + 1 for index in range(end - 1, start, -1) if paragraph[index] in "。！？；"), end)
                    if punctuation > start + 120:
                        end = punctuation
                result.append(paragraph[start:end].strip()); start = end
        else:
            if current and len(current) + len(paragraph) + 2 > 620:
                result.append(current); current = ""
            current = current + "\n\n" + paragraph if current else paragraph
    if current:
        result.append(current)
    return result


def build(records, privacy=False, minimum=30, maximum=1_000_000):
    documents, chunks, structured_records, filtered, duplicates = [], [], [], [], []
    seen_ids, prior = set(), []
    for number, original in enumerate(records, 1):
        record = {ALIASES.get(key, key): value for key, value in original.items()}
        reasons = ["missing:" + key for key in FIELDS if key not in record or not str(record[key]).strip()]
        content = str(record.get("content", "")).replace("\r\n", "\n").strip()
        if len(content) < minimum: reasons.append("too-short")
        if len(content) > maximum: reasons.append("too-long")
        if "\ufffd" in content or any(ord(char) < 32 and char not in "\n\r\t" for char in content): reasons.append("garbled-or-control-character")
        url = urlparse(str(record.get("sourceUrl", "")))
        if url.scheme not in {"http", "https"} or not url.hostname: reasons.append("invalid-source-url")
        try:
            year = int(record.get("publicationYear", 0))
            if not 1 <= year <= date.today().year: reasons.append("invalid-publication-year")
        except (ValueError, TypeError):
            year = 0; reasons.append("invalid-publication-year")
        level = record.get("evidenceLevel") or "未评级"
        if level not in {"未评级", "A", "B", "C", "古籍", "规则"}: reasons.append("invalid-evidence-level")
        for field in ("pageStart", "pageEnd"):
            try:
                if int(record.get(field) or 0) < 0: reasons.append("invalid-page")
            except (TypeError, ValueError): reasons.append("invalid-page")
        if not any(reason == "invalid-page" for reason in reasons):
            if int(record.get("pageEnd") or record.get("pageStart") or 0) < int(record.get("pageStart") or 0): reasons.append("invalid-page-range")
        if reasons:
            filtered.append({"row":number, "title":record.get("title", ""), "reasons":reasons}); continue
        if privacy:
            record = {key:redact(value) if isinstance(value, str) else value for key,value in record.items()}
        doc_terms = extract_metadata(str(record["title"]) + "\n" + content)
        supplied_terms = {field: tags(record.get(field)) for field in ("syndromes", "formulas", "herbs")}
        merged_terms = {field: supplied_terms[field] or doc_terms[field]
                        for field in ("syndromes", "formulas", "herbs")}
        metadata_source = "用户导入" if any(supplied_terms.values()) else (
            "词典匹配" if any(doc_terms.values()) else "")
        structured = {key:str(record[key]) for key in STRUCTURED if record.get(key) is not None and str(record[key]).strip()}
        if privacy:
            content = redact(content)
            structured = {key:redact(value) for key,value in structured.items()}
        if structured:
            content = "\n\n".join([f"{LABELS.get(key,key)}：{value}" for key,value in structured.items()] + ["原文：" + content])
        doc_id = str(record.get("id") or "IMPORT-" + digest(str(record["sourceUrl"]) + str(record["title"]) + str(number))[:16]).strip()
        if doc_id in seen_ids: raise ValueError("duplicate explicit document id: " + doc_id)
        seen_ids.add(doc_id)
        canonical = re.sub(r"\s+", "", content).lower()
        for previous_id, previous in prior:
            similarity = SequenceMatcher(None, canonical[:20000], previous[:20000], autojunk=False).ratio() if abs(len(canonical)-len(previous)) <= .1*max(len(canonical),len(previous)) else 0
            if canonical == previous or similarity >= .9:
                duplicates.append({"documentId":doc_id, "otherDocumentId":previous_id, "kind":"exact" if canonical==previous else "near", "similarity":round(similarity,4)})
        prior.append((doc_id,canonical))
        shared = {"title":str(record["title"]).strip(), "sourceType":str(record["sourceType"]).strip(),
                  "publisher":str(record["publisher"]).strip(), "publicationYear":year, "evidenceLevel":level,
                  "version":str(record.get("version") or "import-v1"), "status":"ACTIVE"}
        timestamp = str(record.get("accessedAt") or "1970-01-01T00:00:00Z")
        documents.append({**shared, "id":doc_id, "authority":str(record.get("authority") or "待人工核验"),
            "diseaseScope":tags(record.get("disease")) or doc_terms["disease"],
            "herbs":merged_terms["herbs"], "formulas":merged_terms["formulas"],
            "syndromes":merged_terms["syndromes"], "metadataSource":metadata_source,
            "fileName":"offline-import", "checksum":digest(content),
            "registeredAt":timestamp, "accessedAt":timestamp, "licenseNote":str(record["licenseNote"]).strip(),
            "description":"离线导入，来源与等级按输入登记，未经独立事实核查", "sourceUrl":str(record["sourceUrl"]).strip()})
        for index, text in enumerate(split_chunks(content), 1):
            chunk_terms = extract_metadata(str(record["title"]) + "\n" + text)
            chunks.append({**shared, "id":f"{doc_id}-C{index:04}", "documentId":doc_id,
                "disease":str(record.get("disease") or "、".join(chunk_terms["disease"])),
                "syndromes":supplied_terms["syndromes"] or chunk_terms["syndromes"],
                "formulas":supplied_terms["formulas"] or chunk_terms["formulas"],
                "herbs":supplied_terms["herbs"] or chunk_terms["herbs"],
                "pageStart":int(record.get("pageStart") or 0), "pageEnd":int(record.get("pageEnd") or record.get("pageStart") or 0),
                "section":str(record.get("section") or "正文"), "keywords":tags(record.get("keywords")),
                "content":text, "checksum":digest(text), "ingestedAt":timestamp,
                "metadataSource": "用户导入" if any(supplied_terms.values()) else
                    ("词典匹配" if any(chunk_terms.values()) else "")})
        if structured: structured_records.append({"documentId":doc_id, "fields":structured})
    return {"knowledge-documents.json":documents, "knowledge-base.json":chunks, "structured-records.json":structured_records,
            "import-report.json":{"inputCount":len(records), "acceptedCount":len(documents), "filtered":filtered,
                                  "duplicates":duplicates, "privacyRedacted":privacy}}


def statistics(documents, chunks):
    return {"documents":len(documents), "chunks":len(chunks), "averageLength":round(sum(len(chunk["content"]) for chunk in chunks)/max(1,len(chunks)),2),
        "sourceTypes":dict(sorted(Counter(str(doc.get("sourceType") or "未知") for doc in documents).items())),
        "years":dict(sorted(Counter(str(doc.get("publicationYear") or "未标注") for doc in documents).items())),
        "evidenceLevels":dict(sorted(Counter(str(doc.get("evidenceLevel") or "未评级") for doc in documents).items()))}


def audit_markdown(import_payload=None):
    lines = ["# 知识库统计与导入说明", "", "由离线脚本确定性生成；不读取任何评测集。统计仅描述数据数量和输入标签，不等同于医学事实或临床证据认证。", ""]
    datasets = []
    for corpus, directory in [("BASELINE", ROOT/"backend/src/main/resources/data"), ("EXPANDED", ROOT/"backend/src/main/resources/data/expanded")]:
        datasets.append((corpus, json.loads((directory/"knowledge-documents.json").read_text(encoding="utf-8")),
                         json.loads((directory/"knowledge-base.json").read_text(encoding="utf-8"))))
    if import_payload:
        datasets.append(("离线导入", import_payload["knowledge-documents.json"], import_payload["knowledge-base.json"]))
    for name, documents, chunks in datasets:
        summary = statistics(documents,chunks)
        lines += ["## " + name, "", f"文档数 {summary['documents']}；片段数 {summary['chunks']}；平均正文长度 {summary['averageLength']} 字符。", "",
                  "|维度|分布|", "|---|---|"]
        for key in ("sourceTypes", "years", "evidenceLevels"):
            lines.append("|" + key + "|" + "; ".join(f"{label}: {count}" for label,count in summary[key].items()) + "|")
        lines += ["", "已有构建产物未在此审计中额外过滤：过滤0条；来源清洗与许可状态由原来源台账维护。", ""]
        if name == "EXPANDED":
            lines += ["阶段 H 自动词典名称匹配覆盖："]
            for field in ("herbs", "formulas", "syndromes"):
                document_hits = sum(bool(document.get(field)) for document in documents)
                chunk_hits = sum(bool(chunk.get(field)) for chunk in chunks)
                lines.append(f"- {field}：文档标题 {document_hits}/{len(documents)}（{document_hits/max(1,len(documents)):.2%}）；片段 {chunk_hits}/{len(chunks)}（{chunk_hits/max(1,len(chunks)):.2%}）。")
            lines += ["Disease 使用来源台账 topicTags 并追加标题/片段中词典命中名称。命中结果只表示共享词典名称出现；metadataSource 标示来源台账和/或词典匹配；不是人工标注，可能漏标或误标，不推断疗效、剂量、诊断或医学关系。", ""]
    if import_payload:
        audit = import_payload["import-report.json"]
        counts = Counter(reason for row in audit["filtered"] for reason in row["reasons"])
        lines += [f"导入输入 {audit['inputCount']}，接受 {audit['acceptedCount']}，过滤 {len(audit['filtered'])}；"
                  f"原因分布：{dict(counts)}；重复/近似重复对 {len(audit['duplicates'])}（报告，不自动删除）。", ""]
    lines += ["## 使用与数据格式", "", "CSV首行为字段名；JSONL每行一个对象；TXT以key: value元数据头、独立---行及正文组成。",
        "必填：title/sourceType/publisher/publicationYear/licenseNote/sourceUrl/content。来源类型支持典籍、临床文献、指南、方药、病案，也保留现有资料类型。",
        "可选：disease/syndromes/formulas/herbs/evidenceLevel/section/pageStart/pageEnd。标签CSV用|或;分隔。证据等级默认未评级，只保存来源声明，不自动评级。",
        "病案/方药输入可含chiefComplaint/syndrome/composition/efficacy/history/treatment或对应中文字段；固定标签模板进入片段，原字段保存在structured-records.json侧车，不改变Java/Python既有片段schema。",
        "--redact脱敏手机号、证件号、邮箱和带标签姓名/住址等；启发式脱敏不能保证识别全部隐私。--check只验证已有输出不写入。",
        "未提供采集时间使用1970-01-01哨兵而非编造采集时间。重复报告不删除内容，导入正式运行库仍遵循Java去重规则。", "",
        "```powershell", "python tools/import_knowledge.py tools/fixtures/import.jsonl --output .tmp/import-preview --redact",
        "python tools/import_knowledge.py tools/fixtures/import.jsonl --output .tmp/import-preview --redact --check",
        "python tools/import_knowledge.py --audit --report docs/knowledge-base.md", "python tools/import_knowledge.py --audit --report docs/knowledge-base.md --check", "```", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--audit", action="store_true")
    parser.add_argument("--redact", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--minimum", type=int, default=30)
    parser.add_argument("--maximum", type=int, default=1_000_000)
    args=parser.parse_args()
    payload=None
    if args.input:
        if not args.output: parser.error("--output required for input")
        if args.minimum < 1 or args.maximum < args.minimum: parser.error("invalid length limits")
        records=read_records(args.input)
        if any("[FIXTURE]" in str(record.get("title") or record.get("标题") or "") for record in records):
            formal = (ROOT/"backend/src/main/resources/data").resolve()
            if args.output.resolve().is_relative_to(formal): raise ValueError("Test fixtures cannot enter the formal corpus")
        payload=build(records,args.redact,args.minimum,args.maximum)
        if not args.check: args.output.mkdir(parents=True,exist_ok=True)
        for name, value in payload.items():
            path=args.output/name
            if args.check:
                if not path.exists() or json.loads(path.read_text(encoding="utf-8")) != value: raise ValueError("Generated output differs: " + name)
            else: path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    if args.audit or args.report:
        path=args.report or ROOT/"docs/knowledge-base.md"
        text=audit_markdown(payload)
        if args.check:
            if path.read_text(encoding="utf-8") != text: raise ValueError("Knowledge report differs")
        else: path.write_text(text,encoding="utf-8")
    if not args.input and not args.audit: parser.error("provide input or --audit")
    print(json.dumps(statistics(payload["knowledge-documents.json"],payload["knowledge-base.json"]) if payload else {"audit":"verified"},ensure_ascii=False))

if __name__ == "__main__": main()
