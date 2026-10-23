import importlib.util
import json
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location("generation_export", Path(__file__).resolve().parents[2] / "tools/generation_experiment.py")
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


def test_file_questions_and_full_export(tmp_path):
    source = tmp_path / "fixtures.jsonl"
    source.write_text(json.dumps({"id":"f1", "question":"[FIXTURE] 非医学占位？"}) + "\n" + json.dumps({"id":"f2", "question":"[FIXTURE] 跳过？", "shouldRefuse":True}), encoding="utf-8")
    assert exporter.load_questions(source) == [{"id":"f1", "question":"[FIXTURE] 非医学占位？"}]
    row = {"questionId":"f1", "mode":"A", "answer":"=unsafe", "citations":[], "failureReason":"MODEL_NOT_CONFIGURED", "judgeFailureReason":""}
    exporter.export({"rows":[row], "summary":{}, "metadata":{"seed":42}}, tmp_path / "out", tmp_path / "report.md")
    assert "'=unsafe" in (tmp_path / "out/results.csv").read_text(encoding="utf-8-sig")
    assert json.loads((tmp_path / "out/results.jsonl").read_text(encoding="utf-8"))["answer"] == "=unsafe"
    assert "MODEL_NOT_CONFIGURED" in (tmp_path / "report.md").read_text(encoding="utf-8")


def test_frozen_file_rejected_before_read(tmp_path):
    with pytest.raises(ValueError, match="Frozen"):
        exporter.load_questions(tmp_path / "holdout-cases.json")
