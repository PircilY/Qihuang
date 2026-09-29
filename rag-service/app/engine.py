from __future__ import annotations

import re
import time
import uuid
from datetime import UTC, datetime
from typing import Any

from llama_index.core import VectorStoreIndex
from llama_index.core.llms import MockLLM
from llama_index.core.retrievers import QueryFusionRetriever
from llama_index.core.schema import NodeWithScore
from llama_index.core.vector_stores import MetadataFilter, MetadataFilters
from llama_index.retrievers.bm25 import BM25Retriever

from app.config import Settings
from app.embeddings import HashEmbedding
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


_EMERGENCY_PATTERN = re.compile(
    r"(胸痛|胸闷|呼吸困难|晕厥|意识异常|大汗).*(持续|不缓解|加重|怎么办|急救|急诊|就医)|"
    r"(立即|马上|急救|急诊).*(胸痛|胸闷|呼吸困难|晕厥|意识异常)"
)
_PRESCRIPTION_PATTERN = re.compile(
    r"(给我|帮我|直接|能不能|可以|该不该).*(处方|用药|吃|服|剂量)|"
    r"(处方|剂量|每天吃|吃多少|怎么吃).*(吗|呢|合适|可以)"
)
_CJK_BM25_TOKEN_PATTERN = r"(?u)[a-z0-9][a-z0-9._-]*|[\u3400-\u4dbf\u4e00-\u9fff]"


