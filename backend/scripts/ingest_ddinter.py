import argparse
import asyncio
import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import structlog
from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import get_settings
from backend.app.core.logging import configure_logging
from backend.app.db.models import DdiPair
from backend.app.db.session import build_async_engine, build_session_factory
from backend.app.services.drug_normalizer import normalize_canonical_salt
from ml.ddi.data_prep import (
    DDINTER_DEFAULT_ATC_CODES,
    SmilesCacheStore,
    canonicalize_smiles,
    download_ddinter_csvs,
    load_smiles_seed_csv,
)

logger = structlog.get_logger(__name__)

_DB_SEVERITY_MAP: dict[str, str] = {
    "major": "MAJOR",
    "moderate": "MODERATE",
    "minor": "MINOR",
    "unknown": "UNKNOWN",
}

_DB_SEVERITY_RANK: dict[str, int] = {
    "UNKNOWN": 0,
    "MINOR": 1,
    "MODERATE": 2,
    "MAJOR": 3,
}

_DEFAULT_CLINICAL_TEXT: dict[str, tuple[str, str, str]] = {
    "MAJOR": (
        "Curated major drug-drug interaction cataloged in DDInter 2.0.",
        "High risk of clinically severe adverse drug reaction or therapeutic failure.",
        "Avoid concurrent administration unless explicitly supervised by a physician with "
        "close clinical monitoring.",
    ),
    "MODERATE": (
        "Curated moderate drug-drug interaction cataloged in DDInter 2.0.",
        "Moderate risk of altered therapeutic efficacy or additive adverse effects.",
        "Monitor clinical response closely and consult a pharmacist regarding dose adjustment "
        "or therapeutic alternatives.",
    ),
    "MINOR": (
        "Curated minor drug-drug interaction cataloged in DDInter 2.0.",
        "Low likelihood of severe clinical consequences; mild interaction effects possible.",
        "Routine monitoring is usually sufficient; discuss with a pharmacist if symptoms occur.",
    ),
    "UNKNOWN": (
        "Drug-drug interaction recorded in DDInter 2.0 without an assigned severity grade.",
        "Clinical significance is ungraded in DDInter 2.0; evaluate using patient context.",
        "Confirm concurrent use with a pharmacist or official prescribing information.",
    ),
}


@dataclass(slots=True)
class DdiIngestionStats:
    files_read: int = 0
    rows_read: int = 0
    pairs_upserted: int = 0
    rows_skipped: int = 0
    major_pairs: int = 0
    moderate_pairs: int = 0
    minor_pairs: int = 0
    unknown_pairs: int = 0


def _pick_field(row: dict[str, str], *aliases: str) -> str:
    lowered = {k.strip().lower(): (v or "").strip() for k, v in row.items() if k is not None}
    for alias in aliases:
        value = lowered.get(alias.lower(), "")
        if value:
            return value
    return ""


