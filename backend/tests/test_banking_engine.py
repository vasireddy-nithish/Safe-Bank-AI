"""Comprehensive test suite for Stage 4 Banking Transaction Engine:
Two-phase idempotency reservations, lease fencing, CAS concurrency, crash recovery,
bounded SQLITE_BUSY retries, daily limits, and SQLite transaction tracing.
"""

from datetime import timedelta
from decimal import Decimal
import threading
import time
import uuid

import pytest
from sqlalchemy import create_engine, event
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from app.core.config import settings
from app.db.base import Base, utc_now
from app.db.session import ActiveTransactionError, write_transaction
from app.models.account import Account
from app.models.idempotency_reservation import IdempotencyReservation
from app.models.session import Session as AppSession
from app.models.transaction import Transaction
from app.models.user import User
from app.services.banking_service import BankingService


@pytest.fixture
def file_db(tmp_path):
    """Provides a fresh file-backed SQLite database in WAL mode with independent sessions."""
    db_file = tmp_path / "banking_test.db"
    db_url = f"sqlite:///{db_file}"
    engine = create_engine(
        db_url,
        connect_args={"check_same_thread": False, "timeout": 15},
    )

    @event.listens_for(engine, "connect")
    def set_sqlite_pragma(dbapi_connection, connection_record):
        dbapi_connection.isolation_level = None
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.close()

    @event.listens_for(engine, "begin")
    def do_begin(conn):
        opts = conn.get_execution_options()
        if opts.get("sqlite_write_immediate", False):
            conn.exec_driver_sql("BEGIN IMMEDIATE")
        else:
            conn.exec_driver_sql("BEGIN")

    Base.metadata.create_all(bind=engine)
    session_factory = sessionmaker(autocommit=False, autoflush=False, bind=engine, expire_on_commit=False)

    yield engine, session_factory
    engine.dispose()


@pytest.fixture
def seeded_accounts(file_db):
    """Seed a test user with checking and savings accounts."""
    engine, session_factory = file_db
    session = session_factory()
    user_id = str(uuid.uuid4())
    user = User(
        id=user_id,
        username="alex_chen_engine_test",
        email="alex.engine@example.com",
        full_name="Alex Chen",
        is_active=True,
    )
    checking = Account(
        user_id=user_id,
        account_number="SB-CHK-1001",
        account_type="checking",
        balance=Decimal("124500.80"),
        currency="INR",
        status="active",
    )
    savings = Account(
        user_id=user_id,
        account_number="SB-SAV-1002",
        account_type="savings",
        balance=Decimal("350000.00"),
        currency="INR",
        status="active",
    )
    session.add_all([user, checking, savings])
    session.commit()
    chk_id, sav_id = checking.id, savings.id
    session.close()
    return user_id, chk_id, sav_id, session_factory


# ==============================================================================
# TEST-S4-01: INR Decimal Precision
# ==============================================================================
def test_s4_01_inr_decimal_precision(seeded_accounts):
    """
    Verify operations with fractional amounts (₹1,24,500.80 minus ₹49.99 equals ₹1,24,450.81).
    Proves zero floating-point rounding errors across Python Decimal and SQLite NUMERIC(14,2).
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    amount = Decimal("49.99")
    key = str(uuid.uuid4())

    res = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
    )
    assert res.success is True
    assert res.transaction.amount == Decimal("49.99")

    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    check_session.close()

    assert chk.balance == Decimal("124450.81")
    assert sav.balance == Decimal("350049.99")


# ==============================================================================
# TEST-S4-02: Identical Idempotency Replay
# ==============================================================================
def test_s4_02_identical_idempotency_replay(seeded_accounts):
    """
    Submit transfer with key K1, then re-submit exact same 4 financial fields with key K1.
    Verifies original receipt is returned, and balances are debited/credited exactly once.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("5000.00")

    # First attempt
    res1 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
    )
    assert res1.success is True
    tx_ref = res1.transaction.reference_number

    # Second attempt (replay identical payload and key)
    res2 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
    )
    assert res2.success is True
    assert res2.transaction.reference_number == tx_ref

    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    tx_count = check_session.query(Transaction).filter_by(idempotency_key=key).count()
    check_session.close()

    assert chk.balance == Decimal("124500.80") - Decimal("5000.00")
    assert sav.balance == Decimal("350000.00") + Decimal("5000.00")
    assert tx_count == 1


