from fastapi.testclient import TestClient

from app.main import app


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
                "question": "高血压肝阳上亢证常见治法是什么？",
                "version": "V2_HYBRID",
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["refused"] is False
    assert body["retrieval"]["strategy"] == "hybrid-rrf"
    assert any(item["chunkId"] == "HTN-GUIDE-001" for item in body["citations"])


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
                "question": "冠心病气虚血瘀证常用哪些方剂？",
                "version": "V3_GUARDED",
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["refused"] is False
    assert body["retrieval"]["sufficientEvidence"] is True
    assert any(item["chunkId"] == "CHD-GUIDE-001" for item in body["citations"])


def test_chat_rejects_whitespace_only_question() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={"question": "   ", "version": "V2_HYBRID"},
        )

    assert response.status_code == 422


def test_filter_matching_is_case_insensitive() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/v1/rag/chat",
            json={
                "question": "高血压肝阳上亢证常见治法是什么？",
                "version": "V2_HYBRID",
                "evidenceLevel": "a",
            },
        )

    assert response.status_code == 200
    assert response.json()["citations"]


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
