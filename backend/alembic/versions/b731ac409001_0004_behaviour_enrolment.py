"""Add user-scoped behavioural enrolment without altering existing data."""
from alembic import op
import sqlalchemy as sa

revision = "b731ac409001"
down_revision = "e025a0f96929"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "behaviour_samples",
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("stage", sa.String(16), primary_key=True),
        sa.Column("ordinal", sa.Integer(), primary_key=True),
        sa.Column("features", sa.JSON(), nullable=False),
        sa.CheckConstraint("stage IN ('TYPING', 'TAPPING', 'SWIPING', 'MOTION')"),
        sa.CheckConstraint("ordinal BETWEEN 1 AND 3"),
    )


def downgrade():
    op.drop_table("behaviour_samples")
