import asyncio
import math
import re
from functools import lru_cache
from typing import Any, Protocol

from backend.app.core.config import Settings, get_settings
from backend.app.schemas.safety import MonographCitation

_TOKEN_RE = re.compile(r"[a-z0-9]+")


class CrossEncoderReranker(Protocol):
    async def rerank(
        self,
        query: str,
        candidates: list[MonographCitation],
        top_k: int,
    ) -> list[MonographCitation]: ...


class SentenceTransformerCrossEncoderReranker:
    def __init__(self, model_name: str = "BAAI/bge-reranker-base") -> None:
        self._model_name = model_name
        self._model: Any = None

    def _load_model(self) -> Any:
        if self._model is None:
            from sentence_transformers import CrossEncoder

            self._model = CrossEncoder(self._model_name)
        return self._model

    def _predict_sync(self, pairs: list[tuple[str, str]]) -> list[float]:
        model = self._load_model()
        raw_scores = model.predict(pairs)
        scores: list[float] = []
        for raw in raw_scores:
            val = float(raw)
            sigmoid = 1.0 / (1.0 + math.exp(-max(min(val, 20.0), -20.0)))
            scores.append(round(sigmoid, 6))
        return scores

    async def rerank(
        self,
        query: str,
        candidates: list[MonographCitation],
        top_k: int,
    ) -> list[MonographCitation]:
        if not candidates or top_k <= 0:
            return []
        pairs = [(query, f"{c.canonical_salt} {c.section_title}: {c.excerpt}") for c in candidates]
        scores = await asyncio.to_thread(self._predict_sync, pairs)
        reranked = [
            candidate.model_copy(update={"rerank_score": score})
            for candidate, score in zip(candidates, scores, strict=True)
        ]
        reranked.sort(key=lambda item: (-item.rerank_score, str(item.chunk_id)))
        return reranked[:top_k]


class LexicalCrossReranker:
    """Deterministic query-passage cross-coverage reranker for offline/test DI."""

    async def rerank(
        self,
        query: str,
        candidates: list[MonographCitation],
        top_k: int,
    ) -> list[MonographCitation]:
        if not candidates or top_k <= 0:
            return []

        q_tokens = set(_TOKEN_RE.findall(query.lower()))
        scored: list[MonographCitation] = []
        for candidate in candidates:
            doc_text = f"{candidate.canonical_salt} {candidate.section_title} {candidate.excerpt}"
            doc_tokens = set(_TOKEN_RE.findall(doc_text.lower()))
            overlap = len(q_tokens & doc_tokens) / max(len(q_tokens), 1)
            section_boost = 0.15 if candidate.section_key == "drug_interactions" else 0.05
            rrf_component = min((candidate.rrf_score or 0.0) * 10.0, 0.35)
            final_score = round(min(overlap * 0.65 + section_boost + rrf_component, 1.0), 6)
            scored.append(candidate.model_copy(update={"rerank_score": final_score}))

        scored.sort(key=lambda item: (-item.rerank_score, str(item.chunk_id)))
        return scored[:top_k]


@lru_cache(maxsize=2)
def _cached_reranker(model_name: str) -> CrossEncoderReranker:
    return SentenceTransformerCrossEncoderReranker(model_name=model_name)


def get_reranker(settings: Settings | None = None) -> CrossEncoderReranker:
    resolved = settings or get_settings()
    return _cached_reranker(resolved.reranker_model)
