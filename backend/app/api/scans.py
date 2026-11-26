from typing import Annotated
from uuid import UUID

import structlog
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sse_starlette.sse import EventSourceResponse

from backend.app.api.deps import get_current_user
from backend.app.core.config import Settings, get_settings
from backend.app.db.models import ScanLog, User
from backend.app.db.session import get_db_session
from backend.app.schemas.safety import ScanAnalyzeRequest
from backend.app.schemas.scans import ScanLogListResponse, ScanLogResponse
from backend.app.services.clinical_briefing import (
    LlmBriefingGenerator,
    get_briefing_generator,
)
from backend.app.services.ddi_ml_inference import (
    DdiMlInferenceService,
    ModelArtifactNotFoundError,
    get_ddi_inference_service,
)
from backend.app.services.embeddings import EmbeddingProvider, get_embedding_provider
from backend.app.services.hybrid_rag import HybridRagRetriever
from backend.app.services.profiles import resolve_user_profile
from backend.app.services.reranker import CrossEncoderReranker, get_reranker
from backend.app.services.scan_analyzer import stream_scan_analysis

logger = structlog.get_logger(__name__)

router = APIRouter(prefix="/api/v1/scans", tags=["scans"])


def get_scan_embedding_provider(
    settings: Annotated[Settings, Depends(get_settings)],
) -> EmbeddingProvider | None:
    try:
        return get_embedding_provider(settings)
    except Exception as exc:
        logger.warning(
            "embedding_provider_unavailable",
            provider=settings.embedding_provider,
            error=str(exc),
        )
        return None


def get_scan_reranker(
    settings: Annotated[Settings, Depends(get_settings)],
) -> CrossEncoderReranker | None:
    try:
        return get_reranker(settings)
    except Exception as exc:
        logger.warning(
            "reranker_unavailable",
            model=settings.reranker_model,
            error=str(exc),
        )
        return None


def get_scan_briefing_generator(
    settings: Annotated[Settings, Depends(get_settings)],
) -> LlmBriefingGenerator:
    return get_briefing_generator(settings)


def get_optional_ml_service(
    settings: Annotated[Settings, Depends(get_settings)],
) -> DdiMlInferenceService | None:
    try:
        return get_ddi_inference_service(settings)
    except (ModelArtifactNotFoundError, Exception) as exc:
        logger.warning(
            "ddi_ml_artifact_unavailable",
            artifact_path=settings.ddi_model_artifact_path,
            error=str(exc),
        )
        return None


@router.post("/analyze")
async def analyze_scan_sse(
    body: ScanAnalyzeRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    embedder: Annotated[EmbeddingProvider | None, Depends(get_scan_embedding_provider)],
    reranker: Annotated[CrossEncoderReranker | None, Depends(get_scan_reranker)],
    briefing_generator: Annotated[LlmBriefingGenerator, Depends(get_scan_briefing_generator)],
    ml_service: Annotated[DdiMlInferenceService | None, Depends(get_optional_ml_service)],
) -> EventSourceResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=body.profile_id,
    )
    rag_retriever: HybridRagRetriever | None = None
    if embedder is not None and reranker is not None:
        rag_retriever = HybridRagRetriever(
            embedding_provider=embedder,
            reranker=reranker,
            top_k_lexical=settings.rag_top_k_lexical,
            top_k_dense=settings.rag_top_k_dense,
            top_k_rerank=settings.rag_top_k_rerank,
            rrf_k=settings.rag_rrf_k,
            min_dense_similarity=settings.rag_min_dense_similarity,
        )
    event_stream = stream_scan_analysis(
        session=session,
        user_id=current_user.id,
        profile=profile,
        request=body,
        settings=settings,
        rag_retriever=rag_retriever,
        briefing_generator=briefing_generator,
        ml_service=ml_service,
    )
    return EventSourceResponse(event_stream)


@router.get("", response_model=ScanLogListResponse)
async def list_scans(
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    profile_id: UUID | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> ScanLogListResponse:
    filters = [ScanLog.user_id == current_user.id]
    if profile_id is not None:
        profile = await resolve_user_profile(
            session=session,
            user_id=current_user.id,
            profile_id=profile_id,
        )
        filters.append(ScanLog.profile_id == profile.id)

    total_stmt = select(func.count(ScanLog.id)).where(*filters)
    total = int((await session.scalar(total_stmt)) or 0)

    items_stmt = (
        select(ScanLog)
        .where(*filters)
        .order_by(ScanLog.created_at.desc())
        .limit(limit)
        .offset(offset)
    )
    rows = list((await session.execute(items_stmt)).scalars().all())
    return ScanLogListResponse(
        items=[ScanLogResponse.model_validate(row) for row in rows],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get("/{scan_id}", response_model=ScanLogResponse)
async def get_scan(
    scan_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ScanLogResponse:
    scan = await session.scalar(
        select(ScanLog).where(
            ScanLog.id == scan_id,
            ScanLog.user_id == current_user.id,
        )
    )
    if scan is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Scan log not found.",
        )
    return ScanLogResponse.model_validate(scan)
