from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from backend.app.api import auth, cabinet, drugs, health, internal, scans
from backend.app.core.config import Settings, get_settings
from backend.app.core.logging import configure_logging
from backend.app.core.telemetry import OpenTelemetryMiddleware
from backend.app.db.session import dispose_engine
from eval.tracing.otel import configure_telemetry


@asynccontextmanager
async def _lifespan(_app: FastAPI) -> AsyncIterator[None]:
    yield
    await dispose_engine()


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or get_settings()
    configure_logging(
        log_level=resolved_settings.log_level,
        app_env=resolved_settings.app_env,
    )
    configure_telemetry(
        service_name=resolved_settings.otel_service_name,
        otlp_endpoint=resolved_settings.otel_exporter_otlp_endpoint,
        langfuse_public_key=resolved_settings.langfuse_public_key,
        langfuse_secret_key=resolved_settings.langfuse_secret_key,
        langfuse_host=resolved_settings.langfuse_host,
    )

    app = FastAPI(
        title="PillScope API",
        version="0.1.0",
        description="AI Medication Safety & Drug-Interaction Copilot Backend",
        lifespan=_lifespan,
    )

    app.add_middleware(OpenTelemetryMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolved_settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(drugs.router)
    app.include_router(cabinet.router)
    app.include_router(scans.router)
    app.include_router(internal.router)
    return app


app = create_app()
