import argparse
import asyncio
from pathlib import Path

from backend.app.core.config import get_settings
from backend.app.core.logging import configure_logging
from backend.app.db.session import build_async_engine, build_session_factory
from backend.app.services.embeddings import get_embedding_provider
from backend.app.workers.tasks import (
    ingest_drug_monographs_job,
    ingest_monographs_from_file_job,
)


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Ingest openFDA / DailyMed SPL drug monographs into PostgreSQL + pgvector."
    )
    parser.add_argument(
        "--drugs",
        nargs="+",
        default=None,
        help="Generic drug names to fetch from openFDA / DailyMed.",
    )
    parser.add_argument(
        "--json-file",
        type=Path,
        default=None,
        help="Local openFDA drug label JSON file to ingest.",
    )
    parser.add_argument(
        "--source",
        choices=["auto", "openfda", "dailymed"],
        default="auto",
    )
    return parser


async def _run(args: argparse.Namespace) -> None:
    settings = get_settings()
    configure_logging(log_level=settings.log_level, app_env=settings.app_env)
    engine = build_async_engine(settings)
    ctx = {
        "settings": settings,
        "session_factory": build_session_factory(engine),
        "embedding_provider": get_embedding_provider(settings),
    }
    try:
        if args.json_file is not None:
            summary = await ingest_monographs_from_file_job(ctx, str(args.json_file))
        elif args.drugs:
            summary = await ingest_drug_monographs_job(ctx, args.drugs, source=args.source)
        else:
            raise ValueError("Provide either --drugs or --json-file.")
        print(summary)
    finally:
        await engine.dispose()


def main() -> None:
    args = _build_arg_parser().parse_args()
    asyncio.run(_run(args))


if __name__ == "__main__":
    main()
