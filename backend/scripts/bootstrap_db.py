"""Idempotent database bootstrap script for PillScope production & staging startups."""

import argparse
import asyncio
from pathlib import Path

import structlog
from sqlalchemy import func, select

from backend.app.core.config import get_settings
from backend.app.core.logging import configure_logging
from backend.app.db.models import BrandSaltMapping, DdiPair
from backend.app.db.session import build_async_engine, build_session_factory
from backend.scripts.ingest_ddinter import ingest_ddinter_csvs
from backend.scripts.ingest_indian_brands import ingest_indian_brands_csv

logger = structlog.get_logger(__name__)
REPO_ROOT = Path(__file__).resolve().parents[2]


def _resolve_brand_csv(configured_path: str) -> Path | None:
    primary = Path(configured_path)
    if primary.exists():
        return primary
    fallback = REPO_ROOT / "backend" / "tests" / "fixtures" / "indian_brands_sample.csv"
    return fallback if fallback.exists() else None


def _resolve_ddinter_csvs() -> list[Path]:
    ddinter_dir = REPO_ROOT / "data" / "raw" / "ddinter"
    if ddinter_dir.exists():
        found = sorted(ddinter_dir.glob("*.csv"))
        if found:
            return found
    sample = REPO_ROOT / "ml" / "ddi" / "fixtures" / "ddinter_real_sample.csv"
    return [sample] if sample.exists() else []


async def bootstrap_knowledge_base(*, force: bool = False) -> dict[str, int]:
    settings = get_settings()
    configure_logging(log_level=settings.log_level, app_env=settings.app_env)
    engine = build_async_engine(settings)
    factory = build_session_factory(engine)
    stats = {"brands_upserted": 0, "ddi_pairs_upserted": 0}

    try:
        async with factory() as session:
            existing_brands = int(
                (await session.scalar(select(func.count(BrandSaltMapping.id)))) or 0
            )
            if force or existing_brands == 0:
                brand_csv = await asyncio.to_thread(
                    _resolve_brand_csv, settings.india_brand_catalog_path
                )
                if brand_csv is not None:
                    res = await ingest_indian_brands_csv(session, brand_csv)
                    stats["brands_upserted"] = res.rows_upserted
                    logger.info(
                        "bootstrap_brands_seeded",
                        path=str(brand_csv),
                        upserted=res.rows_upserted,
                    )
                else:
                    logger.warning(
                        "bootstrap_brands_catalog_missing",
                        path=settings.india_brand_catalog_path,
                    )

            existing_ddi = int((await session.scalar(select(func.count(DdiPair.id)))) or 0)
            if force or existing_ddi == 0:
                csv_paths = await asyncio.to_thread(_resolve_ddinter_csvs)
                if csv_paths:
                    ddi_res = await ingest_ddinter_csvs(session, csv_paths)
                    stats["ddi_pairs_upserted"] = ddi_res.pairs_upserted
                    logger.info(
                        "bootstrap_ddinter_seeded",
                        files=len(csv_paths),
                        upserted=ddi_res.pairs_upserted,
                    )
                else:
                    logger.warning("bootstrap_ddinter_csvs_missing")
    finally:
        await engine.dispose()

    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed PillScope knowledge base idempotently.")
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-run ingestion even if tables are already populated.",
    )
    args = parser.parse_args()
    asyncio.run(bootstrap_knowledge_base(force=args.force))


if __name__ == "__main__":
    main()
