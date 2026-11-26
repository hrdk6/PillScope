from datetime import datetime
from typing import Any
from uuid import UUID

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    CHAR,
    Boolean,
    CheckConstraint,
    Computed,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    desc,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, TSVECTOR
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.orm import relationship as orm_relationship

from backend.app.db.base import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    email: Mapped[str] = mapped_column(String(255), nullable=False, unique=True)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    display_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("true"))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    profiles: Mapped[list["Profile"]] = orm_relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    medications: Mapped[list["Medication"]] = orm_relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    scan_logs: Mapped[list["ScanLog"]] = orm_relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class Profile(Base):
    __tablename__ = "profiles"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    relationship: Mapped[str] = mapped_column(
        String(64), nullable=False, server_default=text("'self'")
    )
    allergies_json: Mapped[list[Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    is_default: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    user: Mapped["User"] = orm_relationship(back_populates="profiles")
    medications: Mapped[list["Medication"]] = orm_relationship(
        back_populates="profile", cascade="all, delete-orphan"
    )
    scan_logs: Mapped[list["ScanLog"]] = orm_relationship(
        back_populates="profile", cascade="all, delete-orphan"
    )

    __table_args__ = (
        Index("ix_profiles_user_id", "user_id"),
        Index(
            "uq_profiles_user_default",
            "user_id",
            unique=True,
            postgresql_where=text("is_default = true"),
        ),
    )


class BrandSaltMapping(Base):
    __tablename__ = "brand_salt_mappings"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    brand_name: Mapped[str] = mapped_column(String(255), nullable=False)
    normalized_brand: Mapped[str] = mapped_column(String(255), nullable=False)
    manufacturer: Mapped[str | None] = mapped_column(String(255), nullable=True)
    country_code: Mapped[str] = mapped_column(CHAR(2), nullable=False, server_default=text("'IN'"))
    dosage_form: Mapped[str | None] = mapped_column(String(64), nullable=True)
    generic_salts: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False)
    canonical_salt_keys: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    smiles_map: Mapped[dict[str, str]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    source: Mapped[str] = mapped_column(String(64), nullable=False)
    source_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    __table_args__ = (
        Index(
            "uq_brand_salt_mappings_brand_country_form",
            "normalized_brand",
            "country_code",
            func.coalesce(dosage_form, ""),
            unique=True,
        ),
        Index(
            "ix_brand_salt_mappings_normalized_brand_trgm",
            "normalized_brand",
            postgresql_using="gin",
            postgresql_ops={"normalized_brand": "gin_trgm_ops"},
        ),
        Index(
            "ix_brand_salt_mappings_canonical_salt_keys_gin",
            "canonical_salt_keys",
            postgresql_using="gin",
        ),
    )


class Medication(Base):
    __tablename__ = "medications"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    profile_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("profiles.id", ondelete="CASCADE"),
        nullable=False,
    )
    brand_mapping_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("brand_salt_mappings.id", ondelete="SET NULL"),
        nullable=True,
    )
    display_name: Mapped[str] = mapped_column(String(255), nullable=False)
    generic_salts: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False)
    canonical_salt_keys: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    dosage_schedule: Mapped[str | None] = mapped_column(String(120), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("true"))
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    user: Mapped["User"] = orm_relationship(back_populates="medications")
    profile: Mapped["Profile"] = orm_relationship(back_populates="medications")
    brand_mapping: Mapped["BrandSaltMapping | None"] = orm_relationship()

    __table_args__ = (
        Index("ix_medications_user_active", "user_id", "is_active"),
        Index("ix_medications_profile_active", "profile_id", "is_active"),
        Index(
            "ix_medications_canonical_salt_keys_gin",
            "canonical_salt_keys",
            postgresql_using="gin",
        ),
    )


