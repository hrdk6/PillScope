"""Persist active_recalls snapshot on scan_logs for full history fidelity.

Revision ID: 0006_scan_log_active_recalls
Revises: 0005_production_hardening
Create Date: 2026-11-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0006_scan_log_active_recalls"
down_revision: str | Sequence[str] | None = "0005_production_hardening"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "scan_logs",
        sa.Column(
            "active_recalls",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=False,
            server_default=sa.text("'[]'::jsonb"),
        ),
    )


def downgrade() -> None:
    op.drop_column("scan_logs", "active_recalls")
