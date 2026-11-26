"""Use C collation on ddi_pairs canonical_order check constraint to match Python ordering.

Revision ID: 0002_ddi_pairs_c_collation_order
Revises: 0001_initial_schema
Create Date: 2026-11-26
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0002_ddi_pairs_c_collation_order"
down_revision: str | Sequence[str] | None = "0001_initial_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.drop_constraint(op.f("ck_ddi_pairs_canonical_order"), "ddi_pairs", type_="check")
    op.create_check_constraint(
        op.f("ck_ddi_pairs_canonical_order"),
        "ddi_pairs",
        '(salt_a COLLATE "C") < (salt_b COLLATE "C")',
    )


def downgrade() -> None:
    op.drop_constraint(op.f("ck_ddi_pairs_canonical_order"), "ddi_pairs", type_="check")
    op.create_check_constraint(
        op.f("ck_ddi_pairs_canonical_order"),
        "ddi_pairs",
        "salt_a < salt_b",
    )
