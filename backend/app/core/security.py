from datetime import UTC, datetime, timedelta
from typing import Literal
from uuid import UUID, uuid4

import jwt
from pwdlib import PasswordHash
from pydantic import BaseModel

from backend.app.core.config import Settings

TokenType = Literal["access", "refresh"]

_password_hasher = PasswordHash.recommended()


class TokenError(Exception):
    pass


class TokenPayload(BaseModel):
    sub: UUID
    token_type: TokenType
    jti: UUID
    iat: datetime
    exp: datetime


def hash_password(plain_password: str) -> str:
    return _password_hasher.hash(plain_password)


def verify_password(plain_password: str, password_hash: str) -> bool:
    return _password_hasher.verify(plain_password, password_hash)


def _create_token(
    user_id: UUID,
    token_type: TokenType,
    expires_delta: timedelta,
    settings: Settings,
) -> str:
    now = datetime.now(UTC)
    payload = {
        "sub": str(user_id),
        "token_type": token_type,
        "jti": str(uuid4()),
        "iat": int(now.timestamp()),
        "exp": int((now + expires_delta).timestamp()),
    }
    return jwt.encode(payload, settings.jwt_secret_key, algorithm=settings.jwt_algorithm)


def create_access_token(user_id: UUID, settings: Settings) -> str:
    return _create_token(
        user_id=user_id,
        token_type="access",
        expires_delta=timedelta(minutes=settings.access_token_expire_minutes),
        settings=settings,
    )


def create_refresh_token(user_id: UUID, settings: Settings) -> str:
    return _create_token(
        user_id=user_id,
        token_type="refresh",
        expires_delta=timedelta(days=settings.refresh_token_expire_days),
        settings=settings,
    )


def decode_token(
    token: str,
    settings: Settings,
    expected_type: TokenType,
) -> TokenPayload:
    try:
        raw = jwt.decode(
            token,
            settings.jwt_secret_key,
            algorithms=[settings.jwt_algorithm],
            options={"require": ["sub", "token_type", "jti", "iat", "exp"]},
        )
        payload = TokenPayload.model_validate(raw)
    except Exception as exc:
        raise TokenError("Invalid or expired authentication token.") from exc

    if payload.token_type != expected_type:
        raise TokenError(f"Expected {expected_type} token.")
    return payload
