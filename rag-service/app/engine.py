from __future__ import annotations

import logging
import hashlib
import json
import math
import os
from pathlib import Path
import re
import threading
import time
import uuid
from datetime import UTC, datetime
from typing import Any

from llama_index.core import VectorStoreIndex
from llama_index.core.schema import NodeWithScore
from llama_index.core.vector_stores import FilterOperator, MetadataFilter, MetadataFilters
from llama_index.retrievers.bm25 import BM25Retriever

from app.config import Settings
from app.embeddings import HashEmbedding, create_embedding
from app.lexical_index import LexicalIndex, metadata_values, searchable
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
from app.terminology import expanded_query, is_generic, matches as terminology_matches, query_features


_CJK_BM25_TOKEN_PATTERN = r"(?u)[a-z0-9][a-z0-9._-]*|[\u3400-\u4dbf\u4e00-\u9fff]"


class IndexNotReady(Exception):
    def __init__(self, state):
        self.state = state


class KnowledgeOutOfSync(Exception):
    def __init__(self, revision: str, documents: list[dict[str, str]]) -> None:
        super().__init__("knowledge revision mismatch")
        self.revision = revision
        self.documents = documents


class LlamaIndexEngine:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.repository = KnowledgeRepository(settings.data_path, settings.expanded_data_path)
        self.embedding = None
        self.index_state = "BUILDING"
        self.index_error = None
        self.index_build_ms = {}
        self._embedding_key = None
        self._index_cache = {}
        self._warmup_thread = None
        self._nodes: dict[str, list[Any]] = {}
        self._vector_indexes: dict[str, VectorStoreIndex | None] = {}
        self._lexical_indexes: dict[str, LexicalIndex] = {}
        self._bm25_retrievers: dict[str, BM25Retriever | None] = {}
        self._lock = threading.RLock()
        self._index_dirty: set[str] = {"BASELINE", "EXPANDED", "COMBINED"}

    def start_warmup(self):
        if self._warmup_thread and self._warmup_thread.is_alive():
            return
        self.index_state = "BUILDING"
        def work():
            try:
                self.reload()
            except Exception:
                self.index_state = "FAILED"
                self.index_error = "Index build failed; verify optional dependency/local model and data files"
                logging.getLogger(__name__).exception("Index warmup failed")
        self._warmup_thread = threading.Thread(target=work, daemon=True, name="rag-index-warmup")
        self._warmup_thread.start()

    def reload(self) -> None:
        self.index_state = "BUILDING"
        with self._lock:
            self._ensure_embedding()
            self.repository.load()
            self._index_dirty = {"BASELINE", "EXPANDED", "COMBINED"}
            for corpus in ("BASELINE", "EXPANDED"):
                self._rebuild_indexes(corpus)
                self._index_dirty.discard(corpus)
            self.index_error = None
            self.index_state = "READY"

    def ingest(self, document: dict, chunks: list[dict], corpus: str | None = None,
               rebuild: bool = True) -> KnowledgeImportResponse:
        with self._lock:
            if (corpus or "BASELINE").strip().upper() != "BASELINE":
                raise ValueError("expanded corpus is read-only")
            if document.get("id") not in self.repository._seed_ids:
                document, chunks = self._enrich_import_metadata(document, chunks)
            changed = self.repository.upsert_batch([(document, chunks)], self.settings.max_import_documents, self.settings.max_import_characters)
            if changed:
                self._index_dirty.update({"BASELINE", "COMBINED"})
                if rebuild:
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
            items = [
                (document, chunks) if document.get("id") in self.repository._seed_ids
                else self._enrich_import_metadata(document, chunks)
                for document, chunks in items
            ]
            changed = self.repository.upsert_batch(items, self.settings.max_import_documents,
                                                   self.settings.max_import_characters)
            if changed:
                self._index_dirty.update({"BASELINE", "COMBINED"})
                self._rebuild_indexes("BASELINE")
                self._index_dirty.discard("BASELINE")
            return KnowledgeImportResponse(document_id=items[-1][0]["id"],
                imported_chunk_count=sum(len(chunks) for _, chunks in items),
                total_chunk_count=self.repository.chunk_count("BASELINE"), status="READY")

    @staticmethod
    def _enrich_import_metadata(document: dict, chunks: list[dict]) -> tuple[dict, list[dict]]:
        """Fill absent name-only terms from the shared terminology resource."""
        document = dict(document)
        title = str(document.get("title", ""))
        document_text = title + "\n" + "\n".join(str(chunk.get("content", "")) for chunk in chunks)
        candidates: dict[str, list[str]] = {key: [] for key in ("herb", "formula", "syndrome", "disease")}
        for entry in terminology_matches(document_text):
            if entry["kind"] in candidates:
                candidates[entry["kind"]].append(entry["zh"])
        field_map = {"herb": "herbs", "formula": "formulas", "syndrome": "syndromes", "disease": "diseaseScope"}
        supplied = any(document.get(field) for field in ("herbs", "formulas", "syndromes"))
        supplied_disease = bool(document.get("diseaseScope"))
        for kind, field in field_map.items():
            if not document.get(field):
                document[field] = list(dict.fromkeys(candidates[kind]))
        document["metadataSource"] = document.get("metadataSource") or (
            "用户导入" if supplied else ("词典匹配" if any(candidates.values()) else
                ("用户导入" if supplied_disease else "")))
        enriched = []
        for chunk in chunks:
            chunk = dict(chunk)
            chunk_supplied = any(chunk.get(field) for field in ("herbs", "formulas", "syndromes"))
            text = title + "\n" + str(chunk.get("content", ""))
            chunk_candidates: dict[str, list[str]] = {key: [] for key in candidates}
            for entry in terminology_matches(text):
                if entry["kind"] in chunk_candidates:
                    chunk_candidates[entry["kind"]].append(entry["zh"])
            for kind, field in field_map.items():
                chunk_field = "disease" if kind == "disease" else field
                if not chunk.get(chunk_field):
                    values = list(dict.fromkeys(chunk_candidates[kind]))
                    chunk[chunk_field] = "、".join(values) if kind == "disease" else values
            chunk["metadataSource"] = chunk.get("metadataSource") or (
                "用户导入" if chunk_supplied
                else ("词典匹配" if any(chunk_candidates.values()) else ""))
            enriched.append(chunk)
        return document, enriched

    def health(self) -> HealthResponse:
        # Health must remain responsive while the warmup worker builds indexes.
        counts: dict[str, dict[str, int | bool]] = {}
        for corpus in ("BASELINE", "EXPANDED", "COMBINED"):
            chunk_count = self.repository.chunk_count(corpus)
            counts[corpus] = {"documentCount": self.repository.document_count(corpus), "chunkCount": chunk_count, "available": chunk_count > 0}
        return HealthResponse(
            status="UP", engine="llama-index",
            chunk_count=self.repository.chunk_count("BASELINE"),
            document_count=self.repository.document_count("BASELINE"),
            llm_configured=False, corpora=counts,
            knowledge_revision=self.repository.knowledge_revision("BASELINE"),
            documents=self.repository.document_inventory("BASELINE"),
            safety_rules_sha256=RULES_SHA256, index_state=self.index_state,
            embedding_backend=self.settings.embedding_backend, embedding_model=self.settings.embedding_model,
            index_build_ms=dict(self.index_build_ms), index_error=self.index_error,
        )

    def info(self) -> EngineInfoResponse:
        return EngineInfoResponse(
            engine="llama-index",
            data_path=str(self.settings.data_path),
            candidate_limit=self.settings.candidate_limit,
            final_limit=self.settings.final_limit,
            minimum_evidence_score=self.settings.minimum_evidence_score,
            llm_configured=False,
            supported_versions=[
                "V0_DIRECT",
                "V1_VECTOR",
                "V2_HYBRID",
                "V3_GUARDED",
            ],
            metadata={
                "embedding": self.settings.embedding_backend,
                "embeddingModel": self.settings.embedding_model,
                "indexState": self.index_state,
                "lexicalRetriever": "BM25",
                "fusion": "reciprocal-rank-fusion",
                "generation": "grounded-extractive",
            },
        )

    def query(self, request: ChatRequest) -> ChatResponse:
        if self.index_state != "READY":
            raise IndexNotReady(self.index_state)
        with self._lock:
            self._ensure_embedding()
            corpus = (request.corpus or "BASELINE").strip().upper()
            if corpus in self._index_dirty:
                self._rebuild_indexes(corpus)
                self._index_dirty.discard(corpus)
            self.index_state = "READY"
            return self._query(request)

    def _query(self, request: ChatRequest) -> ChatResponse:
        started_at = time.perf_counter()
        corpus = (request.corpus or "BASELINE").strip().upper()
        if corpus not in {"BASELINE", "EXPANDED", "COMBINED"}:
            raise ValueError(f"unsupported corpus: {request.corpus}")
        if request.expected_revision is not None:
            actual_revision = self.repository.knowledge_revision(corpus)
            if request.expected_revision != actual_revision:
                raise KnowledgeOutOfSync(
                    actual_revision,
                    self.repository.document_inventory(corpus),
                )
        self._active_corpus = corpus
        version = _normalize_version(request.version)
        question = request.question.strip()
        query_weights, query_expansions = query_features(question, self.settings.query_bridge)
        retrieval_query = expanded_query(question, self.settings.query_bridge)

        if is_emergency(question):
            return self._refusal(
                request, version, "问题可能涉及急症，超出线上问答边界",
                "问题可能涉及急症，请立即拨打 120 / 前往急诊。",
                [], [], [], [], False, started_at, query_expansions,
            )
        if is_individualized_request(question):
            return self._refusal(
                request, version, DOSE_REFUSAL,
                "不能根据在线资料调整当前用药剂量，请联系开药医生并由医师判断。",
                [], [], [], [], False, started_at, query_expansions,
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
                    notes=["engine=llama-index", "generation=none", "knowledge base bypassed", "查询扩展=" + ("；".join(query_expansions) if query_expansions else "无"), "embedding-backend=" + self.settings.embedding_backend, "embedding-model=" + self.settings.embedding_model, "corpus=" + corpus],
                    engine="llama-index",
                    version=version,
                    threshold=None,
                    rejected=False,
                ),
                started_at=started_at,
            )

        filters = request.to_filter()
        vector_nodes = self._retrieve_vector(question, filters, corpus, query_weights, retrieval_query)
        lexical_nodes = self._retrieve_lexical(question, filters, corpus, query_weights) if version != "V1_VECTOR" else []
        if version == "V3_GUARDED":
            vector_nodes = [node for node in vector_nodes if self._lexical_indexes[corpus].related(question, node, query_weights, self.settings.query_bridge)]
            lexical_nodes = [node for node in lexical_nodes if self._lexical_indexes[corpus].related(question, node, query_weights, self.settings.query_bridge)]
            fused_nodes = self._fuse_ranked_candidates(vector_nodes, lexical_nodes)
        elif version == "V1_VECTOR":
            vector_nodes = [node for node in vector_nodes if self._lexical_indexes[corpus].related(question, node, query_weights, self.settings.query_bridge)]
            fused_nodes = vector_nodes
        else:
            vector_nodes = [node for node in vector_nodes if self._lexical_indexes[corpus].related(question, node, query_weights, self.settings.query_bridge)]
            lexical_nodes = [node for node in lexical_nodes if self._lexical_indexes[corpus].related(question, node, query_weights, self.settings.query_bridge)]
            fused_nodes = self._fuse_ranked_candidates(vector_nodes, lexical_nodes)
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
                query_expansions,
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
                query_expansions=query_expansions,
            ),
            started_at=started_at,
        )

    def _ensure_embedding(self):
        key = (self.settings.embedding_backend, self.settings.embedding_model)
        if key != self._embedding_key:
            self.index_state = "BUILDING"
            try:
                replacement = create_embedding(self.settings)
            except Exception:
                self.index_state = "FAILED"
                self.index_error = "Optional embedding dependency or local model unavailable"
                raise
            self.embedding = replacement
            self._embedding_key = key
            self._index_dirty = {"BASELINE", "EXPANDED", "COMBINED"}

    def _cached_embedding(self, corpus, node):
        text = searchable(node)
        identity = json.dumps(["shared-bigram-v1", self.settings.embedding_backend,
                               self.settings.embedding_model, text], ensure_ascii=False)
        key = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        cache = self.settings.embedding_cache_path if corpus in {"EXPANDED", "COMBINED"} else None
        path = cache / (key + ".json") if cache else None
        if path and path.exists():
            try:
                vector = json.loads(path.read_text(encoding="utf-8"))
                if vector and isinstance(vector, list) and all(isinstance(value, (int, float)) and math.isfinite(value) for value in vector):
                    return vector
            except (ValueError, OSError):
                pass
        vector = self.embedding.get_text_embedding(text)
        if path:
            try:
                path.parent.mkdir(parents=True, exist_ok=True)
                temporary = path.with_suffix(".tmp-" + uuid.uuid4().hex)
                temporary.write_text(json.dumps(vector), encoding="utf-8")
                os.replace(temporary, path)
            except OSError:
                logging.getLogger(__name__).warning("Embedding cache unavailable; keeping in-memory index")
        return vector

    def _rebuild_indexes(self, corpus: str) -> None:
        started = time.perf_counter()
        nodes = [node for node in self.repository.nodes(corpus)
                       if node.metadata.get("status") == "ACTIVE"]
        self._nodes[corpus] = nodes
        content_hash = hashlib.sha256(json.dumps([(node.node_id, searchable(node), node.metadata)
            for node in nodes], ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
        cached = self._index_cache.get((corpus, *self._embedding_key))
        if cached and cached[0] == content_hash:
            self._vector_indexes[corpus], self._lexical_indexes[corpus] = cached[1], cached[2]
            self.index_build_ms[corpus] = round((time.perf_counter() - started) * 1000, 3)
            return
        lexical = self._lexical_indexes.get(corpus, LexicalIndex())
        lexical.update(nodes)
        self._lexical_indexes[corpus] = lexical
        if not nodes:
            self._vector_indexes[corpus] = None
            self._bm25_retrievers[corpus] = None
            return
        for node in nodes:
            node.embedding = self._cached_embedding(corpus, node)
        self._vector_indexes[corpus] = VectorStoreIndex(
            nodes=nodes,
            embed_model=self.embedding,
            show_progress=False,
        )
        self._bm25_retrievers[corpus] = None
        self._index_cache[(corpus, *self._embedding_key)] = (
            content_hash, self._vector_indexes[corpus], lexical)
        self.index_build_ms[corpus] = round((time.perf_counter() - started) * 1000, 3)
        # LlamaIndex owns semantic-vector retrieval; lexical scoring uses the shared BM25 contract.

    def _retrieve_vector(self, question: str, filters: RagFilter, corpus: str,
                         query_weights=None, retrieval_query: str | None = None) -> list[NodeWithScore]:
        index = self._vector_indexes.get(corpus)
        if index is None:
            return []
        if self.settings.query_bridge and query_weights:
            if self.settings.embedding_backend == "hash":
                query_vector = _weighted_hash_vector(query_weights)
            else:
                query_vector = self.embedding.get_query_embedding(question)
                original_terms = set(tokenize(question))
                for term, weight in query_weights.items():
                    if term in original_terms or weight >= 1.0:
                        continue
                    alias_vector = self.embedding.get_query_embedding(term)
                    query_vector = [left + float(weight) * right
                                    for left, right in zip(query_vector, alias_vector)]
                norm = math.sqrt(sum(value * value for value in query_vector))
                if norm:
                    query_vector = [value / norm for value in query_vector]
            candidates = []
            for node in self._nodes.get(corpus, []):
                wrapped = NodeWithScore(node=node, score=0.0)
                if not _matches_filter(wrapped, filters):
                    continue
                if not self._lexical_indexes[corpus].related(question, wrapped, query_weights, strict_topics=True):
                    continue
                score = sum(left * right for left, right in zip(query_vector, node.embedding or []))
                if score > 0:
                    candidates.append(NodeWithScore(node=node, score=score))
            return sorted(candidates, key=lambda item: (-(item.score or 0), item.node.node_id))[:self.settings.candidate_limit]
        retriever = index.as_retriever(
            similarity_top_k=len(self._nodes.get(corpus, [])),
            filters=self._metadata_filters(filters, corpus),
        )
        candidates = [node for node in retriever.retrieve(retrieval_query or question)
                      if (node.score or 0) > 0 and _matches_filter(node, filters)
                      and self._lexical_indexes[corpus].related(question, node, query_weights, self.settings.query_bridge)]
        return sorted(candidates, key=lambda node: (-(node.score or 0), node.node.node_id))[:self.settings.candidate_limit]

    def _retrieve_lexical(self, question: str, filters: RagFilter, corpus: str,
                          query_weights=None) -> list[NodeWithScore]:
        index = self._lexical_indexes.get(corpus)
        if index is None:
            return []
        return index.retrieve(question, self.settings.candidate_limit,
                              lambda node: _matches_filter(node, filters) and index.related(question, node, query_weights, self.settings.query_bridge),
                              query_weights)

    def _retrieve_hybrid(self, question: str, filters: RagFilter, corpus: str) -> list[NodeWithScore]:
        return self._fuse_ranked_candidates(self._retrieve_vector(question, filters, corpus),
                                            self._retrieve_lexical(question, filters, corpus))

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
                boost = _metadata_boost(metadata_question, node)
                score = score * (1 + self.settings.metadata_weight * boost) if self.settings.normalized_fusion else score + boost
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
            rank = 0
            for candidate in nodes:
                chunk_id = candidate.node.node_id
                if chunk_id in seen_in_channel:
                    continue
                seen_in_channel.add(chunk_id)
                rank += 1
                contribution = weight / (self.settings.rrf_rank_constant + rank)
                current = scores.get(chunk_id)
                scores[chunk_id] = (
                    candidate.node,
                    contribution + (current[1] if current else 0.0),
                )
        maximum = (self.settings.lexical_weight + self.settings.vector_weight) / (self.settings.rrf_rank_constant + 1)
        return [
            NodeWithScore(node=node, score=score / maximum if self.settings.normalized_fusion else score)
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
                syndromes=[value for value in str(node.metadata.get("syndromes", "")).split("|") if value],
                formulas=[value for value in str(node.metadata.get("formulas", "")).split("|") if value],
                herbs=[value for value in str(node.metadata.get("herbs", "")).split("|") if value],
                section=str(node.metadata.get("section", "")),
                page=_page_label(
                    int(node.metadata.get("page_start") or 0),
                    int(node.metadata.get("page_end") or 0),
                ),
                evidence_level=str(node.metadata.get("evidence_level", "")),
                quote=node.node.text,
                relevance_score=round(float(node.score or 0.0), 3),
                metadata_source=str(node.metadata.get("metadata_source", "")),
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
        query_expansions: list[str] | None = None,
    ) -> ChatResponse:
        citations = self._citations(selected[:2])
        return self._response(
            request=request,
            version=version,
            answer=answer,
            refused=True,
            refusal_reason=reason,
            confidence=0.2,
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
                query_expansions=query_expansions,
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
        query_expansions: list[str] | None = None,
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
            f"generation={generation or 'grounded-extractive'}",
            "查询扩展=" + ("；".join(query_expansions) if query_expansions else "无"),
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
            notes=notes + ["embedding-backend=" + self.settings.embedding_backend,
                           "embedding-model=" + self.settings.embedding_model,
                           "corpus=" + getattr(self, "_active_corpus", "BASELINE")],
            engine="llama-index",
            version=version,
            threshold=self.settings.minimum_evidence_score if version == "V3_GUARDED" else None,
            rejected=not bool(selected),
        )


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


def _weighted_hash_vector(weights: dict[str, float] | Any) -> list[float]:
    vector = [0.0] * 512
    for term, weight in weights.items():
        digest = hashlib.sha256(term.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % len(vector)
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[index] += sign * (1.0 + min(len(term), 8) / 8.0) * float(weight)
    norm = math.sqrt(sum(value * value for value in vector))
    return [value / norm for value in vector] if norm else vector


def _is_related(question: str, node: NodeWithScore) -> bool:
    generic = {"证据", "研究", "安全", "疗效", "检查", "症状", "未归类"}
    question = question.casefold()
    topics = [value for key in ("disease", "keywords", "herbs", "formulas", "syndromes")
              for value in metadata_values(node.metadata, key)]
    if any(len(term) >= 2 and term not in generic and not is_generic(term) and term.casefold() in question
           for term in topics):
        return True
    disease_tokens = set(tokenize(" ".join(metadata_values(node.metadata, "disease"))))
    if any(
        len(term) >= 2 and term not in generic and not is_generic(term)
        for term in set(tokenize(question)) & disease_tokens
    ):
        return True
    shared = set(tokenize(question)) & set(tokenize(node.node.text))
    return len({term for term in shared if len(term) >= 2 and term not in generic and not is_generic(term)}) >= 3


def _metadata_boost(question: str, node: NodeWithScore) -> float:
    normalized_question = re.sub(r"\s+", "", question).casefold()
    metadata = node.metadata

    def mentioned(key: str) -> bool:
        return any(re.sub(r"\s+", "", value).casefold() in normalized_question
                   for value in metadata_values(metadata, key))

    boost = 0.0
    boost += 0.22 if mentioned("disease") else 0.0
    boost += 0.20 if mentioned("syndromes") else 0.0
    boost += 0.18 if mentioned("formulas") else 0.0
    boost += 0.10 if mentioned("herbs") else 0.0
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
    text = (value or "").strip()
    stop = re.search(r"[。！？.!?](?:\s|$)|[。！？]|[\r\n]", text)
    end = stop.start() + 1 if stop else len(text)
    excerpt = text[:min(end, 400)].strip()
    return excerpt + "…" if end > 400 else excerpt
