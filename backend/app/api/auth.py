from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import delete, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.api.deps import get_current_user
from backend.app.core.config import Settings, get_settings
from backend.app.core.security import (
    TokenError,
    create_access_token,
    create_refresh_token,
    decode_token,
    hash_password,
    verify_password,
)
from backend.app.db.models import RevokedRefreshToken, User
from backend.app.db.session import get_db_session
from backend.app.schemas.auth import (
    AuthResponse,
    LoginRequest,
    LogoutRequest,
    RefreshRequest,
    RegisterRequest,
    TokenResponse,
    UserResponse,
)
from backend.app.services.profiles import create_default_profile_for_user

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _issue_tokens(user: User, settings: Settings) -> TokenResponse:
    return TokenResponse(
        access_token=create_access_token(user.id, settings),
        refresh_token=create_refresh_token(user.id, settings),
        expires_in=settings.access_token_expire_minutes * 60,
    )


async def _revoke_refresh_jti(
    session: AsyncSession,
    *,
    jti: object,
    user_id: object,
    expires_at: datetime,
) -> None:
    stmt = (
        pg_insert(RevokedRefreshToken)
        .values(jti=jti, user_id=user_id, expires_at=expires_at)
        .on_conflict_do_nothing(index_elements=["jti"])
    )
    await session.execute(stmt)
    await session.execute(
        delete(RevokedRefreshToken).where(RevokedRefreshToken.expires_at < datetime.now(UTC))
    )


@router.post("/register", response_model=AuthResponse, status_code=status.HTTP_201_CREATED)
async def register(
    body: RegisterRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AuthResponse:
    normalized_email = body.email.strip().lower()
    existing = await session.scalar(select(User).where(User.email == normalized_email))
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email already exists.",
        )

    user = User(
        email=normalized_email,
        password_hash=hash_password(body.password),
        display_name=body.display_name.strip() if body.display_name else None,
    )
    session.add(user)
    try:
        await session.flush()
        await create_default_profile_for_user(session, user)
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email already exists.",
        ) from exc

    await session.refresh(user)

    return AuthResponse(
        user=UserResponse.model_validate(user),
        tokens=_issue_tokens(user, settings),
    )


@router.post("/login", response_model=AuthResponse)
async def login(
    body: LoginRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AuthResponse:
    normalized_email = body.email.strip().lower()
    user = await session.scalar(select(User).where(User.email == normalized_email))
    if user is None or not user.is_active or not verify_password(body.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password.",
        )

    return AuthResponse(
        user=UserResponse.model_validate(user),
        tokens=_issue_tokens(user, settings),
    )


@router.post("/refresh", response_model=TokenResponse)
async def refresh_tokens(
    body: RefreshRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> TokenResponse:
    try:
        payload = decode_token(
            token=body.refresh_token,
            settings=settings,
            expected_type="refresh",
        )
    except TokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(exc),
        ) from exc

    revoked = await session.scalar(
        select(RevokedRefreshToken.jti).where(RevokedRefreshToken.jti == payload.jti)
    )
    if revoked is not None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token has been revoked. Please sign in again.",
        )

    user = await session.scalar(select(User).where(User.id == payload.sub))
    if user is None or not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User account not found or inactive.",
        )

    await _revoke_refresh_jti(
        session,
        jti=payload.jti,
        user_id=user.id,
        expires_at=payload.exp,
    )
    await session.commit()

    return _issue_tokens(user, settings)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    body: LogoutRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Response:
    if body.refresh_token:
        try:
            payload = decode_token(
                token=body.refresh_token,
                settings=settings,
                expected_type="refresh",
            )
            if payload.sub == current_user.id:
                await _revoke_refresh_jti(
                    session,
                    jti=payload.jti,
                    user_id=current_user.id,
                    expires_at=payload.exp,
                )
                await session.commit()
        except TokenError:
            pass
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/me", response_model=UserResponse)
async def get_me(current_user: Annotated[User, Depends(get_current_user)]) -> UserResponse:
    return UserResponse.model_validate(current_user)


@router.delete("/me", status_code=status.HTTP_204_NO_CONTENT)
async def delete_me(
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> Response:
    await session.execute(
        delete(RevokedRefreshToken).where(RevokedRefreshToken.user_id == current_user.id)
    )
    await session.delete(current_user)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
