from pathlib import Path
import json

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


def test_identical_upsert_does_not_require_reindexing() -> None:
    repository = KnowledgeRepository(Path("unused.json"))
    assert repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-1")]) is True
    assert repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-1")]) is False
    assert repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-1", "changed")]) is True


def test_imported_evidence_level_survives_into_retrieval_node_metadata() -> None:
    repository = KnowledgeRepository(Path("unused.json"))
    repository.upsert(
        {"id": "DOC-1"},
        [_chunk("DOC-1", "C-1", "该资料记录用于核对导入证据等级可以进入检索节点元数据。")],
    )

    node = repository.nodes()[0]
    assert node.metadata["evidence_level"] == "A"


def test_corpora_use_independent_storage() -> None:
    root = Path(__file__).resolve().parents[2]
    baseline = root / "backend/src/main/resources/data/knowledge-base.json"
    expanded = root / "backend/src/main/resources/data/expanded/knowledge-base.json"
    repository = KnowledgeRepository(baseline, expanded)
    repository.load()

    assert repository.chunk_count("BASELINE") == 11
    expected = json.loads(expanded.read_text(encoding="utf-8"))
    assert repository.chunk_count("EXPANDED") == len(expected)
    assert not {n.node_id for n in repository.nodes("BASELINE")} & {n.node_id for n in repository.nodes("EXPANDED")}
    assert repository.chunk_count("BASELINE") == 11


def test_combined_view_includes_seeds_expanded_and_imports(tmp_path: Path) -> None:
    baseline = tmp_path / "baseline.json"
    expanded = tmp_path / "expanded.json"
    baseline.write_text(json.dumps([_chunk("BASE", "B-1")]), encoding="utf-8")
    expanded.write_text(json.dumps([_chunk("EXP", "E-1")]), encoding="utf-8")
    repository = KnowledgeRepository(baseline, expanded)
    repository.load()

    assert [node.node_id for node in repository.nodes("COMBINED")] == ["B-1", "E-1"]
    assert repository.chunk_count("COMBINED") == repository.document_count("COMBINED") == 2
    before = repository.knowledge_revision("COMBINED")
    repository.upsert({"id": "IMPORT", "checksum": "import-hash"}, [_chunk("IMPORT", "I-1")])
    assert [node.node_id for node in repository.nodes("COMBINED")] == ["B-1", "I-1", "E-1"]
    assert repository.document_count("COMBINED") == 3
    assert repository.knowledge_revision("COMBINED") != before
    assert repository.knowledge_revision("COMBINED") == repository.knowledge_revision("BASELINE")
    assert repository.document_inventory("COMBINED") == repository.document_inventory("BASELINE")
    assert repository.document_inventory("COMBINED") == [{"documentId": "IMPORT", "checksum": "import-hash"}]
    assert repository.chunk_count("BASELINE") == 2
    assert repository.chunk_count("EXPANDED") == 1
    with pytest.raises(ValueError, match="expanded documents"):
        repository.upsert({"id": "EXP"}, [_chunk("EXP", "NEW")])
    with pytest.raises(ValueError, match="expanded corpus"):
        repository.upsert_batch([({"id": "SHADOW"}, [_chunk("SHADOW", "E-1")])], 10, 1000)
    assert repository.chunk_count("COMBINED") == 3


def test_combined_view_works_without_an_expanded_dataset() -> None:
    repository = KnowledgeRepository(Path("unused.json"))
    repository.upsert({"id": "DOC-1"}, [_chunk("DOC-1", "C-1")])
    assert repository.chunk_count("COMBINED") == 1
    assert repository.document_count("COMBINED") == 1
    assert repository.nodes("COMBINED") == repository.nodes("BASELINE")


def test_revision_uses_the_cross_language_canonical_vector() -> None:
    repository = KnowledgeRepository(Path("unused.json"))
    repository.upsert({"id": "DOC-B", "checksum": "def"}, [_chunk("DOC-B", "C-B")])
    repository.upsert({"id": "DOC-A", "checksum": "abc"}, [_chunk("DOC-A", "C-A")])
    assert repository.knowledge_revision() == (
        "679852034c6e66a9aceff2816972266392a6a651cd00a353ce8bd8ed21ddd6c0"
    )


def test_metadata_only_change_does_not_change_revision() -> None:
    repository = KnowledgeRepository(Path("unused.json"))
    chunk = _chunk("DOC-META", "C-META", "稳定校验内容")
    repository.upsert({"id": "DOC-META", "checksum": "stable"}, [chunk])
    before = repository.knowledge_revision()

    updated = {**chunk, "herbs": ["黄芪"], "formulas": ["补阳还五汤"], "syndromes": ["气虚"],
               "metadataSource": "词典匹配"}
    repository.upsert({"id": "DOC-META", "checksum": "stable", "herbs": ["黄芪"]}, [updated])

    assert repository.knowledge_revision() == before
