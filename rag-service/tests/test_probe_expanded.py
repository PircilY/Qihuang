"""[FIXTURE] Probe contract checks without starting services."""
import importlib.util
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "probe_expanded", Path(__file__).resolve().parents[2] / "tools/probe_expanded.py"
)
PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(PROBE)


def response(citations=None, engine="llama-index", version="V3_GUARDED"):
    return {"data": {"version": version, "refused": False, "citations": citations or [],
                     "retrieval": {"engine": engine, "notes": []}}}


def run(monkeypatch, body, chunks=None):
    monkeypatch.setattr(PROBE, "request", lambda *args: body)
    return PROBE.run_probes(
        [{"title": "Fixture", "content": "AlphaTerm"}] if chunks is None else chunks,
        "http://127.0.0.1:1234", [("AlphaTerm study", ["AlphaTerm"])],
    )[0]


def test_probe_uses_quote_from_real_citation_contract(monkeypatch):
    row = run(monkeypatch, response([{"documentTitle": "Fixture", "quote": "AlphaTerm"}]))
    assert row["hit"] is True
    assert row["failed"] is False


def test_probe_does_not_count_fourth_citation(monkeypatch):
    citations = [{"documentTitle": "Fixture", "quote": "OtherTerm"}] * 3
    row = run(monkeypatch, response(citations + [{"documentTitle": "Fixture", "quote": "AlphaTerm"}]))
    assert row["hit"] is False
    assert row["failed"] is True


@pytest.mark.parametrize("engine,version", [
    ("java-local", "V3_GUARDED"), ("llama-index", "V1_VECTOR"),
])
def test_probe_rejects_wrong_execution_path(monkeypatch, engine, version):
    row = run(monkeypatch, response([{"documentTitle": "AlphaTerm", "quote": "Fixture"}],
                                    engine, version))
    assert row["hit"] is True
    assert row["failed"] is True


def test_probe_request_errors_fail_even_without_corpus_coverage(monkeypatch):
    def unavailable(*args):
        raise OSError("fixture service unavailable")
    monkeypatch.setattr(PROBE, "request", unavailable)
    row = PROBE.run_probes([], "http://127.0.0.1:1234",
                           [("AlphaTerm study", ["AlphaTerm"])])[0]
    assert row["failed"] is True
    assert "fixture service unavailable" in row["error"]


@pytest.mark.parametrize("body", [
    {"data": {}},
    response([{"documentTitle": "AlphaTerm", "content": "wrong contract"}]),
    response([None]),
])
def test_probe_malformed_responses_cannot_pass(monkeypatch, body):
    assert run(monkeypatch, body)["failed"] is True


def test_probe_reports_missing_coverage_without_inventing_regression(monkeypatch):
    row = run(monkeypatch, response(), chunks=[])
    assert row["coverage"] == 0
    assert row["hit"] is False
    assert row["failed"] is False


def test_report_escapes_table_cells(monkeypatch):
    row = run(monkeypatch, response([{"documentTitle": "AlphaTerm | Fixture\nNext",
                                     "quote": "Fixture"}]))
    report = PROBE.render_report([row], query_bridge=False)
    assert "AlphaTerm \\| Fixture<br>Next" in report
    assert "RAG_QUERY_BRIDGE=false" in report
