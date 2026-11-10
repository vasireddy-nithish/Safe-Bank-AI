"""Base declarative class, UTC DateTime TypeDecorator, and common model mixins."""

import uuid
from datetime import datetime, timezone
from sqlalchemy import DateTime, String, TypeDecorator
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

def utc_now() -> datetime:
    """Return timezone-aware current UTC datetime."""
    return datetime.now(timezone.utc)

class UTCDateTime(TypeDecorator):
    """
    Ensures datetimes read from SQLite (which drops tzinfo) are always
    restored as timezone-aware UTC datetimes.
    """
    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is not None:
            if value.tzinfo is None:
                value = value.replace(tzinfo=timezone.utc)
            return value.astimezone(timezone.utc)
        return value

    def process_result_value(self, value, dialect):
        if value is not None:
            if value.tzinfo is None:
                return value.replace(tzinfo=timezone.utc)
            return value.astimezone(timezone.utc)
        return value

class Base(DeclarativeBase):
    """Base class for all SQLAlchemy ORM models."""
    pass

class TimestampMixin:
    """Mixin that adds created_at and updated_at timestamps."""
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=utc_now,
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=utc_now,
        onupdate=utc_now,
        nullable=False,
    )

class UUIDPrimaryKeyMixin:
    """Mixin that provides a UUID string primary key."""
    id: Mapped[str] = mapped_column(
        String(36),
        primary_key=True,
        default=lambda: str(uuid.uuid4()),
        index=True,
    )
