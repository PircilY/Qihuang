from dataclasses import replace
from unittest.mock import Mock, patch

import pytest

from app.config import Settings
from app.engine import LlamaIndexEngine
from app.schemas import ChatRequest


@pytest.fixture
def engine():
    instance = LlamaIndexEngine(replace(Settings.from_env(), llm_model="", llm_api_base=""))
    instance.reload()
    return instance


def test_unchanged_upsert_does_not_rebuild_indexes(engine):
    import json

    chunks = json.loads(engine.settings.data_path.read_text(encoding="utf-8"))
    doc_id = chunks[0]["documentId"]
    batch = [chunk for chunk in chunks if chunk["documentId"] == doc_id]
    with patch.object(engine, "_rebuild_indexes") as rebuild:
        engine.ingest({"id": doc_id}, batch)
        rebuild.assert_not_called()


@pytest.mark.parametrize("version", ["V1_VECTOR", "V2_HYBRID", "V3_GUARDED"])
def test_filtered_retrieval_is_case_insensitive_and_excludes_inactive_nodes(engine, version):
    for node in engine.repository.nodes():
        node.metadata["source_type"] = "Public Health"
    engine._rebuild_indexes()
    response = engine.query(ChatRequest(
        question="高血压没有症状也需要检查血压吗？",
        version=version, source_type="public",
    ))
    assert response.citations
    assert all(c.source_type == "Public Health" for c in response.citations)

    for node in engine.repository.nodes():
        node.metadata["status"] = "RETIRED"
    engine._rebuild_indexes()
    response = engine.query(ChatRequest(question="高血压的症状是什么？", version=version))
    assert response.refused
    assert not response.citations


def test_extractive_request_does_not_call_configured_legacy_model(engine):
    engine._llm = Mock()
    response = engine.query(ChatRequest(
        question="高血压没有症状也需要检查血压吗？",
        version="V3_GUARDED", extractiveOnly=True,
    ))
    assert response.citations
    assert "generation=grounded-extractive" in response.retrieval.notes
    engine._llm.complete.assert_not_called()
