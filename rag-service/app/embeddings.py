from __future__ import annotations

import hashlib
import math
from typing import List

from llama_index.core.embeddings import BaseEmbedding

from app.tokenization import tokenize


class HashEmbedding(BaseEmbedding):
    """Deterministic local embedding used when no external embedding API is configured."""

    dimension: int = 512

    @classmethod
    def class_name(cls) -> str:
        return "HashEmbedding"

    def _embed(self, text: str) -> List[float]:
        vector = [0.0] * self.dimension
        for token in tokenize(text):
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimension
            sign = 1.0 if digest[4] & 1 else -1.0
            vector[index] += sign * (1.0 + min(len(token), 8) / 8.0)

        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            return vector
        return [value / norm for value in vector]

    def _get_query_embedding(self, query: str) -> List[float]:
        return self._embed(query)

    def _get_text_embedding(self, text: str) -> List[float]:
        return self._embed(text)

    async def _aget_query_embedding(self, query: str) -> List[float]:
        return self._embed(query)

    async def _aget_text_embedding(self, text: str) -> List[float]:
        return self._embed(text)