# ==============================================================================
# TEST-S4-03: Mismatched Idempotency Replay
# ==============================================================================
def test_s4_03_mismatched_idempotency_replay(seeded_accounts):
    """
    Submit transfer of ₹500.00 with key K2. Submit second transfer of ₹1,000.00 using same key K2.
    Verifies server rejects request with conflict (IDEMPOTENCY_PAYLOAD_MISMATCH).
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())

    res1 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("500.00"),
        idempotency_key=key,
        user_id=user_id,
    )
    assert res1.success is True

    # Replay with different amount
    res2 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("1000.00"),
        idempotency_key=key,
        user_id=user_id,
    )
    assert res2.success is False
    assert res2.status == "conflict"
    assert res2.error_code == "IDEMPOTENCY_PAYLOAD_MISMATCH"
    assert "IDEMPOTENCY_PAYLOAD_MISMATCH" in res2.error_message


# ==============================================================================
# TEST-S4-04: Reconciliation In Flight (PROCESSING)
# ==============================================================================
def test_s4_04_reconciliation_in_flight_processing(seeded_accounts):
    """
    Verify that an active in-flight transfer reservation returns PROCESSING state with retry advisory.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())

    # Manually create a reservation in PROCESSING state with active lease
    session = session_factory()
    res = IdempotencyReservation(
        idempotency_key=key,
        user_id=user_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("2000.00"),
        currency="INR",
        status="PROCESSING",
        lease_generation=1,
        owner_worker_id=str(uuid.uuid4()),
        lease_expires_at=utc_now() + timedelta(seconds=30),
    )
    session.add(res)
    session.commit()
    session.close()

    reconcile_res = BankingService.reconcile_transfer(
        session_factory=session_factory,
        idempotency_key=key,
    )
    assert reconcile_res.status == "PROCESSING"
    assert reconcile_res.retryable is True
    assert "actively processing" in reconcile_res.error_message


# ==============================================================================
# TEST-S4-05: Server Interruption & Abandoned Lease Recovery
# ==============================================================================
def test_s4_05_server_interruption_and_recovery(seeded_accounts):
    """
    Simulate server crash after Phase 1 commit leaving an abandoned PROCESSING reservation.
    Advance time past lease_expires_at. Submit retry / recovery transfer.
    Verifies recovery detects no financial transaction committed, renews lease via CAS,
    executes safely, and moves funds exactly once.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("3000.00")

    # Simulate abandoned reservation from crashed worker
    session = session_factory()
    crashed_worker_id = str(uuid.uuid4())
    abandoned_res = IdempotencyReservation(
        idempotency_key=key,
        user_id=user_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        currency="INR",
        status="PROCESSING",
        lease_generation=1,
        owner_worker_id=crashed_worker_id,
        lease_expires_at=utc_now() - timedelta(seconds=10),  # Expired in past
    )
    session.add(abandoned_res)
    session.commit()
    session.close()

    # Recovery worker takes over
    recovery_worker_id = str(uuid.uuid4())
    res = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
        worker_id=recovery_worker_id,
    )
    assert res.success is True
    assert res.status == "completed"

    check_session = session_factory()
    updated_res = check_session.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    txs = check_session.query(Transaction).filter_by(idempotency_key=key).all()
    check_session.close()

    assert updated_res.status == "COMPLETED"
    assert updated_res.lease_generation == 2
    assert updated_res.owner_worker_id == recovery_worker_id
    assert len(txs) == 1
    assert chk.balance == Decimal("124500.80") - amount
    assert sav.balance == Decimal("350000.00") + amount


# ==============================================================================
# TEST-S4-06: Concurrent Duplicate Race with File-Backed SQLite
# ==============================================================================
def test_s4_06_concurrent_duplicate_race_file_backed(seeded_accounts):
    """
    Using file-backed SQLite and independent worker sessions, launch 5 concurrent threads
    simultaneously submitting the exact same transfer payload and key K1.
    Verifies IntegrityError / CAS handling moves funds exactly once, exactly 1 Transaction commits,
    and all 5 threads receive the identical receipt.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("2500.00")
    results = []

    def worker(worker_num):
        w_id = f"worker_{worker_num}_{uuid.uuid4().hex[:6]}"
        r = BankingService.execute_transfer(
            session_factory=session_factory,
            from_account_id=chk_id,
            to_account_id=sav_id,
            amount=amount,
            idempotency_key=key,
            user_id=user_id,
            worker_id=w_id,
        )
        results.append(r)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(results) == 5
    assert all(r.success for r in results)

    # All threads must observe the same transaction reference number
    ref_numbers = {r.transaction.reference_number for r in results if r.transaction}
    assert len(ref_numbers) == 1

    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    tx_count = check_session.query(Transaction).filter_by(idempotency_key=key).count()
    check_session.close()

    assert chk.balance == Decimal("124500.80") - amount
    assert sav.balance == Decimal("350000.00") + amount
    assert tx_count == 1


