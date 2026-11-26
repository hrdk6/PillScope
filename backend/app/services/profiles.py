from collections.abc import Sequence
from typing import Any
from uuid import UUID

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import Profile, User
from backend.app.schemas.profiles import AllergyEntry


def serialize_allergies_json(entries: Sequence[str | AllergyEntry]) -> list[str | dict[str, Any]]:
    serialized: list[str | dict[str, Any]] = []
    seen_keys: set[str] = set()
    for entry in entries:
        if isinstance(entry, str):
            cleaned = entry.strip()
            if not cleaned:
                continue
            dedupe_key = cleaned.lower()
            if dedupe_key in seen_keys:
                continue
            seen_keys.add(dedupe_key)
            serialized.append(cleaned)
        else:
            dedupe_key = entry.allergen.strip().lower()
            if dedupe_key in seen_keys:
                continue
            seen_keys.add(dedupe_key)
            serialized.append(entry.model_dump(exclude_none=True))
    return serialized


async def create_default_profile_for_user(
    session: AsyncSession,
    user: User,
) -> Profile:
    profile_name = (user.display_name or "").strip() or "Primary Profile"
    profile = Profile(
        user_id=user.id,
        name=profile_name[:120],
        relationship="self",
        allergies_json=[],
        notes=None,
        is_default=True,
    )
    session.add(profile)
    await session.flush()
    return profile


async def ensure_user_default_profile(
    session: AsyncSession,
    user_id: UUID,
) -> Profile:
    default_profile = await session.scalar(
        select(Profile).where(
            Profile.user_id == user_id,
            Profile.is_default.is_(True),
        )
    )
    if default_profile is not None:
        return default_profile

    fallback = await session.scalar(
        select(Profile)
        .where(Profile.user_id == user_id)
        .order_by(Profile.created_at.asc())
        .limit(1)
    )
    if fallback is not None:
        fallback.is_default = True
        await session.commit()
        await session.refresh(fallback)
        return fallback

    user = await session.scalar(select(User).where(User.id == user_id))
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )
    profile = await create_default_profile_for_user(session, user)
    await session.commit()
    await session.refresh(profile)
    return profile


async def resolve_user_profile(
    session: AsyncSession,
    user_id: UUID,
    profile_id: UUID | None = None,
) -> Profile:
    if profile_id is None:
        return await ensure_user_default_profile(session=session, user_id=user_id)

    profile = await session.scalar(
        select(Profile).where(
            Profile.id == profile_id,
            Profile.user_id == user_id,
        )
    )
    if profile is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Profile not found.",
        )
    return profile
