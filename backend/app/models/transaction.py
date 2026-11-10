"""Transaction ORM Model managing money transfers with atomicity & idempotency."""

from decimal import Decimal
from typing import Optional
from datetime import datetime
from sqlalchemy import String, Numeric, Boolean, ForeignKey, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin, UTCDateTime

class Transaction(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "transactions"
    __table_args__ = (
        CheckConstraint("amount > 0", name="check_transaction_amount_positive"),
        CheckConstraint(
            "status IN ('pending', 'completed', 'failed', 'cancelled', 'simulated_duress')",
            name="check_transaction_status_valid"
        ),
        CheckConstraint(
            "transaction_type IN ('transfer', 'payment', 'deposit')",
            name="check_transaction_type_valid"
        ),
    )

    from_account_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), index=True, nullable=False)
    to_account_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), index=True, nullable=True)

    # Monetary precision handling
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="INR", nullable=False)

    transaction_type: Mapped[str] = mapped_column(String(32), default="transfer", nullable=False)  # transfer, payment, deposit
    # Status: pending, completed, failed, cancelled, simulated_duress (NEVER 'completed' for fake duress transfers)
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True, nullable=False)

    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    reference_number: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)

    # Idempotency Key: Guarantees protection against double-spending and network replay attempts
    idempotency_key: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)

    # Coercion Protection: When user is in duress, simulated transfers are flagged and isolated
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
