"""Add multi-profile cabinets and profile scoping for medications and scan_logs.

Revision ID: 0003_multi_profile_cabinets
Revises: 0002_ddi_pairs_c_collation_order
Create Date: 2026-11-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0003_multi_profile_cabinets"
down_revision: str | Sequence[str] | None = "0002_ddi_pairs_c_collation_order"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "profiles",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column(
            "relationship",
            sa.String(length=64),
            server_default=sa.text("'self'"),
            nullable=False,
        ),
        sa.Column(
            "allergies_json",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "is_default",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
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
            ["user_id"],
            ["users.id"],
            name=op.f("fk_profiles_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_profiles")),
    )
    op.create_index("ix_profiles_user_id", "profiles", ["user_id"])
    op.create_index(
        "uq_profiles_user_default",
        "profiles",
        ["user_id"],
        unique=True,
        postgresql_where=sa.text("is_default = true"),
    )

    op.execute(
        """
        INSERT INTO profiles (user_id, name, relationship, allergies_json, is_default)
        SELECT
            id,
            COALESCE(NULLIF(BTRIM(display_name), ''), 'Primary Profile'),
            'self',
            '[]'::jsonb,
            true
        FROM users
        """
    )

    op.add_column(
        "medications",
        sa.Column("profile_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_foreign_key(
        op.f("fk_medications_profile_id_profiles"),
        "medications",
        "profiles",
        ["profile_id"],
        ["id"],
        ondelete="CASCADE",
    )

    op.add_column(
        "scan_logs",
        sa.Column("profile_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "scan_logs",
        sa.Column(
            "allergy_alerts",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )
    op.add_column(
        "scan_logs",
        sa.Column(
            "lifestyle_triggers",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )
    op.create_foreign_key(
        op.f("fk_scan_logs_profile_id_profiles"),
        "scan_logs",
        "profiles",
        ["profile_id"],
        ["id"],
        ondelete="CASCADE",
    )

    op.execute(
        """
        UPDATE medications AS m
        SET profile_id = p.id
        FROM profiles AS p
        WHERE p.user_id = m.user_id AND p.is_default = true AND m.profile_id IS NULL
        """
    )
    op.execute(
        """
        UPDATE scan_logs AS s
        SET profile_id = p.id
        FROM profiles AS p
        WHERE p.user_id = s.user_id AND p.is_default = true AND s.profile_id IS NULL
        """
    )

    op.alter_column("medications", "profile_id", nullable=False)
    op.alter_column("scan_logs", "profile_id", nullable=False)

    op.create_index("ix_medications_profile_active", "medications", ["profile_id", "is_active"])
    op.create_index(
        "ix_scan_logs_profile_created",
        "scan_logs",
        ["profile_id", sa.text("created_at DESC")],
    )


def downgrade() -> None:
    op.drop_index("ix_scan_logs_profile_created", table_name="scan_logs")
    op.drop_index("ix_medications_profile_active", table_name="medications")
    op.drop_constraint(op.f("fk_scan_logs_profile_id_profiles"), "scan_logs", type_="foreignkey")
    op.drop_column("scan_logs", "lifestyle_triggers")
    op.drop_column("scan_logs", "allergy_alerts")
    op.drop_column("scan_logs", "profile_id")
    op.drop_constraint(
        op.f("fk_medications_profile_id_profiles"), "medications", type_="foreignkey"
    )
    op.drop_column("medications", "profile_id")
    op.drop_index("uq_profiles_user_default", table_name="profiles")
    op.drop_index("ix_profiles_user_id", table_name="profiles")
    op.drop_table("profiles")
