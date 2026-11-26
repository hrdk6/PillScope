from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.api.deps import get_current_user
from backend.app.core.config import Settings, get_settings
from backend.app.db.models import User
from backend.app.db.session import get_db_session
from backend.app.schemas.drugs import (
    BrandCandidate,
    DrugResolveRequest,
    DrugResolveResponse,
)
from backend.app.services.brand_resolver import BrandSaltResolver

router = APIRouter(prefix="/api/v1/drugs", tags=["drugs"])


@router.post("/resolve", response_model=DrugResolveResponse)
async def resolve_drug(
    body: DrugResolveRequest,
    _current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> DrugResolveResponse:
    resolver = BrandSaltResolver(trigram_threshold=settings.trigram_similarity_threshold)
    return await resolver.resolve(session=session, request=body)


@router.get("/search", response_model=list[BrandCandidate])
async def search_drugs(
    _current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    q: str = Query(min_length=1, max_length=255),
    country: str | None = Query(default=None, min_length=2, max_length=2),
    limit: int = Query(default=10, ge=1, le=25),
) -> list[BrandCandidate]:
    resolver = BrandSaltResolver(trigram_threshold=settings.trigram_similarity_threshold)
    return await resolver.search(
        session=session,
        query=q,
        country_code=country,
        limit=limit,
    )
