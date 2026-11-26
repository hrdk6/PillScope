import os
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio
from alembic import command
from alembic.config import Config
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from backend.app.core.config import Settings, get_settings
from backend.app.db.session import build_async_engine, build_session_factory, get_db_session
from backend.app.main import create_app

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"


@pytest.fixture(scope="session")
def test_settings() -> Settings:
    database_url = os.environ.get(
        "TEST_DATABASE_URL",
        os.environ.get(
            "DATABASE_URL",
            "postgresql+asyncpg://pillscope:pillscope_dev_password@localhost:5432/pillscope",
        ),
    )
    redis_url = os.environ.get("TEST_REDIS_URL", "redis://localhost:6379/0")
    return Settings(
        app_env="test",
        database_url=database_url,
        redis_url=redis_url,
        jwt_secret_key="test-secret-key-for-pillscope-integration-tests-32b",
        trigram_similarity_threshold=0.25,
    )


@pytest.fixture(scope="session")
def migrated_database(test_settings: Settings) -> str:
    previous_url = os.environ.get("DATABASE_URL")
    os.environ["DATABASE_URL"] = test_settings.database_url
    try:
        alembic_cfg = Config(str(REPO_ROOT / "alembic.ini"))
        command.upgrade(alembic_cfg, "head")
    finally:
        if previous_url is None:
            os.environ.pop("DATABASE_URL", None)
        else:
            os.environ["DATABASE_URL"] = previous_url
    return test_settings.database_url


@pytest_asyncio.fixture
async def db_engine(
    test_settings: Settings,
    migrated_database: str,
) -> AsyncIterator[AsyncEngine]:
    assert migrated_database == test_settings.database_url
    engine = build_async_engine(test_settings)
    try:
        yield engine
    finally:
        await engine.dispose()


@pytest_asyncio.fixture
async def db_session_factory(
    db_engine: AsyncEngine,
) -> async_sessionmaker[AsyncSession]:
    factory = build_session_factory(db_engine)
    async with factory() as session:
        await session.execute(
            text(
                "TRUNCATE TABLE revoked_refresh_tokens, recall_alerts, scan_logs, "
                "monograph_chunks, ddi_pairs, medications, profiles, brand_salt_mappings, users "
                "RESTART IDENTITY CASCADE"
            )
        )
        await session.commit()
    return factory


@pytest_asyncio.fixture
async def db_session(
    db_session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    async with db_session_factory() as session:
        yield session


@pytest_asyncio.fixture
async def api_client(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncClient]:
    app = create_app(settings=test_settings)

    async def _override_db_session() -> AsyncIterator[AsyncSession]:
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _override_db_session
    app.dependency_overrides[get_settings] = lambda: test_settings

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        yield client
