import argparse
import asyncio
import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
import structlog
from sqlalchemy import func, text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import get_settings
from backend.app.core.logging import configure_logging
from backend.app.db.models import BrandSaltMapping
from backend.app.db.session import build_async_engine, build_session_factory
from backend.app.schemas.drugs import SaltComponent
from backend.app.services.drug_normalizer import (
    canonical_salt_keys,
    extract_dosage_form,
    normalize_brand_name,
    parse_composition_cell,
)
from ml.ddi.data_prep import SmilesCacheStore

logger = structlog.get_logger(__name__)

_TRUTHY_DISCONTINUED = frozenset({"true", "1", "yes", "y"})


@dataclass(slots=True)
class IngestionStats:
    rows_read: int = 0
    rows_upserted: int = 0
    rows_skipped_discontinued: int = 0
    rows_skipped_invalid: int = 0


def _pick_field(row: dict[str, str], *aliases: str) -> str:
    lowered = {k.strip().lower(): (v or "").strip() for k, v in row.items() if k is not None}
    for alias in aliases:
        value = lowered.get(alias.lower(), "")
        if value:
            return value
    return ""


def _load_smiles_cache_map(cache_db_path: Path | None) -> dict[str, str]:
    if cache_db_path is None or not cache_db_path.exists():
        return {}
    store = SmilesCacheStore(cache_db_path)
    with store._connect() as conn:  # noqa: SLF001
        rows = conn.execute(
            "SELECT canonical_name, smiles FROM smiles_cache WHERE smiles IS NOT NULL"
        ).fetchall()
    return {str(name): str(smi) for name, smi in rows if name and smi}


def _parse_csv_row(
    row: dict[str, str],
    country_code: str,
    source: str,
    include_discontinued: bool,
    smiles_lookup: dict[str, str] | None = None,
) -> tuple[dict[str, Any] | None, str | None]:
    discontinued_raw = _pick_field(row, "Is_discontinued", "is_discontinued", "discontinued")
    if not include_discontinued and discontinued_raw.lower() in _TRUTHY_DISCONTINUED:
        return None, "discontinued"

    brand_name = _pick_field(row, "name", "brand_name", "medicine_name")
    if not brand_name:
        return None, "invalid"

    normalized_brand = normalize_brand_name(brand_name)
    if not normalized_brand:
        return None, "invalid"

    comp1 = _pick_field(
        row, "short_composition1", "composition1", "salt_composition", "composition"
    )
    comp2 = _pick_field(row, "short_composition2", "composition2")

    salts: list[SaltComponent] = []
    seen_salts: set[str] = set()
    for cell in (comp1, comp2):
        for comp in parse_composition_cell(cell):
            if comp.canonical_salt not in seen_salts:
                seen_salts.add(comp.canonical_salt)
                salts.append(comp)

    salt_keys = canonical_salt_keys(salts)
    if not salt_keys:
        return None, "invalid"

    manufacturer = _pick_field(row, "manufacturer_name", "manufacturer") or None
    pack_size = _pick_field(row, "pack_size_label", "pack_size") or None
    dosage_form = extract_dosage_form(brand_name, pack_size)
    source_id = _pick_field(row, "id", "source_id", "product_id") or None

    smiles_map = (
        {salt: smiles_lookup[salt] for salt in salt_keys if salt in smiles_lookup}
        if smiles_lookup
        else {}
    )

    return (
        {
            "brand_name": brand_name[:255],
            "normalized_brand": normalized_brand[:255],
            "manufacturer": manufacturer[:255] if manufacturer else None,
            "country_code": country_code.upper()[:2],
            "dosage_form": dosage_form[:64] if dosage_form else None,
            "generic_salts": [salt.model_dump() for salt in salts],
            "canonical_salt_keys": salt_keys,
            "smiles_map": smiles_map,
            "source": source,
            "source_id": source_id[:128] if source_id else None,
        },
        None,
    )


async def _upsert_batch(session: AsyncSession, records: list[dict[str, Any]]) -> int:
    if not records:
        return 0

    deduped: dict[tuple[str, str, str], dict[str, Any]] = {}
    for rec in records:
        key = (
            rec["normalized_brand"],
            rec["country_code"],
            rec["dosage_form"] or "",
        )
        deduped[key] = rec

    values = list(deduped.values())
    stmt = pg_insert(BrandSaltMapping).values(values)
    stmt = stmt.on_conflict_do_update(
        index_elements=[
            BrandSaltMapping.normalized_brand,
            BrandSaltMapping.country_code,
            text("COALESCE(dosage_form, '')"),
        ],
        set_={
            "brand_name": stmt.excluded.brand_name,
            "manufacturer": stmt.excluded.manufacturer,
            "generic_salts": stmt.excluded.generic_salts,
            "canonical_salt_keys": stmt.excluded.canonical_salt_keys,
            "smiles_map": stmt.excluded.smiles_map,
            "source": stmt.excluded.source,
            "source_id": stmt.excluded.source_id,
            "updated_at": func.now(),
        },
    )
    await session.execute(stmt)
    return len(values)


