"""Banking operations service: atomic transfers, idempotency, Decimal precision, and Coercion Isolation."""

from dataclasses import dataclass
from decimal import Decimal
from typing import Optional
import json
import uuid
from sqlalchemy.orm import Session as DbSession
from sqlalchemy.exc import IntegrityError
from app.db.base import utc_now
from app.models.account import Account
from app.models.transaction import Transaction
from app.models.duress_event import DuressEvent
from app.models.security_event import SecurityEvent
from app.models.session import Session

@dataclass
class TransferResult:
    success: bool
    transaction: Optional[Transaction] = None
    is_simulated: bool = False
    error_code: Optional[str] = None
    error_message: Optional[str] = None

class BankingService:

    @staticmethod
    def is_operation_restricted(db: DbSession, session_id: Optional[str]) -> bool:
        """
        Check if the active session is under coercion restricted mode.
        Server-side authoritative check to prevent API bypass.
        """
        if not session_id:
            return False
        session = db.query(Session).filter(Session.id == session_id).first()
        return bool(session and session.is_restricted)

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
        session_id: Optional[str] = None,
        client_ip: Optional[str] = None,
    ) -> TransferResult:
        """
        Execute an atomic transfer between two accounts with strict Decimal precision,
        idempotency guarantees, and coercion-aware Restricted Mode simulation.

        Security & Coercion Rules:
        - In normal mode: atomic debit and credit inside a single database transaction.
        - In duress mode: NEVER debit or credit either account.
        - In duress mode: NEVER record a simulated transfer with status="completed".
          Recorded status is strictly "simulated_duress" with is_duress_masked=True.
        - An auditable DuressEvent and SecurityEvent are logged.
        - Client response indicates success to protect the user from immediate physical danger.
        """
        # 1. Idempotency Check: Avoid duplicate execution on retry
        existing_tx = db.query(Transaction).filter(
            Transaction.idempotency_key == idempotency_key
        ).first()
        if existing_tx:
            is_completed = existing_tx.status in ("completed", "simulated_duress")
            return TransferResult(
                success=is_completed,
                transaction=existing_tx,
                is_simulated=existing_tx.status == "simulated_duress",
                error_code="IDEMPOTENT_REPLAY" if not is_completed else None,
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

            # 5. Coercion Restricted Mode Enforcement
            if is_restricted:
                # CRITICAL SECURITY RULE:
                # In Coercion Restricted Mode:
                # 1. Balances are NOT debited or credited under any circumstances.
                # 2. Status is explicitly 'simulated_duress', NEVER 'completed'.
                # 3. An internal DuressEvent and SecurityEvent audit record are generated.
                # 4. The response envelope conveys simulated success to protect the user's safety.
                simulated_tx = Transaction(
                    from_account_id=from_account_id,
                    to_account_id=to_account_id,
                    amount=amount,
                    currency=from_account.currency,
                    transaction_type="transfer",
                    status="simulated_duress",  # Explicitly distinguished from genuine 'completed'
                    description=description or "Transfer (Simulated Receipt)",
                    reference_number=f"TX-{uuid.uuid4().hex[:12].upper()}",
                    idempotency_key=idempotency_key,
                    is_duress_masked=True,
                    completed_at=utc_now(),
                )
                db.add(simulated_tx)

                # Record an auditable DuressEvent
                duress_event = DuressEvent(
                    user_id=from_account.user_id,
                    session_id=session_id,
                    event_source="duress_mode_transfer_attempt",
                    action_taken="restricted_simulation_funds_protected",
                    delivery_status="delivered",
                    ip_address=client_ip,
                )
                db.add(duress_event)

                # Record critical audit trail SecurityEvent
                security_event = SecurityEvent(
                    user_id=from_account.user_id,
                    event_type="duress_transfer_blocked_silently",
                    severity="critical",
                    details_json=json.dumps({
                        "from_account_id": from_account_id,
                        "to_account_id": to_account_id,
                        "amount": str(amount),
                        "idempotency_key": idempotency_key,
                        "funds_moved": False,
                    }),
                    ip_address=client_ip,
                )
                db.add(security_event)

                db.commit()
                db.refresh(simulated_tx)
                return TransferResult(
                    success=True,
                    transaction=simulated_tx,
                    is_simulated=True,
                    error_message="Simulated transfer receipt generated (Coercion Restricted Mode).",
                )

            # Normal Operation: Genuine Atomic Settlement
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
            return TransferResult(success=True, transaction=tx, is_simulated=False)

        except Exception as e:
            db.rollback()
            return TransferResult(
                success=False,
                error_code="TRANSACTION_ROLLBACK",
                error_message=f"Database transaction error: {str(e)}",
            )
