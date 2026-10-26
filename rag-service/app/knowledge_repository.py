from __future__ import annotations

import json
import hashlib
import threading
from pathlib import Path
from typing import Iterable

from llama_index.core.schema import TextNode


class KnowledgeRepository:
    def __init__(self, data_path: Path, expanded_data_path: Path | None = None) -> None:
        self._data_paths = {"BASELINE": data_path}
        if expanded_data_path is not None:
            self._data_paths["EXPANDED"] = expanded_data_path
        self._lock = threading.RLock()
        self._nodes_by_corpus: dict[str, dict[str, TextNode]] = {}
        self._document_ids_by_corpus: dict[str, set[str]] = {}
        self._documents_by_corpus: dict[str, dict[str, str]] = {}
        self._seed_ids: set[str] = set()

    def load(self) -> None:
        loaded: dict[str, tuple[dict[str, TextNode], set[str], dict[str, str]]] = {}
        for corpus, data_path in self._data_paths.items():
            if not data_path.exists():
                if corpus == "EXPANDED":
                    loaded[corpus] = ({}, set(), {})
                    continue
                raise FileNotFoundError(f"Knowledge data file not found: {data_path}")
            with data_path.open("r", encoding="utf-8") as stream:
                chunks = json.load(stream)
            nodes = {chunk["id"]: self._to_node(chunk) for chunk in chunks}
            documents_path = data_path.parent / "knowledge-documents.json"
            if documents_path.exists():
                with documents_path.open("r", encoding="utf-8") as document_stream:
                    documents = json.load(document_stream)
                checksums = {
                    str(document.get("id", "")): str(document.get("checksum") or "")
                    for document in documents if document.get("id")
                }
            else:
                checksums = {}
            for chunk in chunks:
                checksums.setdefault(str(chunk["documentId"]), str(chunk.get("checksum") or ""))
            loaded[corpus] = (nodes, {chunk["documentId"] for chunk in chunks}, {})
        with self._lock:
            self._nodes_by_corpus = {key: value[0] for key, value in loaded.items()}
            self._document_ids_by_corpus = {key: value[1] for key, value in loaded.items()}
            self._documents_by_corpus = {key: value[2] for key, value in loaded.items()}
            self._seed_ids = set(self._document_ids_by_corpus.get("BASELINE", set()))

    @property
    def data_path(self) -> Path:
        return self._data_paths["BASELINE"]

    def _normalize_corpus(self, corpus: str | None) -> str:
        normalized = (corpus or "BASELINE").strip().upper()
        if normalized not in self._data_paths:
            raise ValueError(f"unsupported corpus: {corpus}")
        return normalized

    def _nodes(self, corpus: str) -> dict[str, TextNode]:
        return self._nodes_by_corpus.setdefault(corpus, {})

    def _document_ids(self, corpus: str) -> set[str]:
        return self._document_ids_by_corpus.setdefault(corpus, set())

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
                existing = self._nodes("BASELINE").get(chunk_id)
                existing_document = existing.metadata.get("document_id") if existing else None
                if existing_document and existing_document != document_id:
                    raise ValueError(f"chunk id already belongs to another document: {chunk_id}")

            # Upsert means replacement for this document. Removing stale chunks
            # prevents re-importing a document from leaving unreachable evidence.
            stale_ids = [
                node_id
                for node_id, node in self._nodes("BASELINE").items()
                if node.metadata.get("document_id") == document_id
            ]
            if (document_id in self._seed_ids or self._documents_by_corpus.setdefault("BASELINE", {}).get(document_id) == str(document.get("checksum") or "")) and set(stale_ids) == chunk_ids and all(
                self._nodes("BASELINE").get(chunk_id).text == node.text
                and self._nodes("BASELINE").get(chunk_id).metadata == node.metadata
                for chunk_id, node in nodes
            ):
                return False
            if document_id in self._seed_ids:
                raise ValueError("seed documents are read-only")
            for node_id in stale_ids:
                del self._nodes("BASELINE")[node_id]
            self._document_ids("BASELINE").add(document_id)
            self._documents_by_corpus.setdefault("BASELINE", {})[document_id] = str(
                document.get("checksum") or ""
            )
            self._nodes("BASELINE").update(nodes)
            return True

    def upsert_batch(self, items: list[tuple[dict, list[dict]]], max_documents: int,
                     max_characters: int) -> bool:
        # Validate all changes on a private snapshot before publishing a batch.
        with self._lock:
            staged = KnowledgeRepository(self._data_paths["BASELINE"])
            staged._nodes_by_corpus = {key: dict(value) for key, value in self._nodes_by_corpus.items()}
            staged._document_ids_by_corpus = {key: set(value) for key, value in self._document_ids_by_corpus.items()}
            staged._documents_by_corpus = {key: dict(value) for key, value in self._documents_by_corpus.items()}
            staged._seed_ids = set(self._seed_ids)
            ids = [str(document.get("id", "")) for document, _ in items]
            if len(ids) != len(set(ids)):
                raise ValueError("duplicate document id in batch")
            changed = False
            for document, chunks in items:
                changed = staged.upsert(document, chunks) or changed
            inventory = staged._documents_by_corpus.get("BASELINE", {})
            characters = sum(len(node.text) for node in staged.nodes("BASELINE")
                             if node.metadata.get("document_id") in inventory)
            if len(inventory) > max_documents or characters > max_characters:
                raise ValueError("runtime import capacity exceeded")
            if changed:
                self._nodes_by_corpus = staged._nodes_by_corpus
                self._document_ids_by_corpus = staged._document_ids_by_corpus
                self._documents_by_corpus = staged._documents_by_corpus
            return changed

    def knowledge_revision(self, corpus: str | None = None) -> str:
        normalized = self._normalize_corpus(corpus)
        if normalized == "EXPANDED":
            return ""
        with self._lock:
            entries = self._documents_by_corpus.setdefault(normalized, {})
            canonical = "\n".join(
                f"{document_id}\t{checksum}"
                for document_id, checksum in sorted(entries.items(), key=lambda item: (item[0], item[1]))
            )
            return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def import_revision(self, corpus: str | None = None) -> str:
        return self.knowledge_revision(corpus)

    def document_inventory(self, corpus: str | None = None) -> list[dict[str, str]]:
        normalized = self._normalize_corpus(corpus)
        with self._lock:
            return [
                {"documentId": document_id, "checksum": checksum}
                for document_id, checksum in sorted(
                    self._documents_by_corpus.setdefault(normalized, {}).items(),
                    key=lambda item: (item[0], item[1]),
                )
            ]

    def nodes(self, corpus: str | None = None) -> list[TextNode]:
        normalized = self._normalize_corpus(corpus)
        with self._lock:
            return list(self._nodes(normalized).values())

    def chunk_count(self, corpus: str | None = None) -> int:
        normalized = self._normalize_corpus(corpus)
        with self._lock:
            return len(self._nodes(normalized))

    def document_count(self, corpus: str | None = None) -> int:
        normalized = self._normalize_corpus(corpus)
        with self._lock:
            return len(self._document_ids(normalized))

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
            "metadata_source": chunk.get("metadataSource", chunk.get("metadata_source", "")),
        }
        return TextNode(
            id_=chunk["id"],
            text=chunk["content"],
            metadata=metadata,
        )
