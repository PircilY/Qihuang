from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Iterable

from llama_index.core.schema import TextNode


class KnowledgeRepository:
    def __init__(self, data_path: Path) -> None:
        self._data_path = data_path
        self._lock = threading.RLock()
        self._nodes: dict[str, TextNode] = {}
        self._document_ids: set[str] = set()

    @property
    def data_path(self) -> Path:
        return self._data_path

    def load(self) -> None:
        if not self._data_path.exists():
            raise FileNotFoundError(f"Knowledge data file not found: {self._data_path}")
        with self._data_path.open("r", encoding="utf-8") as stream:
            chunks = json.load(stream)
        nodes = {chunk["id"]: self._to_node(chunk) for chunk in chunks}
        document_ids = {chunk["documentId"] for chunk in chunks}
        with self._lock:
            self._nodes = nodes
            self._document_ids = document_ids

    def upsert(self, document: dict, chunks: Iterable[dict]) -> None:
        with self._lock:
            self._document_ids.add(document["id"])
            for chunk in chunks:
                self._nodes[chunk["id"]] = self._to_node(chunk)

    def nodes(self) -> list[TextNode]:
        with self._lock:
            return list(self._nodes.values())

    def chunk_count(self) -> int:
        with self._lock:
            return len(self._nodes)

    def document_count(self) -> int:
        with self._lock:
            return len(self._document_ids)

    @staticmethod
    def _to_node(chunk: dict) -> TextNode:
        metadata = {
            "chunk_id": chunk["id"],
            "document_id": chunk["documentId"],
            "document_title": chunk["title"],
            "source_type": chunk.get("sourceType", ""),
            "publisher": chunk.get("publisher", ""),
            "publication_year": int(chunk.get("publicationYear") or 0),
            "disease": chunk.get("disease", ""),
            "syndromes": "|".join(chunk.get("syndromes") or []),
            "formulas": "|".join(chunk.get("formulas") or []),
            "herbs": "|".join(chunk.get("herbs") or []),
            "page_start": int(chunk.get("pageStart") or 0),
            "page_end": int(chunk.get("pageEnd") or 0),
            "section": chunk.get("section", ""),
            "evidence_level": chunk.get("evidenceLevel", ""),
            "keywords": "|".join(chunk.get("keywords") or []),
            "checksum": chunk.get("checksum", ""),
            "status": chunk.get("status", "ACTIVE"),
            "version": chunk.get("version", "v1"),
        }
        return TextNode(
            id_=chunk["id"],
            text=chunk["content"],
            metadata=metadata,
        )
