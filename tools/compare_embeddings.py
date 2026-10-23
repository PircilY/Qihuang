"""Optional original-50 hash/semantic comparison, with explicit unavailable results."""
import argparse
import importlib.util
import json
import sys
from dataclasses import replace
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "rag-service"))
from app.config import Settings
from app.engine import LlamaIndexEngine
from app.schemas import ChatRequest


def measure(settings):
    engine = LlamaIndexEngine(settings)
    engine.reload()
    cases = json.loads((ROOT / "backend/src/main/resources/data/evaluation-cases.json").read_text(encoding="utf-8"))
    results = []
    for case in cases:
        answer = engine.query(ChatRequest(question=case["question"], version="V3_GUARDED", extractiveOnly=True))
        expected = set(case.get("expectedChunkIds", []))
        ids = [citation.chunk_id for citation in answer.citations]
        rank = next((i + 1 for i, value in enumerate(ids) if value in expected), 0)
        results.append({"caseId": case["id"], "expectedRefusal": case["shouldRefuse"],
                        "refused": answer.refused, "rank": rank, "ids": ids, "latencyMs": answer.latency_ms,
                        "recall": len(expected & set(ids))/len(expected) if expected else 0})
    retrieval = [row for row in results if not row["expectedRefusal"]]
    return {"backend": settings.embedding_backend, "model": settings.embedding_model,
        "Hit@1": sum(row["rank"] == 1 for row in retrieval)/len(retrieval),
        "Hit@K": sum(row["rank"] > 0 for row in retrieval)/len(retrieval),
        "MRR": sum(1/row["rank"] if row["rank"] else 0 for row in retrieval)/len(retrieval),
        "Recall@K": sum(row["recall"] for row in retrieval)/len(retrieval),
        "refusalAccuracy": sum(row["refused"] == row["expectedRefusal"] for row in results)/len(results),
        "indexBuildMs": engine.health().index_build_ms, "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default="BAAI/bge-small-zh-v1.5")
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / ".tmp/embedding-comparison.json")
    args = parser.parse_args()
    config = Settings.from_env()
    results = [measure(replace(config, embedding_backend="hash", embedding_model="hash-sha256-bigram-v1"))]
    unavailable = None
    if importlib.util.find_spec("sentence_transformers") is None:
        unavailable = "Optional sentence-transformers dependency unavailable; semantic experiment skipped"
    else:
        try:
            results.append(measure(replace(config, embedding_backend="sentence-transformers", embedding_model=args.model,
                embedding_allow_download=args.allow_download)))
        except Exception as exception:
            unavailable = "Semantic experiment failed: " + type(exception).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"results": results, "semanticUnavailable": unavailable},
                                     ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"results": [{key: value for key, value in row.items() if key != "cases"} for row in results],
                      "semanticUnavailable": unavailable}, ensure_ascii=False))

if __name__ == "__main__":
    main()
