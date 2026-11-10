"""Transaction ORM Model managing money transfers with atomicity & idempotency."""

from decimal import Decimal
from typing import Optional
from datetime import datetime
from sqlalchemy import String, Numeric, Boolean, DateTime, ForeignKey, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin, UTCDateTime

class Transaction(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "transactions"

    from_account_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), index=True, nullable=False)
    to_account_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), index=True, nullable=True)

    # Monetary precision handling
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="USD", nullable=False)

    transaction_type: Mapped[str] = mapped_column(String(32), default="transfer", nullable=False)  # transfer, payment, deposit
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True, nullable=False)  # pending, completed, failed, cancelled

    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    reference_number: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)

    # Idempotency Key: Guarantees protection against double-spending and network replay attempts
    idempotency_key: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)

    # Coercion Protection: When user is in duress, mock transactions are flagged as masked/simulated
    is_duress_masked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    completed_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)

    # Relationships
    from_account: Mapped["Account"] = relationship(
        "Account",
        foreign_keys=[from_account_id],
        back_populates="sent_transactions",
    )
    to_account: Mapped[Optional["Account"]] = relationship(
        "Account",
        foreign_keys=[to_account_id],
        back_populates="received_transactions",
    )
