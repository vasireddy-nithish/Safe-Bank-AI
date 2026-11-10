"""Session ORM Model managing active sessions, token hashes, and Restricted Mode."""

from typing import List, Optional
from datetime import datetime
from sqlalchemy import String, Boolean, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin, UTCDateTime

class Session(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "sessions"

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    device_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True)

    # Secure hash (SHA-256) of the issued bearer access token
    session_token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)

    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True, nullable=False)

    # Coercion Protection: Restricted Mode flag. When True, all sensitive banking actions are blocked server-side.
    is_restricted: Mapped[bool] = mapped_column(Boolean, default=False, index=True, nullable=False)

    expires_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    revoked_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    revocation_reason: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)

    ip_address: Mapped[Optional[str]] = mapped_column(String(45), nullable=True)
    user_agent: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="sessions")
    device: Mapped[Optional["Device"]] = relationship("Device", back_populates="sessions")
    risk_events: Mapped[List["RiskEvent"]] = relationship("RiskEvent", back_populates="session")
    duress_events: Mapped[List["DuressEvent"]] = relationship("DuressEvent", back_populates="session")