def _parse_ddinter_files_for_db(
    csv_paths: list[Path],
    *,
    include_unknown: bool,
    smiles_lookup: dict[str, str],
) -> tuple[list[dict[str, Any]], int, int]:
    merged: dict[tuple[str, str], dict[str, Any]] = {}
    rows_read = 0
    rows_skipped = 0

    for path in csv_paths:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                rows_read += 1
                raw_a = _pick_field(row, "Drug_A", "salt_a", "drug_a")
                raw_b = _pick_field(row, "Drug_B", "salt_b", "drug_b")
                raw_level = _pick_field(row, "Level", "severity", "level").lower()
                if not raw_a or not raw_b:
                    rows_skipped += 1
                    continue

                severity = _DB_SEVERITY_MAP.get(raw_level)
                if severity is None or (severity == "UNKNOWN" and not include_unknown):
                    rows_skipped += 1
                    continue

                salt_a = normalize_canonical_salt(raw_a)[:160]
                salt_b = normalize_canonical_salt(raw_b)[:160]
                if not salt_a or not salt_b or salt_a == salt_b:
                    rows_skipped += 1
                    continue

                id_a = _pick_field(row, "DDInterID_A", "ddinter_id_a") or None
                id_b = _pick_field(row, "DDInterID_B", "ddinter_id_b") or None
                smiles_a = canonicalize_smiles(
                    _pick_field(row, "smiles_a", "SMILES_A")
                ) or smiles_lookup.get(salt_a)
                smiles_b = canonicalize_smiles(
                    _pick_field(row, "smiles_b", "SMILES_B")
                ) or smiles_lookup.get(salt_b)

                if salt_a > salt_b:
                    salt_a, salt_b = salt_b, salt_a
                    id_a, id_b = id_b, id_a
                    smiles_a, smiles_b = smiles_b, smiles_a

                default_mech, default_effect, default_mgmt = _DEFAULT_CLINICAL_TEXT[severity]
                mechanism = _pick_field(row, "mechanism") or default_mech
                clinical_effect = _pick_field(row, "clinical_effect") or default_effect
                management = _pick_field(row, "management") or default_mgmt

                pair_key = (salt_a, salt_b)
                existing = merged.get(pair_key)
                if (
                    existing is None
                    or _DB_SEVERITY_RANK[severity] > _DB_SEVERITY_RANK[existing["severity"]]
                ):
                    source_ids: dict[str, Any] = {}
                    if id_a or id_b:
                        source_ids["ddinter_2"] = {"id_a": id_a, "id_b": id_b}
                    merged[pair_key] = {
                        "salt_a": salt_a,
                        "salt_b": salt_b,
                        "salt_a_smiles": smiles_a,
                        "salt_b_smiles": smiles_b,
                        "severity": severity,
                        "mechanism": mechanism,
                        "clinical_effect": clinical_effect,
                        "management": management,
                        "evidence_level": "CURATED",
                        "sources": ["ddinter_2"],
                        "source_record_ids": source_ids,
                    }

    return list(merged.values()), rows_read, rows_skipped


def _load_smiles_lookup(
    cache_db_path: Path | None,
    smiles_seed_csv: Path | None,
) -> dict[str, str]:
    lookup: dict[str, str] = {}
    if smiles_seed_csv is not None and smiles_seed_csv.exists():
        lookup.update(load_smiles_seed_csv(smiles_seed_csv))
    if cache_db_path is not None and cache_db_path.exists():
        store = SmilesCacheStore(cache_db_path)
        with store._connect() as conn:
            rows = conn.execute(
                "SELECT canonical_name, smiles FROM smiles_cache WHERE smiles IS NOT NULL"
            ).fetchall()
        for name, smi in rows:
            if name and smi:
                lookup[str(name)] = str(smi)
    return lookup


async def _upsert_ddi_batch(session: AsyncSession, records: list[dict[str, Any]]) -> int:
    if not records:
        return 0
    stmt = pg_insert(DdiPair).values(records)
    stmt = stmt.on_conflict_do_update(
        constraint="uq_ddi_pairs_salt_pair",
        set_={
            "salt_a_smiles": func.coalesce(stmt.excluded.salt_a_smiles, DdiPair.salt_a_smiles),
            "salt_b_smiles": func.coalesce(stmt.excluded.salt_b_smiles, DdiPair.salt_b_smiles),
            "severity": stmt.excluded.severity,
            "mechanism": stmt.excluded.mechanism,
            "clinical_effect": stmt.excluded.clinical_effect,
            "management": stmt.excluded.management,
            "evidence_level": stmt.excluded.evidence_level,
            "sources": stmt.excluded.sources,
            "source_record_ids": stmt.excluded.source_record_ids,
            "updated_at": func.now(),
        },
    )
    await session.execute(stmt)
    return len(records)


def _discover_csvs(input_path: Path) -> list[Path]:
    if input_path.is_file():
        return [input_path]
    return sorted(input_path.glob("*.csv"))


