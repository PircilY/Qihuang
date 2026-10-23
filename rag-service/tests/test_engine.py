from dataclasses import replace
from unittest.mock import Mock, patch

import pytest

from app.config import Settings
from app.engine import KnowledgeOutOfSync, LlamaIndexEngine
from app.schemas import ChatRequest
from app.engine import _is_related, _metadata_boost
from llama_index.core.schema import NodeWithScore, TextNode


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
    engine._rebuild_indexes("BASELINE")
    response = engine.query(ChatRequest(
        question="高血压没有症状也需要检查血压吗？",
        version=version, source_type="public",
    ))
    assert response.citations
    assert all(c.source_type == "Public Health" for c in response.citations)

    for node in engine.repository.nodes():
        node.metadata["status"] = "RETIRED"
    engine._rebuild_indexes("BASELINE")
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


def test_query_reuses_corpus_indexes_and_health_does_not_rebuild(engine):
    request = ChatRequest(question="高血压的症状是什么？", version="V1_VECTOR")
    with patch.object(engine, "_rebuild_indexes", wraps=engine._rebuild_indexes) as rebuild:
        engine.query(request)
        engine.query(request)
        engine.health()
        rebuild.assert_not_called()


def test_query_reports_revision_inventory_after_reload_or_restart(engine):
    with pytest.raises(KnowledgeOutOfSync) as failure:
        engine.query(ChatRequest(
            question="高血压的症状是什么？", version="V1_VECTOR", expectedRevision="stale"
        ))
    assert failure.value.documents == []
    assert failure.value.revision == engine.repository.knowledge_revision("BASELINE")


def test_rank_fusion_deduplicates_same_chunk_across_retrievers(engine):
    node = TextNode(id_="CONTRACT-C1", text="重复候选只应保留一个身份。")
    fused = engine._fuse_ranked_candidates(
        [NodeWithScore(node=node, score=0.9)],
        [NodeWithScore(node=node, score=4.2)],
    )

    assert [item.node.node_id for item in fused] == ["CONTRACT-C1"]
    assert fused[0].score == pytest.approx(1.0)


def test_metadata_rank_weights_match_contract():
    node = NodeWithScore(
        node=TextNode(
            id_="CONTRACT-META",
            text="权重校验。",
            metadata={
                "disease": "高血压",
                "syndromes": "气虚证",
                "formulas": "测试方",
                "herbs": "甘草|黄芪",
                "evidence_level": "A",
            },
        ),
        score=0.0,
    )

    assert _metadata_boost("高血压气虚证测试方甘草", node) == pytest.approx(0.82)


def test_is_related_allows_shared_token_anchored_to_disease_metadata():
    node = NodeWithScore(
        node=TextNode(text="完全无关的证据内容。", metadata={"disease": "高血压"}),
        score=0.0,
    )

    assert _is_related("血压非常高", node)


def test_is_related_does_not_allow_single_body_token_without_disease_anchor():
    node = NodeWithScore(
        node=TextNode(text="血压异常。", metadata={"disease": "其他病种"}),
        score=0.0,
    )

    assert not _is_related("血压", node)


def test_is_related_does_not_use_generic_disease_metadata_as_anchor():
    node = NodeWithScore(
        node=TextNode(text="检查结果需要结合临床判断。", metadata={"disease": "症状"}),
        score=0.0,
    )

    assert not _is_related("症状", node)
