"""IdempotencyReservation ORM Model guaranteeing two-phase atomic reservation, lease fencing, and crash recovery."""

from decimal import Decimal
from typing import Optional
from datetime import datetime
from sqlalchemy import String, Numeric, Integer, ForeignKey, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, TimestampMixin, UTCDateTime

class IdempotencyReservation(Base, TimestampMixin):
    __tablename__ = "idempotency_reservations"
    __table_args__ = (
        CheckConstraint(
            "status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED_ABANDONED')",
            name="check_reservation_status_valid"
        ),
        CheckConstraint("amount > 0", name="check_reservation_amount_positive"),
        CheckConstraint("lease_generation >= 1", name="check_reservation_lease_generation_positive"),
    )

    # Primary key is the client-provided idempotency key (UUIDv4 string)
    idempotency_key: Mapped[str] = mapped_column(String(128), primary_key=True, index=True)

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    from_account_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), nullable=False)
    to_account_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), nullable=False)

    # Monetary precision handling
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="INR", nullable=False)

    # Status: PROCESSING, COMPLETED, FAILED, EXPIRED_ABANDONED
    status: Mapped[str] = mapped_column(String(32), default="PROCESSING", index=True, nullable=False)
    error_code: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    error_message: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)

    # Linked Transaction (set upon successful completion)
    transaction_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True)

    # Fencing and Lease Ownership
    lease_generation: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    owner_worker_id: Mapped[str] = mapped_column(String(64), nullable=False)
    lease_expires_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)

    # Relationships
    user: Mapped["User"] = relationship("User")
    from_account: Mapped["Account"] = relationship("Account", foreign_keys=[from_account_id])
    to_account: Mapped["Account"] = relationship("Account", foreign_keys=[to_account_id])
    transaction: Mapped[Optional["Transaction"]] = relationship("Transaction")
