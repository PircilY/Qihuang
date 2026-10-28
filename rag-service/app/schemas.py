from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
    )


class RagFilter(ApiModel):
    disease: str | None = None
    source_type: str | None = None
    evidence_level: str | None = None


class ChatRequest(ApiModel):
    question: str = Field(min_length=1, max_length=500)
    version: str
    disease: str | None = None
    source_type: str | None = None
    evidence_level: str | None = None
    extractive_only: bool = False
    corpus: str = "BASELINE"
    expected_revision: str | None = None

    @field_validator("question")
    @classmethod
    def question_must_contain_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("问题不能为空")
        return normalized

    def to_filter(self) -> RagFilter:
        return RagFilter(
            disease=self.disease,
            source_type=self.source_type,
            evidence_level=self.evidence_level,
        )


class CitationResponse(ApiModel):
    chunk_id: str
    document_id: str
    document_title: str
    source_type: str
    publisher: str
    publication_year: int
    disease: str
    syndromes: list[str] = Field(default_factory=list)
    formulas: list[str] = Field(default_factory=list)
    herbs: list[str] = Field(default_factory=list)
    section: str
    page: str
    evidence_level: str
    quote: str
    relevance_score: float
    metadata_source: str = ""

    @field_validator("syndromes", "formulas", "herbs", mode="before")
    @classmethod
    def empty_metadata_lists(cls, value):
        return [] if value is None else value


class RetrievalTrace(ApiModel):
    strategy: str
    lexical_candidate_count: int
    vector_candidate_count: int
    fused_candidate_count: int
    selected_count: int
    sufficient_evidence: bool
    notes: list[str] = Field(default_factory=list)
    engine: str = "llama-index"
    version: str | None = None
    threshold: float | None = None
    rejected: bool = False


class ChatResponse(ApiModel):
    request_id: str
    question: str
    version: str
    answer: str
    refused: bool
    refusal_reason: str | None = None
    confidence: float
    latency_ms: int
    citations: list[CitationResponse] = Field(default_factory=list)
    retrieval: RetrievalTrace
    generated_at: str


class KnowledgeDocument(ApiModel):
    id: str
    title: str
    source_type: str
    publisher: str
    publication_year: int
    authority: str = ""
    evidence_level: str = "未评级"
    disease_scope: list[str] = Field(default_factory=list)
    herbs: list[str] = Field(default_factory=list)
    formulas: list[str] = Field(default_factory=list)
    syndromes: list[str] = Field(default_factory=list)
    file_name: str = ""
    checksum: str = ""
    version: str = "v1"
    status: str = "ACTIVE"
    registered_at: str
    license_note: str = ""
    description: str = ""
    source_url: str = ""
    accessed_at: str | None = None
    metadata_source: str = ""

    @field_validator("herbs", "formulas", "syndromes", mode="before")
    @classmethod
    def empty_metadata_lists(cls, value):
        return [] if value is None else value


class KnowledgeChunk(ApiModel):
    id: str
    document_id: str
    title: str
    source_type: str
    publisher: str
    publication_year: int
    disease: str
    syndromes: list[str] = Field(default_factory=list)
    formulas: list[str] = Field(default_factory=list)
    herbs: list[str] = Field(default_factory=list)
    page_start: int
    page_end: int
    section: str
    evidence_level: str
    keywords: list[str] = Field(default_factory=list)
    content: str
    checksum: str
    status: str = "ACTIVE"
    version: str = "v1"
    ingested_at: str
    metadata_source: str = ""
    metadata_confidence: dict[str, list[str]] = Field(default_factory=dict)

    @field_validator("metadata_confidence", mode="before")
    @classmethod
    def empty_metadata_confidence(cls, value):
        return {} if value is None else value

    @field_validator("syndromes", "formulas", "herbs", mode="before")
    @classmethod
    def empty_metadata_lists(cls, value):
        return [] if value is None else value


class KnowledgeImportRequest(ApiModel):
    document: KnowledgeDocument
    chunks: list[KnowledgeChunk] = Field(min_length=1)
    corpus: str = "BASELINE"


class KnowledgeImportResponse(ApiModel):
    document_id: str
    imported_chunk_count: int
    total_chunk_count: int
    status: str


class KnowledgeImportBatchRequest(ApiModel):
    items: list[KnowledgeImportRequest] = Field(min_length=1)
    corpus: str = "BASELINE"


class KnowledgeInventoryItem(ApiModel):
    document_id: str
    checksum: str


class HealthResponse(ApiModel):
    status: str
    engine: str
    chunk_count: int
    document_count: int
    llm_configured: bool
    corpora: dict[str, dict[str, int | bool]] = Field(default_factory=dict)
    knowledge_revision: str = ""
    documents: list[KnowledgeInventoryItem] = Field(default_factory=list)
    index_state: str = "BUILDING"
    embedding_backend: str = "hash"
    embedding_model: str = "hash-sha256-bigram-v1"
    index_build_ms: dict[str, float] = Field(default_factory=dict)
    index_error: str | None = None
    safety_rules_sha256: str


class EngineInfoResponse(ApiModel):
    engine: str
    data_path: str
    candidate_limit: int
    final_limit: int
    minimum_evidence_score: float
    llm_configured: bool
    supported_versions: list[str]
    metadata: dict[str, Any]
