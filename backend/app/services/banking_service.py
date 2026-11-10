"""Banking operations service: two-phase idempotency reservations, lease fencing,
atomic SQLite write transactions, Decimal precision, and Coercion Isolation.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import json
import logging
import random
import sqlite3
import time
from typing import Optional, Tuple
import uuid

from sqlalchemy import func
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session as DbSession, sessionmaker

from app.core.config import settings
from app.db.base import utc_now
from app.db.session import ActiveTransactionError, SessionLocal, engine, write_transaction
from app.models.account import Account
from app.models.duress_event import DuressEvent
from app.models.idempotency_reservation import IdempotencyReservation
from app.models.security_event import SecurityEvent
from app.models.session import Session
from app.models.transaction import Transaction

logger = logging.getLogger(__name__)


@dataclass
class TransferResult:
    success: bool
    status: str = "completed"  # "completed", "simulated_duress", "processing", "failed", "conflict", "contention_retryable", "fencing_rejected"
    transaction: Optional[Transaction] = None
    is_simulated: bool = False
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    retryable: bool = False
    idempotency_key: Optional[str] = None
    reservation: Optional[IdempotencyReservation] = None
    is_replay: bool = False


@dataclass
class ReconcileResult:
    status: str  # "COMPLETED", "PROCESSING", "FAILED", "NOT_FOUND"
    transaction: Optional[Transaction] = None
    is_simulated: bool = False
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    retryable: bool = False
    idempotency_key: Optional[str] = None
    reservation: Optional[IdempotencyReservation] = None


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
    def _resolve_session_factory(
        cls,
        db: Optional[DbSession] = None,
        session_factory: Optional[sessionmaker] = None,
    ) -> sessionmaker:
        """
        Resolve the sessionmaker factory to create fresh dedicated sessions.
        Guarantees that write_transaction runs on a clean session with no active transaction.
        """
        if session_factory is not None:
            return session_factory
        if db is not None:
            if db.in_transaction():
                if db.dirty or db.new or db.deleted:
                    raise ActiveTransactionError(
                        "write_transaction requires a fresh or clean session with no active transaction. "
                        "Uncommitted modifications detected on provided session."
                    )
                # Harmlessly reset idle read transaction
                db.rollback()
            return sessionmaker(
                autocommit=False,
                autoflush=False,
                bind=db.get_bind(),
                expire_on_commit=False,
            )
        return sessionmaker(
            autocommit=False,
            autoflush=False,
            bind=engine,
            expire_on_commit=False,
        )

    @classmethod
    def attempt_cas_lease_claim(
        cls,
        session: DbSession,
        idempotency_key: str,
        current_generation: int,
        worker_id: str,
        lease_duration_seconds: int = 30,
        now_utc: Optional[datetime] = None,
    ) -> bool:
        """
        Atomic Compare-And-Swap lease claim for an expired lease:
        UPDATE idempotency_reservations
        SET lease_generation = lease_generation + 1,
            owner_worker_id = :worker_id,
            lease_expires_at = :new_lease_expiry
        WHERE idempotency_key = :key
          AND status = 'PROCESSING'
          AND lease_generation = :current_generation
          AND lease_expires_at <= :now;
        """
        now = now_utc or utc_now()
        new_expiry = now + timedelta(seconds=lease_duration_seconds)
        rows_affected = session.query(IdempotencyReservation).filter(
            IdempotencyReservation.idempotency_key == idempotency_key,
            IdempotencyReservation.status == "PROCESSING",
            IdempotencyReservation.lease_generation == current_generation,
            IdempotencyReservation.lease_expires_at <= now,
        ).update(
            {
                IdempotencyReservation.lease_generation: current_generation + 1,
                IdempotencyReservation.owner_worker_id: worker_id,
                IdempotencyReservation.lease_expires_at: new_expiry,
            },
            synchronize_session=False,
        )
        return rows_affected == 1

    @classmethod
    def attempt_cas_lease_renewal(
        cls,
        session: DbSession,
        idempotency_key: str,
        current_generation: int,
        worker_id: str,
        lease_duration_seconds: int = 30,
        now_utc: Optional[datetime] = None,
    ) -> bool:
        """
        Atomic Compare-And-Swap lease renewal for a worker's own expired lease:
        UPDATE idempotency_reservations
        SET lease_generation = lease_generation + 1,
            owner_worker_id = :worker_id,
            lease_expires_at = :new_lease_expiry
        WHERE idempotency_key = :key
          AND status = 'PROCESSING'
          AND owner_worker_id = :worker_id
          AND lease_generation = :current_generation
          AND lease_expires_at <= :now;
        """
        now = now_utc or utc_now()
        new_expiry = now + timedelta(seconds=lease_duration_seconds)
        rows_affected = session.query(IdempotencyReservation).filter(
            IdempotencyReservation.idempotency_key == idempotency_key,
            IdempotencyReservation.status == "PROCESSING",
            IdempotencyReservation.owner_worker_id == worker_id,
            IdempotencyReservation.lease_generation == current_generation,
            IdempotencyReservation.lease_expires_at <= now,
        ).update(
            {
                IdempotencyReservation.lease_generation: current_generation + 1,
                IdempotencyReservation.owner_worker_id: worker_id,
                IdempotencyReservation.lease_expires_at: new_expiry,
            },
            synchronize_session=False,
        )
        return rows_affected == 1

    @classmethod
    def reserve_transfer_key(
        cls,
        session_factory: sessionmaker,
        idempotency_key: str,
        from_account_id: str,
        to_account_id: str,
        amount: Decimal,
        currency: str = "INR",
        user_id: Optional[str] = None,
        worker_id: Optional[str] = None,
        lease_duration_seconds: int = 30,
        now_utc_override: Optional[datetime] = None,
    ) -> Tuple[str, Optional[int], Optional[str], Optional[TransferResult]]:
        """
        Phase 1: Reserve Key & Commit.
        
        Returns:
            Tuple of (outcome, fencing_token, worker_id, result):
            - ("PROCEED", fencing_token, worker_id, None): Ready for Phase 2.
            - ("COMPLETED", None, None, TransferResult): Already committed transfer.
            - ("FAILED", None, None, TransferResult): Terminal failure.
            - ("PROCESSING", None, None, TransferResult): Active lease held by another worker.
            - ("CONFLICT", None, None, TransferResult): Mismatched 4 financial fields.
            - ("CAS_RACE_LOST", None, None, None): Concurrent race; retry Phase 1.
        """
        worker_id = worker_id or str(uuid.uuid4())
        session = session_factory()
        now = now_utc_override or utc_now()

        try:
            with write_transaction(session):
                res = session.query(IdempotencyReservation).filter(
                    IdempotencyReservation.idempotency_key == idempotency_key
                ).first()

                if res is not None:
                    # Enforce four-field financial payload matching
                    payload_matches = (
                        res.from_account_id == from_account_id
                        and res.to_account_id == to_account_id
                        and Decimal(str(res.amount)) == Decimal(str(amount))
                        and res.currency == currency
                    )
                    if not payload_matches:
                        return (
                            "CONFLICT",
                            None,
                            None,
                            TransferResult(
                                success=False,
                                status="conflict",
                                error_code="IDEMPOTENCY_PAYLOAD_MISMATCH",
                                error_message="IDEMPOTENCY_PAYLOAD_MISMATCH: The provided idempotency key was previously submitted with different financial parameters.",
                                idempotency_key=idempotency_key,
                                reservation=res,
                                is_replay=True,
                            ),
                        )

                    # State-dependent resolution
                    if res.status == "COMPLETED":
                        tx = None
                        if res.transaction_id:
                            tx = session.query(Transaction).filter(Transaction.id == res.transaction_id).first()
                        if not tx:
                            tx = session.query(Transaction).filter(
                                Transaction.idempotency_key == idempotency_key
                            ).first()
                        return (
                            "COMPLETED",
                            None,
                            None,
                            TransferResult(
                                success=True,
                                status="completed" if (tx and tx.status == "completed") else "simulated_duress",
                                transaction=tx,
                                is_simulated=(tx.status == "simulated_duress" if tx else False),
                                idempotency_key=idempotency_key,
                                reservation=res,
                                is_replay=True,
                            ),
                        )

                    if res.status == "FAILED":
                        tx = None
                        if res.transaction_id:
                            tx = session.query(Transaction).filter(Transaction.id == res.transaction_id).first()
                        return (
                            "FAILED",
                            None,
                            None,
                            TransferResult(
                                success=False,
                                status="failed",
                                transaction=tx,
                                error_code=res.error_code,
                                error_message=res.error_message,
                                idempotency_key=idempotency_key,
                                reservation=res,
                                is_replay=True,
                            ),
                        )

                    if res.status in ("PROCESSING", "EXPIRED_ABANDONED"):
                        if now < res.lease_expires_at and res.status == "PROCESSING":
                            # Active lease held by another worker
                            return (
                                "PROCESSING",
                                None,
                                None,
                                TransferResult(
                                    success=False,
                                    status="processing",
                                    retryable=True,
                                    error_code="PROCESSING",
                                    error_message="Transfer is actively processing. Please check back shortly.",
                                    idempotency_key=idempotency_key,
                                    reservation=res,
                                ),
                            )
                        else:
                            # Lease is expired! Check if transaction was already committed before crash
                            existing_tx = session.query(Transaction).filter(
                                Transaction.idempotency_key == idempotency_key,
                                Transaction.status.in_(["completed", "simulated_duress"]),
                            ).first()
                            if existing_tx:
                                res.status = "COMPLETED"
                                res.transaction_id = existing_tx.id
                                session.flush()
                                return (
                                    "COMPLETED",
                                    None,
                                    None,
                                    TransferResult(
                                        success=True,
                                        status="completed" if existing_tx.status == "completed" else "simulated_duress",
                                        transaction=existing_tx,
                                        is_simulated=(existing_tx.status == "simulated_duress"),
                                        idempotency_key=idempotency_key,
                                        reservation=res,
                                    ),
                                )
                            else:
                                # Attempt atomic CAS lease claim
                                claimed = cls.attempt_cas_lease_claim(
                                    session=session,
                                    idempotency_key=idempotency_key,
                                    current_generation=res.lease_generation,
                                    worker_id=worker_id,
                                    lease_duration_seconds=lease_duration_seconds,
                                    now_utc=now,
                                )
                                if claimed:
                                    new_gen = res.lease_generation + 1
                                    return ("PROCEED", new_gen, worker_id, None)
                                else:
                                    return ("CAS_RACE_LOST", None, None, None)

                # No reservation found: insert new reservation in PROCESSING state
                from_acc = session.query(Account).filter(Account.id == from_account_id).first()
                if not from_acc:
                    return (
                        "FAILED",
                        None,
                        None,
                        TransferResult(
                            success=False,
                            status="failed",
                            error_code="SOURCE_ACCOUNT_NOT_FOUND",
                            error_message="Source account does not exist.",
                            idempotency_key=idempotency_key,
                        ),
                    )

                resolved_user_id = user_id or from_acc.user_id
                new_res = IdempotencyReservation(
                    idempotency_key=idempotency_key,
                    user_id=resolved_user_id,
                    from_account_id=from_account_id,
                    to_account_id=to_account_id,
                    amount=amount,
                    currency=currency,
                    status="PROCESSING",
                    lease_generation=1,
                    owner_worker_id=worker_id,
                    lease_expires_at=now + timedelta(seconds=lease_duration_seconds),
                )
                session.add(new_res)
                # write_transaction will commit on block exit
                return ("PROCEED", 1, worker_id, None)

        except IntegrityError:
            # Concurrent worker inserted same idempotency_key simultaneously
            return ("CAS_RACE_LOST", None, None, None)
        finally:
            session.close()

    @classmethod
    def execute_transfer_phase2(
        cls,
        session_factory: sessionmaker,
        idempotency_key: str,
        fencing_token: int,
        worker_id: str,
        from_account_id: str,
        to_account_id: str,
        amount: Decimal,
        currency: str = "INR",
        description: Optional[str] = None,
        is_restricted: bool = False,
        session_id: Optional[str] = None,
        client_ip: Optional[str] = None,
        user_id: Optional[str] = None,
        enforce_internal_only: bool = False,
        allow_lease_renewal: bool = True,
        lease_duration_seconds: int = 30,
        now_utc_override: Optional[datetime] = None,
    ) -> TransferResult:
        """
        Phase 2: Atomic 5-Point Fencing & Financial Execution.
        Guarantees that balances are modified ONLY if the 5-point verification passes.
        """
        session = session_factory()
        now = now_utc_override or utc_now()

        try:
            with write_transaction(session):
                # 1. Strict 5-Point Fencing Verification Query
                reservation = session.query(IdempotencyReservation).filter(
                    IdempotencyReservation.idempotency_key == idempotency_key,
                    IdempotencyReservation.lease_generation == fencing_token,
                    IdempotencyReservation.owner_worker_id == worker_id,
                    IdempotencyReservation.status == "PROCESSING",
                    IdempotencyReservation.lease_expires_at > now,  # Strictly active and unexpired!
                ).first()

                # If condition 5 (or any condition) failed: check for own lease renewal
                if reservation is None:
                    if allow_lease_renewal:
                        own_expired = session.query(IdempotencyReservation).filter(
                            IdempotencyReservation.idempotency_key == idempotency_key,
                            IdempotencyReservation.lease_generation == fencing_token,
                            IdempotencyReservation.owner_worker_id == worker_id,
                            IdempotencyReservation.status == "PROCESSING",
                            IdempotencyReservation.lease_expires_at <= now,
                        ).first()
                        if own_expired:
                            renewed = cls.attempt_cas_lease_renewal(
                                session=session,
                                idempotency_key=idempotency_key,
                                current_generation=fencing_token,
                                worker_id=worker_id,
                                lease_duration_seconds=lease_duration_seconds,
                                now_utc=now,
                            )
                            if renewed:
                                fencing_token += 1
                                reservation = session.query(IdempotencyReservation).filter(
                                    IdempotencyReservation.idempotency_key == idempotency_key,
                                    IdempotencyReservation.lease_generation == fencing_token,
                                    IdempotencyReservation.owner_worker_id == worker_id,
                                    IdempotencyReservation.status == "PROCESSING",
                                ).first()

                    if reservation is None:
                        # Fencing check failed! Rollback and abort without altering balances
                        current_res = session.query(IdempotencyReservation).filter(
                            IdempotencyReservation.idempotency_key == idempotency_key
                        ).first()
                        if current_res and current_res.status == "COMPLETED":
                            tx = session.query(Transaction).filter(
                                Transaction.id == current_res.transaction_id
                            ).first()
                            return TransferResult(
                                success=True,
                                status="completed" if (tx and tx.status == "completed") else "simulated_duress",
                                transaction=tx,
                                is_simulated=(tx.status == "simulated_duress" if tx else False),
                                idempotency_key=idempotency_key,
                                reservation=current_res,
                                is_replay=True,
                            )
                        return TransferResult(
                            success=False,
                            status="fencing_rejected",
                            error_code="STALE_WORKER_FENCING_REJECTED",
                            error_message="Fencing token or lease expired; worker rejected without touching balances.",
                            idempotency_key=idempotency_key,
                            reservation=current_res,
                        )

                # 2. Financial Validations (Only executed after 5-point verification passes)
                if not isinstance(amount, Decimal):
                    amount = Decimal(str(amount))

                if amount <= Decimal("0.00"):
                    reservation.status = "FAILED"
                    reservation.error_code = "INVALID_AMOUNT"
                    reservation.error_message = "Transfer amount must be strictly positive."
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        error_code="INVALID_AMOUNT",
                        error_message="Transfer amount must be strictly positive.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                if from_account_id == to_account_id:
                    reservation.status = "FAILED"
                    reservation.error_code = "SAME_ACCOUNT_TRANSFER"
                    reservation.error_message = "Source and destination accounts must be distinct."
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        error_code="SAME_ACCOUNT_TRANSFER",
                        error_message="Source and destination accounts must be distinct.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                from_account = session.query(Account).filter(Account.id == from_account_id).first()
                if not from_account:
                    reservation.status = "FAILED"
                    reservation.error_code = "SOURCE_ACCOUNT_NOT_FOUND"
                    reservation.error_message = "Source account does not exist."
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        error_code="SOURCE_ACCOUNT_NOT_FOUND",
                        error_message="Source account does not exist.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                if from_account.status != "active":
                    reservation.status = "FAILED"
                    reservation.error_code = "ACCOUNT_INACTIVE"
                    reservation.error_message = f"Source account is {from_account.status}."
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        error_code="ACCOUNT_INACTIVE",
                        error_message=f"Source account is {from_account.status}.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                to_account = session.query(Account).filter(Account.id == to_account_id).first()
                if not to_account:
                    reservation.status = "FAILED"
                    reservation.error_code = "DESTINATION_ACCOUNT_NOT_FOUND"
                    reservation.error_message = "Destination account does not exist."
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        error_code="DESTINATION_ACCOUNT_NOT_FOUND",
                        error_message="Destination account does not exist.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                if to_account.status != "active":
                    reservation.status = "FAILED"
                    reservation.error_code = "DESTINATION_ACCOUNT_INACTIVE"
                    reservation.error_message = f"Destination account is {to_account.status}."
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        error_code="DESTINATION_ACCOUNT_INACTIVE",
                        error_message=f"Destination account is {to_account.status}.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                # Strict internal transfer ownership check
                if enforce_internal_only:
                    if user_id and from_account.user_id != user_id:
                        reservation.status = "FAILED"
                        reservation.error_code = "CROSS_USER_TRANSFER_UNAUTHORIZED"
                        reservation.error_message = "CROSS_USER_TRANSFER_UNAUTHORIZED: Source account does not belong to authenticated user."
                        session.flush()
                        return TransferResult(
                            success=False,
                            status="failed",
                            error_code="CROSS_USER_TRANSFER_UNAUTHORIZED",
                            error_message=reservation.error_message,
                            idempotency_key=idempotency_key,
                            reservation=reservation,
                        )
                    if from_account.user_id != to_account.user_id:
                        reservation.status = "FAILED"
                        reservation.error_code = "CROSS_USER_TRANSFER_UNAUTHORIZED"
                        reservation.error_message = "CROSS_USER_TRANSFER_UNAUTHORIZED: Destination account belongs to a different user. Only internal transfers are permitted."
                        session.flush()
                        return TransferResult(
                            success=False,
                            status="failed",
                            error_code="CROSS_USER_TRANSFER_UNAUTHORIZED",
                            error_message=reservation.error_message,
                            idempotency_key=idempotency_key,
                            reservation=reservation,
                        )

                # Solvency Check
                if from_account.balance < amount:
                    failed_tx = Transaction(
                        from_account_id=from_account_id,
                        to_account_id=to_account_id,
                        amount=amount,
                        currency=from_account.currency,
                        transaction_type="transfer",
                        status="failed",
                        description=f"Failed: Insufficient funds. {description or ''}".strip(),
                        reference_number=f"TX-{utc_now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:6].upper()}",
                        idempotency_key=idempotency_key,
                        is_duress_masked=is_restricted,
                        completed_at=utc_now(),
                    )
                    session.add(failed_tx)
                    session.flush()
                    reservation.status = "FAILED"
                    reservation.error_code = "INSUFFICIENT_FUNDS"
                    reservation.error_message = "Insufficient available balance to complete transfer."
                    reservation.transaction_id = failed_tx.id
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        transaction=failed_tx,
                        error_code="INSUFFICIENT_FUNDS",
                        error_message="Insufficient available balance to complete transfer.",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                # Daily Limit Check (Strictly 'completed' non-duress transfers only)
                today_start = utc_now().replace(hour=0, minute=0, second=0, microsecond=0)
                daily_sum = session.query(
                    func.coalesce(func.sum(Transaction.amount), Decimal("0.00"))
                ).filter(
                    Transaction.from_account_id == from_account_id,
                    Transaction.status == "completed",  # Strictly 'completed' only!
                    Transaction.created_at >= today_start,
                ).scalar() or Decimal("0.00")

                if not isinstance(daily_sum, Decimal):
                    daily_sum = Decimal(str(daily_sum))

                if daily_sum + amount > settings.DAILY_TRANSFER_LIMIT_INR:
                    failed_tx = Transaction(
                        from_account_id=from_account_id,
                        to_account_id=to_account_id,
                        amount=amount,
                        currency=from_account.currency,
                        transaction_type="transfer",
                        status="failed",
                        description=f"Failed: Daily limit exceeded. {description or ''}".strip(),
                        reference_number=f"TX-{utc_now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:6].upper()}",
                        idempotency_key=idempotency_key,
                        is_duress_masked=is_restricted,
                        completed_at=utc_now(),
                    )
                    session.add(failed_tx)
                    session.flush()
                    reservation.status = "FAILED"
                    reservation.error_code = "DAILY_LIMIT_EXCEEDED"
                    reservation.error_message = (
                        f"DAILY_LIMIT_EXCEEDED: Daily transfer limit of ₹{settings.DAILY_TRANSFER_LIMIT_INR} exceeded. "
                        f"Cumulative completed today: ₹{daily_sum}, requested: ₹{amount}."
                    )
                    reservation.transaction_id = failed_tx.id
                    session.flush()
                    return TransferResult(
                        success=False,
                        status="failed",
                        transaction=failed_tx,
                        error_code="DAILY_LIMIT_EXCEEDED",
                        error_message=reservation.error_message,
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                # Coercion Restricted Mode: Balances strictly untouched
                if is_restricted:
                    simulated_tx = Transaction(
                        from_account_id=from_account_id,
                        to_account_id=to_account_id,
                        amount=amount,
                        currency=from_account.currency,
                        transaction_type="transfer",
                        status="simulated_duress",
                        description=description or "Transfer (Simulated Receipt)",
                        reference_number=f"TX-{utc_now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:6].upper()}",
                        idempotency_key=idempotency_key,
                        is_duress_masked=True,
                        completed_at=utc_now(),
                    )
                    session.add(simulated_tx)
                    session.flush()

                    duress_event = DuressEvent(
                        user_id=from_account.user_id,
                        session_id=session_id,
                        event_source="duress_mode_transfer_attempt",
                        action_taken="restricted_simulation_funds_protected",
                        delivery_status="delivered",
                        ip_address=client_ip,
                    )
                    session.add(duress_event)

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
                    session.add(security_event)

                    reservation.status = "COMPLETED"
                    reservation.transaction_id = simulated_tx.id
                    session.flush()

                    return TransferResult(
                        success=True,
                        status="simulated_duress",
                        transaction=simulated_tx,
                        is_simulated=True,
                        error_message="Simulated transfer receipt generated (Coercion Restricted Mode).",
                        idempotency_key=idempotency_key,
                        reservation=reservation,
                    )

                # Normal Mode: Genuine atomic settlement
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
                    reference_number=f"TX-{utc_now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:6].upper()}",
                    idempotency_key=idempotency_key,
                    is_duress_masked=False,
                    completed_at=utc_now(),
                )
                session.add(tx)
                session.flush()

                reservation.status = "COMPLETED"
                reservation.transaction_id = tx.id
                session.flush()

                return TransferResult(
                    success=True,
                    status="completed",
                    transaction=tx,
                    is_simulated=False,
                    idempotency_key=idempotency_key,
                    reservation=reservation,
                )

        finally:
            session.close()

    @classmethod
    def execute_transfer(
        cls,
        db: Optional[DbSession] = None,
        from_account_id: str = "",
        to_account_id: str = "",
        amount: Decimal = Decimal("0.00"),
        idempotency_key: str = "",
        currency: str = "INR",
        description: Optional[str] = None,
        is_restricted: bool = False,
        session_id: Optional[str] = None,
        client_ip: Optional[str] = None,
        session_factory: Optional[sessionmaker] = None,
        worker_id: Optional[str] = None,
        user_id: Optional[str] = None,
        enforce_internal_only: bool = False,
        allow_lease_renewal: bool = True,
        max_retries: int = 3,
    ) -> TransferResult:
        """
        Execute an atomic transfer using the two-phase reservation protocol with
        fencing tokens, bounded SQLITE_BUSY retries, Decimal precision, and Coercion Isolation.
        """
        # 1. Input pre-validation
        if not isinstance(amount, Decimal):
            amount = Decimal(str(amount))

        if amount <= Decimal("0.00"):
            return TransferResult(
                success=False,
                status="failed",
                error_code="INVALID_AMOUNT",
                error_message="Transfer amount must be strictly positive.",
                idempotency_key=idempotency_key,
            )

        if from_account_id == to_account_id:
            return TransferResult(
                success=False,
                status="failed",
                error_code="SAME_ACCOUNT_TRANSFER",
                error_message="Source and destination accounts must be distinct.",
                idempotency_key=idempotency_key,
            )

        worker_id = worker_id or str(uuid.uuid4())
        sf = cls._resolve_session_factory(db=db, session_factory=session_factory)

        # 2. Authoritative Coercion Check
        if session_id and not is_restricted:
            check_session = sf()
            try:
                is_restricted = cls.is_operation_restricted(check_session, session_id)
            finally:
                check_session.close()

        # 3. Bounded Retry Loop for Lock Contention and CAS Races
        for attempt in range(max_retries):
            try:
                # Phase 1: Reserve Key & Commit
                outcome, fencing_token, assigned_worker, result = cls.reserve_transfer_key(
                    session_factory=sf,
                    idempotency_key=idempotency_key,
                    from_account_id=from_account_id,
                    to_account_id=to_account_id,
                    amount=amount,
                    currency=currency,
                    user_id=user_id,
                    worker_id=worker_id,
                )

                if outcome in ("COMPLETED", "FAILED", "PROCESSING", "CONFLICT"):
                    return result

                if outcome == "CAS_RACE_LOST":
                    backoff = min(0.5, 0.05 * (2 ** attempt)) + random.uniform(0.01, 0.05)
                    time.sleep(backoff)
                    continue

                # outcome == "PROCEED" -> Proceed to Phase 2
                phase2_res = cls.execute_transfer_phase2(
                    session_factory=sf,
                    idempotency_key=idempotency_key,
                    fencing_token=fencing_token,
                    worker_id=assigned_worker,
                    from_account_id=from_account_id,
                    to_account_id=to_account_id,
                    amount=amount,
                    currency=currency,
                    description=description,
                    is_restricted=is_restricted,
                    session_id=session_id,
                    client_ip=client_ip,
                    user_id=user_id,
                    enforce_internal_only=enforce_internal_only,
                    allow_lease_renewal=allow_lease_renewal,
                )
                return phase2_res

            except (sqlite3.OperationalError, OperationalError) as err:
                err_str = str(err).lower()
                if "locked" in err_str or "busy" in err_str:
                    if attempt < max_retries - 1:
                        backoff = min(0.5, 0.05 * (2 ** attempt)) + random.uniform(0.01, 0.05)
                        time.sleep(backoff)
                        continue
                    else:
                        return TransferResult(
                            success=False,
                            status="contention_retryable",
                            retryable=True,
                            error_code="DATABASE_CONTENTION_RETRYABLE",
                            error_message="DATABASE_CONTENTION_RETRYABLE: Temporary database lock contention. Please retry.",
                            idempotency_key=idempotency_key,
                        )
                else:
                    raise

        # Exhausted retries without resolution
        return TransferResult(
            success=False,
            status="contention_retryable",
            retryable=True,
            error_code="DATABASE_CONTENTION_RETRYABLE",
            error_message="DATABASE_CONTENTION_RETRYABLE: Temporary database lock contention. Please retry.",
            idempotency_key=idempotency_key,
        )

    @classmethod
    def reconcile_transfer(
        cls,
        db: Optional[DbSession] = None,
        session_factory: Optional[sessionmaker] = None,
        idempotency_key: str = "",
        user_id: Optional[str] = None,
    ) -> ReconcileResult:
        """
        Reconcile a transfer by idempotency key.
        Read-only scoped: operates on a standard read transaction (no BEGIN IMMEDIATE).
        
        Returns one of four durable states:
        - COMPLETED: full committed receipt.
        - PROCESSING: in progress; safe advisory.
        - FAILED: terminal failure details.
        - NOT_FOUND: key never seen; safe to retry.
        """
        sf = cls._resolve_session_factory(db=db, session_factory=session_factory)
        session = sf()

        try:
            res = session.query(IdempotencyReservation).filter(
                IdempotencyReservation.idempotency_key == idempotency_key
            ).first()

            if res is None:
                return ReconcileResult(
                    status="NOT_FOUND",
                    retryable=True,
                    idempotency_key=idempotency_key,
                    error_code="NOT_FOUND",
                    error_message="No transfer reservation found for the specified idempotency key.",
                )

            if res.status == "COMPLETED":
                tx = None
                if res.transaction_id:
                    tx = session.query(Transaction).filter(Transaction.id == res.transaction_id).first()
                if not tx:
                    tx = session.query(Transaction).filter(Transaction.idempotency_key == idempotency_key).first()
                return ReconcileResult(
                    status="COMPLETED",
                    transaction=tx,
                    is_simulated=(tx.status == "simulated_duress" if tx else False),
                    idempotency_key=idempotency_key,
                    reservation=res,
                )

            if res.status == "FAILED":
                tx = None
                if res.transaction_id:
                    tx = session.query(Transaction).filter(Transaction.id == res.transaction_id).first()
                return ReconcileResult(
                    status="FAILED",
                    transaction=tx,
                    error_code=res.error_code,
                    error_message=res.error_message,
                    idempotency_key=idempotency_key,
                    reservation=res,
                )

            if res.status in ("PROCESSING", "EXPIRED_ABANDONED"):
                now = utc_now()
                if now < res.lease_expires_at and res.status == "PROCESSING":
                    return ReconcileResult(
                        status="PROCESSING",
                        retryable=True,
                        idempotency_key=idempotency_key,
                        error_message="Transfer is actively processing. Please check back shortly.",
                        reservation=res,
                    )
                else:
                    # Check if transaction was committed before server interrupted
                    tx = session.query(Transaction).filter(
                        Transaction.idempotency_key == idempotency_key,
                        Transaction.status.in_(["completed", "simulated_duress"]),
                    ).first()
                    if tx:
                        return ReconcileResult(
                            status="COMPLETED",
                            transaction=tx,
                            is_simulated=(tx.status == "simulated_duress"),
                            idempotency_key=idempotency_key,
                            reservation=res,
                        )
                    else:
                        return ReconcileResult(
                            status="PROCESSING",
                            retryable=True,
                            idempotency_key=idempotency_key,
                            error_message="Previous execution lease expired without committed transaction. Transfer may be retried.",
                            reservation=res,
                        )

            return ReconcileResult(
                status="NOT_FOUND",
                retryable=True,
                idempotency_key=idempotency_key,
                error_code="NOT_FOUND",
                error_message="Unknown reservation status.",
            )

        finally:
            session.close()