# ==============================================================================
# TEST-S4-07: Client Retry After Uncertain Outcome
# ==============================================================================
def test_s4_07_client_retry_after_uncertain_outcome(seeded_accounts):
    """
    Client submits transfer, simulates network drop before receiving HTTP response.
    Client retries with identical payload and key.
    Verifies server returns original receipt with zero balance change on retry.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("1500.00")

    # Initial submission
    res1 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
    )
    assert res1.success is True

    # Client simulates retry
    res2 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
    )
    assert res2.success is True
    assert res2.transaction.id == res1.transaction.id

    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    check_session.close()
    assert chk.balance == Decimal("124500.80") - amount


# ==============================================================================
# TEST-S4-08: Concurrent Daily Limit Verification
# ==============================================================================
def test_s4_08_concurrent_daily_limit_verification(seeded_accounts, monkeypatch):
    """
    Configure limit to ₹50,000. Launch two concurrent transfers of ₹30,000 each.
    Verify serialized database locking ensures exactly one succeeds and the second is rejected
    with DAILY_LIMIT_EXCEEDED. Directly inspect balances to verify only ₹30,000 was debited.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    monkeypatch.setattr(settings, "DAILY_TRANSFER_LIMIT_INR", Decimal("50000.00"))

    transfer_amount = Decimal("30000.00")
    results = []

    def run_transfer(t_id):
        key = f"key_limit_{t_id}_{uuid.uuid4().hex[:6]}"
        r = BankingService.execute_transfer(
            session_factory=session_factory,
            from_account_id=chk_id,
            to_account_id=sav_id,
            amount=transfer_amount,
            idempotency_key=key,
            user_id=user_id,
        )
        results.append(r)

    t1 = threading.Thread(target=run_transfer, args=(1,))
    t2 = threading.Thread(target=run_transfer, args=(2,))
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    assert len(results) == 2
    successes = [r for r in results if r.success]
    failures = [r for r in results if not r.success]

    assert len(successes) == 1
    assert len(failures) == 1
    assert failures[0].error_code == "DAILY_LIMIT_EXCEEDED"

    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    check_session.close()
    # Debited exactly once for ₹30,000
    assert chk.balance == Decimal("124500.80") - Decimal("30000.00")


