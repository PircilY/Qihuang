from __future__ import annotations

import logging
import re
import threading
import time
import uuid
from datetime import UTC, datetime
from typing import Any

from llama_index.core import VectorStoreIndex
from llama_index.core.llms import MockLLM
from llama_index.core.retrievers import QueryFusionRetriever
from llama_index.core.schema import NodeWithScore
from llama_index.core.vector_stores import FilterOperator, MetadataFilter, MetadataFilters
from llama_index.retrievers.bm25 import BM25Retriever

from app.config import Settings
from app.embeddings import HashEmbedding
from app.evidence_coverage import (
    DOSE_REFUSAL,
    assess_evidence_coverage,
)
from app.safety_rules import RULES_SHA256, is_emergency, is_individualized_request
from app.knowledge_repository import KnowledgeRepository
from app.schemas import (
    ChatRequest,
    ChatResponse,
    CitationResponse,
    EngineInfoResponse,
    HealthResponse,
    KnowledgeImportResponse,
    RagFilter,
    RetrievalTrace,
)
from app.tokenization import tokenize


_CJK_BM25_TOKEN_PATTERN = r"(?u)[a-z0-9][a-z0-9._-]*|[\u3400-\u4dbf\u4e00-\u9fff]"


class KnowledgeOutOfSync(Exception):
    def __init__(self, revision: str, documents: list[dict[str, str]]) -> None:
        super().__init__("knowledge revision mismatch")
        self.revision = revision
        self.documents = documents