async def download_catalog_csv(download_url: str, destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    async with (
        httpx.AsyncClient(timeout=120.0, follow_redirects=True) as client,
        client.stream("GET", download_url) as response,
    ):
        response.raise_for_status()
        with destination.open("wb") as handle:
            async for chunk in response.aiter_bytes(chunk_size=65536):
                handle.write(chunk)
    return destination


def _read_csv_rows(csv_path: Path) -> list[dict[str, str]]:
    if not csv_path.exists():
        raise FileNotFoundError(f"Indian brand catalog CSV not found: {csv_path}")
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


async def ingest_indian_brands_csv(
    session: AsyncSession,
    csv_path: Path,
    *,
    country_code: str = "IN",
    source: str = "india_drug_catalog",
    batch_size: int = 1000,
    include_discontinued: bool = False,
    smiles_cache_db: Path | None = None,
) -> IngestionStats:
    rows = await asyncio.to_thread(_read_csv_rows, csv_path)
    smiles_lookup = await asyncio.to_thread(_load_smiles_cache_map, smiles_cache_db)

    stats = IngestionStats()
    batch: list[dict[str, Any]] = []

    for row in rows:
        stats.rows_read += 1
        parsed, skip_reason = _parse_csv_row(
            row=row,
            country_code=country_code,
            source=source,
            include_discontinued=include_discontinued,
            smiles_lookup=smiles_lookup,
        )
        if skip_reason == "discontinued":
            stats.rows_skipped_discontinued += 1
            continue
        if parsed is None:
            stats.rows_skipped_invalid += 1
            continue

        batch.append(parsed)
        if len(batch) >= batch_size:
            stats.rows_upserted += await _upsert_batch(session, batch)
            await session.commit()
            batch.clear()

    if batch:
        stats.rows_upserted += await _upsert_batch(session, batch)
        await session.commit()

    return stats


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Ingest the Indian Medicine Dataset (A-Z brand-to-salt CSV) into PostgreSQL."
    )
    parser.add_argument(
        "--csv-path",
        type=Path,
        default=None,
        help="Path to the A-Z Indian medicine CSV file (defaults to INDIA_BRAND_CATALOG_PATH).",
    )
    parser.add_argument(
        "--download-url",
        type=str,
        default=None,
        help="Optional HTTP(S) URL to download the CSV before ingestion.",
    )
    parser.add_argument("--country-code", type=str, default="IN")
    parser.add_argument("--source", type=str, default="india_drug_catalog")
    parser.add_argument("--batch-size", type=int, default=1000)
    parser.add_argument("--include-discontinued", action="store_true")
    parser.add_argument(
        "--smiles-cache-db",
        type=Path,
        default=Path("data/cache/pubchem_smiles_cache.sqlite3"),
    )
    return parser


async def _run_cli(args: argparse.Namespace) -> None:
    settings = get_settings()
    configure_logging(log_level=settings.log_level, app_env=settings.app_env)
    csv_path: Path = args.csv_path or Path(settings.india_brand_catalog_path)

    if args.download_url:
        logger.info("downloading_brand_catalog", url=args.download_url, destination=str(csv_path))
        await download_catalog_csv(args.download_url, csv_path)

    engine = build_async_engine(settings)
    factory = build_session_factory(engine)
    try:
        async with factory() as session:
            stats = await ingest_indian_brands_csv(
                session=session,
                csv_path=csv_path,
                country_code=args.country_code,
                source=args.source,
                batch_size=args.batch_size,
                include_discontinued=args.include_discontinued,
                smiles_cache_db=args.smiles_cache_db,
            )
        logger.info(
            "indian_brand_ingestion_complete",
            csv_path=str(csv_path),
            rows_read=stats.rows_read,
            rows_upserted=stats.rows_upserted,
            rows_skipped_discontinued=stats.rows_skipped_discontinued,
            rows_skipped_invalid=stats.rows_skipped_invalid,
        )
    finally:
        await engine.dispose()


def main() -> None:
    parser = _build_arg_parser()
    args = parser.parse_args()
    asyncio.run(_run_cli(args))


if __name__ == "__main__":
    main()
