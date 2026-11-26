import argparse
import asyncio
import csv
import random
import sqlite3
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx
import pandas as pd
import structlog
from rdkit import Chem
from rdkit.Chem.Scaffolds import MurckoScaffold

from backend.app.services.drug_normalizer import normalize_canonical_salt
from ml.ddi.schemas import SEVERITY_RANK, SeverityLabel

logger = structlog.get_logger(__name__)

DDINTER_DEFAULT_ATC_CODES: tuple[str, ...] = (
    "A",
    "B",
    "C",
    "D",
    "G",
    "H",
    "J",
    "L",
    "M",
    "N",
    "P",
    "R",
    "S",
    "V",
)
DDINTER_CSV_URL_TEMPLATE = (
    "https://ddinter2.scbdd.com/static/media/download/ddinter_downloads_code_{code}.csv"
)
PUBCHEM_SMILES_URL_TEMPLATE = (
    "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/{name}"
    "/property/CanonicalSMILES,IsomericSMILES/JSON"
)

_LEVEL_MAP: dict[str, SeverityLabel] = {
    "major": "MAJOR",
    "moderate": "MODERATE",
    "minor": "MINOR",
    "none": "NONE",
    "safe": "NONE",
}


@dataclass(slots=True)
class SmilesLookupResult:
    canonical_name: str
    smiles: str | None
    cid: int | None
    status: str


def canonicalize_smiles(raw_smiles: str | None) -> str | None:
    if not raw_smiles or not raw_smiles.strip():
        return None
    mol = Chem.MolFromSmiles(raw_smiles.strip())
    if mol is None:
        return None
    return str(Chem.MolToSmiles(mol, canonical=True))


def compute_murcko_scaffold(canonical_smiles: str) -> str:
    mol = Chem.MolFromSmiles(canonical_smiles)
    if mol is None:
        raise ValueError(f"Invalid SMILES for scaffold calculation: {canonical_smiles}")
    scaffold = MurckoScaffold.MurckoScaffoldSmiles(mol=mol, includeChirality=False)
    return scaffold if scaffold else canonical_smiles