class LlamaIndexEngine:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.repository = KnowledgeRepository(settings.data_path, settings.expanded_data_path)
        self.embedding = HashEmbedding()
        self._nodes: dict[str, list[Any]] = {}
        self._vector_indexes: dict[str, VectorStoreIndex | None] = {}
        self._bm25_retrievers: dict[str, BM25Retriever | None] = {}
        self._llm = self._create_llm()
        self._lock = threading.RLock()
        self._index_dirty: set[str] = {"BASELINE", "EXPANDED"}

    def reload(self) -> None:
        with self._lock:
            self.repository.load()
            self._index_dirty = {"BASELINE", "EXPANDED"}
            for corpus in ("BASELINE", "EXPANDED"):
                self._rebuild_indexes(corpus)
            self._index_dirty.clear()

    def ingest(self, document: dict, chunks: list[dict], corpus: str | None = None,
               rebuild: bool = True) -> KnowledgeImportResponse:
        with self._lock:
            if (corpus or "BASELINE").strip().upper() != "BASELINE":
                raise ValueError("expanded corpus is read-only")
            changed = self.repository.upsert_batch([(document, chunks)], self.settings.max_import_documents, self.settings.max_import_characters)
            if changed and rebuild:
                self._index_dirty.add("BASELINE")
                self._rebuild_indexes("BASELINE")
                self._index_dirty.discard("BASELINE")
            return KnowledgeImportResponse(
                document_id=document["id"],
                imported_chunk_count=len(chunks),
                total_chunk_count=self.repository.chunk_count("BASELINE"),
                status="READY",
            )

    def ingest_batch(self, items: list[tuple[dict, list[dict]]], corpus: str | None = None) -> KnowledgeImportResponse:
        with self._lock:
            if len(items) > self.settings.sync_batch_size:
                raise ValueError(f"import batch exceeds limit {self.settings.sync_batch_size}")
            if (corpus or "BASELINE").strip().upper() != "BASELINE":
                raise ValueError("runtime imports require BASELINE")
            if not items:
                raise ValueError("import batch must not be empty")
            changed = self.repository.upsert_batch(items, self.settings.max_import_documents,
                                                   self.settings.max_import_characters)
            if changed:
                self._index_dirty.add("BASELINE")
                self._rebuild_indexes("BASELINE")
                self._index_dirty.discard("BASELINE")
            return KnowledgeImportResponse(document_id=items[-1][0]["id"],
                imported_chunk_count=sum(len(chunks) for _, chunks in items),
                total_chunk_count=self.repository.chunk_count("BASELINE"), status="READY")

    def health(self) -> HealthResponse:
        with self._lock:
            counts: dict[str, dict[str, int | bool]] = {}
            for corpus in ("BASELINE", "EXPANDED"):
                chunk_count = self.repository.chunk_count(corpus)
                counts[corpus] = {"documentCount": self.repository.document_count(corpus), "chunkCount": chunk_count, "available": chunk_count > 0}
            return HealthResponse(
                status="UP", engine="llama-index",
                chunk_count=self.repository.chunk_count("BASELINE"),
                document_count=self.repository.document_count("BASELINE"),
                llm_configured=self._llm is not None, corpora=counts,
                knowledge_revision=self.repository.knowledge_revision("BASELINE"),
                documents=self.repository.document_inventory("BASELINE"),
                safety_rules_sha256=RULES_SHA256,
            )

    def info(self) -> EngineInfoResponse:
        return EngineInfoResponse(
            engine="llama-index",
            data_path=str(self.settings.data_path),
            candidate_limit=self.settings.candidate_limit,
            final_limit=self.settings.final_limit,
            minimum_evidence_score=self.settings.minimum_evidence_score,
            llm_configured=self._llm is not None,
            supported_versions=[
                "V0_DIRECT",
                "V1_VECTOR",
                "V2_HYBRID",
                "V3_GUARDED",
            ],
            metadata={
                "embedding": "deterministic-hash-ngram",
                "lexicalRetriever": "BM25",
                "fusion": "reciprocal-rank-fusion",
                "generation": "grounded-extractive" if self._llm is None else "llm",
            },
        )

    def query(self, request: ChatRequest) -> ChatResponse:
        with self._lock:
            corpus = (request.corpus or "BASELINE").strip().upper()
            if corpus in self._index_dirty:
                self._rebuild_indexes(corpus)
                self._index_dirty.discard(corpus)
            return self._query(request)

    def _query(self, request: ChatRequest) -> ChatResponse:
        started_at = time.perf_counter()
        corpus = (request.corpus or "BASELINE").strip().upper()
        if corpus not in {"BASELINE", "EXPANDED"}:
            raise ValueError(f"unsupported corpus: {request.corpus}")
        if request.expected_revision is not None:
            actual_revision = self.repository.knowledge_revision(corpus)
            if request.expected_revision != actual_revision:
                raise KnowledgeOutOfSync(
                    actual_revision,
                    self.repository.document_inventory(corpus),
                )
        version = _normalize_version(request.version)
        question = request.question.strip()

        if is_emergency(question):
            return self._refusal(
                request, version, "问题可能涉及急症，超出线上问答边界",
                "问题可能涉及急症，请立即拨打 120 / 前往急诊。",
                [], [], [], [], False, started_at,
            )
        if is_individualized_request(question):
            return self._refusal(
                request, version, DOSE_REFUSAL,
                "不能根据在线资料调整当前用药剂量，请联系开药医生并由医师判断。",
                [], [], [], [], False, started_at,
            )
        if version == "V0_DIRECT":
            return self._response(
                request=request,
                version=version,
                answer=(
                    "当前版本没有检索知识库，不能为这个问题提供可追溯的专科证据。"
                    "作为基线，仅提示中医心血管诊疗需要结合四诊信息并由专业医师判断。"
                ),
                refused=False,
                refusal_reason=None,
                confidence=0.35,
                citations=[],
                retrieval=RetrievalTrace(
                    strategy="direct",
                    lexical_candidate_count=0,
                    vector_candidate_count=0,
                    fused_candidate_count=0,
                    selected_count=0,
                    sufficient_evidence=False,
                    notes=["engine=llama-index", "generation=none", "knowledge base bypassed"],
                    engine="llama-index",
                    version=version,
                    threshold=None,
                    rejected=False,
                ),
                started_at=started_at,
            )

        filters = request.to_filter()
        vector_nodes = self._retrieve_vector(question, filters, corpus)
        lexical_nodes = self._retrieve_lexical(question, filters, corpus) if version != "V1_VECTOR" else []
        if version == "V3_GUARDED":
            vector_nodes = [node for node in vector_nodes if _is_related(question, node)]
            lexical_nodes = [node for node in lexical_nodes if _is_related(question, node)]
            fused_nodes = self._fuse_ranked_candidates(vector_nodes, lexical_nodes)
        elif version == "V1_VECTOR":
            vector_nodes = [node for node in vector_nodes if _is_related(question, node)]
            fused_nodes = vector_nodes
        else:
            fused_nodes = [
                node for node in self._retrieve_hybrid(question, filters, corpus)
                if _is_related(question, node)
            ]
        selected = self._select_nodes(
            fused_nodes,
            filters,
            question if version == "V3_GUARDED" else None,
        )
        top_score = selected[0].score or 0.0 if selected else 0.0
        sufficient = bool(selected) and top_score >= self.settings.minimum_evidence_score
        coverage = None
        if version == "V3_GUARDED" and sufficient:
            coverage = assess_evidence_coverage(
                question, [node.node.text for node in selected]
            )
            if not coverage.covered:
                selected = []
                sufficient = False

        if not selected or (version == "V3_GUARDED" and not sufficient):
            reason = (
                coverage.refusal_reason
                if coverage is not None and not coverage.covered
                else "未检索到达到阈值的相关证据"
            )
            answer = (
                coverage.refusal_reason
                if coverage is not None and not coverage.covered
                else "当前知识库中没有找到足以支持回答的证据。系统已停止生成具体诊疗建议。"
            )
            return self._refusal(
                request,
                version,
                reason,
                answer,
                [],
                vector_nodes,
                lexical_nodes,
                fused_nodes,
                sufficient,
                started_at,
            )

        citations = self._citations(selected)
        answer, generation = self._grounded_answer(question, selected, citations, request.extractive_only)
        confidence = (
            min(0.96, 0.65 + top_score * 1.4)
            if version == "V3_GUARDED"
            else min(0.88, 0.48 + top_score * 1.2)
        )
        return self._response(
            request=request,
            version=version,
            answer=answer,
            refused=False,
            refusal_reason=None,
            confidence=round(confidence, 3),
            citations=citations,
            retrieval=self._trace(
                version,
                vector_nodes,
                lexical_nodes,
                fused_nodes,
                selected,
                sufficient,
                remote=False,
                generation=generation,
            ),
            started_at=started_at,
        )

    def _rebuild_indexes(self, corpus: str) -> None:
        nodes = [node for node in self.repository.nodes(corpus)
                       if node.metadata.get("status") == "ACTIVE"]
        self._nodes[corpus] = nodes
        if not nodes:
            self._vector_indexes[corpus] = None
            self._bm25_retrievers[corpus] = None
            return
        self._vector_indexes[corpus] = VectorStoreIndex(
            nodes=nodes,
            embed_model=self.embedding,
            show_progress=False,
        )
        self._bm25_retrievers[corpus] = BM25Retriever.from_defaults(
            nodes=nodes,
            similarity_top_k=self.settings.candidate_limit,
            token_pattern=_CJK_BM25_TOKEN_PATTERN,
            skip_stemming=True,
        )

    def _retrieve_vector(self, question: str, filters: RagFilter, corpus: str) -> list[NodeWithScore]:
        index = self._vector_indexes.get(corpus)
        if index is None:
            return []
        retriever = index.as_retriever(
            similarity_top_k=self.settings.candidate_limit,
            filters=self._metadata_filters(filters, corpus),
        )
        return list(retriever.retrieve(question))

    def _retrieve_lexical(self, question: str, filters: RagFilter, corpus: str) -> list[NodeWithScore]:
        retriever = self._scoped_lexical_retriever(filters, corpus)
        if retriever is None:
            return []
        nodes = list(retriever.retrieve(question))
        return [node for node in nodes if _matches_filter(node, filters)]

    def _scoped_lexical_retriever(self, filters: RagFilter, corpus: str) -> BM25Retriever | None:
        if not any((filters.disease, filters.source_type, filters.evidence_level)):
            return self._bm25_retrievers.get(corpus)
        nodes = [node for node in self._nodes.get(corpus, []) if _matches_filter(node, filters)]
        if not nodes:
            return None
        return BM25Retriever.from_defaults(
            nodes=nodes,
            similarity_top_k=min(self.settings.candidate_limit, len(nodes)),
            token_pattern=_CJK_BM25_TOKEN_PATTERN,
            skip_stemming=True,
        )

    def _retrieve_hybrid(self, question: str, filters: RagFilter, corpus: str) -> list[NodeWithScore]:
        lexical_retriever = self._scoped_lexical_retriever(filters, corpus)
        index = self._vector_indexes.get(corpus)
        if index is None or lexical_retriever is None:
            return []
        vector_retriever = index.as_retriever(
            similarity_top_k=self.settings.candidate_limit,
            filters=self._metadata_filters(filters, corpus),
        )
        fused = QueryFusionRetriever(
            retrievers=[vector_retriever, lexical_retriever],
            similarity_top_k=self.settings.candidate_limit,
            num_queries=1,
            mode="reciprocal_rerank",
            llm=MockLLM(max_tokens=32),
            use_async=False,
            verbose=False,
        )
        return [
            node
            for node in fused.retrieve(question)
            if _matches_filter(node, filters)
        ]

    def _select_nodes(
        self,
        nodes: list[NodeWithScore],
        filters: RagFilter,
        metadata_question: str | None = None,
    ) -> list[NodeWithScore]:
        best_by_id: dict[str, NodeWithScore] = {}
        for node in nodes:
            if not _matches_filter(node, filters):
                continue
            node_id = node.node.node_id
            current = best_by_id.get(node_id)
            score = (node.score or 0.0)
            if metadata_question is not None:
                score += _metadata_boost(metadata_question, node)
            ranked_node = NodeWithScore(node=node.node, score=score)
            if current is None or score > (current.score or 0.0):
                best_by_id[node_id] = ranked_node
        ranked = sorted(
            best_by_id.values(),
            key=lambda item: (-(item.score or 0.0), item.node.node_id),
        )
        return ranked[: self.settings.final_limit]

    def _fuse_ranked_candidates(
        self,
        vector_nodes: list[NodeWithScore],
        lexical_nodes: list[NodeWithScore],
    ) -> list[NodeWithScore]:
        scores: dict[str, tuple[Any, float]] = {}
        for nodes, weight in (
            (lexical_nodes, self.settings.lexical_weight),
            (vector_nodes, self.settings.vector_weight),
        ):
            seen_in_channel: set[str] = set()
            for rank, candidate in enumerate(nodes, start=1):
                chunk_id = candidate.node.node_id
                if chunk_id in seen_in_channel:
                    continue
                seen_in_channel.add(chunk_id)
                contribution = weight / (self.settings.rrf_rank_constant + rank)
                current = scores.get(chunk_id)
                scores[chunk_id] = (
                    candidate.node,
                    contribution + (current[1] if current else 0.0),
                )
        return [
            NodeWithScore(node=node, score=score)
            for _, (node, score) in sorted(
                scores.items(), key=lambda item: (-item[1][1], item[0])
            )
        ]

    def _metadata_filters(self, filters: RagFilter, corpus: str) -> MetadataFilters | None:
        items: list[MetadataFilter] = []
        for key in ("disease", "source_type", "evidence_level"):
            requested = getattr(filters, key)
            if requested:
                values = sorted({
                    str(node.metadata.get(key, "")) for node in self._nodes.get(corpus, [])
                    if requested.casefold() in str(node.metadata.get(key, "")).casefold()
                })
                items.append(MetadataFilter(key=key, value=values, operator=FilterOperator.IN))
        return MetadataFilters(filters=items) if items else None

    def _citations(self, nodes: list[NodeWithScore]) -> list[CitationResponse]:
        return [
            CitationResponse(
                chunk_id=str(node.metadata.get("chunk_id", node.node_id)),
                document_id=str(node.metadata.get("document_id", "")),
                document_title=str(node.metadata.get("document_title", "")),
                source_type=str(node.metadata.get("source_type", "")),
                publisher=str(node.metadata.get("publisher", "")),
                publication_year=int(node.metadata.get("publication_year") or 0),
                disease=str(node.metadata.get("disease", "")),
                section=str(node.metadata.get("section", "")),
                page=_page_label(
                    int(node.metadata.get("page_start") or 0),
                    int(node.metadata.get("page_end") or 0),
                ),
                evidence_level=str(node.metadata.get("evidence_level", "")),
                quote=node.node.text,
                relevance_score=round(float(node.score or 0.0), 3),
            )
            for node in nodes
        ]

    def _grounded_answer(
        self,
        question: str,
        nodes: list[NodeWithScore],
        citations: list[CitationResponse],
        extractive_only: bool = False,
    ) -> tuple[str, str]:
        if self._llm is not None and not extractive_only:
            context = "\n\n".join(
                f"[{index}] {citation.quote}"
                for index, citation in enumerate(citations, start=1)
            )
            prompt = (
                "你是中医药心血管文献检索助手。只能依据给定证据回答，"
                "不得生成个体化诊断或处方。回答中使用 [1] 形式引用来源。\n\n"
                f"问题：{question}\n\n证据：\n{context}"
            )
            try:
                generated = self._llm.complete(prompt).text.strip()
                cited = [int(value) for value in re.findall(r"\[(\d+)\]", generated)]
                if generated and cited and all(1 <= index <= len(citations) for index in cited):
                    return (
                        generated + "\n\n以上内容用于专科文献检索参考，不替代医生诊断和处方。",
                        "llm",
                    )
            except Exception:
                logging.getLogger(__name__).warning("LLM unavailable; using extractive answer")

        statements = []
        for index, node in enumerate(nodes, start=1):
            statements.append(f"{index}. {_first_sentence(node.node.text)} [{index}]")
        return (
            "相关文献摘录：\n"
            + "\n".join(statements)
            + "\n\n以上内容用于专科文献检索参考，不替代医生诊断和处方。",
            "grounded-extractive",
        )

    def _refusal(
        self,
        request: ChatRequest,
        version: str,
        reason: str,
        answer: str,
        selected: list[NodeWithScore],
        vector_nodes: list[NodeWithScore],
        lexical_nodes: list[NodeWithScore],
        fused_nodes: list[NodeWithScore],
        sufficient: bool,
        started_at: float,
    ) -> ChatResponse:
        citations = self._citations(selected[:2])
        return self._response(
            request=request,
            version=version,
            answer=answer,
            refused=True,
            refusal_reason=reason,
            confidence=0.72,
            citations=citations,
            retrieval=self._trace(
                version,
                vector_nodes,
                lexical_nodes,
                fused_nodes,
                selected,
                sufficient,
                remote=False,
                generation="none",
            ),
            started_at=started_at,
        )

    def _response(
        self,
        request: ChatRequest,
        version: str,
        answer: str,
        refused: bool,
        refusal_reason: str | None,
        confidence: float,
        citations: list[CitationResponse],
        retrieval: RetrievalTrace,
        started_at: float,
    ) -> ChatResponse:
        return ChatResponse(
            request_id=str(uuid.uuid4()),
            question=request.question.strip(),
            version=version,
            answer=answer,
            refused=refused,
            refusal_reason=refusal_reason,
            confidence=confidence,
            latency_ms=max(1, int((time.perf_counter() - started_at) * 1000)),
            citations=citations,
            retrieval=retrieval,
            generated_at=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        )

    def _trace(
        self,
        version: str,
        vector_nodes: list[NodeWithScore],
        lexical_nodes: list[NodeWithScore],
        fused_nodes: list[NodeWithScore],
        selected: list[NodeWithScore],
        sufficient: bool,
        remote: bool,
        generation: str | None = None,
    ) -> RetrievalTrace:
        strategy = {
            "V1_VECTOR": "vector",
            "V2_HYBRID": "hybrid-rrf",
            "V3_GUARDED": "guarded-hybrid",
        }.get(version, "direct")
        notes = [
            "engine=llama-index",
            "fusion=reciprocal-rank-fusion",
            "embedding=deterministic-hash-ngram",
            f"generation={generation or ('llm' if self._llm is not None else 'grounded-extractive')}",
        ]
        if remote:
            notes.append("transport=remote")
        if version == "V3_GUARDED":
            notes.extend(
                [
                    "fusion=weighted-rrf",
                    "deduplication=chunk-id",
                    "ranking=rrf+metadata",
                    "gate=post-ranking-score+question-evidence-coverage",
                ]
            )
        return RetrievalTrace(
            strategy=strategy,
            lexical_candidate_count=len(lexical_nodes),
            vector_candidate_count=len(vector_nodes),
            fused_candidate_count=len(fused_nodes),
            selected_count=len(selected),
            sufficient_evidence=sufficient,
            notes=notes,
            engine="llama-index",
            version=version,
            threshold=self.settings.minimum_evidence_score if version == "V3_GUARDED" else None,
            rejected=not bool(selected),
        )

    def _create_llm(self):
        if not (self.settings.llm_model and self.settings.llm_api_base):
            return None
        try:
            from llama_index.llms.openai_like import OpenAILike

            return OpenAILike(
                model=self.settings.llm_model,
                api_base=self.settings.llm_api_base,
                api_key=self.settings.llm_api_key or "local",
                is_chat_model=True,
                context_window=32768,
                timeout=60,
            )
        except ImportError:
            logging.getLogger(__name__).warning("Optional LLM dependency missing; using extractive answers")
            return None


