"""DuressEvent ORM Model logging explicit coercion activations."""

from typing import Optional
from sqlalchemy import String, ForeignKey, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin

class DuressEvent(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "duress_events"
    __table_args__ = (
        CheckConstraint("delivery_status IN ('delivered', 'queued')", name="check_duress_delivery_status_valid"),
    )

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    session_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("sessions.id", ondelete="SET NULL"), nullable=True)
    device_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True)

    # Coercion Trigger Source: explicit user activation independent of ML
    event_source: Mapped[str] = mapped_column(String(32), default="duress_pin", nullable=False)  # duress_pin, gesture_override, panic_action
    action_taken: Mapped[str] = mapped_column(String(64), default="restricted_mode_activated", nullable=False)
    delivery_status: Mapped[str] = mapped_column(String(32), default="delivered", nullable=False)  # delivered, queued

    ip_address: Mapped[Optional[str]] = mapped_column(String(45), nullable=True)

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="duress_events")
    session: Mapped[Optional["Session"]] = relationship("Session", back_populates="duress_events")
    device: Mapped[Optional["Device"]] = relationship("Device", back_populates="duress_events")
