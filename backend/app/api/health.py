from typing import Annotated, Any

import redis.asyncio as aioredis
from fastapi import APIRouter, Depends, status
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import Settings, get_settings
from backend.app.db.session import get_db_session

router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/readyz")
async def readyz(
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> JSONResponse:
    checks: dict[str, Any] = {"postgres": False, "extensions": [], "redis": False}
    healthy = True

    try:
        await session.execute(text("SELECT 1"))
        ext_rows = await session.execute(
            text(
                "SELECT extname FROM pg_extension "
                "WHERE extname IN ('vector', 'pg_trgm', 'pgcrypto')"
            )
        )
        extensions = sorted(row[0] for row in ext_rows.all())
        checks["postgres"] = True
        checks["extensions"] = extensions
        if not {"vector", "pg_trgm", "pgcrypto"}.issubset(set(extensions)):
            healthy = False
    except Exception as exc:
        checks["postgres_error"] = str(exc)
        healthy = False

    redis_client = aioredis.Redis.from_url(
        settings.redis_url,
        socket_connect_timeout=2,
        socket_timeout=2,
    )
    try:
        checks["redis"] = bool(await redis_client.ping())
    except Exception as exc:
        checks["redis_error"] = str(exc)
        healthy = False
    finally:
        await redis_client.aclose()

    http_status = status.HTTP_200_OK if healthy else status.HTTP_503_SERVICE_UNAVAILABLE
    return JSONResponse(
        status_code=http_status,
        content={"status": "ready" if healthy else "degraded", "checks": checks},
    )
