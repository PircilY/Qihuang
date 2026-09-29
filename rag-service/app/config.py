from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return int(value)


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return float(value)


@dataclass(frozen=True)
class Settings:
    data_path: Path
    host: str
    port: int
    candidate_limit: int
    final_limit: int
    minimum_evidence_score: float
    llm_api_base: str
    llm_api_key: str
    llm_model: str

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

        return cls(
            data_path=configured_path,
            host=os.getenv("RAG_HOST", "127.0.0.1"),
            port=_env_int("RAG_PORT", 8000),
            candidate_limit=_env_int("RAG_CANDIDATE_LIMIT", 8),
            final_limit=_env_int("RAG_FINAL_LIMIT", 4),
            minimum_evidence_score=_env_float("RAG_MINIMUM_EVIDENCE_SCORE", 0.016),
            llm_api_base=os.getenv("LLM_API_BASE", "").strip(),
            llm_api_key=os.getenv("LLM_API_KEY", "").strip(),
            llm_model=os.getenv("LLM_MODEL", "").strip(),
        )
