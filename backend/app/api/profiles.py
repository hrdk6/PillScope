from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.api.deps import get_current_user
from backend.app.db.models import Profile, User
from backend.app.db.session import get_db_session
from backend.app.schemas.profiles import (
    ProfileCreateRequest,
    ProfileResponse,
    ProfileUpdateRequest,
)
from backend.app.schemas.recalls import ProfileRecallsResponse
from backend.app.schemas.schedule import ProfileScheduleResponse
from backend.app.services.dose_scheduler import build_profile_schedule
from backend.app.services.profiles import (
    ensure_user_default_profile,
    resolve_user_profile,
    serialize_allergies_json,
)
from backend.app.services.recall_watchdog import get_profile_recalls

router = APIRouter(prefix="/api/v1/profiles", tags=["profiles"])


@router.get("", response_model=list[ProfileResponse])
async def list_profiles(
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> list[ProfileResponse]:
    await ensure_user_default_profile(session=session, user_id=current_user.id)
    stmt = (
        select(Profile)
        .where(Profile.user_id == current_user.id)
        .order_by(Profile.is_default.desc(), Profile.created_at.asc())
    )
    rows = list((await session.execute(stmt)).scalars().all())
    return [ProfileResponse.model_validate(row) for row in rows]


@router.post("", response_model=ProfileResponse, status_code=status.HTTP_201_CREATED)
async def create_profile(
    body: ProfileCreateRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ProfileResponse:
    existing_default = await session.scalar(
        select(Profile).where(
            Profile.user_id == current_user.id,
            Profile.is_default.is_(True),
        )
    )
    should_be_default = body.is_default or existing_default is None
    if should_be_default and existing_default is not None:
        await session.execute(
            update(Profile)
            .where(Profile.user_id == current_user.id, Profile.is_default.is_(True))
            .values(is_default=False)
        )
        await session.flush()

    profile = Profile(
        user_id=current_user.id,
        name=body.name,
        relationship=body.relationship,
        allergies_json=serialize_allergies_json(body.allergies_json),
        notes=body.notes,
        is_default=should_be_default,
    )
    session.add(profile)
    await session.commit()
    await session.refresh(profile)
    return ProfileResponse.model_validate(profile)


@router.get("/{profile_id}", response_model=ProfileResponse)
async def get_profile(
    profile_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ProfileResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=profile_id,
    )
    return ProfileResponse.model_validate(profile)


@router.patch("/{profile_id}", response_model=ProfileResponse)
async def update_profile(
    profile_id: UUID,
    body: ProfileUpdateRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ProfileResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=profile_id,
    )

    updates = body.model_dump(exclude_unset=True)
    if "is_default" in updates:
        desired_default = updates.pop("is_default")
        if desired_default is True and not profile.is_default:
            await session.execute(
                update(Profile)
                .where(
                    Profile.user_id == current_user.id,
                    Profile.id != profile.id,
                    Profile.is_default.is_(True),
                )
                .values(is_default=False)
            )
            await session.flush()
            profile.is_default = True
        elif desired_default is False and profile.is_default:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot unset default profile directly; set another profile as default.",
            )

    if "allergies_json" in updates and body.allergies_json is not None:
        updates.pop("allergies_json")
        profile.allergies_json = serialize_allergies_json(body.allergies_json)

    for field, value in updates.items():
        if value is None and field in {"name", "relationship"}:
            continue
        setattr(profile, field, value)

    await session.commit()
    await session.refresh(profile)
    return ProfileResponse.model_validate(profile)


@router.delete("/{profile_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_profile(
    profile_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> Response:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=profile_id,
    )
    if profile.is_default:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot delete the default profile. Promote another profile to default first.",
        )

    await session.delete(profile)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{profile_id}/schedule", response_model=ProfileScheduleResponse)
async def get_profile_dose_schedule(
    profile_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ProfileScheduleResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=profile_id,
    )
    return await build_profile_schedule(session=session, profile=profile)


@router.get("/{profile_id}/recalls", response_model=ProfileRecallsResponse)
async def list_profile_recalls(
    profile_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    active_only: bool = Query(default=True),
) -> ProfileRecallsResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=profile_id,
    )
    return await get_profile_recalls(session=session, profile=profile, active_only=active_only)
