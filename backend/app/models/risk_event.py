"""RiskEvent ORM Model recording continuous behavioral and hybrid risk assessments."""

from typing import Optional
from sqlalchemy import String, Integer, Float, ForeignKey, Text, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin

class RiskEvent(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "risk_events"
    __table_args__ = (
        CheckConstraint("risk_score >= 0 AND risk_score <= 100", name="check_risk_score_range"),
        CheckConstraint("risk_level IN ('low', 'medium', 'high')", name="check_risk_level_valid"),
        CheckConstraint("behavioral_verdict IN ('consistent', 'uncertain', 'deviation')", name="check_behavioral_verdict_valid"),
        CheckConstraint("action_taken IN ('allow', 'step_up', 'restrict')", name="check_risk_action_taken_valid"),
    )

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    session_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("sessions.id", ondelete="SET NULL"), index=True, nullable=True)

    # Risk score between 0 and 100 (0-29 Low, 30-69 Medium, 70-100 High)
    risk_score: Mapped[int] = mapped_column(Integer, nullable=False)
    risk_level: Mapped[str] = mapped_column(String(16), index=True, nullable=False)  # low, medium, high

    # On-Device Behavioral ML telemetry (Derived classification only; raw sensor data is never sent)
    behavioral_verdict: Mapped[str] = mapped_column(String(32), nullable=False)  # consistent, uncertain, deviation
    behavioral_confidence: Mapped[float] = mapped_column(Float, nullable=False)

    # JSON representation of contributing factors (e.g. device integrity, session age, baseline stability)
    factors_json: Mapped[Optional[str]] = mapped_column(Text, nullable=True)

    action_taken: Mapped[str] = mapped_column(String(32), default="allow", nullable=False)  # allow, step_up, restrict

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="risk_events")
    session: Mapped[Optional["Session"]] = relationship("Session", back_populates="risk_events")
