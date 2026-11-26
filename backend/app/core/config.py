from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_DEV_ADMIN_KEY = "dev-internal-admin-key"
_DEV_JWT_SECRET = "pillscope-dev-secret-key-change-in-production-32bytes"
_PLACEHOLDER_MARKERS: tuple[str, ...] = (
    "replace-with",
    "changeme",
    "pillscope_dev_password",
    "dev-internal-admin-key",
    "pillscope-dev-secret",
)


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
    enable_internal_routes: bool = True
    allowed_ingest_dir: str = "data"
    rate_limit_enabled: bool | None = None
    rate_limit_auth_per_minute: int = 20
    rate_limit_scan_per_minute: int = 30
    rate_limit_assistant_per_minute: int = 30
    rate_limit_api_per_minute: int = 120
    rate_limit_internal_per_minute: int = 10
    trusted_proxy_ips: list[str] = Field(default_factory=list)
    privacy_policy_url: str = "https://api.pillscope.app/privacy"
    account_deletion_url: str = "https://api.pillscope.app/account-deletion"

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
    gemini_tts_model: str = "gemini-3.8-flash-tts"
    gemini_tts_voice: str = "Kore"
    openai_tts_model: str = "gpt-4o-mini-tts"
    openai_tts_voice: str = "alloy"
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
            jwt_lower = self.jwt_secret_key.lower()
            if (
                self.jwt_secret_key == _DEV_JWT_SECRET
                or len(self.jwt_secret_key) < 32
                or any(marker in jwt_lower for marker in _PLACEHOLDER_MARKERS)
            ):
                raise ValueError(
                    "JWT_SECRET_KEY must be overridden with >=32 random non-placeholder chars "
                    "in staging/production."
                )
            admin_lower = self.internal_admin_api_key.lower()
            if (
                self.internal_admin_api_key == _DEV_ADMIN_KEY
                or len(self.internal_admin_api_key) < 16
                or any(marker in admin_lower for marker in _PLACEHOLDER_MARKERS)
            ):
                raise ValueError(
                    "INTERNAL_ADMIN_API_KEY must be overridden with a strong non-placeholder key "
                    "in staging/production."
                )
            db_lower = self.database_url.lower()
            if any(marker in db_lower for marker in _PLACEHOLDER_MARKERS):
                raise ValueError(
                    "DATABASE_URL must not contain development/placeholder credentials "
                    "in staging/production."
                )
            if self.app_env == "production" and "@" not in self.redis_url:
                raise ValueError("REDIS_URL must include authentication credentials in production.")
        return self

    @property
    def effective_rate_limit_enabled(self) -> bool:
        if self.rate_limit_enabled is not None:
            return self.rate_limit_enabled
        return self.app_env != "test"


@lru_cache
def get_settings() -> Settings:
    return Settings()
