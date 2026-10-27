from fastapi.testclient import TestClient as FastAPITestClient
from contextlib import contextmanager
import os
import time

@contextmanager
def TestClient(application):
    with FastAPITestClient(application) as client:
        timeout = float(os.environ.get("RAG_TEST_INDEX_READY_TIMEOUT_SECONDS", "180"))
        deadline = time.monotonic() + timeout
        while main.engine.index_state == "BUILDING" and time.monotonic() < deadline:
            time.sleep(.01)
        assert main.engine.index_state == "READY", (
            f"Python index did not reach READY within {timeout:g}s; "
            f"indexState={main.engine.index_state}; health={main.health()}"
        )
        yield client

from app.main import app
from app import main
from app.engine import KnowledgeOutOfSync
import pytest
import hashlib

from app.safety_rules import RULES_PATH, RULES_SHA256


def test_health_exposes_llamaindex_engine() -> None:
    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["engine"] == "llama-index"
    assert body["chunkCount"] > 0
    assert body["knowledgeRevision"]
    assert body["documents"] == []
    assert len(body["safetyRulesSha256"]) == 64
    assert body["safetyRulesSha256"] == hashlib.sha256(RULES_PATH.read_bytes()).hexdigest() == RULES_SHA256


@pytest.mark.parametrize("version", ["V0_DIRECT", "V1_VECTOR", "V2_HYBRID", "V3_GUARDED"])
def test_current_emergency_is_refused_before_every_version(version) -> None:
    with TestClient(app) as client:
        response = client.post("/v1/rag/chat", json={
            "question": "我现在胸痛", "version": version, "extractiveOnly": False,
        })
    body = response.json()
    assert response.status_code == 200
    assert body["refused"] is True
    assert "立即拨打 120 / 前往急诊" in body["answer"]
    assert body["citations"] == []


def test_revision_mismatch_returns_inventory_for_java_resync(monkeypatch) -> None:
    def raise_mismatch(request):
        raise KnowledgeOutOfSync("remote-revision", [{"documentId": "DOC-1", "checksum": "abc"}])

    monkeypatch.setattr(main.engine, "query", raise_mismatch)
    with TestClient(app) as client:
        response = client.post("/v1/query", json={
            "question": "测试", "version": "V2_HYBRID", "expectedRevision": "stale"
        })
    assert response.status_code == 409
    assert response.json()["code"] == "KNOWLEDGE_OUT_OF_SYNC"
    assert response.json()["documents"] == [{"documentId": "DOC-1", "checksum": "abc"}]


def test_import_endpoint_forwards_evidence_level_to_python_engine(monkeypatch) -> None:
    captured = {}

    def capture_import(document, chunks, corpus="BASELINE"):
        captured["document"] = document
        captured["chunks"] = chunks
        return {"documentId": document["id"], "importedChunkCount": 1,
                "totalChunkCount": 1, "status": "READY"}

    monkeypatch.setattr(main.engine, "ingest", capture_import)
    payload = {
        "document": {
            "id": "SYNC-EVIDENCE-1", "title": "同步测试", "sourceType": "指南",
            "publisher": "测试机构", "publicationYear": 2026, "evidenceLevel": "B",
            "registeredAt": "2026-01-01T00:00:00Z"
        },
        "chunks": [{
            "id": "SYNC-EVIDENCE-1-C001", "documentId": "SYNC-EVIDENCE-1",
            "title": "同步测试", "sourceType": "指南", "publisher": "测试机构",
            "publicationYear": 2026, "disease": "测试病种", "pageStart": 0,
            "pageEnd": 0, "section": "正文", "evidenceLevel": "B",
            "content": "用于验证 Java 同步载荷中的等级可被 Python 正确接收。",
            "checksum": "fixture", "ingestedAt": "2026-01-01T00:00:00Z"
        }]
    }

    with TestClient(app) as client:
        response = client.post("/v1/knowledge/import", json=payload)

    assert response.status_code == 200
    assert captured["document"]["evidenceLevel"] == "B"
    assert captured["chunks"][0]["evidenceLevel"] == "B"


def test_hybrid_query_returns_traceable_citation() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={
                "question": "高血压患者没有症状也需要检查血压吗？",
                "version": "V2_HYBRID",
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["refused"] is False
    assert body["retrieval"]["strategy"] == "hybrid-rrf"
    assert any(item["chunkId"] == "WHO-HTN-001" for item in body["citations"])


def test_guarded_query_refuses_individual_prescription() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={
                "question": "给我一个具体患者每天服用多少克附子的处方",
                "version": "V3_GUARDED",
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["refused"] is True
    assert "个体化处方" in body["refusalReason"]


def test_guarded_query_accepts_directly_grounded_evidence() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={
                "question": "甘草有哪些严重不良反应？",
                "version": "V3_GUARDED",
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["refused"] is False
    assert body["retrieval"]["sufficientEvidence"] is True
    assert any(item["chunkId"] == "NCCIH-LICORICE-001" for item in body["citations"])


def test_chat_rejects_whitespace_only_question() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={"question": "   ", "version": "V2_HYBRID"},
        )

    assert response.status_code == 422


def test_query_applies_evidence_filter() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={
                "question": "高血压患者没有症状也需要检查血压吗？",
                "version": "V2_HYBRID",
                "evidenceLevel": "未评级",
            },
        )

    assert response.status_code == 200
    assert response.json()["citations"]
    assert all(item["evidenceLevel"] == "未评级" for item in response.json()["citations"])


@pytest.mark.parametrize("version", ["V0_DIRECT", "V1_VECTOR", "V2_HYBRID", "V3_GUARDED"])
def test_safety_applies_to_every_version(version) -> None:
    with TestClient(app) as client:
        for question in ("我每天服用多少克附子？", "胸痛持续不缓解，\n能不能给我一个具体处方？"):
            body = client.post("/v1/rag/chat", json={"question": question, "version": version}).json()
            assert body["refused"] is True
            assert body["citations"] == []


@pytest.mark.parametrize("version", ["V1_VECTOR", "V2_HYBRID", "V3_GUARDED"])
def test_unknown_question_has_no_citations(version) -> None:
    with TestClient(app) as client:
        for question in ("量子计算机的纠错编码是什么？", "天麻钩藤饮的组成和疗效证据是什么？"):
            body = client.post("/v1/rag/chat", json={"question": question, "version": version}).json()
            assert body["refused"] is True
            assert body["citations"] == []


def test_guarded_query_prioritizes_emergency_safety_boundary() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={
                "question": "胸痛持续不缓解，能不能给我一个具体处方？",
                "version": "V3_GUARDED",
            },
        )

    assert response.status_code == 200
    assert response.json()["refusalReason"] == "问题可能涉及急症，超出线上问答边界"
