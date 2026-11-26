from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_DEV_ADMIN_KEY = "dev-internal-admin-key"
_DEV_JWT_SECRET = "pillscope-dev-secret-key-change-in-production-32bytes"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_env: Literal["development", "staging", "production", "test"] = "development"
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    log_level: str = "INFO"
    cors_origins: list[str] = Field(default_factory=lambda: ["http://localhost:3000"])
    internal_admin_api_key: str = _DEV_ADMIN_KEY

    database_url: str = (
        "postgresql+asyncpg://pillscope:pillscope_dev_password@localhost:5432/pillscope"
    )
    db_pool_size: int = 10
    db_max_overflow: int = 20
    db_echo: bool = False

    redis_url: str = "redis://localhost:6379/0"

    jwt_secret_key: str = _DEV_JWT_SECRET
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 14

    embedding_provider: Literal["local", "gemini", "openai"] = "local"
    embedding_model: str = "BAAI/bge-base-en-v1.5"
    embedding_dim: int = 768
    reranker_model: str = "BAAI/bge-reranker-base"

    llm_provider: Literal["gemini", "openai"] = "gemini"
    llm_model: str = "gemini-2.5-flash"
    gemini_api_key: str = ""
    openai_api_key: str = ""
    llm_timeout_seconds: float = 20.0

    openfda_base_url: str = "https://api.fda.gov/drug/label.json"
    openfda_enforcement_url: str = "https://api.fda.gov/drug/enforcement.json"
    openfda_api_key: str = ""
    dailymed_base_url: str = "https://dailymed.nlm.nih.gov/dailymed/services/v2"

    rag_top_k_lexical: int = 12
    rag_top_k_dense: int = 12
    rag_top_k_rerank: int = 5
    rag_rrf_k: int = 60
    rag_min_dense_similarity: float = 0.20

    india_brand_catalog_path: str = "data/raw/india_brands.csv"
    trigram_similarity_threshold: float = 0.25

    ddi_model_artifact_path: str = "ml/artifacts/ddi_ensemble.joblib"
    ddi_ml_major_threshold: float | None = None
    ddi_ml_moderate_threshold: float | None = None
    smiles_cache_db_path: str = "data/cache/pubchem_smiles_cache.sqlite3"

    otel_service_name: str = "pillscope-backend"
    otel_exporter_otlp_endpoint: str = ""
    langfuse_public_key: str = ""
    langfuse_secret_key: str = ""
    langfuse_host: str = "https://cloud.langfuse.com"

    @model_validator(mode="after")
    def validate_production_secrets(self) -> "Settings":
        if self.app_env in {"staging", "production"}:
            if self.jwt_secret_key == _DEV_JWT_SECRET or len(self.jwt_secret_key) < 32:
                raise ValueError(
                    "JWT_SECRET_KEY must be overridden with >=32 random chars "
                    "in staging/production."
                )
            if (
                self.internal_admin_api_key == _DEV_ADMIN_KEY
                or len(self.internal_admin_api_key) < 16
            ):
                raise ValueError(
                    "INTERNAL_ADMIN_API_KEY must be overridden with a strong key "
                    "in staging/production."
                )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