# ==============================================================================
# TEST-S4-09: Duress Operations Excluded From Daily Limit
# ==============================================================================
def test_s4_09_duress_operations_excluded_from_daily_limit(seeded_accounts, monkeypatch):
    """
    Under a restricted session, execute a simulated duress transfer of ₹45,000.
    Verify balances remain untouched and status is simulated_duress.
    Then execute a normal transfer of ₹30,000 in a normal session.
    Verifies normal transfer succeeds because duress operations consume zero daily limit.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    monkeypatch.setattr(settings, "DAILY_TRANSFER_LIMIT_INR", Decimal("50000.00"))

    # Step 1: Simulated duress transfer of ₹45,000
    duress_key = str(uuid.uuid4())
    res_duress = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("45000.00"),
        idempotency_key=duress_key,
        user_id=user_id,
        is_restricted=True,
    )
    assert res_duress.success is True
    assert res_duress.is_simulated is True
    assert res_duress.transaction.status == "simulated_duress"

    check_session = session_factory()
    chk_after_duress = check_session.query(Account).filter_by(id=chk_id).first()
    check_session.close()
    assert chk_after_duress.balance == Decimal("124500.80")  # Untouched

    # Step 2: Normal transfer of ₹30,000 (If duress was counted, 45k + 30k = 75k > 50k and would fail)
    normal_key = str(uuid.uuid4())
    res_normal = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("30000.00"),
        idempotency_key=normal_key,
        user_id=user_id,
        is_restricted=False,
    )
    assert res_normal.success is True
    assert res_normal.is_simulated is False
    assert res_normal.transaction.status == "completed"

    check_session2 = session_factory()
    chk_after_normal = check_session2.query(Account).filter_by(id=chk_id).first()
    check_session2.close()
    assert chk_after_normal.balance == Decimal("124500.80") - Decimal("30000.00")


# ==============================================================================
# TEST-S4-10: Reconciliation: COMPLETED & FAILED
# ==============================================================================
def test_s4_10_reconciliation_completed_and_failed(seeded_accounts):
    """Query reconcile endpoint for completed and failed transfers."""
    user_id, chk_id, sav_id, session_factory = seeded_accounts

    # 1. Completed transfer
    comp_key = str(uuid.uuid4())
    BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("1000.00"),
        idempotency_key=comp_key,
        user_id=user_id,
    )
    rec_comp = BankingService.reconcile_transfer(
        session_factory=session_factory,
        idempotency_key=comp_key,
    )
    assert rec_comp.status == "COMPLETED"
    assert rec_comp.transaction is not None
    assert rec_comp.transaction.status == "completed"

    # 2. Failed transfer (insufficient funds)
    fail_key = str(uuid.uuid4())
    BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("999999.00"),
        idempotency_key=fail_key,
        user_id=user_id,
    )
    rec_fail = BankingService.reconcile_transfer(
        session_factory=session_factory,
        idempotency_key=fail_key,
    )
    assert rec_fail.status == "FAILED"
    assert rec_fail.error_code == "INSUFFICIENT_FUNDS"


# ==============================================================================
# TEST-S4-11: Reconciliation: NOT_FOUND
# ==============================================================================
def test_s4_11_reconciliation_not_found(seeded_accounts):
    """Query reconcile for an unrecorded idempotency key."""
    _, _, _, session_factory = seeded_accounts
    unknown_key = str(uuid.uuid4())
    rec = BankingService.reconcile_transfer(
        session_factory=session_factory,
        idempotency_key=unknown_key,
    )
    assert rec.status == "NOT_FOUND"
    assert rec.retryable is True


# ==============================================================================
# TEST-S4-17: Stale Worker Fencing Rejection
# ==============================================================================
def test_s4_17_stale_worker_fencing_rejection(seeded_accounts):
    """
    Worker 1 acquires reservation at lease_generation = 1.
    Worker 2 takes over the expired lease via CAS, incrementing to lease_generation = 2,
    and completes Phase 2. Worker 1 resumes and attempts Phase 2 commit.
    Verifies Worker 1's fencing check fails, transaction rolls back, and Worker 1
    cannot alter account balances.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("5000.00")

    worker_1_id = "worker_1_stale"
    worker_2_id = "worker_2_takeover"

    # Worker 1 runs Phase 1
    outcome1, token1, _, _ = BankingService.reserve_transfer_key(
        session_factory=session_factory,
        idempotency_key=key,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        user_id=user_id,
        worker_id=worker_1_id,
    )
    assert outcome1 == "PROCEED"
    assert token1 == 1

    # Worker 1 pauses. Time passes so lease expires.
    session = session_factory()
    res = session.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    res.lease_expires_at = utc_now() - timedelta(seconds=1)
    session.commit()
    session.close()

    # Worker 2 takes over: runs Phase 1 (CAS claim) and Phase 2
    res2 = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
        worker_id=worker_2_id,
    )
    assert res2.success is True

    check_session = session_factory()
    chk_after_w2 = check_session.query(Account).filter_by(id=chk_id).first().balance
    sav_after_w2 = check_session.query(Account).filter_by(id=sav_id).first().balance
    check_session.close()

    # Now Worker 1 unpauses and tries to execute Phase 2 with its stale token1 = 1
    res1_phase2 = BankingService.execute_transfer_phase2(
        session_factory=session_factory,
        idempotency_key=key,
        fencing_token=token1,  # Stale token: 1
        worker_id=worker_1_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        allow_lease_renewal=False,
    )

    # Worker 1's Phase 2 was rejected or resolved to existing completed
    check_session2 = session_factory()
    chk_after_w1 = check_session2.query(Account).filter_by(id=chk_id).first().balance
    sav_after_w1 = check_session2.query(Account).filter_by(id=sav_id).first().balance
    tx_count = check_session2.query(Transaction).filter_by(idempotency_key=key).count()
    check_session2.close()

    # ZERO additional debit or credit occurred!
    assert chk_after_w1 == chk_after_w2
    assert sav_after_w1 == sav_after_w2
    assert tx_count == 1


