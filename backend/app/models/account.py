"""Account ORM Model representing financial accounts."""

from decimal import Decimal
from typing import List
from sqlalchemy import String, Numeric, ForeignKey, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, UUIDPrimaryKeyMixin, TimestampMixin

class Account(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    __tablename__ = "accounts"
    __table_args__ = (
        CheckConstraint("balance >= 0", name="check_account_balance_non_negative"),
        CheckConstraint("status IN ('active', 'frozen', 'restricted')", name="check_account_status_valid"),
        CheckConstraint("account_type IN ('checking', 'savings')", name="check_account_type_valid"),
    )

    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    account_number: Mapped[str] = mapped_column(String(32), unique=True, index=True, nullable=False)
    account_type: Mapped[str] = mapped_column(String(32), default="checking", nullable=False)  # checking, savings

    # Monetary precision handling: Numeric(14, 2) rather than float to guarantee zero floating point drift
    balance: Mapped[Decimal] = mapped_column(Numeric(14, 2), default=Decimal("0.00"), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="INR", nullable=False)
    status: Mapped[str] = mapped_column(String(32), default="active", nullable=False)  # active, frozen, restricted

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="accounts")
    sent_transactions: Mapped[List["Transaction"]] = relationship(
        "Transaction",
        foreign_keys="Transaction.from_account_id",
        back_populates="from_account",
    )
    received_transactions: Mapped[List["Transaction"]] = relationship(
        "Transaction",
        foreign_keys="Transaction.to_account_id",
        back_populates="to_account",
    )
