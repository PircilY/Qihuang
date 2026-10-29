"""[FIXTURE] Offline embedding and warmup tests; no medical facts."""
from dataclasses import replace
import json
import threading
import time
from unittest.mock import Mock
import pytest
from llama_index.core.schema import TextNode
from app.config import Settings
from app.embeddings import HashEmbedding, SentenceTransformerEmbedding
from app.engine import LlamaIndexEngine, IndexNotReady
from app.schemas import ChatRequest


def settings(tmp_path):
    path = tmp_path / "knowledge-base.json"
    path.write_text("[]", encoding="utf-8")
    return replace(Settings.from_env(), data_path=path, expanded_data_path=tmp_path / "expanded.json",
                   embedding_cache_path=tmp_path / "cache")


def test_optional_adapter_does_not_download_by_default(monkeypatch):
    import sys
    from types import SimpleNamespace
    model = Mock()
    model.encode.return_value.tolist.return_value = [1.0, 0.0]
    factory = Mock(return_value=model)
    monkeypatch.setitem(sys.modules, "sentence_transformers", SimpleNamespace(SentenceTransformer=factory))
    embedding = SentenceTransformerEmbedding("fixture-local-model")
    assert embedding.get_query_embedding("[FIXTURE]") == [1.0, 0.0]
    factory.assert_called_once_with("fixture-local-model", local_files_only=True, trust_remote_code=False, device="cpu")
    model.encode.assert_called_once_with("[FIXTURE]", normalize_embeddings=True)


def test_warmup_health_remains_responsive_and_failure_is_explicit(tmp_path, monkeypatch):
    engine = LlamaIndexEngine(settings(tmp_path))
    started = threading.Event()
    release = threading.Event()
    def blocked():
        started.set()
        release.wait(5)
        raise RuntimeError("fixture failure")
    monkeypatch.setattr(engine, "reload", blocked)
    engine.start_warmup()
    assert started.wait(1)
    begin = time.monotonic()
    assert engine.health().index_state == "BUILDING"
    assert time.monotonic() - begin < .5
    with pytest.raises(IndexNotReady) as failure:
        engine.query(ChatRequest(question="[FIXTURE] 占位", version="V3_GUARDED"))
    assert failure.value.state == "BUILDING"
    release.set()
    engine._warmup_thread.join(2)
    assert engine.health().index_state == "FAILED"
    assert engine.health().index_error


def test_disk_cache_reuses_embeddings_and_invalidates_model(tmp_path, monkeypatch):
    from app import engine as module
    monkeypatch.setattr(module, "create_embedding", lambda settings: HashEmbedding())
    engine = LlamaIndexEngine(replace(settings(tmp_path),
        embedding_backend="sentence-transformers", embedding_model="fixture-model-one"))
    engine._ensure_embedding()
    node = TextNode(id_="FIXTURE", text="[FIXTURE] 占位正文 非医学事实")
    first = engine._cached_embedding("EXPANDED", node)
    from unittest.mock import patch
    with patch.object(HashEmbedding, "get_text_embedding", side_effect=AssertionError("cache miss")):
        assert engine._cached_embedding("EXPANDED", node) == first
    engine.settings = replace(engine.settings, embedding_backend="sentence-transformers", embedding_model="fixture-model")
    replacement = HashEmbedding()
    monkeypatch.setattr(module, "create_embedding", lambda settings: replacement)
    engine._ensure_embedding()
    assert engine.embedding is replacement
    assert engine._embedding_key == ("sentence-transformers", "fixture-model")
    engine._cached_embedding("EXPANDED", node)
    assert len(list((tmp_path / "cache").glob("*.json"))) == 2


def test_index_cache_is_keyed_by_corpus_backend_model_and_content(tmp_path, monkeypatch):
    config = settings(tmp_path)
    chunk = {"id": "FIXTURE-C1", "documentId": "FIXTURE-DOC", "title": "[FIXTURE] 非医学事实",
             "content": "占位正文 占位字段 alpha"}
    config.data_path.write_text(json.dumps([chunk]), encoding="utf-8")
    engine = LlamaIndexEngine(config)
    engine.reload()
    before = engine._vector_indexes["BASELINE"]
    engine.reload()
    assert engine._vector_indexes["BASELINE"] is before
    monkeypatch.setattr(__import__("app.engine", fromlist=["create_embedding"]), "create_embedding", lambda settings: HashEmbedding())
    engine.settings = replace(config, embedding_backend="sentence-transformers", embedding_model="fixture-model")
    engine.query(ChatRequest(question="占位正文", version="V1_VECTOR"))
    assert engine._vector_indexes["BASELINE"] is not before
    assert ("BASELINE", "sentence-transformers", "fixture-model") in engine._index_cache


def test_api_reports_building_without_blocking(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main
    monkeypatch.setattr(main.engine, "start_warmup", lambda: None)
    monkeypatch.setattr(main.engine, "index_state", "BUILDING")
    with TestClient(main.app) as client:
        assert client.get("/health").json()["indexState"] == "BUILDING"
        response = client.post("/v1/query", json={"question": "[FIXTURE] 占位", "version": "V3_GUARDED"})
    assert response.status_code == 503
    assert response.json()["indexState"] == "BUILDING"
