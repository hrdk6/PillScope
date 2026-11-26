from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.api.deps import get_current_user
from backend.app.api.scans import get_scan_embedding_provider, get_scan_reranker
from backend.app.core.config import Settings, get_settings
from backend.app.core.languages import list_supported_languages
from backend.app.db.models import User
from backend.app.db.session import get_db_session
from backend.app.schemas.assistant import (
    AssistantAskRequest,
    AssistantAskResponse,
    AssistantTtsRequest,
    SupportedLanguageInfo,
    SupportedLanguagesResponse,
)
from backend.app.services.embeddings import EmbeddingProvider
from backend.app.services.hybrid_rag import HybridRagRetriever
from backend.app.services.profiles import resolve_user_profile
from backend.app.services.reranker import CrossEncoderReranker
from backend.app.services.voice_assistant import (
    AssistantAnswerGenerator,
    SpeechSynthesisUnavailableError,
    answer_voice_question,
    get_assistant_generator,
    is_cloud_tts_available,
    synthesize_speech_audio,
)

router = APIRouter(prefix="/api/v1/assistant", tags=["assistant"])


def get_voice_assistant_generator(
    settings: Annotated[Settings, Depends(get_settings)],
) -> AssistantAnswerGenerator:
    return get_assistant_generator(settings)


@router.get("/languages", response_model=SupportedLanguagesResponse)
async def get_supported_languages(
    settings: Annotated[Settings, Depends(get_settings)],
) -> SupportedLanguagesResponse:
    return SupportedLanguagesResponse(
        default_language="en",
        cloud_tts_available=is_cloud_tts_available(settings),
        languages=[
            SupportedLanguageInfo(
                code=spec.code,
                bcp47=spec.bcp47,
                english_name=spec.english_name,
                native_name=spec.native_name,
                script_name=spec.script_name,
                edge_tts_voice=spec.edge_tts_voice,
            )
            for spec in list_supported_languages()
        ],
    )


@router.post("/ask", response_model=AssistantAskResponse)
async def ask_multilingual_assistant(
    body: AssistantAskRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    embedder: Annotated[EmbeddingProvider | None, Depends(get_scan_embedding_provider)],
    reranker: Annotated[CrossEncoderReranker | None, Depends(get_scan_reranker)],
    generator: Annotated[AssistantAnswerGenerator, Depends(get_voice_assistant_generator)],
) -> AssistantAskResponse:
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
    return await answer_voice_question(
        session=session,
        user_id=current_user.id,
        request=body,
        settings=settings,
        rag_retriever=rag_retriever,
        generator=generator,
        profile=profile,
    )


@router.post("/tts")
async def synthesize_assistant_speech(
    body: AssistantTtsRequest,
    _current_user: Annotated[User, Depends(get_current_user)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Response:
    try:
        audio_bytes, media_type = await synthesize_speech_audio(
            text=body.text,
            language=body.language,
            voice=body.voice,
            tone=body.tone,
            speech_rate=body.speech_rate,
            settings=settings,
        )
    except SpeechSynthesisUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(exc),
        ) from exc
    return Response(
        content=audio_bytes,
        media_type=media_type,
        headers={
            "X-PillScope-Language": body.language,
            "X-PillScope-Tone": body.tone,
        },
    )
