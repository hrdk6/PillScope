import asyncio
import json
from pathlib import Path
from typing import Any

import httpx
import structlog
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from backend.app.core.config import Settings, get_settings
from backend.app.services.embeddings import EmbeddingProvider, get_embedding_provider
from backend.app.services.monograph_ingestion import (
    ingest_drug_monographs,
    parse_openfda_label_record,
    upsert_monograph_chunks,
)
from backend.app.services.recall_watchdog import (
    ingest_recalls_from_file,
    sync_openfda_recalls,
)

logger = structlog.get_logger(__name__)


def _read_json_records(json_path: Path) -> list[dict[str, Any]]:
    payload = json.loads(json_path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and isinstance(payload.get("results"), list):
        return [dict(item) for item in payload["results"]]
    if isinstance(payload, dict) and ("set_id" in payload or "id" in payload):
        return [dict(payload)]
    if isinstance(payload, list):
        return [dict(item) for item in payload]
    raise ValueError(f"Unsupported openFDA JSON structure in {json_path}")


async def ingest_drug_monographs_job(
    ctx: dict[str, Any],
    drug_names: list[str],
    source: str = "auto",
) -> dict[str, Any]:
    session_factory: async_sessionmaker[AsyncSession] = ctx["session_factory"]
    settings: Settings = ctx.get("settings") or get_settings()
    embedding_provider: EmbeddingProvider = ctx.get("embedding_provider") or get_embedding_provider(
        settings
    )
    http_client: httpx.AsyncClient | None = ctx.get("http_client")

    async with session_factory() as session:
        result = await ingest_drug_monographs(
            session=session,
            drug_names=drug_names,
            settings=settings,
            embedding_provider=embedding_provider,
            source=source,
            http_client=http_client,
        )

    return {
        "drugs_requested": result.drugs_requested,
        "labels_processed": result.labels_processed,
        "chunks_upserted": result.chunks_upserted,
        "drugs_not_found": result.drugs_not_found or [],
    }


async def ingest_monographs_from_file_job(
    ctx: dict[str, Any],
    json_path_str: str,
) -> dict[str, Any]:
    session_factory: async_sessionmaker[AsyncSession] = ctx["session_factory"]
    settings: Settings = ctx.get("settings") or get_settings()
    embedding_provider: EmbeddingProvider = ctx.get("embedding_provider") or get_embedding_provider(
        settings
    )

    records = await asyncio.to_thread(_read_json_records, Path(json_path_str))
    total_chunks = 0
    labels_processed = 0

    async with session_factory() as session:
        for record in records:
            chunks = parse_openfda_label_record(record)
            if not chunks:
                continue
            labels_processed += 1
            total_chunks += await upsert_monograph_chunks(
                session=session,
                chunks=chunks,
                embedding_provider=embedding_provider,
            )

    logger.info(
        "monograph_file_job_complete",
        json_path=json_path_str,
        labels_processed=labels_processed,
        chunks_upserted=total_chunks,
    )
    return {
        "labels_processed": labels_processed,
        "chunks_upserted": total_chunks,
    }


async def sync_fda_recalls_job(
    ctx: dict[str, Any],
    max_pages: int = 5,
    page_size: int = 100,
    status_filter: str = "Ongoing",
) -> dict[str, Any]:
    session_factory: async_sessionmaker[AsyncSession] = ctx["session_factory"]
    settings: Settings = ctx.get("settings") or get_settings()
    http_client: httpx.AsyncClient | None = ctx.get("http_client")
    sleep_fn = ctx.get("sleep_fn")

    async with session_factory() as session:
        if sleep_fn is not None:
            result = await sync_openfda_recalls(
                session=session,
                settings=settings,
                max_pages=max_pages,
                page_size=page_size,
                status_filter=status_filter,
                http_client=http_client,
                sleep_fn=sleep_fn,
            )
        else:
            result = await sync_openfda_recalls(
                session=session,
                settings=settings,
                max_pages=max_pages,
                page_size=page_size,
                status_filter=status_filter,
                http_client=http_client,
            )

    return {
        "pages_fetched": result.pages_fetched,
        "records_read": result.records_read,
        "records_upserted": result.records_upserted,
        "active_class_1_or_2_upserted": result.active_class_1_or_2_upserted,
        "rate_limited": result.rate_limited,
        "warnings": result.warnings,
    }


async def ingest_fda_recalls_from_file_job(
    ctx: dict[str, Any],
    json_path_str: str,
) -> dict[str, Any]:
    session_factory: async_sessionmaker[AsyncSession] = ctx["session_factory"]
    settings: Settings = ctx.get("settings") or get_settings()

    async with session_factory() as session:
        result = await ingest_recalls_from_file(
            session=session,
            json_path=Path(json_path_str),
            enforcement_base_url=settings.openfda_enforcement_url,
        )

    return {
        "records_read": result.records_read,
        "records_upserted": result.records_upserted,
        "active_class_1_or_2_upserted": result.active_class_1_or_2_upserted,
    }