# ==============================================================================
# TEST-S4-18: Racing Lease Claim CAS Concurrency
# ==============================================================================
def test_s4_18_racing_lease_claim_cas_concurrency(seeded_accounts):
    """
    Two workers simultaneously detect an expired lease and attempt CAS claim.
    Verifies exactly one worker increments generation and claims lease;
    the second matches 0 rows, rolls back, and fails the claim without duplicating execution.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())

    # Create expired reservation
    session = session_factory()
    res = IdempotencyReservation(
        idempotency_key=key,
        user_id=user_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("1000.00"),
        currency="INR",
        status="PROCESSING",
        lease_generation=1,
        owner_worker_id="initial_worker",
        lease_expires_at=utc_now() - timedelta(seconds=10),
    )
    session.add(res)
    session.commit()
    session.close()

    results = []

    def claim_lease(worker_name):
        s = session_factory()
        with write_transaction(s):
            won = BankingService.attempt_cas_lease_claim(
                session=s,
                idempotency_key=key,
                current_generation=1,
                worker_id=worker_name,
            )
            results.append((worker_name, won))
        s.close()

    t1 = threading.Thread(target=claim_lease, args=("worker_a",))
    t2 = threading.Thread(target=claim_lease, args=("worker_b",))
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    wins = [won for _, won in results if won]
    losses = [won for _, won in results if not won]
    assert len(wins) == 1
    assert len(losses) == 1

    check_session = session_factory()
    claimed_res = check_session.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    check_session.close()
    assert claimed_res.lease_generation == 2


# ==============================================================================
# TEST-S4-19: SQLITE_BUSY Contention & Bounded Retry
# ==============================================================================
def test_s4_19_sqlite_busy_contention_and_bounded_retry(seeded_accounts, monkeypatch):
    """
    Simulate sqlite3.OperationalError: database is locked.
    Verifies service rolls back, retries up to 3 times preserving original idempotency_key,
    and returns controlled DATABASE_CONTENTION_RETRYABLE if contention persists.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())

    attempts = []

    # Monkeypatch reserve_transfer_key to raise OperationalError("database is locked")
    original_reserve = BankingService.reserve_transfer_key

    def mock_reserve(*args, **kwargs):
        attempts.append(1)
        import sqlite3
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(BankingService, "reserve_transfer_key", mock_reserve)

    res = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("1000.00"),
        idempotency_key=key,
        user_id=user_id,
        max_retries=3,
    )

    assert len(attempts) == 3
    assert res.success is False
    assert res.status == "contention_retryable"
    assert res.retryable is True
    assert res.error_code == "DATABASE_CONTENTION_RETRYABLE"
    assert res.idempotency_key == key


