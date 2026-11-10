"""SecurityEvent ORM Model maintaining tamper-evident audit logging."""

from typing import Optional
from sqlalchemy import String, ForeignKey, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin

class SecurityEvent(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "security_events"

    user_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("users.id", ondelete="SET NULL"), index=True, nullable=True)

    # Event classification
    event_type: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    # Event severity: info, warning, critical
    severity: Mapped[str] = mapped_column(String(16), default="info", index=True, nullable=False)

    details_json: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    ip_address: Mapped[Optional[str]] = mapped_column(String(45), nullable=True)

    # Relationships
    user: Mapped[Optional["User"]] = relationship("User", back_populates="security_events")
