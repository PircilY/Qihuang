from pathlib import Path

import pytest

from app.knowledge_repository import KnowledgeRepository


def _chunk(document_id: str, chunk_id: str, content: str = "内容") -> dict:
    return {
        "id": chunk_id,
        "documentId": document_id,
        "title": "测试文档",
        "sourceType": "指南",
        "publisher": "测试机构",
        "publicationYear": 2024,
        "disease": "高血压",
        "pageStart": 1,
        "pageEnd": 1,
        "section": "正文",
        "evidenceLevel": "A",
        "content": content,
        "ingestedAt": "2024-01-01T00:00:00Z",
    }


def test_upsert_replaces_stale_chunks_atomically() -> None:
    repository = KnowledgeRepository(Path("unused.json"))

    repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-1"), _chunk("DOC-1", "C-2")])
    repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-3", "新内容")])

    assert repository.chunk_count() == 1
    assert [node.node_id for node in repository.nodes()] == ["C-3"]


def test_upsert_rejects_mismatched_chunk_without_mutation() -> None:
    repository = KnowledgeRepository(Path("unused.json"))
    repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-1")])

    with pytest.raises(ValueError, match="does not belong"):
        repository.upsert({"id": "DOC-1"}, [_chunk("DOC-2", "C-2")])

    assert repository.chunk_count() == 1
    assert repository.nodes()[0].node_id == "C-1"
