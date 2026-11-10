"""Credential ORM Model for WebAuthn / FIDO2 Passkeys."""

from typing import Optional
from datetime import datetime
from sqlalchemy import String, Integer, Boolean, DateTime, ForeignKey, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin, UTCDateTime, utc_now

class Credential(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "credentials"

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)

    # Base64url encoded WebAuthn Credential ID
    credential_id: Mapped[str] = mapped_column(String(512), unique=True, index=True, nullable=False)

    # Cryptographic Public Key only (COSE/PEM bytes). PRIVATE KEYS NEVER STORED.
    public_key: Mapped[str] = mapped_column(Text, nullable=False)

    # Sign counter used by WebAuthn to detect cloned authenticators
    sign_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    # Authenticator Attestation GUID identifying authenticator make/model
    aaguid: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)

    # Supported transports: internal, hybrid, usb, nfc, ble
    transports: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)

    # Passkey synchronization status flags
    is_backup_eligible: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    is_backed_up: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    last_used_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utc_now, nullable=False)

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="credentials")