class SmilesCacheStore:
    def __init__(self, db_path: Path) -> None:
        self._db_path = db_path
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self._db_path)

    def _init_schema(self) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS smiles_cache (
                    canonical_name TEXT PRIMARY KEY,
                    smiles TEXT,
                    cid INTEGER,
                    status TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            conn.commit()

    def get(self, canonical_name: str) -> SmilesLookupResult | None:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT canonical_name, smiles, cid, status "
                "FROM smiles_cache WHERE canonical_name = ?",
                (canonical_name,),
            ).fetchone()
        if row is None:
            return None
        return SmilesLookupResult(
            canonical_name=str(row[0]),
            smiles=str(row[1]) if row[1] is not None else None,
            cid=int(row[2]) if row[2] is not None else None,
            status=str(row[3]),
        )

    def put(self, result: SmilesLookupResult) -> None:
        now = datetime.now(UTC).isoformat()
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO smiles_cache (canonical_name, smiles, cid, status, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(canonical_name) DO UPDATE SET
                    smiles = excluded.smiles,
                    cid = excluded.cid,
                    status = excluded.status,
                    updated_at = excluded.updated_at
                """,
                (result.canonical_name, result.smiles, result.cid, result.status, now),
            )
            conn.commit()

    def bulk_seed(self, smiles_by_name: dict[str, str], source_status: str = "seeded") -> int:
        inserted = 0
        now = datetime.now(UTC).isoformat()
        with self._connect() as conn:
            for raw_name, raw_smiles in smiles_by_name.items():
                canonical_name = normalize_canonical_salt(raw_name)
                canonical = canonicalize_smiles(raw_smiles)
                if not canonical_name or not canonical:
                    continue
                conn.execute(
                    """
                    INSERT INTO smiles_cache (canonical_name, smiles, cid, status, updated_at)
                    VALUES (?, ?, NULL, ?, ?)
                    ON CONFLICT(canonical_name) DO UPDATE SET
                        smiles = excluded.smiles,
                        status = excluded.status,
                        updated_at = excluded.updated_at
                    """,
                    (canonical_name, canonical, source_status, now),
                )
                inserted += 1
            conn.commit()
        return inserted


class AsyncRateLimiter:
    def __init__(self, max_requests_per_second: float = 4.0) -> None:
        if max_requests_per_second <= 0:
            raise ValueError("max_requests_per_second must be positive.")
        self._min_interval = 1.0 / max_requests_per_second
        self._lock = asyncio.Lock()
        self._next_allowed = 0.0

    async def acquire(self) -> None:
        async with self._lock:
            now = time.monotonic()
            wait_seconds = self._next_allowed - now
            if wait_seconds > 0:
                await asyncio.sleep(wait_seconds)
            self._next_allowed = max(time.monotonic(), self._next_allowed) + self._min_interval


class PubChemSmilesResolver:
    def __init__(
        self,
        cache: SmilesCacheStore,
        *,
        max_requests_per_second: float = 4.0,
        max_retries: int = 4,
        base_backoff_seconds: float = 0.6,
        offline: bool = False,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        self._cache = cache
        self._limiter = AsyncRateLimiter(max_requests_per_second=max_requests_per_second)
        self._max_retries = max_retries
        self._base_backoff = base_backoff_seconds
        self._offline = offline
        self._external_client = http_client

    async def resolve_one(self, drug_name: str) -> SmilesLookupResult:
        canonical_name = normalize_canonical_salt(drug_name)
        if not canonical_name:
            return SmilesLookupResult(canonical_name="", smiles=None, cid=None, status="invalid")

        cached = self._cache.get(canonical_name)
        if cached is not None:
            return cached

        if self._offline:
            return SmilesLookupResult(
                canonical_name=canonical_name,
                smiles=None,
                cid=None,
                status="cache_miss_offline",
            )

        if self._external_client is not None:
            result = await self._fetch_from_pubchem(self._external_client, canonical_name)
        else:
            async with httpx.AsyncClient(timeout=20.0) as client:
                result = await self._fetch_from_pubchem(client, canonical_name)

        if result.status in {"ok", "not_found"}:
            self._cache.put(result)
        return result

    async def resolve_many(self, drug_names: list[str]) -> dict[str, str]:
        unique_names = sorted({normalize_canonical_salt(n) for n in drug_names if n.strip()})
        resolved: dict[str, str] = {}
        if self._external_client is not None:
            for name in unique_names:
                res = await self.resolve_one(name)
                if res.smiles:
                    resolved[name] = res.smiles
            return resolved

        async with httpx.AsyncClient(timeout=20.0) as client:
            self._external_client = client
            try:
                for name in unique_names:
                    res = await self.resolve_one(name)
                    if res.smiles:
                        resolved[name] = res.smiles
            finally:
                self._external_client = None
        return resolved

    async def _fetch_from_pubchem(
        self,
        client: httpx.AsyncClient,
        canonical_name: str,
    ) -> SmilesLookupResult:
        url = PUBCHEM_SMILES_URL_TEMPLATE.format(name=quote(canonical_name, safe=""))
        for attempt in range(self._max_retries + 1):
            await self._limiter.acquire()
            try:
                response = await client.get(url)
            except httpx.HTTPError:
                if attempt == self._max_retries:
                    return SmilesLookupResult(
                        canonical_name=canonical_name,
                        smiles=None,
                        cid=None,
                        status="network_error",
                    )
                await asyncio.sleep(self._base_backoff * (2**attempt))
                continue

            if response.status_code == 200:
                data = response.json()
                props = data.get("PropertyTable", {}).get("Properties", [])
                if props:
                    first = props[0]
                    raw_smiles = (
                        first.get("CanonicalSMILES")
                        or first.get("IsomericSMILES")
                        or first.get("ConnectivitySMILES")
                        or first.get("SMILES")
                    )
                    canonical = canonicalize_smiles(raw_smiles)
                    cid = first.get("CID")
                    if canonical:
                        return SmilesLookupResult(
                            canonical_name=canonical_name,
                            smiles=canonical,
                            cid=int(cid) if cid is not None else None,
                            status="ok",
                        )
                return SmilesLookupResult(
                    canonical_name=canonical_name,
                    smiles=None,
                    cid=None,
                    status="not_found",
                )

            if response.status_code == 404:
                return SmilesLookupResult(
                    canonical_name=canonical_name,
                    smiles=None,
                    cid=None,
                    status="not_found",
                )

            if response.status_code in {429, 500, 502, 503, 504}:
                if attempt == self._max_retries:
                    return SmilesLookupResult(
                        canonical_name=canonical_name,
                        smiles=None,
                        cid=None,
                        status=f"http_{response.status_code}",
                    )
                retry_after = response.headers.get("Retry-After")
                delay = (
                    float(retry_after)
                    if retry_after and retry_after.replace(".", "", 1).isdigit()
                    else self._base_backoff * (2**attempt)
                )
                await asyncio.sleep(min(delay, 30.0))
                continue

            return SmilesLookupResult(
                canonical_name=canonical_name,
                smiles=None,
                cid=None,
                status=f"http_{response.status_code}",
            )

        return SmilesLookupResult(
            canonical_name=canonical_name,
            smiles=None,
            cid=None,
            status="retry_exhausted",
        )


def _pick_csv_value(row: dict[str, str], *aliases: str) -> str:
    lowered = {k.strip().lower(): (v or "").strip() for k, v in row.items() if k is not None}
    for alias in aliases:
        value = lowered.get(alias.lower(), "")
        if value:
            return value
    return ""


def load_smiles_seed_csv(seed_csv: Path) -> dict[str, str]:
    mapping: dict[str, str] = {}
    with seed_csv.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            name = _pick_csv_value(row, "canonical_salt", "drug", "drug_name", "name")
            smiles = _pick_csv_value(row, "smiles", "canonical_smiles")
            if name and smiles:
                norm_name = normalize_canonical_salt(name)
                canonical = canonicalize_smiles(smiles)
                if norm_name and canonical:
                    mapping[norm_name] = canonical
    return mapping


def _write_bytes_file(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def _save_dataframe(df: pd.DataFrame, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.suffix.lower() == ".parquet":
        df.to_parquet(output_path, index=False)
    else:
        df.to_csv(output_path, index=False)


async def download_ddinter_csvs(
    output_dir: Path,
    atc_codes: tuple[str, ...] = DDINTER_DEFAULT_ATC_CODES,
) -> list[Path]:
    downloaded: list[Path] = []
    async with httpx.AsyncClient(timeout=60.0, follow_redirects=True) as client:
        for code in atc_codes:
            url = DDINTER_CSV_URL_TEMPLATE.format(code=code.upper())
            target = output_dir / f"ddinter_downloads_code_{code.upper()}.csv"
            response = await client.get(url)
            response.raise_for_status()
            await asyncio.to_thread(_write_bytes_file, target, response.content)
            downloaded.append(target)
    return downloaded


def parse_ddinter_csv_files(
    csv_paths: list[Path],
    *,
    drop_unknown: bool = True,
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    merged_pairs: dict[tuple[str, str], dict[str, Any]] = {}
    inline_smiles: dict[str, str] = {}

    for path in csv_paths:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                raw_a = _pick_csv_value(row, "Drug_A", "salt_a", "drug_a")
                raw_b = _pick_csv_value(row, "Drug_B", "salt_b", "drug_b")
                raw_level = _pick_csv_value(row, "Level", "severity", "level").lower()
                if not raw_a or not raw_b:
                    continue

                if raw_level == "unknown":
                    if drop_unknown:
                        continue
                    severity: str | None = "UNKNOWN"
                else:
                    severity = _LEVEL_MAP.get(raw_level)
                if severity is None:
                    continue

                salt_a = normalize_canonical_salt(raw_a)
                salt_b = normalize_canonical_salt(raw_b)
                if not salt_a or not salt_b or salt_a == salt_b:
                    continue

                id_a = _pick_csv_value(row, "DDInterID_A", "ddinter_id_a") or None
                id_b = _pick_csv_value(row, "DDInterID_B", "ddinter_id_b") or None
                smiles_a = canonicalize_smiles(_pick_csv_value(row, "smiles_a", "SMILES_A"))
                smiles_b = canonicalize_smiles(_pick_csv_value(row, "smiles_b", "SMILES_B"))

                if smiles_a:
                    inline_smiles[salt_a] = smiles_a
                if smiles_b:
                    inline_smiles[salt_b] = smiles_b

                if salt_a > salt_b:
                    salt_a, salt_b = salt_b, salt_a
                    id_a, id_b = id_b, id_a

                pair_key = (salt_a, salt_b)
                existing = merged_pairs.get(pair_key)
                rank_new = SEVERITY_RANK.get(severity, -1)  # type: ignore[arg-type]
                rank_old = SEVERITY_RANK.get(existing["severity"], -1) if existing else -2
                if existing is None or rank_new > rank_old:
                    merged_pairs[pair_key] = {
                        "salt_a": salt_a,
                        "salt_b": salt_b,
                        "severity": severity,
                        "ddinter_id_a": id_a,
                        "ddinter_id_b": id_b,
                        "source": "ddinter_2",
                    }

    return list(merged_pairs.values()), inline_smiles


def _sample_negative_pairs(
    existing_pairs: set[tuple[str, str]],
    resolved_drugs: list[str],
    target_count: int,
    seed: int,
) -> list[dict[str, Any]]:
    if target_count <= 0 or len(resolved_drugs) < 2:
        return []

    rng = random.Random(seed)
    candidates: list[tuple[str, str]] = []
    sorted_drugs = sorted(resolved_drugs)
    for idx, drug_a in enumerate(sorted_drugs):
        for drug_b in sorted_drugs[idx + 1 :]:
            if (drug_a, drug_b) not in existing_pairs:
                candidates.append((drug_a, drug_b))

    rng.shuffle(candidates)
    selected = candidates[:target_count]
    return [
        {
            "salt_a": a,
            "salt_b": b,
            "severity": "NONE",
            "ddinter_id_a": None,
            "ddinter_id_b": None,
            "source": "negative_sampled",
        }
        for a, b in selected
    ]


async def prepare_ddinter_dataset(
    input_paths: list[Path],
    output_path: Path,
    cache_db_path: Path,
    *,
    smiles_seed_csv: Path | None = None,
    negative_ratio: float = 0.0,
    max_drugs: int | None = None,
    seed: int = 42,
    offline: bool = False,
    max_requests_per_second: float = 4.0,
) -> pd.DataFrame:
    cache = SmilesCacheStore(cache_db_path)
    if smiles_seed_csv is not None:
        seeded = load_smiles_seed_csv(smiles_seed_csv)
        cache.bulk_seed(seeded, source_status="seeded")

    raw_records, inline_smiles = parse_ddinter_csv_files(input_paths)
    if inline_smiles:
        cache.bulk_seed(inline_smiles, source_status="inline_csv")

    degree: dict[str, int] = {}
    for rec in raw_records:
        degree[rec["salt_a"]] = degree.get(rec["salt_a"], 0) + 1
        degree[rec["salt_b"]] = degree.get(rec["salt_b"], 0) + 1

    all_drugs = sorted(degree.keys(), key=lambda d: (-degree[d], d))
    if max_drugs is not None and max_drugs > 0:
        all_drugs = all_drugs[:max_drugs]

    resolver = PubChemSmilesResolver(
        cache=cache,
        max_requests_per_second=max_requests_per_second,
        offline=offline,
    )
    smiles_map = await resolver.resolve_many(all_drugs)

    existing_keys = {(r["salt_a"], r["salt_b"]) for r in raw_records}
    if negative_ratio > 0:
        positive_resolved = [
            r for r in raw_records if r["salt_a"] in smiles_map and r["salt_b"] in smiles_map
        ]
        target_negatives = round(len(positive_resolved) * negative_ratio)
        neg_records = _sample_negative_pairs(
            existing_pairs=existing_keys,
            resolved_drugs=list(smiles_map.keys()),
            target_count=target_negatives,
            seed=seed,
        )
        raw_records.extend(neg_records)

    scaffolds: dict[str, str] = {
        drug: compute_murcko_scaffold(smiles) for drug, smiles in smiles_map.items()
    }

    prepared_rows: list[dict[str, Any]] = []
    for rec in raw_records:
        smiles_a = smiles_map.get(rec["salt_a"])
        smiles_b = smiles_map.get(rec["salt_b"])
        if not smiles_a or not smiles_b:
            continue
        prepared_rows.append(
            {
                "salt_a": rec["salt_a"],
                "salt_b": rec["salt_b"],
                "smiles_a": smiles_a,
                "smiles_b": smiles_b,
                "scaffold_a": scaffolds[rec["salt_a"]],
                "scaffold_b": scaffolds[rec["salt_b"]],
                "severity": rec["severity"],
                "ddinter_id_a": rec["ddinter_id_a"],
                "ddinter_id_b": rec["ddinter_id_b"],
                "source": rec["source"],
            }
        )

    df = pd.DataFrame(prepared_rows)
    if not df.empty:
        df = df.sort_values(["salt_a", "salt_b"]).reset_index(drop=True)

    await asyncio.to_thread(_save_dataframe, df, output_path)

    logger.info(
        "ddinter_dataset_prepared",
        input_files=len(input_paths),
        prepared_rows=len(df),
        resolved_drugs=len(smiles_map),
        unresolved_drugs=len(all_drugs) - len(smiles_map),
        output_path=str(output_path),
    )
    return df


def _discover_csvs(input_path: Path) -> list[Path]:
    if input_path.is_file():
        return [input_path]
    return sorted(input_path.glob("*.csv"))


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Prepare DDInter 2.0 CSVs + PubChem SMILES cache for DDI ML training."
    )
    parser.add_argument(
        "--input",
        type=Path,
        required=True,
        help="Path to a DDInter CSV file or directory containing DDInter CSVs.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/processed/ddi_prepared.parquet"),
        help="Output .parquet or .csv path.",
    )
    parser.add_argument(
        "--cache-db",
        type=Path,
        default=Path("data/cache/pubchem_smiles_cache.sqlite3"),
        help="SQLite cache file for PubChem SMILES lookups.",
    )
    parser.add_argument(
        "--smiles-seed-csv",
        type=Path,
        default=None,
        help="Optional CSV (canonical_salt,smiles) to pre-seed the SMILES cache.",
    )
    parser.add_argument(
        "--download-atc-codes",
        nargs="*",
        default=None,
        help="Optional DDInter ATC codes to download before prep (e.g. A B C).",
    )
    parser.add_argument(
        "--negative-ratio",
        type=float,
        default=0.0,
        help="Optional ratio of sampled NONE pairs relative to positive pairs.",
    )
    parser.add_argument(
        "--max-drugs",
        type=int,
        default=None,
        help="Optional cap on top-degree drugs to resolve via PubChem.",
    )
    parser.add_argument("--max-rps", type=float, default=4.0)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--offline", action="store_true")
    return parser


async def _run_cli(args: argparse.Namespace) -> None:
    if args.download_atc_codes:
        codes = tuple(args.download_atc_codes)
        await download_ddinter_csvs(args.input, atc_codes=codes)

    csv_paths = _discover_csvs(args.input)
    if not csv_paths:
        raise FileNotFoundError(f"No CSV files found under {args.input}")

    await prepare_ddinter_dataset(
        input_paths=csv_paths,
        output_path=args.output,
        cache_db_path=args.cache_db,
        smiles_seed_csv=args.smiles_seed_csv,
        negative_ratio=args.negative_ratio,
        max_drugs=args.max_drugs,
        seed=args.seed,
        offline=args.offline,
        max_requests_per_second=args.max_rps,
    )


def main() -> None:
    parser = _build_arg_parser()
    args = parser.parse_args()
    asyncio.run(_run_cli(args))


if __name__ == "__main__":
    main()
