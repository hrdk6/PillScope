"""Add revoked refresh tokens table and multi-salt monograph unique constraint.

Revision ID: 0005_production_hardening
Revises: 0004_recall_alerts
Create Date: 2026-11-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0005_production_hardening"
down_revision: str | Sequence[str] | None = "0004_recall_alerts"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "revoked_refresh_tokens",
        sa.Column("jti", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "revoked_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("jti", name=op.f("pk_revoked_refresh_tokens")),
    )
    op.create_index(
        "ix_revoked_refresh_tokens_user_id",
        "revoked_refresh_tokens",
        ["user_id"],
    )
    op.create_index(
        "ix_revoked_refresh_tokens_expires_at",
        "revoked_refresh_tokens",
        ["expires_at"],
    )

    op.drop_constraint(
        "uq_monograph_chunks_section_chunk",
        "monograph_chunks",
        type_="unique",
    )
    op.create_unique_constraint(
        "uq_monograph_chunks_section_chunk",
        "monograph_chunks",
        ["set_id", "canonical_salt", "loinc_section_code", "chunk_index"],
    )


def downgrade() -> None:
    op.drop_constraint(
        "uq_monograph_chunks_section_chunk",
        "monograph_chunks",
        type_="unique",
    )
    op.create_unique_constraint(
        "uq_monograph_chunks_section_chunk",
        "monograph_chunks",
        ["set_id", "loinc_section_code", "chunk_index"],
    )
    op.drop_index("ix_revoked_refresh_tokens_expires_at", table_name="revoked_refresh_tokens")
    op.drop_index("ix_revoked_refresh_tokens_user_id", table_name="revoked_refresh_tokens")
    op.drop_table("revoked_refresh_tokens")
