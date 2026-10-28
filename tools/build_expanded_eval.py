"""Build and score deterministic EXPANDED self-supervised proxy evaluations.

Only the expanded corpus, source ledger and shared terminology are read.  The
proxy deliberately measures document-level lexical/hash retrieval and is not a
human relevance judgement.
"""
from __future__ import annotations
import argparse, json, random, re, subprocess, tempfile, sys
from pathlib import Path
from collections import Counter
from terminology_utils import ENTRIES

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json"
BASELINE_CORPUS = ROOT / "backend/src/main/resources/data/knowledge-base.json"
RESULTS = ROOT / "docs" / "expanded-evaluation-results.json"
REPORT = ROOT / "docs" / "expanded-evaluation.md"
SEED = 42
_RANK_CACHE: dict[tuple[str, bool, bool], list[str]] = {}

def tok(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]{2,}|[\u3400-\u9fff]{2}", text.casefold())

def build_samples() -> list[dict]:
    chunks = json.loads(CORPUS.read_text(encoding="utf-8"))
    docs: dict[str, dict] = {}
    for chunk in chunks:
        docs.setdefault(chunk["documentId"], {"title": chunk["title"], "text": []})["text"].append(chunk["content"])
    samples = []
    rng = random.Random(SEED)
    for doc_id, doc in sorted(docs.items()):
        words = doc["title"].split()
        variants = [doc["title"], doc["title"][: max(8, len(doc["title"]) // 2)],
                    " ".join(rng.sample(words, len(words))) if len(words) > 1 else doc["title"],
                    re.sub(r"[^\w\u3400-\u9fff ]", "", doc["title"])]
        for variant in dict.fromkeys(variants):
            samples.append({"query": variant, "kind": "title", "language": "same-language", "targets": [doc_id], "rule": "title-variant-no-translation", "seed": SEED})
    for doc_id, doc in sorted(docs.items()):
        text = doc["title"] + " " + " ".join(doc["text"])
        folded = text.casefold()
        hits = []
        for entry in ENTRIES:
            aliases = [entry["zh"], *entry.get("zhAliases", []), *entry.get("en", [])]
            if any(a and a.casefold() in folded for a in aliases):
                hits.append(entry)
        for entry in hits:
            q = entry["zh"]
            chinese_hit = any(alias and alias.casefold() in folded
                              for alias in [entry["zh"], *entry.get("zhAliases", [])])
            samples.append({"query": q, "kind": "term-dictionary",
                            "language": "same-language" if chinese_hit else "cross-language",
                            "targets": [doc_id], "rule": "dictionary-name", "seed": SEED})
            independent_hit = source_term_match(text, q)
            if independent_hit:
                samples.append({"query": q, "kind": "term-independent",
                                "language": "same-language", "targets": [doc_id],
                                "rule": "chinese-source-name", "seed": SEED})
        # Add up to three deterministic two-term combinations per document.
        for left_index, left in enumerate(hits):
            for right in hits[left_index + 1:]:
                left_zh = any(alias and alias.casefold() in folded
                              for alias in [left["zh"], *left.get("zhAliases", [])])
                right_zh = any(alias and alias.casefold() in folded
                               for alias in [right["zh"], *right.get("zhAliases", [])])
                samples.append({"query": left["zh"] + " " + right["zh"], "kind": "term-dictionary",
                                "language": "same-language" if left_zh and right_zh else "cross-language",
                                "targets": [doc_id], "rule": "dictionary-name-pair", "seed": SEED})
                if sum(sample["rule"] == "dictionary-name-pair" and sample["targets"] == [doc_id]
                       for sample in samples[-3:]) >= 3:
                    break
            if sum(sample["rule"] == "dictionary-name-pair" and sample["targets"] == [doc_id]
                   for sample in samples[-3:]) >= 3:
                break
    return samples

def rank(query: str, docs: dict[str, dict], bridge: bool, title_only: bool = False) -> list[str]:
    cache_key = (query, bridge, title_only)
    if cache_key in _RANK_CACHE:
        return _RANK_CACHE[cache_key]
    q = Counter(tok(query)); aliases = []
    if bridge:
        query_folded = query.casefold()
        for e in ENTRIES:
            if e["zh"].casefold() in query_folded:
                aliases.extend(tok(" ".join(e.get("en", []))))
            elif any(a.casefold() in query_folded for a in e.get("en", [])):
                aliases.extend(tok(e["zh"]))
    q.update({term: 0.35 for term in aliases})
    scored = []
    for doc_id, doc in docs.items():
        text = doc["title"] if title_only else doc["title"] + " " + " ".join(doc["text"])
        cache_key = "title_counts" if title_only else "counts"
        counts = doc.get(cache_key)
        if counts is None:
            counts = Counter(tok(text))
            doc[cache_key] = counts
        score = sum(weight * counts[t] for t, weight in q.items())
        scored.append((score, doc_id))
    ranked = [doc_id for score, doc_id in sorted(scored, key=lambda x: (-x[0], x[1]))]
    _RANK_CACHE[cache_key] = ranked
    return ranked

def metrics(samples, docs, bridge, k=4):
    values = {name: [] for name in ("hit@1", "hit@3", f"hit@{k}", "mrr")}
    for sample in samples:
        ranked = rank(sample["query"], docs, bridge)
        target = set(sample["targets"]); position = next((i + 1 for i, d in enumerate(ranked) if d in target), None)
        values["hit@1"].append(float(position == 1)); values["hit@3"].append(float(position is not None and position <= 3)); values[f"hit@{k}"].append(float(position is not None and position <= k)); values["mrr"].append(1 / position if position else 0.0)
    return {name: round(sum(v) / len(v), 4) if v else 0.0 for name, v in values.items()}

def observations(samples, docs, bridge, k=4, title_only=False):
    values = []
    for sample in samples:
        ranked = rank(sample["query"], docs, bridge, title_only)
        target = set(sample["targets"])
        pos = next((i + 1 for i, d in enumerate(ranked) if d in target), None)
        values.append((float(pos == 1), float(pos is not None and pos <= 3),
                       float(pos is not None and pos <= k), 1 / pos if pos else 0.0))
    return values

def metrics_from_observations(values):
    names = ("hit@1", "hit@3", "hit@4", "mrr")
    return {name: round(sum(row[index] for row in values) / len(values), 4) if values else 0.0
            for index, name in enumerate(names)}

def bootstrap_observations(values, draws=1000):
    if not values:
        return {name: [0.0, 0.0] for name in ("hit@1", "hit@3", "hit@4", "mrr")}
    rng = random.Random(SEED)
    names = ("hit@1", "hit@3", "hit@4", "mrr")
    result = {}
    for index, name in enumerate(names):
        draws_values = sorted(sum(values[rng.randrange(len(values))][index] for _ in values) / len(values)
                              for _ in range(draws)) if values else [0.0]
        result[name] = [round(draws_values[int(.025 * (draws - 1))], 4), round(draws_values[int(.975 * (draws - 1))], 4)]
    return result

def score_documents(ranked, targets, k=4):
    position = next((index + 1 for index, document_id in enumerate(ranked) if document_id in targets), None)
    return (float(position == 1), float(position is not None and position <= 3),
            float(position is not None and position <= k), 1 / position if position else 0.0)

def source_term_match(source_text: str, query: str, _terminology_entries=None) -> bool:
    """Judge by Chinese source-string overlap; no dictionary or expansion lookup."""
    return any(term in source_text for term in re.findall(r"[\u3400-\u9fff]{2,}", query))

def _metrics_payload(values):
    return {"sampleCount": len(values), "metrics": metrics_from_observations(values),
            "bootstrap95": bootstrap_observations(values)}

def _python_engine(settings):
    sys.path.insert(0, str(ROOT / "rag-service"))
    from app.engine import LlamaIndexEngine
    engine = LlamaIndexEngine(settings)
    engine.reload()
    return engine

def service_observations(samples, engine, version):
    from app.schemas import ChatRequest
    ranked_cache = {}
    observations_by_sample = []
    for sample in samples:
        query = sample["query"]
        if query not in ranked_cache:
            response = engine.query(ChatRequest(question=query, version=version,
                                                corpus="EXPANDED", extractive_only=True))
            ranked_cache[query] = list(dict.fromkeys(citation.document_id for citation in response.citations))
        observations_by_sample.append(score_documents(ranked_cache[query], set(sample["targets"])))
    return observations_by_sample

def service_evaluation(samples, embedding_backend=None):
    from dataclasses import replace
    sys.path.insert(0, str(ROOT / "rag-service"))
    from app.config import Settings
    from app.terminology import POLICY
    versions = ("V0_DIRECT", "V1_VECTOR", "V2_HYBRID", "V3_GUARDED")
    settings = Settings.from_env()
    backend = embedding_backend or settings.embedding_backend
    # The requested delivery-c9652d3 tag is absent locally; its commit exists and is the pre-G snapshot.
    baseline_ref = "delivery-c9652d3"
    if subprocess.run(["git", "rev-parse", "--verify", "refs/tags/delivery-c9652d3"], cwd=ROOT,
                      capture_output=True).returncode != 0:
        baseline_ref = "c9652d3"
    old_bytes = subprocess.run(
        ["git", "show", f"{baseline_ref}:backend/src/main/resources/data/expanded/knowledge-base.json"],
        cwd=ROOT, check=True, capture_output=True,
    ).stdout
    with tempfile.TemporaryDirectory(prefix="expanded-eval-before-", dir=ROOT / ".tmp") as temporary:
        before_path = Path(temporary) / "knowledge-base.json"
        before_path.write_bytes(old_bytes)
        phases = {
            "before": (before_path, False),
            "after": (CORPUS, True),
        }
        results = {}
        for phase, (expanded_path, bridge) in phases.items():
            phase_settings = replace(
                settings, data_path=BASELINE_CORPUS, expanded_data_path=expanded_path,
                query_bridge=bridge, metadata_weight=0.0, embedding_backend=backend,
            )
            engine = _python_engine(phase_settings)
            for version in versions:
                for kind in ("title", "term-dictionary", "term-independent"):
                    for language in ("same-language", "cross-language"):
                        subset = [sample for sample in samples
                                  if sample["kind"] == kind and sample["language"] == language]
                        values = service_observations(subset, engine, version)
                        results[f"{phase}/{version}/{kind}/{language}"] = _metrics_payload(values)
            del engine
        results["provenance"] = {
            "mode": "actual-python-engine-query",
            "engine": "LlamaIndexEngine.query",
            "embeddingBackend": backend,
            "beforeSnapshot": f"{baseline_ref} EXPANDED knowledge-base.json; query bridge disabled",
            "afterSnapshot": "working tree rebuilt EXPANDED corpus; query bridge enabled",
            "metadataWeight": 0.0,
            "genericWeight": POLICY["genericWeight"],
            "expansionWeight": POLICY["expansionWeight"],
        }
        return results

def baseline_evaluation(samples, docs, k=4):
    random_values = []
    rng = random.Random(SEED)
    document_ids = sorted(docs)
    for sample in samples:
        random_values.append(score_documents(rng.sample(document_ids, min(k, len(document_ids))), set(sample["targets"]), k))
    title_values = observations(samples, docs, False, title_only=True)
    result = {"random": _metrics_payload(random_values), "title-only": _metrics_payload(title_values), "groups": {}}
    for kind in ("title", "term-dictionary", "term-independent"):
        for language in ("same-language", "cross-language"):
            subset = [sample for sample in samples if sample["kind"] == kind and sample["language"] == language]
            group_random = []
            group_rng = random.Random(SEED)
            for sample in subset:
                group_random.append(score_documents(
                    group_rng.sample(document_ids, min(k, len(document_ids))), set(sample["targets"]), k))
            result["groups"][f"{kind}/{language}"] = {
                "random": _metrics_payload(group_random),
                "title-only": _metrics_payload(observations(subset, docs, False, title_only=True)),
            }
    return result

def render_report(result):
    counts = Counter((sample["kind"], sample["language"]) for sample in result["samples"])
    lines = [
        "# EXPANDED 自监督检索评测",
        "",
        "## 结论可信度",
        "",
        "|结果|可信度|理由|",
        "|---|---|---|",
        "|标题类|较可信的检索代理证据|查询和目标来自原始标题，但仍为标题词面自监督，不是人工相关性判断。|",
        "|术语类，词典目标|设计验证；循环风险|查询扩展与目标构造共用词典；提升仅说明扩展按设计工作，不能证明检索能力。|",
        "|术语类，中文原文目标|较可信的检索代理证据|目标判定只查文档原始字段中的中文术语，不使用扩展出来的英文词。|",
        "|真实医学相关性|未验证|没有人工相关性标注或独立 judge。|",
        "",
        "固定种子42，K=4，1000次样本 bootstrap 百分位95%区间。未读取 holdout 或原50题。",
        "",
        f"总样本 {result['sampleCount']}；标题同语言 {counts[('title', 'same-language')]}、标题跨语言 {counts[('title', 'cross-language')]}；词典目标术语同/跨语言 {counts[('term-dictionary', 'same-language')]}/{counts[('term-dictionary', 'cross-language')]}；中文原文独立目标术语 {counts[('term-independent', 'same-language')]}。标题样本只由标题构造；中文原文组不使用扩展出来的英文词判定目标。",
        "",
        "V0–V3 通过真实 Python `LlamaIndexEngine.query` 执行，默认 SHA-256 hash 向量；before 读取提交时 G/H 前 EXPANDED 快照并关闭查询桥接，after 使用工作区重建语料并启用桥接。元数据排序权重为 0。结果是实际检索策略的自监督代理结果，不是人工评审。",
        "",
        "|基线 / 类别 / 语言|Hit@1|Hit@3|Hit@4|MRR|",
        "|---|---:|---:|---:|---:|",
    ]
    for kind in ("title", "term-dictionary", "term-independent"):
        for language in ("same-language", "cross-language"):
            key = f"{kind}/{language}"
            for baseline in ("random", "title-only"):
                metric = result["baselines"]["groups"][key][baseline]["metrics"]
                lines.append(f"|{baseline} / {kind} / {language}|{metric['hit@1']:.4f}|{metric['hit@3']:.4f}|{metric['hit@4']:.4f}|{metric['mrr']:.4f}|")
    lines += ["", "|版本 / 阶段 / 类别|样本数|Hit@1 [95% CI]|Hit@3 [95% CI]|Hit@4 [95% CI]|MRR [95% CI]|", "|---|---:|---:|---:|---:|---:|"]
    for version in ("V0_DIRECT", "V1_VECTOR", "V2_HYBRID", "V3_GUARDED"):
        for phase in ("before", "after"):
            for kind in ("title", "term-dictionary", "term-independent"):
                for language in ("same-language", "cross-language"):
                    key = f"{phase}/{version}/{kind}/{language}"
                    group = result["groups"][key]
                    m, ci = group["metrics"], group["bootstrap95"]
                    cells = [f"{m[name]:.4f} [{ci[name][0]:.4f}, {ci[name][1]:.4f}]"
                             for name in ("hit@1", "hit@3", "hit@4", "mrr")]
                    friendly = {"term-dictionary": "术语词典目标（循环风险）", "term-independent": "术语中文原文目标", "title": "标题"}[kind]
                    label = f"{version} / {phase} / {friendly} {language}"
                    lines.append("|" + label + "|" + str(group["sampleCount"]) + "|" + "|".join(cells) + "|")
    lines += [
        "",
        "空分组以样本数 0 和空区间 [0,0] 保留，不解释为测得零性能。各类别无提升或下降均在上表按版本、阶段、语言完整列出；V0 不访问知识库，故检索指标为零。随机基线按每个样本固定种子不放回抽取 K 个文档，标题基线只对文档标题进行词面检索。",
        "",
        "本地 hash 索引真实策略实测；sentence-transformers 未在本次加载，模型结果待验证。BASELINE 原 50 题未在本阶段重测，其前后变化待隔离复测。",
        "",
    ]
    REPORT.write_text("\n".join(lines), encoding="utf-8")

def build_result(check=False, run_service=False):
    chunks = json.loads(CORPUS.read_text(encoding="utf-8")); docs = {}
    for c in chunks: docs.setdefault(c["documentId"], {"title": c["title"], "text": []})["text"].append(c["content"])
    for doc in docs.values():
        doc["counts"] = Counter(tok(doc["title"] + " " + " ".join(doc["text"])))
    samples = build_samples()
    result = {"seed": SEED, "k": 4, "bootstrapDraws": 1000, "sampleCount": len(samples),
              "samples": samples, "groups": {}, "baselines": baseline_evaluation(samples, docs)}
    if run_service:
        result["groups"] = service_evaluation(samples)
    else:
        for phase, bridge in (("before", False), ("after", True)):
            for kind in ("title", "term-dictionary", "term-independent"):
                for language in ("same-language", "cross-language"):
                    group = [s for s in samples if s["kind"] == kind and s["language"] == language]
                    result["groups"][f"proxy/{phase}/{kind}/{language}"] = _metrics_payload(observations(group, docs, bridge))
        result["groups"]["provenance"] = {"mode": "deterministic-lexical-proxy", "notServiceEvaluation": True}
    if check:
        existing = json.loads(RESULTS.read_text(encoding="utf-8"))
        assert existing["sampleCount"] == len(samples)
        assert existing["samples"] == samples
        assert all(sample["seed"] == SEED and sample["targets"] for sample in samples)
        if existing.get("groups", {}).get("provenance", {}).get("mode") == "actual-python-engine-query":
            for version in ("V0_DIRECT", "V1_VECTOR", "V2_HYBRID", "V3_GUARDED"):
                for phase in ("before", "after"):
                    for kind in ("title", "term-dictionary", "term-independent"):
                        for language in ("same-language", "cross-language"):
                            key = f"{phase}/{version}/{kind}/{language}"
                            assert key in existing["groups"] and "bootstrap95" in existing["groups"][key]
        else:
            raise AssertionError("evaluation results are not tagged as actual service-strategy results")
        print(json.dumps({"sampleCount": len(samples), "output": str(RESULTS), "status": "ok"}, ensure_ascii=False))
        return
    RESULTS.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if run_service:
        render_report(result)
    print(json.dumps({"sampleCount": len(samples), "output": str(RESULTS), "status": "ok",
                      "mode": result["groups"].get("provenance", {}).get("mode", "service")}, ensure_ascii=False))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--check", action="store_true")
    parser.add_argument("--service-eval", action="store_true", help="run actual Python engine strategies")
    args = parser.parse_args(); build_result(args.check, args.service_eval)