class LlamaIndexEngine:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.repository = KnowledgeRepository(settings.data_path)
        self.embedding = HashEmbedding()
        self._nodes: list[Any] = []
        self._vector_index: VectorStoreIndex | None = None
        self._bm25_retriever: BM25Retriever | None = None
        self._llm = self._create_llm()

    def reload(self) -> None:
        self.repository.load()
        self._rebuild_indexes()

    def ingest(self, document: dict, chunks: list[dict]) -> KnowledgeImportResponse:
        self.repository.upsert(document, chunks)
        self._rebuild_indexes()
        return KnowledgeImportResponse(
            document_id=document["id"],
            imported_chunk_count=len(chunks),
            total_chunk_count=self.repository.chunk_count(),
            status="READY",
        )

    def health(self) -> HealthResponse:
        return HealthResponse(
            status="UP",
            engine="llama-index",
            chunk_count=self.repository.chunk_count(),
            document_count=self.repository.document_count(),
            llm_configured=self._llm is not None,
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
        started_at = time.perf_counter()
        version = _normalize_version(request.version)
        question = request.question.strip()

        if version == "V0_DIRECT":
            return self._response(
                request=request,
                version=version,
                answer=(
                    "当前版本没有检索知识库，不能为这个问题提供可追溯的专科证据。"
                    "演示用途下，只能提示中医心血管诊疗需要结合四诊信息并由专业医师判断。"
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
                    notes=["llama-index engine", "knowledge base bypassed"],
                ),
                started_at=started_at,
            )

        vector_nodes = self._retrieve_vector(question, request.to_filter())
        lexical_nodes = self._retrieve_lexical(question, request.to_filter()) if version != "V1_VECTOR" else []
        fused_nodes = (
            self._retrieve_hybrid(question, request.to_filter())
            if version != "V1_VECTOR"
            else vector_nodes
        )
        selected = self._select_nodes(fused_nodes, request.to_filter())
        top_score = selected[0].score or 0.0 if selected else 0.0
        sufficient = bool(selected) and top_score >= self.settings.minimum_evidence_score

        if version == "V3_GUARDED" and _PRESCRIPTION_PATTERN.search(question):
            return self._refusal(
                request,
                version,
                "涉及个体化处方或剂量判断，超出知识问答边界",
                "不能根据单段文字直接为具体患者确定处方或剂量。"
                "系统仅展示一般性的文献依据，个体用药必须结合面诊、既往史、合并用药和医师判断。",
                selected,
                vector_nodes,
                lexical_nodes,
                fused_nodes,
                sufficient,
                started_at,
            )

        if version == "V3_GUARDED" and _EMERGENCY_PATTERN.search(question):
            return self._refusal(
                request,
                version,
                "问题可能涉及急症，超出线上问答边界",
                "胸痛持续不缓解、呼吸困难、晕厥或意识异常需要立即就医或联系急救。"
                "本系统不进行急诊分诊，也不替代现场医疗处置。",
                selected,
                vector_nodes,
                lexical_nodes,
                fused_nodes,
                sufficient,
                started_at,
            )

        if not selected or (version == "V3_GUARDED" and not sufficient):
            return self._refusal(
                request,
                version,
                "未检索到达到阈值的相关证据",
                "当前知识库中没有找到足以支持回答的证据。系统已停止生成具体诊疗建议。",
                selected,
                vector_nodes,
                lexical_nodes,
                fused_nodes,
                sufficient,
                started_at,
            )

        citations = self._citations(selected)
        answer = self._grounded_answer(question, selected, citations)
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
            ),
            started_at=started_at,
        )

    def _rebuild_indexes(self) -> None:
        self._nodes = self.repository.nodes()
        if not self._nodes:
            self._vector_index = None
            self._bm25_retriever = None
            return
        self._vector_index = VectorStoreIndex(
            nodes=self._nodes,
            embed_model=self.embedding,
            show_progress=False,
        )
        self._bm25_retriever = BM25Retriever.from_defaults(
            nodes=self._nodes,
            similarity_top_k=self.settings.candidate_limit,
            token_pattern=_CJK_BM25_TOKEN_PATTERN,
            skip_stemming=True,
        )

    def _retrieve_vector(self, question: str, filters: RagFilter) -> list[NodeWithScore]:
        if self._vector_index is None:
            return []
        retriever = self._vector_index.as_retriever(
            similarity_top_k=self.settings.candidate_limit,
            filters=self._metadata_filters(filters),
        )
        return list(retriever.retrieve(question))

    def _retrieve_lexical(self, question: str, filters: RagFilter) -> list[NodeWithScore]:
        if self._bm25_retriever is None:
            return []
        nodes = list(self._bm25_retriever.retrieve(question))
        return [node for node in nodes if _matches_filter(node, filters)]

    def _retrieve_hybrid(self, question: str, filters: RagFilter) -> list[NodeWithScore]:
        if self._vector_index is None or self._bm25_retriever is None:
            return []
        vector_retriever = self._vector_index.as_retriever(
            similarity_top_k=self.settings.candidate_limit,
            filters=self._metadata_filters(filters),
        )
        fused = QueryFusionRetriever(
            retrievers=[vector_retriever, self._bm25_retriever],
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
    ) -> list[NodeWithScore]:
        best_by_id: dict[str, NodeWithScore] = {}
        for node in nodes:
            if not _matches_filter(node, filters):
                continue
            node_id = node.node.node_id
            current = best_by_id.get(node_id)
            if current is None or (node.score or 0.0) > (current.score or 0.0):
                best_by_id[node_id] = node
        ranked = sorted(
            best_by_id.values(),
            key=lambda item: item.score or 0.0,
            reverse=True,
        )
        return ranked[: self.settings.final_limit]

    def _metadata_filters(self, filters: RagFilter) -> MetadataFilters | None:
        items: list[MetadataFilter] = []
        if filters.disease:
            items.append(MetadataFilter(key="disease", value=filters.disease))
        if filters.source_type:
            items.append(MetadataFilter(key="source_type", value=filters.source_type))
        if filters.evidence_level:
            items.append(MetadataFilter(key="evidence_level", value=filters.evidence_level))
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
                quote=_abbreviate(node.node.get_content(), 120),
                relevance_score=round(float(node.score or 0.0), 3),
            )
            for node in nodes
        ]

    def _grounded_answer(
        self,
        question: str,
        nodes: list[NodeWithScore],
        citations: list[CitationResponse],
    ) -> str:
        if self._llm is not None:
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
                if generated:
                    return generated + "\n\n以上内容用于专科文献检索参考，不替代医生诊断和处方。"
            except Exception:
                pass

        statements = []
        for index, node in enumerate(nodes[:3], start=1):
            statements.append(f"{index}. {_first_sentence(node.node.get_content())} [{index}]")
        return (
            "依据已检索的证据，可以整理为：\n"
            + "\n".join(statements)
            + "\n\n以上内容用于专科文献检索参考，不替代医生诊断和处方。"
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
        ]
        if remote:
            notes.append("transport=remote")
        if version == "V3_GUARDED":
            notes.append("metadata guard and evidence threshold enabled")
        return RetrievalTrace(
            strategy=strategy,
            lexical_candidate_count=len(lexical_nodes),
            vector_candidate_count=len(vector_nodes),
            fused_candidate_count=len(fused_nodes),
            selected_count=len(selected),
            sufficient_evidence=sufficient,
            notes=notes,
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
    if filters.disease and filters.disease not in str(node.metadata.get("disease", "")):
        return False
    if filters.source_type and filters.source_type not in str(node.metadata.get("source_type", "")):
        return False
    if filters.evidence_level and filters.evidence_level not in str(
        node.metadata.get("evidence_level", "")
    ):
        return False
    return True


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


def _abbreviate(value: str, max_length: int) -> str:
    normalized = re.sub(r"\s+", " ", value).strip()
    if len(normalized) <= max_length:
        return normalized
    return normalized[:max_length] + "..."
