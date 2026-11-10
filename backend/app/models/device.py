"""Device ORM Model for hardware-backed device binding."""

from typing import List, Optional
from datetime import datetime
from sqlalchemy import String, Boolean, ForeignKey, Text, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin, UTCDateTime, utc_now

class Device(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "devices"
    __table_args__ = (
        CheckConstraint("status IN ('active', 'revoked')", name="check_device_status_valid"),
        CheckConstraint("attestation_type IN ('strongbox', 'tee', 'software')", name="check_device_attestation_type_valid"),
    )

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    device_name: Mapped[str] = mapped_column(String(128), nullable=False)
    device_fingerprint: Mapped[str] = mapped_column(String(255), index=True, nullable=False)

    # Hardware-backed public key generated inside Android Keystore (StrongBox/TEE)
    public_key_pem: Mapped[str] = mapped_column(Text, nullable=False)
    is_verified: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    attestation_type: Mapped[str] = mapped_column(String(32), default="tee", nullable=False)  # strongbox, tee, software
    status: Mapped[str] = mapped_column(String(32), default="active", nullable=False)  # active, revoked

    last_seen_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utc_now, nullable=False)

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="devices")
    sessions: Mapped[List["Session"]] = relationship("Session", back_populates="device")
    duress_events: Mapped[List["DuressEvent"]] = relationship("DuressEvent", back_populates="device")
