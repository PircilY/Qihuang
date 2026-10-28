import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "audit_relevance", Path(__file__).resolve().parents[2] / "tools/audit_relevance.py"
)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def test_independent_source_questions_have_fixed_shape():
    assert len(audit.POSITIVE) == 24
    assert len(audit.NEGATIVE) == 6
    assert len({row[1] for row in audit.POSITIVE}) == 24


def test_generic_term_or_other_document_does_not_count():
    chunks = {"c1": {"documentId": "TARGET", "content": "hypertension"},
              "c2": {"documentId": "OTHER", "content": "astragalus hypertension"}}
    row = {"targetDocumentIds": ["TARGET"], "bodyAnchors": ["astragalus", "hypertension"]}
    cites = [{"chunkId": key, "documentId": value["documentId"], "quote": value["content"]}
             for key, value in chunks.items()]
    assert audit.citation_checks(cites, chunks, row) == (True, False)


def test_changed_quote_and_fourth_place_fail():
    chunks = {"c": {"documentId": "TARGET", "content": "astragalus heart failure"}}
    row = {"targetDocumentIds": ["TARGET"], "bodyAnchors": ["astragalus", "heart failure"]}
    cite = {"chunkId": "c", "documentId": "TARGET", "quote": chunks["c"]["content"]}
    assert audit.citation_checks([cite], chunks, row) == (True, True)
    assert audit.citation_checks([{**cite, "quote": cite["quote"] + " altered"}], chunks, row) == (False, False)
    other = {"chunkId": "x", "documentId": "OTHER", "quote": "other"}
    chunks["x"] = {"documentId": "OTHER", "content": "other"}
    assert audit.citation_checks([other, other, other, cite], chunks, row) == (True, False)
