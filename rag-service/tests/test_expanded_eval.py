from __future__ import annotations

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
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
