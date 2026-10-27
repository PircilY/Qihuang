from __future__ import annotations

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0, str(ROOT / "tools"))
SPEC = importlib.util.spec_from_file_location("expanded_eval", ROOT / "tools" / "build_expanded_eval.py")
EVAL = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(EVAL)


def test_evaluation_generators_are_seeded_and_have_document_targets() -> None:
    first = EVAL.build_samples()
    second = EVAL.build_samples()

    assert first == second
    assert len(first) > 1000
    assert all(sample["seed"] == 42 and sample["targets"] for sample in first)
    assert any(sample["rule"] == "dictionary-name-pair" for sample in first)
    assert all(sample["language"] in {"same-language", "cross-language"} for sample in first)


def test_bootstrap_reports_requested_percentile_interval_and_empty_group() -> None:
    observations = [(1.0, 1.0, 1.0, 1.0), (0.0, 1.0, 1.0, 0.5)]

    interval = EVAL.bootstrap_observations(observations)

    assert interval["hit@1"][0] <= 0.5 <= interval["hit@1"][1]
    assert EVAL.bootstrap_observations({}) == {
        "hit@1": [0.0, 0.0], "hit@3": [0.0, 0.0],
        "hit@4": [0.0, 0.0], "mrr": [0.0, 0.0],
    }


def test_independent_term_target_uses_chinese_source_text_only() -> None:
    query = "黄芪"
    assert EVAL.source_term_match("Astragalus efficacy", query, []) is False
    assert EVAL.source_term_match("黄芪的研究进展", query, []) is True


def test_low_confidence_metadata_is_ignored_by_rerank() -> None:
    from llama_index.core.schema import NodeWithScore, TextNode
    node = NodeWithScore(node=TextNode(text="fixture", metadata={
        "disease": "黄芪", "syndromes": "", "formulas": "", "herbs": "",
        "low_metadata_terms": "黄芪",
    }), score=0.5)
    assert EVAL._metrics_payload([])["sampleCount"] == 0
    import sys
    sys.path.insert(0, str(ROOT / "rag-service"))
    from app.engine import _metadata_boost
    assert _metadata_boost("黄芪", node) == 0.0


def test_probe_full_corpus_coverage_distinguishes_missing_terms() -> None:
    import importlib.util
    probe_spec = importlib.util.spec_from_file_location("probe_expanded", ROOT / "tools" / "probe_expanded.py")
    probe = importlib.util.module_from_spec(probe_spec)
    assert probe_spec and probe_spec.loader
    probe_spec.loader.exec_module(probe)
    chunks = [{"title": "黄芪研究", "content": "正文内容"}, {"title": "Other", "content": "Astragalus"}]

    assert probe.scan_coverage(chunks, ["黄芪", "Astragalus"]) == 2
    assert probe.scan_coverage(chunks, ["丹参", "Danshen"]) == 0
