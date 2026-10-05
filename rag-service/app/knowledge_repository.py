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

    def upsert(self, document: dict, chunks: Iterable[dict]) -> bool:
        document_id = str(document.get("id", "")).strip()
        if not document_id:
            raise ValueError("document.id is required")
        materialized = list(chunks)
        if not materialized:
            raise ValueError("at least one chunk is required")

        nodes = []
        chunk_ids: set[str] = set()
        for chunk in materialized:
            chunk_id = str(chunk.get("id", "")).strip()
            if not chunk_id:
                raise ValueError("chunk.id is required")
            if chunk_id in chunk_ids:
                raise ValueError(f"duplicate chunk id: {chunk_id}")
            if str(chunk.get("documentId", "")).strip() != document_id:
                raise ValueError(f"chunk {chunk_id} does not belong to document {document_id}")
            chunk_ids.add(chunk_id)
            nodes.append((chunk_id, self._to_node(chunk)))

        with self._lock:
            for chunk_id, node in nodes:
                existing = self._nodes.get(chunk_id)
                existing_document = existing.metadata.get("document_id") if existing else None
                if existing_document and existing_document != document_id:
                    raise ValueError(f"chunk id already belongs to another document: {chunk_id}")

            # Upsert means replacement for this document. Removing stale chunks
            # prevents re-importing a document from leaving unreachable evidence.
            stale_ids = [
                node_id
                for node_id, node in self._nodes.items()
                if node.metadata.get("document_id") == document_id
            ]
            if set(stale_ids) == chunk_ids and all(
                self._nodes[chunk_id].text == node.text
                and self._nodes[chunk_id].metadata == node.metadata
                for chunk_id, node in nodes
            ):
                return False
            for node_id in stale_ids:
                del self._nodes[node_id]
            self._document_ids.add(document_id)
            self._nodes.update(nodes)
            return True

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
