import re
import time
from uuid import UUID

from sqlalchemy import Float, cast, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import MonographChunk
from backend.app.schemas.safety import MonographCitation
from backend.app.services.drug_normalizer import canonical_salt_keys
from backend.app.services.embeddings import EmbeddingProvider
from backend.app.services.reranker import CrossEncoderReranker
from eval.tracing.otel import traced_span

_QUERY_TOKEN_RE = re.compile(r"[a-z0-9]+")


def _build_or_tsquery_text(query: str, involved_salts: list[str]) -> str:
    tokens = _QUERY_TOKEN_RE.findall(query.lower())
    salt_tokens = [tok for salt in involved_salts for tok in _QUERY_TOKEN_RE.findall(salt.lower())]
    unique_terms = list(dict.fromkeys([*salt_tokens, *tokens]))[:24]
    return " | ".join(unique_terms)


def _chunk_to_citation(
    chunk: MonographChunk,
    *,
    lexical_score: float | None = None,
    dense_score: float | None = None,
    rrf_score: float | None = None,
) -> MonographCitation:
    meta = chunk.chunk_metadata or {}
    section_key = str(meta.get("section_key") or chunk.section_title.lower().replace(" ", "_"))
    excerpt = chunk.content[:500].strip()
    return MonographCitation(
        chunk_id=chunk.id,
        set_id=chunk.set_id,
        canonical_salt=chunk.canonical_salt,
        section_key=section_key,
        section_title=chunk.section_title,
        loinc_section_code=chunk.loinc_section_code,
        excerpt=excerpt,
        source_url=chunk.source_url,
        lexical_score=round(lexical_score, 6) if lexical_score is not None else None,
        dense_score=round(dense_score, 6) if dense_score is not None else None,
        rrf_score=round(rrf_score, 6) if rrf_score is not None else None,
    )


class HybridRagRetriever:
    def __init__(
        self,
        embedding_provider: EmbeddingProvider,
        reranker: CrossEncoderReranker,
        *,
        top_k_lexical: int = 12,
        top_k_dense: int = 12,
        top_k_rerank: int = 5,
        rrf_k: int = 60,
        min_dense_similarity: float = 0.20,
    ) -> None:
        self._embedder = embedding_provider
        self._reranker = reranker
        self._top_k_lexical = top_k_lexical
        self._top_k_dense = top_k_dense
        self._top_k_rerank = top_k_rerank
        self._rrf_k = rrf_k
        self._min_dense_similarity = min_dense_similarity

    async def retrieve(
        self,
        session: AsyncSession,
        *,
        query: str,
        involved_salts: list[str],
        top_k: int | None = None,
    ) -> list[MonographCitation]:
        started = time.perf_counter()
        normalized_salts = canonical_salt_keys(involved_salts)
        with traced_span(
            "pillscope.rag.retrieve",
            attributes={"pillscope.rag.salt_count": len(normalized_salts)},
            instrumentation_name="pillscope.rag",
        ) as span:
            if not normalized_salts:
                span.set_attribute("pillscope.rag.reranked_hits", 0)
                return []

            lexical_hits = await self._search_lexical(session, query, normalized_salts)
            dense_hits = await self._search_dense(session, query, normalized_salts)
            span.set_attribute("pillscope.rag.lexical_hits", len(lexical_hits))
            span.set_attribute("pillscope.rag.dense_hits", len(dense_hits))

            if not lexical_hits and not dense_hits:
                span.set_attribute("pillscope.rag.fused_hits", 0)
                span.set_attribute("pillscope.rag.reranked_hits", 0)
                span.set_attribute(
                    "pillscope.rag.duration_ms",
                    round((time.perf_counter() - started) * 1000.0, 2),
                )
                return []

            fused = self._fuse_rrf(lexical_hits, dense_hits)
            span.set_attribute("pillscope.rag.fused_hits", len(fused))
            final_k = top_k if top_k is not None else self._top_k_rerank
            reranked = await self._reranker.rerank(query=query, candidates=fused, top_k=final_k)
            span.set_attribute("pillscope.rag.reranked_hits", len(reranked))
            span.set_attribute(
                "pillscope.rag.duration_ms",
                round((time.perf_counter() - started) * 1000.0, 2),
            )
            return reranked

    async def _search_lexical(
        self,
        session: AsyncSession,
        query: str,
        involved_salts: list[str],
    ) -> list[tuple[MonographChunk, float]]:
        or_expr = _build_or_tsquery_text(query, involved_salts)
        if not or_expr:
            return []

        ts_query = func.to_tsquery("english", or_expr)
        rank_expr = cast(func.ts_rank_cd(MonographChunk.fts_vector, ts_query, 32), Float).label(
            "bm25_rank"
        )
        stmt = (
            select(MonographChunk, rank_expr)
            .where(
                MonographChunk.canonical_salt.in_(involved_salts),
                MonographChunk.fts_vector.op("@@")(ts_query),
            )
            .order_by(rank_expr.desc(), MonographChunk.chunk_index.asc())
            .limit(self._top_k_lexical)
        )
        rows = (await session.execute(stmt)).all()
        return [(row[0], float(row[1])) for row in rows if float(row[1]) > 0.0]

    async def _search_dense(
        self,
        session: AsyncSession,
        query: str,
        involved_salts: list[str],
    ) -> list[tuple[MonographChunk, float]]:
        query_vec = await self._embedder.embed_query(query)
        distance_expr = MonographChunk.embedding.cosine_distance(query_vec)
        similarity_expr = cast(1.0 - distance_expr, Float).label("cosine_sim")

        stmt = (
            select(MonographChunk, similarity_expr)
            .where(MonographChunk.canonical_salt.in_(involved_salts))
            .order_by(distance_expr.asc())
            .limit(self._top_k_dense)
        )
        rows = (await session.execute(stmt)).all()
        return [
            (row[0], float(row[1])) for row in rows if float(row[1]) >= self._min_dense_similarity
        ]

    def _fuse_rrf(
        self,
        lexical_hits: list[tuple[MonographChunk, float]],
        dense_hits: list[tuple[MonographChunk, float]],
    ) -> list[MonographCitation]:
        chunks_by_id: dict[UUID, MonographChunk] = {}
        lex_scores: dict[UUID, float] = {}
        dense_scores: dict[UUID, float] = {}
        rrf_scores: dict[UUID, float] = {}

        for rank, (chunk, score) in enumerate(lexical_hits, start=1):
            chunks_by_id[chunk.id] = chunk
            lex_scores[chunk.id] = score
            rrf_scores[chunk.id] = rrf_scores.get(chunk.id, 0.0) + (1.0 / (self._rrf_k + rank))

        for rank, (chunk, score) in enumerate(dense_hits, start=1):
            chunks_by_id[chunk.id] = chunk
            dense_scores[chunk.id] = score
            rrf_scores[chunk.id] = rrf_scores.get(chunk.id, 0.0) + (1.0 / (self._rrf_k + rank))

        ordered_ids = sorted(
            chunks_by_id.keys(),
            key=lambda cid: (-rrf_scores[cid], str(cid)),
        )
        return [
            _chunk_to_citation(
                chunks_by_id[cid],
                lexical_score=lex_scores.get(cid),
                dense_score=dense_scores.get(cid),
                rrf_score=rrf_scores[cid],
            )
            for cid in ordered_ids
        ]