async def ingest_ddinter_csvs(
    session: AsyncSession,
    csv_paths: list[Path],
    *,
    include_unknown: bool = False,
    smiles_cache_db: Path | None = None,
    smiles_seed_csv: Path | None = None,
    batch_size: int = 1000,
) -> DdiIngestionStats:
    smiles_lookup = await asyncio.to_thread(_load_smiles_lookup, smiles_cache_db, smiles_seed_csv)
    records, rows_read, rows_skipped = await asyncio.to_thread(
        _parse_ddinter_files_for_db,
        csv_paths,
        include_unknown=include_unknown,
        smiles_lookup=smiles_lookup,
    )

    stats = DdiIngestionStats(
        files_read=len(csv_paths),
        rows_read=rows_read,
        rows_skipped=rows_skipped,
    )
    for rec in records:
        sev = rec["severity"]
        if sev == "MAJOR":
            stats.major_pairs += 1
        elif sev == "MODERATE":
            stats.moderate_pairs += 1
        elif sev == "MINOR":
            stats.minor_pairs += 1
        elif sev == "UNKNOWN":
            stats.unknown_pairs += 1

    for start in range(0, len(records), batch_size):
        batch = records[start : start + batch_size]
        stats.pairs_upserted += await _upsert_ddi_batch(session, batch)
        await session.commit()

    return stats


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Ingest DDInter 2.0 CSV files into PostgreSQL ddi_pairs table."
    )
    parser.add_argument(
        "--input",
        type=Path,
        required=True,
        help="Path to a DDInter 2.0 CSV file or directory of CSVs.",
    )
    parser.add_argument(
        "--download-atc-codes",
        nargs="*",
        default=None,
        help=(
            "Optional DDInter 2.0 ATC codes to download before ingesting "
            f"(default full set: {' '.join(DDINTER_DEFAULT_ATC_CODES)})."
        ),
    )
    parser.add_argument("--include-unknown", action="store_true")
    parser.add_argument(
        "--smiles-cache-db",
        type=Path,
        default=Path("data/cache/pubchem_smiles_cache.sqlite3"),
    )
    parser.add_argument("--smiles-seed-csv", type=Path, default=None)
    parser.add_argument("--batch-size", type=int, default=1000)
    return parser


async def _run_cli(args: argparse.Namespace) -> None:
    settings = get_settings()
    configure_logging(log_level=settings.log_level, app_env=settings.app_env)

    if args.download_atc_codes is not None:
        codes = (
            tuple(args.download_atc_codes)
            if len(args.download_atc_codes) > 0
            else DDINTER_DEFAULT_ATC_CODES
        )
        logger.info("downloading_ddinter_csvs", codes=list(codes), output_dir=str(args.input))
        await download_ddinter_csvs(args.input, atc_codes=codes)

    csv_paths = _discover_csvs(args.input)
    if not csv_paths:
        raise FileNotFoundError(f"No DDInter CSV files found under {args.input}")

    engine = build_async_engine(settings)
    factory = build_session_factory(engine)
    try:
        async with factory() as session:
            stats = await ingest_ddinter_csvs(
                session=session,
                csv_paths=csv_paths,
                include_unknown=args.include_unknown,
                smiles_cache_db=args.smiles_cache_db,
                smiles_seed_csv=args.smiles_seed_csv,
                batch_size=args.batch_size,
            )
        logger.info(
            "ddinter_pg_ingestion_complete",
            files_read=stats.files_read,
            rows_read=stats.rows_read,
            pairs_upserted=stats.pairs_upserted,
            rows_skipped=stats.rows_skipped,
            major_pairs=stats.major_pairs,
            moderate_pairs=stats.moderate_pairs,
            minor_pairs=stats.minor_pairs,
            unknown_pairs=stats.unknown_pairs,
        )
    finally:
        await engine.dispose()


def main() -> None:
    args = _build_arg_parser().parse_args()
    asyncio.run(_run_cli(args))


if __name__ == "__main__":
    main()