def _normalize_version(value: str) -> str:
    normalized = (value or "").strip().upper()
    if normalized in {"V0", "V0_DIRECT", "DIRECT"}:
        return "V0_DIRECT"
    if normalized in {"V1", "V1_VECTOR", "VECTOR"}:
        return "V1_VECTOR"
    if normalized in {"V2", "V2_HYBRID", "HYBRID"}:
        return "V2_HYBRID"
    if normalized in {"V3", "V3_GUARDED", "GUARDED"}:
        return "V3_GUARDED"
    raise ValueError(f"Unsupported version: {value}")


def _matches_filter(node: NodeWithScore, filters: RagFilter) -> bool:
    if node.metadata.get("status") != "ACTIVE":
        return False
    if filters.disease and filters.disease.casefold() not in str(
        node.metadata.get("disease", "")
    ).casefold():
        return False
    if filters.source_type and filters.source_type.casefold() not in str(
        node.metadata.get("source_type", "")
    ).casefold():
        return False
    if filters.evidence_level and filters.evidence_level.casefold() not in str(
        node.metadata.get("evidence_level", "")
    ).casefold():
        return False
    return True


def _is_related(question: str, node: NodeWithScore) -> bool:
    generic = {"证据", "研究", "安全", "疗效", "检查", "症状", "未归类"}
    question = question.casefold()
    topics = [str(node.metadata.get("disease", ""))]
    for key in ("keywords", "herbs", "formulas", "syndromes"):
        topics.extend(str(node.metadata.get(key, "")).split("|"))
    if any(len(term) >= 2 and term not in generic and term.casefold() in question
           for term in topics):
        return True
    disease_tokens = set(tokenize(str(node.metadata.get("disease", ""))))
    if any(
        len(term) >= 2 and term not in generic
        for term in set(tokenize(question)) & disease_tokens
    ):
        return True
    shared = set(tokenize(question)) & set(tokenize(node.node.text))
    return len({term for term in shared if len(term) >= 2 and term not in generic}) >= 3


