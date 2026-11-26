import asyncio
import hashlib
import math
import re
from functools import lru_cache
from typing import Any, Protocol

import httpx

from backend.app.core.config import Settings, get_settings

_TOKEN_RE = re.compile(r"[a-z0-9]+")


class EmbeddingProvider(Protocol):
    @property
    def dim(self) -> int: ...

    async def embed_texts(self, texts: list[str]) -> list[list[float]]: ...

    async def embed_query(self, query: str) -> list[float]: ...


def _l2_normalize(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vector))
    if norm <= 1e-12:
        return vector
    return [v / norm for v in vector]


class HashingEmbeddingProvider:
    """Deterministic local n-gram projection into L2-normalized dense space for offline tests."""

    def __init__(self, dim: int = 768) -> None:
        self._dim = dim

    @property
    def dim(self) -> int:
        return self._dim

    def _embed_single(self, text: str) -> list[float]:
        vec = [0.0] * self._dim
        tokens = _TOKEN_RE.findall(text.lower())
        if not tokens:
            vec[0] = 1.0
            return vec

        features: list[str] = list(tokens)
        for idx in range(len(tokens) - 1):
            features.append(f"{tokens[idx]}_{tokens[idx + 1]}")
        for tok in tokens:
            if len(tok) >= 4:
                for start in range(len(tok) - 2):
                    features.append(f"c3:{tok[start : start + 3]}")

        for feat in features:
            digest = hashlib.blake2b(feat.encode("utf-8"), digest_size=8).digest()
            bucket = int.from_bytes(digest[:4], "little") % self._dim
            sign = 1.0 if (digest[4] & 1) == 0 else -1.0
            vec[bucket] += sign

        return _l2_normalize(vec)

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_single(t) for t in texts]

    async def embed_query(self, query: str) -> list[float]:
        return self._embed_single(query)


class SentenceTransformerEmbeddingProvider:
    def __init__(self, model_name: str, dim: int = 768) -> None:
        self._model_name = model_name
        self._dim = dim
        self._model: Any = None

    @property
    def dim(self) -> int:
        return self._dim

    def _load_model(self) -> Any:
        if self._model is None:
            from sentence_transformers import SentenceTransformer

            self._model = SentenceTransformer(self._model_name)
        return self._model

    def _encode_sync(self, texts: list[str]) -> list[list[float]]:
        model = self._load_model()
        arr = model.encode(texts, normalize_embeddings=True, convert_to_numpy=True)
        vectors: list[list[float]] = []
        for row in arr:
            values = [float(x) for x in row.tolist()]
            if len(values) != self._dim:
                raise ValueError(
                    f"Embedding model {self._model_name} produced dim={len(values)}, "
                    f"expected {self._dim}."
                )
            vectors.append(values)
        return vectors

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        return await asyncio.to_thread(self._encode_sync, texts)

    async def embed_query(self, query: str) -> list[float]:
        prefixed = (
            f"Represent this sentence for searching relevant passages: {query}"
            if "bge" in self._model_name.lower()
            else query
        )
        result = await self.embed_texts([prefixed])
        return result[0]


class GeminiEmbeddingProvider:
    def __init__(
        self,
        api_key: str,
        model_name: str = "text-embedding-004",
        dim: int = 768,
    ) -> None:
        if not api_key:
            raise ValueError("GEMINI_API_KEY is required for GeminiEmbeddingProvider.")
        self._api_key = api_key
        self._model_name = model_name
        self._dim = dim

    @property
    def dim(self) -> int:
        return self._dim

    async def _embed_one(self, client: httpx.AsyncClient, text: str, task_type: str) -> list[float]:
        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{self._model_name}:embedContent"
        )
        response = await client.post(
            url,
            headers={"x-goog-api-key": self._api_key},
            json={
                "model": f"models/{self._model_name}",
                "content": {"parts": [{"text": text}]},
                "taskType": task_type,
                "outputDimensionality": self._dim,
            },
        )
        response.raise_for_status()
        values: list[float] = [float(v) for v in response.json()["embedding"]["values"]]
        return _l2_normalize(values)

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        async with httpx.AsyncClient(timeout=30.0) as client:
            return [await self._embed_one(client, t, "RETRIEVAL_DOCUMENT") for t in texts]

    async def embed_query(self, query: str) -> list[float]:
        async with httpx.AsyncClient(timeout=30.0) as client:
            return await self._embed_one(client, query, "RETRIEVAL_QUERY")


class OpenAIEmbeddingProvider:
    def __init__(
        self,
        api_key: str,
        model_name: str = "text-embedding-3-small",
        dim: int = 768,
    ) -> None:
        if not api_key:
            raise ValueError("OPENAI_API_KEY is required for OpenAIEmbeddingProvider.")
        self._api_key = api_key
        self._model_name = model_name
        self._dim = dim

    @property
    def dim(self) -> int:
        return self._dim

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                "https://api.openai.com/v1/embeddings",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "model": self._model_name,
                    "input": texts,
                    "dimensions": self._dim,
                },
            )
            response.raise_for_status()
            data = sorted(response.json()["data"], key=lambda item: int(item["index"]))
            return [_l2_normalize([float(v) for v in item["embedding"]]) for item in data]

    async def embed_query(self, query: str) -> list[float]:
        result = await self.embed_texts([query])
        return result[0]


@lru_cache(maxsize=4)
def _cached_provider(
    provider_name: str,
    model_name: str,
    dim: int,
    gemini_key: str,
    openai_key: str,
) -> EmbeddingProvider:
    if provider_name == "gemini":
        return GeminiEmbeddingProvider(api_key=gemini_key, model_name=model_name, dim=dim)
    if provider_name == "openai":
        return OpenAIEmbeddingProvider(api_key=openai_key, model_name=model_name, dim=dim)
    return SentenceTransformerEmbeddingProvider(model_name=model_name, dim=dim)


def get_embedding_provider(settings: Settings | None = None) -> EmbeddingProvider:
    resolved = settings or get_settings()
    return _cached_provider(
        resolved.embedding_provider,
        resolved.embedding_model,
        resolved.embedding_dim,
        resolved.gemini_api_key,
        resolved.openai_api_key,
    )
