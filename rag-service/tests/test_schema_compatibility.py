from app.schemas import CitationResponse, KnowledgeChunk, KnowledgeDocument


def test_old_schema_defaults_new_metadata_lists() -> None:
    document = KnowledgeDocument.model_validate({
        "id": "DOC-OLD", "title": "旧格式", "sourceType": "指南",
        "publisher": "机构", "publicationYear": 2024, "registeredAt": "2024-01-01T00:00:00Z",
    })
    chunk = KnowledgeChunk.model_validate({
        "id": "C-OLD", "documentId": "DOC-OLD", "title": "旧格式", "sourceType": "指南",
        "publisher": "机构", "publicationYear": 2024, "disease": "", "pageStart": 0,
        "pageEnd": 0, "section": "正文", "evidenceLevel": "未评级", "content": "内容",
        "checksum": "x", "ingestedAt": "2024-01-01T00:00:00Z",
    })
    assert document.herbs == [] and document.formulas == [] and document.syndromes == []
    assert chunk.herbs == [] and chunk.formulas == [] and chunk.syndromes == []


def test_new_schema_preserves_metadata_and_ignores_unknown_compatibility_fields() -> None:
    document = KnowledgeDocument.model_validate({
        "id": "DOC-NEW", "title": "新格式", "sourceType": "指南", "publisher": "机构",
        "publicationYear": 2024, "registeredAt": "2024-01-01T00:00:00Z",
        "herbs": ["黄芪"], "formulas": ["补阳还五汤"], "syndromes": ["气虚"],
        "futureField": "ignored",
    })
    assert document.herbs == ["黄芪"]
    assert document.formulas == ["补阳还五汤"]
    assert document.syndromes == ["气虚"]


def test_explicit_null_metadata_lists_default_to_empty_for_import_and_citation_models() -> None:
    document = KnowledgeDocument.model_validate({
        "id": "DOC-NULL", "title": "空元数据", "sourceType": "指南", "publisher": "机构",
        "publicationYear": 2024, "registeredAt": "2024-01-01T00:00:00Z",
        "herbs": None, "formulas": None, "syndromes": None,
    })
    chunk = KnowledgeChunk.model_validate({
        "id": "C-NULL", "documentId": "DOC-NULL", "title": "空元数据", "sourceType": "指南",
        "publisher": "机构", "publicationYear": 2024, "disease": "", "pageStart": 0,
        "pageEnd": 0, "section": "正文", "evidenceLevel": "未评级", "content": "内容",
        "checksum": "x", "ingestedAt": "2024-01-01T00:00:00Z",
        "herbs": None, "formulas": None, "syndromes": None,
    })
    assert document.herbs == document.formulas == document.syndromes == []
    assert chunk.herbs == chunk.formulas == chunk.syndromes == []

    citation = CitationResponse.model_validate({
        "chunkId": "C-NULL", "documentId": "DOC-NULL", "documentTitle": "空元数据",
        "sourceType": "指南", "publisher": "机构", "publicationYear": 2024, "disease": "",
        "section": "正文", "page": "1", "evidenceLevel": "未评级", "quote": "内容",
        "relevanceScore": 1.0, "herbs": None, "formulas": None, "syndromes": None,
    })
    assert citation.herbs == citation.formulas == citation.syndromes == []