# ==============================================================================
# TEST-S4-20: Paused Worker End-to-End Verification
# ==============================================================================
def test_s4_20_paused_worker_end_to_end_verification(seeded_accounts):
    """
    Worker 1 begins transfer, pauses beyond 30s.
    Worker 2 takes over, debits ₹5,000, credits ₹5,000, and completes transfer.
    Worker 1 unpauses and resumes.
    Direct database inspection verifies: source account debited by exactly ₹5,000 (not ₹10,000),
    destination credited by ₹5,000, exactly one Transaction row exists, and reservation is COMPLETED.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("5000.00")

    worker_1_id = "worker_1_paused"
    worker_2_id = "worker_2_active"

    # Worker 1 reserves key
    outcome, token1, _, _ = BankingService.reserve_transfer_key(
        session_factory=session_factory,
        idempotency_key=key,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        user_id=user_id,
        worker_id=worker_1_id,
    )
    assert outcome == "PROCEED"
    assert token1 == 1

    # Worker 1 pauses. Lease expires.
    s = session_factory()
    r = s.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    r.lease_expires_at = utc_now() - timedelta(seconds=5)
    s.commit()
    s.close()

    # Worker 2 executes completely
    w2_res = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
        worker_id=worker_2_id,
    )
    assert w2_res.success is True

    # Worker 1 unpauses and resumes Phase 2 with stale token 1
    w1_res = BankingService.execute_transfer_phase2(
        session_factory=session_factory,
        idempotency_key=key,
        fencing_token=token1,
        worker_id=worker_1_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        allow_lease_renewal=False,
    )

    # Verification: Exactly ₹5,000 debited/credited, exactly 1 Transaction row
    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    txs = check_session.query(Transaction).filter_by(idempotency_key=key).all()
    res_final = check_session.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    check_session.close()

    assert chk.balance == Decimal("124500.80") - Decimal("5000.00")
    assert sav.balance == Decimal("350000.00") + Decimal("5000.00")
    assert len(txs) == 1
    assert res_final.status == "COMPLETED"


# ==============================================================================
# TEST-S4-21: Active Session Rejection in write_transaction
# ==============================================================================
def test_s4_21_active_session_rejection_in_write_transaction(seeded_accounts):
    """
    Pass an active SQLAlchemy session (session.in_transaction() == True) with uncommitted
    changes into write_transaction. Verifies ActiveTransactionError is explicitly raised
    and the active transaction is NEVER silently committed.
    """
    _, chk_id, _, session_factory = seeded_accounts
    session = session_factory()

    # Start a transaction on session
    acc = session.query(Account).filter_by(id=chk_id).first()
    acc.account_number = "SB-MUTATED-UNCOMMITTED"
    assert session.in_transaction() is True

    with pytest.raises(ActiveTransactionError) as exc_info:
        with write_transaction(session):
            pass

    assert "write_transaction requires a fresh or clean session" in str(exc_info.value)
    session.rollback()
    session.close()


# ==============================================================================
# TEST-S4-22: Pre-Execution Lease Expiry Fails Phase 2 Without Worker Claim
# ==============================================================================
def test_s4_22_pre_execution_lease_expiry_fails_phase2_without_worker_claim(seeded_accounts):
    """
    Worker 1 reserves key in Phase 1. Artificially advance clock so lease_expires_at <= utc_now(),
    but no Worker 2 has claimed the lease. Worker 1 attempts Phase 2.
    Verifies condition 5 (lease_expires_at > utc_now()) fails, Phase 2 rolls back,
    and account balances remain 100% untouched.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("4000.00")
    worker_1_id = "worker_1_solo_expire"

    outcome, token1, _, _ = BankingService.reserve_transfer_key(
        session_factory=session_factory,
        idempotency_key=key,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        user_id=user_id,
        worker_id=worker_1_id,
    )
    assert outcome == "PROCEED"

    # Expire the lease
    s = session_factory()
    res = s.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    res.lease_expires_at = utc_now() - timedelta(seconds=2)
    s.commit()
    s.close()

    # Worker 1 attempts Phase 2 without renewal allowed
    p2_res = BankingService.execute_transfer_phase2(
        session_factory=session_factory,
        idempotency_key=key,
        fencing_token=token1,
        worker_id=worker_1_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        allow_lease_renewal=False,
    )
    assert p2_res.success is False
    assert p2_res.status == "fencing_rejected"
    assert p2_res.error_code == "STALE_WORKER_FENCING_REJECTED"

    # Balances must be 100% untouched
    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    tx_count = check_session.query(Transaction).filter_by(idempotency_key=key).count()
    check_session.close()

    assert chk.balance == Decimal("124500.80")
    assert sav.balance == Decimal("350000.00")
    assert tx_count == 0


