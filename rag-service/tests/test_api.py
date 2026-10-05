from fastapi.testclient import TestClient

from app.main import app
import pytest


def test_health_exposes_llamaindex_engine() -> None:
    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["engine"] == "llama-index"
    assert body["chunkCount"] > 0


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
        for question in ("每天服用多少克附子？", "胸痛持续不缓解，\n能不能给我一个具体处方？"):
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
