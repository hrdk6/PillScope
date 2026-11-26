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

_MEMORY_PRUNE_THRESHOLD = 1024


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Sliding-window per-client rate limiter for auth, scan, core API, and admin endpoints."""

    def __init__(self, app: Any, settings: Settings) -> None:
        super().__init__(app)
        self._settings = settings
        self._enabled = settings.effective_rate_limit_enabled
        self._trusted_proxies: frozenset[str] = frozenset(
            ip.strip() for ip in settings.trusted_proxy_ips if ip.strip()
        )
        self._redis: aioredis.Redis | None = None
        self._memory_buckets: dict[str, deque[float]] = defaultdict(deque)

    def _limit_for_path(self, path: str, method: str) -> tuple[str, int] | None:
        upper_method = method.upper()
        if upper_method == "OPTIONS":
            return None
        if path.startswith("/api/v1/auth/") or (
            path == "/account-deletion" and upper_method == "POST"
        ):
            return "auth", self._settings.rate_limit_auth_per_minute
        if path == "/api/v1/scans/analyze":
            return "scan", self._settings.rate_limit_scan_per_minute
        if path.startswith("/api/v1/assistant/"):
            return "assistant", self._settings.rate_limit_assistant_per_minute
        if path.startswith("/api/v1/internal/"):
            return "internal", self._settings.rate_limit_internal_per_minute
        if path.startswith(
            (
                "/api/v1/drugs/",
                "/api/v1/cabinet/",
                "/api/v1/profiles",
                "/api/v1/scans",
                "/api/v1/assistant",
            )
        ):
            return "api", self._settings.rate_limit_api_per_minute
        return None

    def _client_key(self, request: Request) -> str:
        peer_host = (
            request.client.host if request.client is not None and request.client.host else ""
        )
        if self._trusted_proxies and (
            "*" in self._trusted_proxies or peer_host in self._trusted_proxies
        ):
            forwarded = request.headers.get("X-Forwarded-For")
            if forwarded:
                # Walk right-to-left and pick the rightmost hop not in trusted proxies
                # so untrusted clients cannot spoof the leftmost X-Forwarded-For entry.
                hops = [part.strip() for part in forwarded.split(",") if part.strip()]
                for hop in reversed(hops):
                    if "*" in self._trusted_proxies or hop not in self._trusted_proxies:
                        return hop
                if hops:
                    return hops[0]
        return peer_host or "unknown"

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

    def _prune_memory_buckets(self, cutoff: float) -> None:
        stale_keys: list[str] = []
        for key, dq in self._memory_buckets.items():
            while dq and dq[0] <= cutoff:
                dq.popleft()
            if not dq:
                stale_keys.append(key)
        for key in stale_keys:
            self._memory_buckets.pop(key, None)

    def _increment_memory(self, bucket_key: str, window_seconds: int) -> int:
        now = time.monotonic()
        cutoff = now - window_seconds
        if len(self._memory_buckets) > _MEMORY_PRUNE_THRESHOLD:
            self._prune_memory_buckets(cutoff)
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
