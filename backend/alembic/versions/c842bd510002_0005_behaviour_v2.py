"""Isolate v2 baselines; keep all legacy enrolment rows unchanged."""
from alembic import op
import sqlalchemy as sa
revision="c842bd510002"
down_revision="b731ac409001"
branch_labels=None
depends_on=None

def upgrade():
    op.create_table("behaviour_samples_v2",
        sa.Column("user_id",sa.String(36),sa.ForeignKey("users.id",ondelete="CASCADE"),primary_key=True),
        sa.Column("stage",sa.String(16),primary_key=True),
        sa.Column("ordinal",sa.Integer(),primary_key=True),
        sa.Column("features",sa.JSON(),nullable=False),
        sa.CheckConstraint("stage IN ('TYPING','TAPPING','SWIPING','MOTION')"),
        sa.CheckConstraint("ordinal BETWEEN 1 AND 3"))
    # Carry forward any enrolment progress from the v1 table.
    op.execute(
        "INSERT OR IGNORE INTO behaviour_samples_v2 (user_id, stage, ordinal, features) "
        "SELECT user_id, stage, ordinal, features FROM behaviour_samples"
    )

def downgrade():
    op.drop_table("behaviour_samples_v2")
