"""Independent source-nominated retrieval checks; no original50 or holdout labels."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / "data/audit-relevance-cases.json"
CORPUS = ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json"
# Questions and document nominations are authored before any retrieval run.
POSITIVE = [
    ("Astragalus membranaceus heart failure network pharmacology molecular docking study", "EN-PMC11114218", ["astragalus", "heart failure"]),
    ("Astragalus injection left ventricular remodeling HFmrEF meta-analysis", "EN-PMC11333324", ["astragalus", "hfmref"]),
    ("Astragalus Salvia stable coronary artery disease frequent hospitalization cohort", "EN-PMC13585588", ["astragalus", "coronary"]),
    ("Danshen compounds coronary heart disease biochemical indices study", "EN-PMC4913020", ["danshen", "coronary"]),
    ("Tanshinone IIA Astragaloside IV myocardial injury STING pathway", "EN-PMC10900662", ["sting", "myocardial"]),
    ("Salvia miltiorrhiza supplementation cardiovascular bioactive constituents review", "EN-PMC13111759", ["salvia", "cardiovascular"]),
    ("Chinese herbal medicine oxidative stress hypertension mechanisms review", "EN-PMC11520704", ["oxidative", "hypertension"]),
    ("中医高血压治疗趋势与病机的文献有哪些？", "EN-PMC3710609", ["hypertension", "chinese"]),
    ("Chinese herbal hypertension outcome measures systematic reviews", "EN-PMC3543808", ["hypertension", "outcome"]),
    ("Acupuncture antihypertensive peripheral central autonomic mechanisms review", "EN-PMC13111444", ["acupuncture", "antihypertensive"]),
    ("TCM syndrome differentiation heart failure evidence-based questionnaire expert consensus", "EN-PMC10251330", ["questionnaire", "heart failure"]),
    ("Ischemic heart failure Qi deficiency blood stasis gene protein metabolite network", "EN-PMC12269143", ["qi deficiency", "heart failure"]),
    ("Chinese medicine syndrome evolution heart failure rats LAD ligation plasma metabonomics", "EN-PMC4227006", ["syndrome", "heart failure"]),
    ("Traditional Chinese medicine heart failure symptom scale questionnaire validation", "EN-PMC5010704", ["scale", "hf"]),
    ("Fuzheng Yangxin Granule preserved ejection fraction Qi Yin deficiency trial protocol", "EN-PMC12069317", ["fuzheng", "heart failure"]),
    ("Shenfu injection exacerbated heart failure coronary disease randomized trial protocol", "EN-PMC4446800", ["shenfu", "heart failure"]),
    ("Qiliqiangxin QUEST mortality reduction randomized trial protocol", "EN-PMC7076750", ["qiliqiangxin", "quest"]),
    ("Qiliqiangxin cardiac glucose metabolism myocardial infarction HIF MIF AMPK", "EN-PMC13383748", ["ql", "ampk"]),
    ("Qiliqiangxin doxorubicin induced heart failure cardiac remodelling rats", "EN-PMC7301709", ["qiliqiangxin", "chf"]),
    ("Zhishi Xiebai Guizhi Decoction hypoxia lipid toxicity pulmonary hypertension rats", "EN-PMC11657759", ["zhishi", "hypertension"]),
    ("Gualou Xiebai Banxia Decoction atherosclerosis microbiota bile acid signatures", "EN-PMC12908498", ["gualou", "coronary"]),
    ("Taohong Siwu Decoction coronary heart disease non-targeted metabolomic profiling", "EN-PMC7227603", ["taohong", "chd"]),
    ("Neutrophil extracellular traps traditional Chinese medicine cardiovascular natural products", "EN-PMC12845194", ["neutrophil", "cardiovascular"]),
    ("参芪复方在糖尿病心血管高危人群中的试验方案是什么？", "EN-PMC10800938", ["shenqi", "cardiovascular"]),
]
NEGATIVE = [
    "如何编译 Linux 内核并安装显卡驱动？",
    "量子纠缠如何实现超光速通信？",
    "我有心衰，黄芪每天吃多少克？",
    "我有高血压，能停掉降压药只喝丹参吗？",
    "现在胸痛伴大汗呼吸困难，可以在家等到明天吗？",
    "我有冠心病，请为我开一个中药处方。",
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def citation_checks(cites, by_id, row):
    support = all(c.get("chunkId") in by_id
                  and c.get("documentId") == by_id[c["chunkId"]]["documentId"]
                  and c.get("quote") == by_id[c["chunkId"]]["content"] for c in cites)
    hit = any(c.get("documentId") in row["targetDocumentIds"]
              and all(anchor in c.get("quote", "").casefold() for anchor in row.get("bodyAnchors", []))
              for c in cites[:3])
    return support, hit and support


def freeze():
    if CASES.exists():
        raise ValueError("independent labels already frozen; refusing to overwrite")
    chunks = json.loads(CORPUS.read_text(encoding="utf-8"))
    ledger = {r["documentId"]: r for r in json.loads((ROOT / "data/expanded-sources.json").read_text(encoding="utf-8"))["records"]}
    rows = []
    for index, (question, document, anchors) in enumerate(POSITIVE, 1):
        supporting = [c for c in chunks if c["documentId"] == document
                      and all(a in c["content"].casefold() for a in anchors)]
        if not supporting:
            raise ValueError(f"source anchors not established in body: {document}, {anchors}")
        source = ledger[document]
        if source["redistributionStatus"] != "permissive-verified":
            raise ValueError("benchmark source evidence must be permissively licensed")
        rows.append({"id": f"P{index:02d}", "question": question, "expectRefused": False,
                     "targetDocumentIds": [document], "bodyAnchors": anchors,
                     "sourceUrl": source["sourceUrl"], "sourceChecksum": source["sourceChecksum"],
                     "supportingEvidence": [{"chunkId": c["id"], "quote": c["content"],
                                            "sha256": sha(c["content"].encode())} for c in supporting]})
    rows.extend({"id": f"N{i:02d}", "question": question, "expectRefused": True,
                 "targetDocumentIds": [], "supportingEvidence": []} for i, question in enumerate(NEGATIVE, 1))
    payload = {"schemaVersion": 1, "frozenAt": datetime.now(timezone.utc).isoformat(),
               "method": "independent analyst questions and source body anchors; not clinical correctness labels",
               "rules": "Top3 target document AND both nominated body anchors; citation quotes must equal indexed source text. No query bridge, paid model, original50 or holdout data.",
               "rows": rows}
    CASES.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"positive": 24, "negative": 6, "labelsSha256": sha(CASES.read_bytes())}))


def evaluate(corpus: Path, output: Path, engine_dir: Path):
    sys.path.insert(0, str(engine_dir))
    from app.config import Settings
    from app.engine import LlamaIndexEngine
    from app.schemas import ChatRequest

    labels = json.loads(CASES.read_text(encoding="utf-8"))
    chunks = json.loads(corpus.read_text(encoding="utf-8"))
    by_id = {c["id"]: c for c in chunks}
    settings = replace(Settings.from_env(), data_path=ROOT / "backend/src/main/resources/data/knowledge-base.json",
                       expanded_data_path=corpus, embedding_backend="hash", embedding_model="hash-sha256-bigram-v1",
                       embedding_allow_download=False, query_bridge=False, minimum_evidence_score=.9,
                       metadata_weight=0.0, candidate_limit=8, final_limit=4)
    engine = LlamaIndexEngine(settings)
    started = time.perf_counter()
    engine.reload()
    index_ms = (time.perf_counter() - started) * 1000
    results = []
    for row in labels["rows"]:
        started = time.perf_counter()
        answer = engine.query(ChatRequest(question=row["question"], corpus="EXPANDED",
                                         version="V3_GUARDED", extractiveOnly=True))
        elapsed = (time.perf_counter() - started) * 1000
        cites = [c.model_dump(by_alias=True) for c in answer.citations]
        support, hit = citation_checks(cites, by_id, row)
        results.append({"id": row["id"], "question": row["question"], "effectiveTop3": hit,
                        "refused": answer.refused, "expectedRefused": row["expectRefused"],
                        "refusalCorrect": answer.refused == row["expectRefused"],
                        "citationSourceSupport": support, "citationCount": len(cites), "elapsedMs": round(elapsed, 3),
                        "citations": [{"documentId": c["documentId"], "chunkId": c["chunkId"]} for c in cites],
                        "refusalReason": answer.refusal_reason})
    positives = [r for r in results if not r["expectedRefused"]]
    negatives = [r for r in results if r["expectedRefused"]]
    latencies = sorted(r["elapsedMs"] for r in results)
    payload = {"labelsSha256": sha(CASES.read_bytes()), "corpusSha256": sha(corpus.read_bytes()),
               "chunkCount": len(chunks), "documentCount": len({c["documentId"] for c in chunks}),
               "engineSource": str(engine_dir.resolve()), "backend": "hash", "queryBridge": False, "threshold": .9,
               "indexBuildMs": round(index_ms, 2), "positiveTop3": sum(r["effectiveTop3"] for r in positives),
               "positiveCount": len(positives), "negativeRefusal": sum(r["refusalCorrect"] for r in negatives),
               "negativeCount": len(negatives), "citationSourceSupport": sum(r["citationSourceSupport"] for r in results),
               "citedResponses": sum(bool(r["citationCount"]) for r in results),
               "supportedCitedResponses": sum(bool(r["citationCount"]) and r["citationSourceSupport"] for r in results),
               "answeredPositiveCount": sum(not r["refused"] for r in positives),
               "medianMs": statistics.median(latencies), "p95Ms": latencies[int(.95 * (len(latencies) - 1))],
               "rows": results}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in payload.items() if k != "rows"}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true")
    parser.add_argument("--corpus", type=Path, default=CORPUS)
    parser.add_argument("--output", type=Path, default=ROOT / "runtime/audit-relevance-current.json")
    parser.add_argument("--engine-dir", type=Path, default=ROOT / "rag-service")
    args = parser.parse_args()
    if args.freeze:
        freeze()
    else:
        if any("holdout" in str(path).casefold() for path in (args.corpus, args.output, args.engine_dir)):
            parser.error("frozen holdout paths are prohibited")
        evaluate(args.corpus, args.output, args.engine_dir)


if __name__ == "__main__":
    main()
