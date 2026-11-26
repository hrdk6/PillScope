from typing import Any, ClassVar

import httpx
from arq.connections import RedisSettings
from arq.cron import cron

from backend.app.core.config import get_settings
from backend.app.core.logging import configure_logging
from backend.app.db.session import build_async_engine, build_session_factory
from backend.app.services.embeddings import get_embedding_provider
from backend.app.workers.tasks import (
    ingest_drug_monographs_job,
    ingest_fda_recalls_from_file_job,
    ingest_monographs_from_file_job,
    sync_fda_recalls_job,
    sync_terminated_recalls_job,
)


async def on_startup(ctx: dict[str, Any]) -> None:
    settings = get_settings()
    configure_logging(log_level=settings.log_level, app_env=settings.app_env)
    engine = build_async_engine(settings)
    ctx["settings"] = settings
    ctx["engine"] = engine
    ctx["session_factory"] = build_session_factory(engine)
    ctx["embedding_provider"] = get_embedding_provider(settings)
    ctx["http_client"] = httpx.AsyncClient(timeout=30.0, follow_redirects=True)


async def on_shutdown(ctx: dict[str, Any]) -> None:
    http_client: httpx.AsyncClient | None = ctx.get("http_client")
    if http_client is not None:
        await http_client.aclose()
    engine = ctx.get("engine")
    if engine is not None:
        await engine.dispose()


class WorkerSettings:
    functions: ClassVar[list[Any]] = [
        ingest_drug_monographs_job,
        ingest_monographs_from_file_job,
        sync_fda_recalls_job,
        sync_terminated_recalls_job,
        ingest_fda_recalls_from_file_job,
    ]
    cron_jobs: ClassVar[list[Any]] = [
        cron(sync_fda_recalls_job, hour={0, 6, 12, 18}, minute=0),
        cron(sync_terminated_recalls_job, hour={3, 15}, minute=30),
    ]
    on_startup = on_startup
    on_shutdown = on_shutdown
    redis_settings = RedisSettings.from_dsn(get_settings().redis_url)
    max_jobs = 8
    job_timeout = 900
