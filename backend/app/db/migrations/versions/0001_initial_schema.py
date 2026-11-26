"""Initial PostgreSQL + pgvector schema for PillScope.

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-11-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects import postgresql

revision: str = "0001_initial_schema"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    for ext in ("pgcrypto", "vector", "pg_trgm"):
        op.execute(
            f"DO $$ BEGIN CREATE EXTENSION IF NOT EXISTS {ext}; "
            "EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;"
        )

    op.create_table(
        "users",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("password_hash", sa.Text(), nullable=False),
        sa.Column("display_name", sa.String(length=120), nullable=True),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
        sa.UniqueConstraint("email", name=op.f("uq_users_email")),
    )

    op.create_table(
        "brand_salt_mappings",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("brand_name", sa.String(length=255), nullable=False),
        sa.Column("normalized_brand", sa.String(length=255), nullable=False),
        sa.Column("manufacturer", sa.String(length=255), nullable=True),
        sa.Column(
            "country_code", sa.CHAR(length=2), server_default=sa.text("'IN'"), nullable=False
        ),
        sa.Column("dosage_form", sa.String(length=64), nullable=True),
        sa.Column("generic_salts", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("canonical_salt_keys", postgresql.ARRAY(sa.Text()), nullable=False),
        sa.Column(
            "smiles_map",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("source", sa.String(length=64), nullable=False),
        sa.Column("source_id", sa.String(length=128), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_brand_salt_mappings")),
    )
    op.execute(
        "CREATE UNIQUE INDEX uq_brand_salt_mappings_brand_country_form "
        "ON brand_salt_mappings (normalized_brand, country_code, COALESCE(dosage_form, ''))"
    )
    op.create_index(
        "ix_brand_salt_mappings_normalized_brand_trgm",
        "brand_salt_mappings",
        ["normalized_brand"],
        unique=False,
        postgresql_using="gin",
        postgresql_ops={"normalized_brand": "gin_trgm_ops"},
    )
    op.create_index(
        "ix_brand_salt_mappings_canonical_salt_keys_gin",
        "brand_salt_mappings",
        ["canonical_salt_keys"],
        unique=False,
        postgresql_using="gin",
    )

    op.create_table(
        "medications",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("brand_mapping_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("display_name", sa.String(length=255), nullable=False),
        sa.Column("generic_salts", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("canonical_salt_keys", postgresql.ARRAY(sa.Text()), nullable=False),
        sa.Column("dosage_schedule", sa.String(length=120), nullable=True),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["brand_mapping_id"],
            ["brand_salt_mappings.id"],
            name=op.f("fk_medications_brand_mapping_id_brand_salt_mappings"),
            ondelete="SET NULL",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_medications_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_medications")),
    )
    op.create_index("ix_medications_user_active", "medications", ["user_id", "is_active"])
    op.create_index(
        "ix_medications_canonical_salt_keys_gin",
        "medications",
        ["canonical_salt_keys"],
        unique=False,
        postgresql_using="gin",
    )

    op.create_table(
        "ddi_pairs",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("salt_a", sa.String(length=160), nullable=False),
        sa.Column("salt_b", sa.String(length=160), nullable=False),
        sa.Column("salt_a_smiles", sa.Text(), nullable=True),
        sa.Column("salt_b_smiles", sa.Text(), nullable=True),
        sa.Column("severity", sa.String(length=16), nullable=False),
        sa.Column("mechanism", sa.Text(), nullable=False),
        sa.Column("clinical_effect", sa.Text(), nullable=False),
        sa.Column("management", sa.Text(), nullable=False),
        sa.Column(
            "evidence_level",
            sa.String(length=32),
            server_default=sa.text("'CURATED'"),
            nullable=False,
        ),
        sa.Column("sources", postgresql.ARRAY(sa.Text()), nullable=False),
        sa.Column(
            "source_record_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "is_hard_safety_override",
            sa.Boolean(),
            sa.Computed("severity = 'MAJOR'", persisted=True),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint("salt_a < salt_b", name=op.f("ck_ddi_pairs_canonical_order")),
        sa.CheckConstraint(
            "severity IN ('MAJOR', 'MODERATE', 'MINOR', 'UNKNOWN')",
            name=op.f("ck_ddi_pairs_severity_valid"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_ddi_pairs")),
        sa.UniqueConstraint("salt_a", "salt_b", name="uq_ddi_pairs_salt_pair"),
    )
    op.create_index("ix_ddi_pairs_salt_pair", "ddi_pairs", ["salt_a", "salt_b"])
    op.create_index(
        "ix_ddi_pairs_major_only",
        "ddi_pairs",
        ["salt_a", "salt_b"],
        unique=False,
        postgresql_where=sa.text("severity = 'MAJOR'"),
    )

    op.create_table(
        "monograph_chunks",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("set_id", sa.String(length=128), nullable=False),
        sa.Column("document_version", sa.String(length=32), nullable=True),
        sa.Column("canonical_salt", sa.String(length=160), nullable=False),
        sa.Column("loinc_section_code", sa.String(length=32), nullable=False),
        sa.Column("section_title", sa.String(length=160), nullable=False),
        sa.Column("chunk_index", sa.Integer(), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("content_sha256", sa.CHAR(length=64), nullable=False),
        sa.Column("source_url", sa.Text(), nullable=False),
        sa.Column(
            "fts_vector",
            postgresql.TSVECTOR(),
            sa.Computed(
                "to_tsvector('english', coalesce(canonical_salt, '') || ' ' || "
                "coalesce(section_title, '') || ' ' || content)",
                persisted=True,
            ),
            nullable=False,
        ),
        sa.Column("embedding", Vector(768), nullable=False),
        sa.Column(
            "metadata",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_monograph_chunks")),
        sa.UniqueConstraint(
            "set_id",
            "loinc_section_code",
            "chunk_index",
            name="uq_monograph_chunks_section_chunk",
        ),
    )
    op.create_index(
        "ix_monograph_chunks_salt_section",
        "monograph_chunks",
        ["canonical_salt", "loinc_section_code"],
    )
    op.create_index(
        "ix_monograph_chunks_fts_gin",
        "monograph_chunks",
        ["fts_vector"],
        unique=False,
        postgresql_using="gin",
    )
    op.create_index(
        "ix_monograph_chunks_embedding_hnsw",
        "monograph_chunks",
        ["embedding"],
        unique=False,
        postgresql_using="hnsw",
        postgresql_with={"m": 16, "ef_construction": 64},
        postgresql_ops={"embedding": "vector_cosine_ops"},
    )

    op.create_table(
        "scan_logs",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("input_mode", sa.String(length=16), nullable=False),
        sa.Column("raw_ocr_text", sa.Text(), nullable=True),
        sa.Column("edge_quality_metrics", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("submitted_query", sa.String(length=255), nullable=False),
        sa.Column("resolved_salts", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("cabinet_snapshot", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("overall_risk", sa.String(length=16), nullable=False),
        sa.Column(
            "safety_banner_locked",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
        sa.Column(
            "deterministic_alerts",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "duplicate_salts",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "interactions",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("clinical_summary", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column(
            "citations",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "citation_verified",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
        sa.Column("trace_id", sa.String(length=64), nullable=True),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "input_mode IN ('CAMERA_OCR', 'MANUAL_TEXT', 'BARCODE')",
            name=op.f("ck_scan_logs_input_mode_valid"),
        ),
        sa.CheckConstraint(
            "overall_risk IN ('MAJOR', 'MODERATE', 'MINOR', 'NONE')",
            name=op.f("ck_scan_logs_overall_risk_valid"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_scan_logs_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_scan_logs")),
    )
    op.create_index(
        "ix_scan_logs_user_created",
        "scan_logs",
        ["user_id", sa.text("created_at DESC")],
    )
    op.create_index(
        "ix_scan_logs_risk_created",
        "scan_logs",
        ["overall_risk", sa.text("created_at DESC")],
    )


def downgrade() -> None:
    op.drop_table("scan_logs")
    op.drop_table("monograph_chunks")
    op.drop_table("ddi_pairs")
    op.drop_table("medications")
    op.drop_table("brand_salt_mappings")
    op.drop_table("users")
