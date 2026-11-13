"""Durable, user-scoped enrolment receipts; never stores raw sensor traces."""
from sqlalchemy import CheckConstraint, ForeignKey, Integer, JSON, String
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base


class BehaviourSample(Base):
    __tablename__ = "behaviour_samples_v2"
    __table_args__ = (
        CheckConstraint("stage IN ('TYPING', 'TAPPING', 'SWIPING', 'MOTION')"),
        CheckConstraint("ordinal BETWEEN 1 AND 3"),
    )
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    stage: Mapped[str] = mapped_column(String(16), primary_key=True)
    ordinal: Mapped[int] = mapped_column(Integer, primary_key=True)
    features: Mapped[list] = mapped_column(JSON, nullable=False)
