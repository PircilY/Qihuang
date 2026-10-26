from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        parsed = float(value)
    except ValueError:
        return default
    return parsed if 0.0 <= parsed <= 1.0 else default


@dataclass(frozen=True)
class Settings:
    data_path: Path
    expanded_data_path: Path
    host: str
    port: int
    candidate_limit: int
    final_limit: int
    rrf_rank_constant: int
    lexical_weight: float
    vector_weight: float
    minimum_evidence_score: float
    sync_batch_size: int
    embedding_backend: str = "hash"
    embedding_model: str = "hash-sha256-bigram-v1"
    embedding_allow_download: bool = False
    embedding_cache_path: Path | None = None
    normalized_fusion: bool = True
    metadata_weight: float = 0.0
    max_import_documents: int = 1000
    max_import_characters: int = 10_000_000
    import_requests_per_minute: int = 60
    query_bridge: bool = False

    @classmethod
    def from_env(cls) -> "Settings":
        default_data_path = (
            Path(__file__).resolve().parents[2]
            / "backend"
            / "src"
            / "main"
            / "resources"
            / "data"
            / "knowledge-base.json"
        )
        configured_path = Path(os.getenv("RAG_DATA_PATH", str(default_data_path)))
        if not configured_path.is_absolute():
            configured_path = (Path.cwd() / configured_path).resolve()
        expanded_default = configured_path.parent / "expanded" / "knowledge-base.json"
        expanded_path = Path(os.getenv("RAG_EXPANDED_DATA_PATH", str(expanded_default)))
        if not expanded_path.is_absolute():
            expanded_path = (Path.cwd() / expanded_path).resolve()

        return cls(
            data_path=configured_path,
            expanded_data_path=expanded_path,
            host=os.getenv("RAG_HOST", "127.0.0.1"),
            port=_env_int("RAG_PORT", 8000),
            candidate_limit=_env_int("RAG_CANDIDATE_LIMIT", 8),
            final_limit=_env_int("RAG_FINAL_LIMIT", 4),
            rrf_rank_constant=_env_int("RAG_RRF_RANK_CONSTANT", 60),
            lexical_weight=_env_float("RAG_RRF_LEXICAL_WEIGHT", 1.0),
            vector_weight=_env_float("RAG_RRF_VECTOR_WEIGHT", 0.85),
            minimum_evidence_score=_env_float("RAG_MINIMUM_EVIDENCE_SCORE", 0.9),
            sync_batch_size=_env_int("RAG_SYNC_BATCH_SIZE", 20),
            embedding_backend=os.getenv("EMBEDDING_BACKEND", "hash").strip(),
            embedding_model=os.getenv("EMBEDDING_MODEL", "hash-sha256-bigram-v1" if os.getenv("EMBEDDING_BACKEND", "hash") == "hash" else "BAAI/bge-small-zh-v1.5"),
            embedding_allow_download=os.getenv("EMBEDDING_ALLOW_DOWNLOAD", "false").lower() == "true",
            embedding_cache_path=Path(os.getenv("EMBEDDING_CACHE_PATH", str(Path(__file__).resolve().parents[2] / "runtime" / "embedding-cache"))),
            normalized_fusion=os.getenv("RAG_NORMALIZED_FUSION", "true").lower() == "true",
            metadata_weight=_env_float("RAG_METADATA_WEIGHT", 0.0),
            max_import_documents=_env_int("KNOWLEDGE_MAX_IMPORT_DOCUMENTS", 1000),
            max_import_characters=_env_int("KNOWLEDGE_MAX_IMPORT_CHARACTERS", 10_000_000),
            import_requests_per_minute=_env_int("RAG_IMPORT_REQUESTS_PER_MINUTE", 60),
            query_bridge=os.getenv("RAG_QUERY_BRIDGE", "false").lower() == "true",
        )
