import time
from collections import defaultdict, deque
from typing import Any

import redis.asyncio as aioredis
import structlog
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from backend.app.core.config import Settings

logger = structlog.get_logger(__name__)


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Sliding-window per-client rate limiter for auth, scan, and internal admin endpoints."""

    def __init__(self, app: Any, settings: Settings) -> None:
        super().__init__(app)
        self._settings = settings
        self._enabled = settings.effective_rate_limit_enabled
        self._redis: aioredis.Redis | None = None
        self._memory_buckets: dict[str, deque[float]] = defaultdict(deque)

    def _limit_for_path(self, path: str, method: str) -> tuple[str, int] | None:
        if method.upper() == "OPTIONS":
            return None
        if path.startswith("/api/v1/auth/"):
            return "auth", self._settings.rate_limit_auth_per_minute
        if path == "/api/v1/scans/analyze":
            return "scan", self._settings.rate_limit_scan_per_minute
        if path.startswith("/api/v1/internal/"):
            return "internal", self._settings.rate_limit_internal_per_minute
        return None

    def _client_key(self, request: Request) -> str:
        forwarded = request.headers.get("X-Forwarded-For")
        if forwarded:
            first = forwarded.split(",")[0].strip()
            if first:
                return first
        if request.client is not None and request.client.host:
            return request.client.host
        return "unknown"

    async def _increment_redis(self, bucket_key: str, window_seconds: int) -> int | None:
        try:
            if self._redis is None:
                self._redis = aioredis.Redis.from_url(
                    self._settings.redis_url,
                    socket_connect_timeout=1,
                    socket_timeout=1,
                )
            pipe = self._redis.pipeline()
            pipe.incr(bucket_key)
            pipe.expire(bucket_key, window_seconds)
            results = await pipe.execute()
            return int(results[0])
        except Exception:
            return None

    def _increment_memory(self, bucket_key: str, window_seconds: int) -> int:
        now = time.monotonic()
        cutoff = now - window_seconds
        dq = self._memory_buckets[bucket_key]
        while dq and dq[0] <= cutoff:
            dq.popleft()
        dq.append(now)
        return len(dq)

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        if not self._enabled:
            return await call_next(request)

        policy = self._limit_for_path(request.url.path, request.method)
        if policy is None:
            return await call_next(request)

        scope, limit = policy
        if limit <= 0:
            return await call_next(request)

        window_seconds = 60
        client_id = self._client_key(request)
        window_slot = int(time.time() // window_seconds)
        redis_key = f"pillscope:rl:{scope}:{client_id}:{window_slot}"
        mem_key = f"{scope}:{client_id}"

        count = await self._increment_redis(redis_key, window_seconds)
        if count is None:
            count = self._increment_memory(mem_key, window_seconds)

        if count > limit:
            logger.warning(
                "rate_limit_exceeded",
                scope=scope,
                client=client_id,
                path=request.url.path,
                count=count,
                limit=limit,
            )
            return JSONResponse(
                status_code=429,
                content={
                    "detail": "Too many requests. Please wait a moment and try again.",
                },
                headers={"Retry-After": str(window_seconds)},
            )

        return await call_next(request)
