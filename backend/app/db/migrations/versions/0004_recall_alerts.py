"""Add FDA drug recall enforcement alerts table.

Revision ID: 0004_recall_alerts
Revises: 0003_multi_profile_cabinets
Create Date: 2026-11-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0004_recall_alerts"
down_revision: str | Sequence[str] | None = "0003_multi_profile_cabinets"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "recall_alerts",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("recall_number", sa.String(length=64), nullable=False),
        sa.Column("event_id", sa.String(length=64), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("classification", sa.String(length=16), nullable=False),
        sa.Column("recalling_firm", sa.String(length=255), nullable=True),
        sa.Column("product_description", sa.Text(), nullable=False),
        sa.Column("reason_for_recall", sa.Text(), nullable=False),
        sa.Column("distribution_pattern", sa.Text(), nullable=True),
        sa.Column("code_info", sa.Text(), nullable=True),
        sa.Column("voluntary_mandated", sa.String(length=128), nullable=True),
        sa.Column("report_date", sa.String(length=16), nullable=True),
        sa.Column("recall_initiation_date", sa.String(length=16), nullable=True),
        sa.Column("termination_date", sa.String(length=16), nullable=True),
        sa.Column(
            "brand_names",
            postgresql.ARRAY(sa.Text()),
            server_default=sa.text("'{}'::text[]"),
            nullable=False,
        ),
        sa.Column(
            "generic_names",
            postgresql.ARRAY(sa.Text()),
            server_default=sa.text("'{}'::text[]"),
            nullable=False,
        ),
        sa.Column(
            "canonical_salt_keys",
            postgresql.ARRAY(sa.Text()),
            server_default=sa.text("'{}'::text[]"),
            nullable=False,
        ),
        sa.Column(
            "raw_payload",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("source_url", sa.Text(), nullable=False),
        sa.Column(
            "ingested_at",
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
        sa.CheckConstraint(
            "classification IN ('Class I', 'Class II', 'Class III')",
            name=op.f("ck_recall_alerts_classification_valid"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_recall_alerts")),
        sa.UniqueConstraint("recall_number", name="uq_recall_alerts_recall_number"),
    )
    op.create_index("ix_recall_alerts_active_class", "recall_alerts", ["status", "classification"])
    op.create_index(
        "ix_recall_alerts_canonical_salt_keys_gin",
        "recall_alerts",
        ["canonical_salt_keys"],
        unique=False,
        postgresql_using="gin",
    )
    op.create_index(
        "ix_recall_alerts_brand_names_gin",
        "recall_alerts",
        ["brand_names"],
        unique=False,
        postgresql_using="gin",
    )


def downgrade() -> None:
    op.drop_index("ix_recall_alerts_brand_names_gin", table_name="recall_alerts")
    op.drop_index("ix_recall_alerts_canonical_salt_keys_gin", table_name="recall_alerts")
    op.drop_index("ix_recall_alerts_active_class", table_name="recall_alerts")
    op.drop_table("recall_alerts")
