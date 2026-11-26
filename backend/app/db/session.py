from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from backend.app.core.config import Settings, get_settings


def build_async_engine(settings: Settings) -> AsyncEngine:
    return create_async_engine(
        settings.database_url,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_pre_ping=True,
        echo=settings.db_echo,
    )


def build_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(
        bind=engine,
        class_=AsyncSession,
        autoflush=False,
        expire_on_commit=False,
    )


_default_engine: AsyncEngine | None = None
_default_session_factory: async_sessionmaker[AsyncSession] | None = None


def get_engine() -> AsyncEngine:
    global _default_engine
    if _default_engine is None:
        _default_engine = build_async_engine(get_settings())
    return _default_engine


def get_session_factory() -> async_sessionmaker[AsyncSession]:
    global _default_session_factory
    if _default_session_factory is None:
        _default_session_factory = build_session_factory(get_engine())
    return _default_session_factory


async def dispose_engine() -> None:
    global _default_engine, _default_session_factory
    if _default_engine is not None:
        await _default_engine.dispose()
        _default_engine = None
        _default_session_factory = None


async def get_db_session() -> AsyncIterator[AsyncSession]:
    factory = get_session_factory()
    async with factory() as session:
        yield session
