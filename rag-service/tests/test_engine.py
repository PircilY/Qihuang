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
    instance = LlamaIndexEngine(Settings.from_env())
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


def test_extractive_request_has_no_python_model_path(engine):
    response = engine.query(ChatRequest(
        question="高血压没有症状也需要检查血压吗？",
        version="V3_GUARDED", extractiveOnly=True,
    ))
    assert response.citations
    assert "generation=grounded-extractive" in response.retrieval.notes
    assert not hasattr(engine, "_create_llm")
    assert engine.health().llm_configured is False


def test_query_reuses_corpus_indexes_and_health_does_not_rebuild(engine):
    request = ChatRequest(question="高血压的症状是什么？", version="V1_VECTOR")
    with patch.object(engine, "_rebuild_indexes", wraps=engine._rebuild_indexes) as rebuild:
        engine.query(request)
        engine.query(request)
        engine.health()
        rebuild.assert_not_called()


def test_combined_query_and_import_invalidation_use_one_runtime_revision(tmp_path):
    import json

    def fixture(document_id, chunk_id, content):
        return {"id": chunk_id, "documentId": document_id, "title": "Fixture",
                "content": content, "sourceType": "fixture", "status": "ACTIVE"}

    baseline = tmp_path / "baseline.json"
    expanded = tmp_path / "expanded.json"
    baseline.write_text(json.dumps([fixture("BASE", "B-1", "baseline fixture")]), encoding="utf-8")
    expanded.write_text(json.dumps([fixture("EXP", "E-1", "expanded fixture")]), encoding="utf-8")
    settings = replace(Settings.from_env(), data_path=baseline, expanded_data_path=expanded,
                       embedding_cache_path=None, embedding_backend="hash",
                       embedding_model="hash-sha256-bigram-v1", embedding_allow_download=False, query_bridge=False)
    instance = LlamaIndexEngine(settings)
    instance.reload()
    request = ChatRequest(question="fixture", version="V1_VECTOR", corpus="COMBINED",
                          expectedRevision=instance.repository.knowledge_revision("BASELINE"))
    with patch.object(instance, "_rebuild_indexes", wraps=instance._rebuild_indexes) as rebuild:
        response = instance.query(request)
        assert "corpus=COMBINED" in response.retrieval.notes
        assert {node.node_id for node in instance._nodes["COMBINED"]} == {"B-1", "E-1"}
        instance.query(request)
        rebuild.assert_called_once_with("COMBINED")
    instance.ingest_batch([({"id": "IMPORT", "checksum": "new"}, [fixture("IMPORT", "I-1", "import fixture")])])
    with pytest.raises(KnowledgeOutOfSync):
        instance.query(request)
    request.expected_revision = instance.repository.knowledge_revision("BASELINE")
    with patch.object(instance, "_rebuild_indexes", wraps=instance._rebuild_indexes) as rebuild:
        instance.query(request)
        instance.query(request)
        rebuild.assert_not_called()
    assert {node.node_id for node in instance._nodes["COMBINED"]} == {"B-1", "E-1", "I-1"}
    assert {node.node_id for node in instance._nodes["EXPANDED"]} == {"E-1"}
    assert instance.health().corpora["COMBINED"]["chunkCount"] == 3


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


def test_citations_carry_automatic_metadata_and_source_marker(engine):
    node = TextNode(
        id_="CONTRACT-METADATA",
        text="可追溯引用内容。",
        metadata={
            "chunk_id": "CONTRACT-METADATA", "document_id": "DOC-META",
            "document_title": "元数据材料", "syndromes": "气虚证|血瘀证",
            "formulas": "补阳还五汤", "herbs": "黄芪|丹参",
            "metadata_source": "词典匹配",
        },
    )
    citation = engine._citations([NodeWithScore(node=node, score=0.8)])[0]

    assert citation.syndromes == ["气虚证", "血瘀证"]
    assert citation.formulas == ["补阳还五汤"]
    assert citation.herbs == ["黄芪", "丹参"]
    assert citation.metadata_source == "词典匹配"


def test_import_fills_only_dictionary_name_metadata_and_preserves_user_terms(engine):
    document, chunks = engine._enrich_import_metadata(
        {"id": "DOC-TERM", "title": "黄芪名称核对", "checksum": "x"},
        [{"id": "C-TERM", "documentId": "DOC-TERM", "content": "仅验证名称词典匹配。"}],
    )
    assert "黄芪" in document["herbs"]
    assert "黄芪" in chunks[0]["herbs"]
    assert document["metadataSource"] == "词典匹配"
    assert chunks[0]["metadataSource"] == "词典匹配"

    user_document, user_chunks = engine._enrich_import_metadata(
        {"id": "DOC-USER", "title": "黄芪名称核对", "herbs": ["用户术语"]},
        [{"id": "C-USER", "documentId": "DOC-USER", "content": "仅验证名称词典匹配。",
          "herbs": ["用户术语"]}],
    )
    assert user_document["herbs"] == ["用户术语"]
    assert user_chunks[0]["herbs"] == ["用户术语"]
    assert user_document["metadataSource"] == user_chunks[0]["metadataSource"] == "用户导入"


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
