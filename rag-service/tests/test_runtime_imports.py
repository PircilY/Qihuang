"""[FIXTURE] Runtime synchronization placeholders; no medical facts."""
import hashlib
import json
from pathlib import Path
import pytest
from app.knowledge_repository import KnowledgeRepository


def repository(tmp_path):
    path = tmp_path / "knowledge-base.json"
    path.write_text("[]", encoding="utf-8")
    repo = KnowledgeRepository(path)
    repo.load()
    return repo


def item(number, text="[FIXTURE] 非医学事实 占位正文"):
    doc = {"id": f"FIXTURE-{number}", "checksum": f"fixture-hash-{number}"}
    chunk = {"id": f"FIXTURE-{number}-C1", "documentId": doc["id"],
             "title": "[FIXTURE] 非医学事实", "content": text}
    return doc, [chunk]


def test_empty_revision_excludes_seed_and_expanded(tmp_path):
    doc, chunks = item(1)
    path = tmp_path / "knowledge-base.json"
    path.write_text(json.dumps(chunks), encoding="utf-8")
    repo = KnowledgeRepository(path, tmp_path / "expanded" / "knowledge-base.json")
    repo.load()
    assert repo.knowledge_revision() == hashlib.sha256(b"").hexdigest()
    assert repo.knowledge_revision("EXPANDED") == ""
    assert repo.document_inventory() == []
    assert not repo.upsert(doc, chunks)
    chunks[0]["content"] = "[FIXTURE] 非医学事实 不同占位内容"
    with pytest.raises(ValueError, match="read-only"):
        repo.upsert(doc, chunks)


def test_batch_capacity_boundary_and_atomic_failure(tmp_path):
    repo = repository(tmp_path)
    one = item(1)
    characters = len(one[1][0]["content"])
    assert repo.upsert_batch([one], 1, characters)
    revision = repo.knowledge_revision()
    with pytest.raises(ValueError, match="capacity"):
        repo.upsert_batch([item(2)], 1, characters)
    assert repo.knowledge_revision() == revision
    assert repo.document_count() == 1
    assert not repo.upsert_batch([one], 1, characters)


def test_invalid_second_item_does_not_publish_first(tmp_path):
    repo = repository(tmp_path)
    broken = item(2)
    broken[1][0]["documentId"] = "WRONG"
    with pytest.raises(ValueError):
        repo.upsert_batch([item(1), broken], 5, 1000)
    assert repo.nodes() == []


def test_checksum_only_change_updates_revision(tmp_path):
    repo = repository(tmp_path)
    one = item(1)
    repo.upsert_batch([one], 5, 1000)
    before = repo.knowledge_revision()
    one[0]["checksum"] = "updated"
    assert repo.upsert_batch([one], 5, 1000)
    assert repo.knowledge_revision() != before


def test_import_rate_limit_returns_429(monkeypatch):
    from dataclasses import replace
    from fastapi.testclient import TestClient
    from app import main
    monkeypatch.setattr(main, "settings", replace(main.settings, import_requests_per_minute=1))
    monkeypatch.setattr(main.engine, "start_warmup", lambda: None)
    main._import_windows.clear()
    with TestClient(main.app) as client:
        first = client.post("/v1/knowledge/import", json={})
        second = client.post("/v1/knowledge/import", json={})
    assert first.status_code == 422
    assert second.status_code == 429
    assert second.headers["Retry-After"] == "60"
    main._import_windows.clear()