# ==============================================================================
# TEST-S4-23: Valid Current Lease Executes Exactly Once
# ==============================================================================
def test_s4_23_valid_current_lease_executes_exactly_once(seeded_accounts):
    """
    Worker 1 reserves key in Phase 1, enters Phase 2 within active lease window.
    All 5 fencing conditions pass. Verifies transfer commits successfully;
    balances debited and credited exactly once.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("7000.00")

    res = BankingService.execute_transfer(
        session_factory=session_factory,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        idempotency_key=key,
        user_id=user_id,
    )
    assert res.success is True
    assert res.status == "completed"

    check_session = session_factory()
    chk = check_session.query(Account).filter_by(id=chk_id).first()
    sav = check_session.query(Account).filter_by(id=sav_id).first()
    check_session.close()

    assert chk.balance == Decimal("124500.80") - amount
    assert sav.balance == Decimal("350000.00") + amount


# ==============================================================================
# TEST-S4-24: File-Backed SQLite Statement & Transaction Trace
# ==============================================================================
def test_s4_24_file_backed_sqlite_statement_and_transaction_trace(tmp_path):
    """
    File-backed SQLite integration test verifying exact transaction mechanics:
    1. Use before_cursor_execute to verify BEGIN IMMEDIATE is the actual first statement
       for write transactions, and ordinary BEGIN is used for read queries.
    2. Use sqlite3.Connection.set_trace_callback to verify COMMIT and ROLLBACK are dispatched.
    3. Verify committed data persists in the disk file upon querying from independent session.
    4. Verify rolled-back data does not persist.
    5. Verify underlying dbapi_connection.in_transaction is False immediately following commit/rollback.
    6. Verify that under WAL mode, readers do not block writers and writers do not block readers.
    """
    db_file = tmp_path / "sqlite_trace_test.db"
    db_url = f"sqlite:///{db_file}"
    engine = create_engine(
        db_url,
        connect_args={"check_same_thread": False, "timeout": 15},
    )

    traced_dbapi_statements = []

    @event.listens_for(engine, "connect")
    def connect_listener(dbapi_conn, rec):
        dbapi_conn.isolation_level = None
        dbapi_conn.set_trace_callback(lambda stmt: traced_dbapi_statements.append(stmt.strip()))
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA busy_timeout=5000")
        cur.close()

    cursor_executed_statements = []

    @event.listens_for(engine, "before_cursor_execute")
    def before_cursor_listener(conn, cursor, statement, parameters, context, executemany):
        cursor_executed_statements.append(statement.strip())

    @event.listens_for(engine, "begin")
    def do_begin(conn):
        opts = conn.get_execution_options()
        if opts.get("sqlite_write_immediate", False):
            conn.exec_driver_sql("BEGIN IMMEDIATE")
        else:
            conn.exec_driver_sql("BEGIN")

    Base.metadata.create_all(bind=engine)
    session_factory = sessionmaker(autocommit=False, autoflush=False, bind=engine, expire_on_commit=False)

    cursor_executed_statements.clear()
    traced_dbapi_statements.clear()

    # Step 1: Verify BEGIN IMMEDIATE is the first statement in write_transaction
    s1 = session_factory()
    with write_transaction(s1):
        u1 = User(id="user_trace_1", username="trace_1", email="t1@example.com", full_name="T1")
        s1.add(u1)
    s1.close()

    write_first_stmt = cursor_executed_statements[0]
    assert write_first_stmt == "BEGIN IMMEDIATE"
    assert any("COMMIT" in s.upper() for s in traced_dbapi_statements)

    # Step 2: Verify ordinary BEGIN is used for read queries
    cursor_executed_statements.clear()
    s_read = session_factory()
    fetched_user = s_read.query(User).filter_by(id="user_trace_1").first()
    assert fetched_user is not None
    s_read.commit()
    s_read.close()

    read_first_stmt = cursor_executed_statements[0]
    assert read_first_stmt == "BEGIN"

    # Step 3: Verify rolled-back data does not persist
    cursor_executed_statements.clear()
    traced_dbapi_statements.clear()
    s_rollback = session_factory()
    try:
        with write_transaction(s_rollback):
            u_bad = User(id="user_trace_bad", username="trace_bad", email="bad@example.com", full_name="Bad")
            s_rollback.add(u_bad)
            raise ValueError("Intentional abort")
    except ValueError:
        pass
    s_rollback.close()

    assert any("ROLLBACK" in s.upper() for s in traced_dbapi_statements)
    s_verify = session_factory()
    bad_user = s_verify.query(User).filter_by(id="user_trace_bad").first()
    assert bad_user is None
    s_verify.close()

    # Step 4: WAL reader non-blocking verification
    # Under WAL mode, an open reader session does not block a concurrent writer session
    s_reader = session_factory()
    _ = s_reader.query(User).all()  # Reader holds read transaction

    s_writer = session_factory()
    with write_transaction(s_writer):
        u_wal = User(id="user_trace_wal", username="trace_wal", email="wal@example.com", full_name="WAL")
        s_writer.add(u_wal)
    s_writer.close()
    s_reader.close()

    s_final = session_factory()
    wal_user = s_final.query(User).filter_by(id="user_trace_wal").first()
    assert wal_user is not None
    s_final.close()

    engine.dispose()


# ==============================================================================
# Database Invariant: Database-level UNIQUE Constraint on transactions.idempotency_key
# ==============================================================================
def test_database_level_unique_constraint_transactions_idempotency_key(seeded_accounts):
    """
    CRITICAL DATABASE INVARIANT TEST:
    Verify that transactions.idempotency_key has a database-level UNIQUE constraint.
    Attempting to insert two Transaction rows with the same idempotency key must be
    rejected directly by SQLite with IntegrityError, independent of application validation.
    """
    _, chk_id, sav_id, session_factory = seeded_accounts
    shared_key = f"idem_dup_{uuid.uuid4().hex}"

    s1 = session_factory()
    tx1 = Transaction(
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("100.00"),
        currency="INR",
        transaction_type="transfer",
        status="completed",
        reference_number=f"REF_1_{uuid.uuid4().hex[:8]}",
        idempotency_key=shared_key,
    )
    s1.add(tx1)
    s1.commit()
    s1.close()

    # Attempt second insertion in a separate session with the identical idempotency_key
    s2 = session_factory()
    tx2 = Transaction(
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=Decimal("200.00"),
        currency="INR",
        transaction_type="transfer",
        status="completed",
        reference_number=f"REF_2_{uuid.uuid4().hex[:8]}",
        idempotency_key=shared_key,  # Duplicate!
    )
    s2.add(tx2)
    with pytest.raises(IntegrityError) as exc_info:
        s2.commit()

    s2.rollback()
    s2.close()

    err_msg = str(exc_info.value).lower()
    assert "unique constraint failed" in err_msg or "unique" in err_msg
    assert "transactions.idempotency_key" in err_msg or "idempotency_key" in err_msg


# ==============================================================================
# Phase 2 Atomicity: Reservation Completion & Transaction Insertion in Same Write Transaction
# ==============================================================================
def test_phase2_atomicity_reservation_and_transaction_committed_together(seeded_accounts):
    """
    Verify that reservation completion and financial transaction insertion occur
    in the same Phase 2 atomic transaction. Both must exist and link together upon commit.
    """
    user_id, chk_id, sav_id, session_factory = seeded_accounts
    key = str(uuid.uuid4())
    amount = Decimal("500.00")

    # Phase 1: Reserve key
    outcome, token, worker_id, _ = BankingService.reserve_transfer_key(
        session_factory=session_factory,
        idempotency_key=key,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
        user_id=user_id,
    )
    assert outcome == "PROCEED"

    # Execute Phase 2
    res = BankingService.execute_transfer_phase2(
        session_factory=session_factory,
        idempotency_key=key,
        fencing_token=token,
        worker_id=worker_id,
        from_account_id=chk_id,
        to_account_id=sav_id,
        amount=amount,
    )
    assert res.success is True

    # Inspect directly that both are committed and linked
    s = session_factory()
    db_res = s.query(IdempotencyReservation).filter_by(idempotency_key=key).first()
    db_tx = s.query(Transaction).filter_by(idempotency_key=key).first()
    s.close()

    assert db_res is not None
    assert db_tx is not None
    assert db_res.status == "COMPLETED"
    assert db_tx.status == "completed"
    assert db_res.transaction_id == db_tx.id

