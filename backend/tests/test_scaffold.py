import sys
from importlib import import_module
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]


def test_python_runtime_is_3_12() -> None:
    assert sys.version_info[:2] == (3, 12)


def test_monorepo_layout_exists() -> None:
    expected_paths = [
        REPO_ROOT / "ARCHITECTURE.md",
        REPO_ROOT / "pyproject.toml",
        REPO_ROOT / ".env.example",
        REPO_ROOT / ".github" / "workflows" / "ci.yml",
        REPO_ROOT / "backend" / "app",
        REPO_ROOT / "ml" / "ddi",
        REPO_ROOT / "ml" / "edge_vision",
        REPO_ROOT / "eval" / "benchmarks",
        REPO_ROOT / "eval" / "rag_eval",
        REPO_ROOT / "eval" / "tracing",
        REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pillscope" / "app",
        REPO_ROOT / "infra" / "docker-compose.yml",
        REPO_ROOT / "infra" / "docker" / "backend.Dockerfile",
        REPO_ROOT / "infra" / "postgres" / "init" / "01_extensions.sql",
    ]
    for path in expected_paths:
        assert path.exists(), f"Missing expected monorepo path: {path}"


def test_python_packages_are_importable() -> None:
    for module_name in (
        "backend.app",
        "backend.app.core",
        "backend.app.db",
        "backend.app.schemas",
        "backend.app.api",
        "backend.app.services",
        "backend.app.workers",
        "ml.ddi",
        "ml.edge_vision",
        "eval.benchmarks",
        "eval.rag_eval",
        "eval.tracing",
    ):
        assert import_module(module_name) is not None


def test_env_example_contains_required_keys() -> None:
    raw_lines = (REPO_ROOT / ".env.example").read_text(encoding="utf-8").splitlines()
    keys = {
        line.split("=", 1)[0].strip()
        for line in raw_lines
        if line.strip() and not line.lstrip().startswith("#") and "=" in line
    }
    required_keys = {
        "APP_ENV",
        "DATABASE_URL",
        "REDIS_URL",
        "JWT_SECRET_KEY",
        "LLM_PROVIDER",
        "EMBEDDING_MODEL",
        "EMBEDDING_DIM",
        "RERANKER_MODEL",
        "DDI_MODEL_ARTIFACT_PATH",
        "OPENFDA_BASE_URL",
        "DAILYMED_BASE_URL",
        "DDINTER_DATA_URL",
    }
    assert required_keys.issubset(keys)


def test_docker_compose_defines_required_services() -> None:
    compose_path = REPO_ROOT / "infra" / "docker-compose.yml"
    compose = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    services = compose["services"]

    assert {"postgres", "redis", "api", "worker"}.issubset(services.keys())
    assert "pgvector" in services["postgres"]["image"]

    init_sql = (REPO_ROOT / "infra" / "postgres" / "init" / "01_extensions.sql").read_text(
        encoding="utf-8"
    )
    for extension in ("pgcrypto", "vector", "pg_trgm"):
        assert extension in init_sql