class DdiPair(Base):
    __tablename__ = "ddi_pairs"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    salt_a: Mapped[str] = mapped_column(String(160), nullable=False)
    salt_b: Mapped[str] = mapped_column(String(160), nullable=False)
    salt_a_smiles: Mapped[str | None] = mapped_column(Text, nullable=True)
    salt_b_smiles: Mapped[str | None] = mapped_column(Text, nullable=True)
    severity: Mapped[str] = mapped_column(String(16), nullable=False)
    mechanism: Mapped[str] = mapped_column(Text, nullable=False)
    clinical_effect: Mapped[str] = mapped_column(Text, nullable=False)
    management: Mapped[str] = mapped_column(Text, nullable=False)
    evidence_level: Mapped[str] = mapped_column(
        String(32), nullable=False, server_default=text("'CURATED'")
    )
    sources: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    source_record_ids: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    is_hard_safety_override: Mapped[bool] = mapped_column(
        Boolean,
        Computed("severity = 'MAJOR'", persisted=True),
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    __table_args__ = (
        CheckConstraint('(salt_a COLLATE "C") < (salt_b COLLATE "C")', name="canonical_order"),
        CheckConstraint(
            "severity IN ('MAJOR', 'MODERATE', 'MINOR', 'UNKNOWN')",
            name="severity_valid",
        ),
        UniqueConstraint("salt_a", "salt_b", name="uq_ddi_pairs_salt_pair"),
        Index("ix_ddi_pairs_salt_pair", "salt_a", "salt_b"),
        Index(
            "ix_ddi_pairs_major_only",
            "salt_a",
            "salt_b",
            postgresql_where=text("severity = 'MAJOR'"),
        ),
    )


class MonographChunk(Base):
    __tablename__ = "monograph_chunks"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    set_id: Mapped[str] = mapped_column(String(128), nullable=False)
    document_version: Mapped[str | None] = mapped_column(String(32), nullable=True)
    canonical_salt: Mapped[str] = mapped_column(String(160), nullable=False)
    loinc_section_code: Mapped[str] = mapped_column(String(32), nullable=False)
    section_title: Mapped[str] = mapped_column(String(160), nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    content_sha256: Mapped[str] = mapped_column(CHAR(64), nullable=False)
    source_url: Mapped[str] = mapped_column(Text, nullable=False)
    fts_vector: Mapped[Any] = mapped_column(
        TSVECTOR,
        Computed(
            "to_tsvector('english', coalesce(canonical_salt, '') || ' ' || "
            "coalesce(section_title, '') || ' ' || content)",
            persisted=True,
        ),
        nullable=False,
    )
    embedding: Mapped[list[float]] = mapped_column(Vector(768), nullable=False)
    chunk_metadata: Mapped[dict[str, Any]] = mapped_column(
        "metadata", JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        UniqueConstraint(
            "set_id",
            "canonical_salt",
            "loinc_section_code",
            "chunk_index",
            name="uq_monograph_chunks_section_chunk",
        ),
        Index(
            "ix_monograph_chunks_salt_section",
            "canonical_salt",
            "loinc_section_code",
        ),
        Index(
            "ix_monograph_chunks_fts_gin",
            "fts_vector",
            postgresql_using="gin",
        ),
        Index(
            "ix_monograph_chunks_embedding_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_with={"m": 16, "ef_construction": 64},
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )


class ScanLog(Base):
    __tablename__ = "scan_logs"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    profile_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("profiles.id", ondelete="CASCADE"),
        nullable=False,
    )
    input_mode: Mapped[str] = mapped_column(String(16), nullable=False)
    raw_ocr_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    edge_quality_metrics: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    submitted_query: Mapped[str] = mapped_column(String(255), nullable=False)
    resolved_salts: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False)
    cabinet_snapshot: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False)
    overall_risk: Mapped[str] = mapped_column(String(16), nullable=False)
    safety_banner_locked: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("false")
    )
    deterministic_alerts: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    duplicate_salts: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    allergy_alerts: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    lifestyle_triggers: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    active_recalls: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    interactions: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    clinical_summary: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    citations: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    citation_verified: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("false")
    )
    trace_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    user: Mapped["User"] = orm_relationship(back_populates="scan_logs")
    profile: Mapped["Profile"] = orm_relationship(back_populates="scan_logs")

    __table_args__ = (
        CheckConstraint(
            "input_mode IN ('CAMERA_OCR', 'MANUAL_TEXT', 'BARCODE')",
            name="input_mode_valid",
        ),
        CheckConstraint(
            "overall_risk IN ('MAJOR', 'MODERATE', 'MINOR', 'NONE')",
            name="overall_risk_valid",
        ),
        Index("ix_scan_logs_user_created", "user_id", desc("created_at")),
        Index("ix_scan_logs_profile_created", "profile_id", desc("created_at")),
        Index("ix_scan_logs_risk_created", "overall_risk", desc("created_at")),
    )


class RecallAlert(Base):
    __tablename__ = "recall_alerts"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    recall_number: Mapped[str] = mapped_column(String(64), nullable=False)
    event_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    classification: Mapped[str] = mapped_column(String(16), nullable=False)
    recalling_firm: Mapped[str | None] = mapped_column(String(255), nullable=True)
    product_description: Mapped[str] = mapped_column(Text, nullable=False)
    reason_for_recall: Mapped[str] = mapped_column(Text, nullable=False)
    distribution_pattern: Mapped[str | None] = mapped_column(Text, nullable=True)
    code_info: Mapped[str | None] = mapped_column(Text, nullable=True)
    voluntary_mandated: Mapped[str | None] = mapped_column(String(128), nullable=True)
    report_date: Mapped[str | None] = mapped_column(String(16), nullable=True)
    recall_initiation_date: Mapped[str | None] = mapped_column(String(16), nullable=True)
    termination_date: Mapped[str | None] = mapped_column(String(16), nullable=True)
    brand_names: Mapped[list[str]] = mapped_column(
        ARRAY(Text), nullable=False, server_default=text("'{}'::text[]")
    )
    generic_names: Mapped[list[str]] = mapped_column(
        ARRAY(Text), nullable=False, server_default=text("'{}'::text[]")
    )
    canonical_salt_keys: Mapped[list[str]] = mapped_column(
        ARRAY(Text), nullable=False, server_default=text("'{}'::text[]")
    )
    raw_payload: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    source_url: Mapped[str] = mapped_column(Text, nullable=False)
    ingested_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    __table_args__ = (
        UniqueConstraint("recall_number", name="uq_recall_alerts_recall_number"),
        CheckConstraint(
            "classification IN ('Class I', 'Class II', 'Class III')",
            name="classification_valid",
        ),
        Index("ix_recall_alerts_active_class", "status", "classification"),
        Index(
            "ix_recall_alerts_canonical_salt_keys_gin",
            "canonical_salt_keys",
            postgresql_using="gin",
        ),
        Index(
            "ix_recall_alerts_brand_names_gin",
            "brand_names",
            postgresql_using="gin",
        ),
    )


class RevokedRefreshToken(Base):
    __tablename__ = "revoked_refresh_tokens"

    jti: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        Index("ix_revoked_refresh_tokens_user_id", "user_id"),
        Index("ix_revoked_refresh_tokens_expires_at", "expires_at"),
    )
