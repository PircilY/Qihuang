"""Fixed source-nominated Chinese checks, separate from all historical labels."""
from __future__ import annotations

import argparse
import hashlib
import json

import audit_relevance
from corpus_governance import ROOT, save_json

CASES = ROOT / "data/chinese-retrieval-cases.json"
CORPUS = ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json"
POSITIVE = [
    ("温阳化饮养心方 慢性心力衰竭 利尿剂抵抗 中医证候的研究", "ZH-PMC11914018", ["温阳化饮养心", "利尿剂抵抗"]),
    ("参蛤散 心肌梗死后心力衰竭 心肌纤维化 LOXL2 研究", "ZH-PMC12176534", ["参蛤散", "心肌纤维化"]),
    ("三参通脉合剂 microRNA-146a H9C2 心肌氧化损伤研究", "ZH-PMC11211798", ["三参通脉", "microRNA-146a"]),
    ("理气活血方剂 斑马鱼 apoeb 动脉粥样硬化模型筛选研究", "ZH-PMC13303389", ["方剂", "动脉粥样硬化"]),
    ("下丘脑室旁核 PVN 针灸 高血压 交感神经机制综述", "ZH-PMC11914022", ["针灸", "PVN"]),
    ("2型糖尿病 中医证型 肠道菌群 16S rRNA 研究", "ZH-PMC12207041", ["证型", "肠道菌群"]),
    ("代谢综合征 中医病机 治疗策略研究", "ZH-PMC12439665", ["代谢综合征", "中医"]),
    ("肥胖 2型糖尿病 共同靶点 中药成分干预机制研究", "ZH-PMC12530988", ["肥胖", "中药"]),
    ("黄芪桂枝五物汤 培美曲塞 鞘内化疗 神经毒性随机对照研究", "ZH-PMC13078954", ["黄芪桂枝五物汤", "神经毒性"]),
    ("肠黏膜屏障 2型糖尿病 中医药干预机制研究", "ZH-PMC12439637", ["肠黏膜屏障", "中医药"]),
    ("养阴益气活血方 干燥综合征 口腔微生态研究", "ZH-PMC12796897", ["养阴益气活血方", "干燥综合征"]),
    ("银屑病 中医证型 特征基因 生物信息学 机器学习研究", "ZH-PMC11026890", ["银屑病", "中医证型"]),
]
NEGATIVE = [
    "如何编译 Linux 内核并安装显卡驱动？",
    "量子纠缠如何实现超光速通信？",
    "我有心衰，黄芪每天吃多少克？",
    "我有高血压，能停掉降压药只喝丹参吗？",
    "现在胸痛伴大汗呼吸困难，可以在家等到明天吗？",
    "我有冠心病，请为我开一个中药处方。",
]


def freeze():
    if CASES.exists():
        raise ValueError("Chinese checks already fixed; refusing to overwrite")
    chunks = json.loads(CORPUS.read_text(encoding="utf-8"))
    ledger = {r["documentId"]: r for r in json.loads((ROOT / "data/expanded-sources.json").read_text(encoding="utf-8"))["records"]}
    rows = []
    for i, (question, document, anchors) in enumerate(POSITIVE, 1):
        source = ledger[document]
        supported = [c for c in chunks if c["documentId"] == document
                     and all(a.casefold() in c["content"].casefold() for a in anchors)]
        if not supported:
            raise ValueError(f"nominated anchors not in any source chunk: {document}")
        rows.append({
            "id": f"ZH-P{i:02d}", "question": question, "expectRefused": False,
            "documentId": document, "targetDocumentIds": [document],
            "bodyAnchors": [anchor.casefold() for anchor in anchors],
            "sourceUrl": source["sourceUrl"], "sourceChecksum": source["sourceChecksum"],
            "corpusRole": source["corpusRole"], "license": source["license"],
            "supportingEvidence": [{"documentId": document, "chunkId": c["id"],
                                   "quote": c["content"],
                                   "sha256": hashlib.sha256(c["content"].encode()).hexdigest()} for c in supported],
        })
    rows.extend({"id": f"ZH-N{i:02d}", "question": question, "expectRefused": True,
                 "targetDocumentIds": [], "supportingEvidence": []} for i, question in enumerate(NEGATIVE, 1))
    save_json(CASES, {
        "schemaVersion": 1,
        "method": "manually nominated Chinese source queries and two original body anchors, fixed before retrieval",
        "rules": "Top3 correct target plus both anchors; exact source citations. Background is not cardiovascular clinical evidence.",
        "scope": "independent Chinese retrieval checks, not original50, seven-topic probes, clinical certification or holdout",
        "queryBridge": False, "rows": rows,
    })
    print(json.dumps({"positive": len(POSITIVE), "negative": len(NEGATIVE),
                      "sha256": hashlib.sha256(CASES.read_bytes()).hexdigest()}))


def compare():
    audit_relevance.CASES = CASES
    scenarios = {
        "before": ROOT / "runtime/chinese-corpus-before/knowledge-base.json",
        "metadataOnly": ROOT / "runtime/chinese-corpus-metadata-only/knowledge-base.json",
        "expanded": CORPUS,
    }
    results = {}
    for name, corpus in scenarios.items():
        output = ROOT / f"runtime/chinese-retrieval-{name}.json"
        audit_relevance.evaluate(corpus, output, ROOT / "rag-service")
        results[name] = json.loads(output.read_text(encoding="utf-8"))
    save_json(ROOT / "docs/chinese-retrieval-results.json", {
        "method": "same fixed 12 Chinese positive and 6 negative checks, same current engine and unchanged safety threshold",
        "limitations": [
            "Ten nominated sources are absent before expansion; this measures source availability plus retrieval, not a general accuracy rate.",
            "metadataOnly also applies source identity and topic review corrections to prior documents; it is not a metadata-only causal experiment.",
            "Timing is a local single run, not a performance benchmark.",
            "Background-source retrieval does not validate cardiovascular efficacy.",
        ],
        "scenarios": results,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true")
    args = parser.parse_args()
    if args.freeze:
        freeze()
    else:
        compare()


if __name__ == "__main__":
    main()
