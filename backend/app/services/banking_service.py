"""Banking operations service: atomic transfers, idempotency, and Decimal precision."""

from dataclasses import dataclass
from decimal import Decimal
from typing import Optional
import uuid
from sqlalchemy.orm import Session as DbSession
from sqlalchemy.exc import IntegrityError
from app.db.base import utc_now
from app.models.account import Account
from app.models.transaction import Transaction

@dataclass
class TransferResult:
    success: bool
    transaction: Optional[Transaction] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None

class BankingService:

    @classmethod
    def execute_transfer(
        cls,
        db: DbSession,
        from_account_id: str,
        to_account_id: str,
        amount: Decimal,
        idempotency_key: str,
        description: Optional[str] = None,
        is_restricted: bool = False,
    ) -> TransferResult:
        """
        Execute an atomic transfer between two accounts with strict Decimal precision,
        idempotency guarantees, and coercion-aware Restricted Mode simulation.
        """
        # 1. Idempotency Check: Avoid duplicate charge on retry
        existing_tx = db.query(Transaction).filter(
            Transaction.idempotency_key == idempotency_key
        ).first()
        if existing_tx:
            return TransferResult(
                success=existing_tx.status == "completed",
                transaction=existing_tx,
                error_code="IDEMPOTENT_REPLAY" if existing_tx.status != "completed" else None,
                error_message="Duplicate request detected. Returning recorded transaction.",
            )

        # 2. Amount Validation
        if not isinstance(amount, Decimal):
            amount = Decimal(str(amount))

        if amount <= Decimal("0.00"):
            return TransferResult(
                success=False,
                error_code="INVALID_AMOUNT",
                error_message="Transfer amount must be strictly positive.",
            )

        if from_account_id == to_account_id:
            return TransferResult(
                success=False,
                error_code="SAME_ACCOUNT_TRANSFER",
                error_message="Source and destination accounts must be distinct.",
            )

        # 3. Transactional Block for Atomicity
        try:
            from_account = db.query(Account).filter(Account.id == from_account_id).first()
            if not from_account:
                return TransferResult(
                    success=False,
                    error_code="SOURCE_ACCOUNT_NOT_FOUND",
                    error_message="Source account does not exist.",
                )

            if from_account.status != "active":
                return TransferResult(
                    success=False,
                    error_code="ACCOUNT_INACTIVE",
                    error_message=f"Source account is {from_account.status}.",
                )

            to_account = db.query(Account).filter(Account.id == to_account_id).first()
            if not to_account:
                return TransferResult(
                    success=False,
                    error_code="DESTINATION_ACCOUNT_NOT_FOUND",
                    error_message="Destination account does not exist.",
                )

            if to_account.status != "active":
                return TransferResult(
                    success=False,
                    error_code="DESTINATION_ACCOUNT_INACTIVE",
                    error_message=f"Destination account is {to_account.status}.",
                )

            # 4. Solvency Check
            if from_account.balance < amount:
                # Record failed transaction audit
                failed_tx = Transaction(
                    from_account_id=from_account_id,
                    to_account_id=to_account_id,
                    amount=amount,
                    currency=from_account.currency,
                    transaction_type="transfer",
                    status="failed",
                    description=f"Failed: Insufficient funds. {description or ''}".strip(),
                    reference_number=f"TX-{uuid.uuid4().hex[:12].upper()}",
                    idempotency_key=idempotency_key,
                    is_duress_masked=is_restricted,
                    completed_at=utc_now(),
                )
                db.add(failed_tx)
                db.commit()
                return TransferResult(
                    success=False,
                    transaction=failed_tx,
                    error_code="INSUFFICIENT_FUNDS",
                    error_message="Insufficient available balance to complete transfer.",
                )

            # 5. Coercion Restricted Mode vs Normal Atomic Transfer
            if is_restricted:
                # In Coercion Restricted Mode: The transfer appears to succeed to the coercer,
                # but no actual money is moved and the record is flagged as masked.
                tx = Transaction(
                    from_account_id=from_account_id,
                    to_account_id=to_account_id,
                    amount=amount,
                    currency=from_account.currency,
                    transaction_type="transfer",
                    status="completed",
                    description=description or "Transfer (Restricted Simulation)",
                    reference_number=f"TX-{uuid.uuid4().hex[:12].upper()}",
                    idempotency_key=idempotency_key,
                    is_duress_masked=True,
                    completed_at=utc_now(),
                )
                db.add(tx)
                db.commit()
                db.refresh(tx)
                return TransferResult(success=True, transaction=tx)

            # Normal Operation: Atomic Debit & Credit
            from_account.balance -= amount
            to_account.balance += amount

            tx = Transaction(
                from_account_id=from_account_id,
                to_account_id=to_account_id,
                amount=amount,
                currency=from_account.currency,
                transaction_type="transfer",
                status="completed",
                description=description or "Money Transfer",
                reference_number=f"TX-{uuid.uuid4().hex[:12].upper()}",
                idempotency_key=idempotency_key,
                is_duress_masked=False,
                completed_at=utc_now(),
            )

            db.add(tx)
            db.commit()
            db.refresh(tx)
            return TransferResult(success=True, transaction=tx)

        except Exception as e:
            db.rollback()
            return TransferResult(
                success=False,
                error_code="TRANSACTION_ROLLBACK",
                error_message=f"Database transaction error: {str(e)}",
            )
