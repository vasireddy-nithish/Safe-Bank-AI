"""User ORM Model."""

from typing import List, Optional
from sqlalchemy import String, Boolean
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin

class User(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "users"

    username: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    email: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)
    full_name: Mapped[str] = mapped_column(String(128), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    # Duress PIN Protection: Stored strictly as a salted Argon2id hash. NEVER plaintext.
    duress_pin_hash: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    duress_configured: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    # Relationships
    devices: Mapped[List["Device"]] = relationship("Device", back_populates="user", cascade="all, delete-orphan")
    credentials: Mapped[List["Credential"]] = relationship("Credential", back_populates="user", cascade="all, delete-orphan")
    sessions: Mapped[List["Session"]] = relationship("Session", back_populates="user", cascade="all, delete-orphan")
    accounts: Mapped[List["Account"]] = relationship("Account", back_populates="user", cascade="all, delete-orphan")
    risk_events: Mapped[List["RiskEvent"]] = relationship("RiskEvent", back_populates="user", cascade="all, delete-orphan")
    duress_events: Mapped[List["DuressEvent"]] = relationship("DuressEvent", back_populates="user", cascade="all, delete-orphan")
    security_events: Mapped[List["SecurityEvent"]] = relationship("SecurityEvent", back_populates="user")