def _metadata_boost(question: str, node: NodeWithScore) -> float:
    normalized_question = re.sub(r"\s+", "", question).casefold()

    def mentioned(values: str | list[str]) -> bool:
        items = values.split("|") if isinstance(values, str) else values
        return any(
            value and re.sub(r"\s+", "", str(value)).casefold() in normalized_question
            for value in items
        )

    metadata = node.metadata
    boost = 0.0
    boost += 0.22 if mentioned(str(metadata.get("disease", ""))) else 0.0
    boost += 0.20 if mentioned(str(metadata.get("syndromes", ""))) else 0.0
    boost += 0.18 if mentioned(str(metadata.get("formulas", ""))) else 0.0
    boost += 0.10 if mentioned(str(metadata.get("herbs", ""))) else 0.0
    boost += {"A": 0.12, "B": 0.08, "C": 0.04}.get(
        str(metadata.get("evidence_level", "")).strip().upper(), 0.0
    )
    return boost


def _page_label(page_start: int, page_end: int) -> str:
    if page_start <= 0:
        return "页码未标注"
    if page_start == page_end:
        return f"第 {page_start} 页"
    return f"第 {page_start}-{page_end} 页"


def _first_sentence(value: str) -> str:
    text = re.sub(r"\s+", " ", value).strip()
    stop = text.find("。")
    return text if stop < 0 else text[: stop + 1]
