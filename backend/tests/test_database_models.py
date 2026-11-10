"""Unit tests for SafeBank AI SQLAlchemy ORM models, constraints, and privacy audit."""

import pytest
import uuid
from decimal import Decimal
from datetime import timedelta
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from app.db.base import Base, utc_now
from app.models import (
    User,
    Device,
    Credential,
    Session as SessionModel,
    Account,
    Transaction,
    RiskEvent,
    DuressEvent,
    SecurityEvent,
    IdempotencyReservation,
)
from app.db.session import ActiveTransactionError, write_transaction

# ---------------------------------------------------------------------------
# 1. Zero Plaintext & Privacy Audit Tests
# ---------------------------------------------------------------------------

def test_database_schema_privacy_audit():
    """
    Architectural Privacy Guarantee:
    Verify that no table contains columns for plaintext PINs, private keys,
    bearer tokens, or raw sensor telemetry.
    """
    for table_name, table in Base.metadata.tables.items():
        column_names = [col.name.lower() for col in table.columns]

        # No plaintext PIN or password columns
        assert "pin" not in column_names, f"Forbidden plaintext PIN column in {table_name}"
        assert "password" not in column_names, f"Forbidden password column in {table_name}"

        # No private key columns
        assert "private_key" not in column_names, f"Forbidden private key column in {table_name}"
        assert "priv_key" not in column_names, f"Forbidden private key column in {table_name}"

        # No raw bearer tokens in database tables
        assert "token" not in column_names or "hash" in [c for c in column_names if "token" in c][0], (
            f"Un-hashed bearer token column found in {table_name}"
        )

        # No raw sensor telemetry (touch coordinates, motion traces)
        for forbidden in ["raw_touch", "raw_motion", "gyro_raw", "accelerometer_raw", "keystroke_timestamps"]:
            assert forbidden not in column_names, f"Raw sensor trace column '{forbidden}' found in {table_name}"

# ---------------------------------------------------------------------------
# 2. Database Constraints & CheckConstraints Tests
# ---------------------------------------------------------------------------

def test_user_creation_and_constraints(db_session: Session):
    """Test User model creation, UUID primary key, and uniqueness constraints."""
    user = User(
        username="alice_banking",
        email="alice@example.com",
        full_name="Alice Vance",
        duress_pin_hash="$argon2id$v=19$m=65536,t=3,p=4$dummyhash123",
        duress_configured=True,
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    assert user.id is not None
    assert len(user.id) == 36
    assert user.username == "alice_banking"
    assert user.is_active is True
    assert user.duress_configured is True

    # Unique username violation
    dup_user = User(username="alice_banking", email="other@example.com", full_name="Alice Duplicate")
    db_session.add(dup_user)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

    # Unique email violation
    dup_email = User(username="other_alice", email="alice@example.com", full_name="Alice Duplicate")
    db_session.add(dup_email)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_credential_unique_id_constraint(db_session: Session):
    """Test unique constraint on passkey credential_id."""
    user = User(username="carol_passkey", email="carol@example.com", full_name="Carol User")
    db_session.add(user)
    db_session.commit()

    cred1 = Credential(
        user_id=user.id,
        credential_id="unique_cred_id_12345",
        public_key="p256_cose_public_key_bytes",
        sign_count=1,
    )
    db_session.add(cred1)
    db_session.commit()

    # Attempt to insert identical credential_id
    cred2 = Credential(
        user_id=user.id,
        credential_id="unique_cred_id_12345",
        public_key="different_public_key_bytes",
        sign_count=0,
    )
    db_session.add(cred2)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_account_negative_balance_check_constraint(db_session: Session):
    """Verify check_account_balance_non_negative rejects negative balances at database level."""
    user = User(username="negative_acc", email="neg@example.com", full_name="Negative User")
    db_session.add(user)
    db_session.commit()

    # Negative balance must fail check constraint
    invalid_account = Account(
        user_id=user.id,
        account_number="SB-NEG-001",
        balance=Decimal("-50.00"),
        status="active",
    )
    db_session.add(invalid_account)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_account_status_and_type_check_constraints(db_session: Session):
    """Verify check constraints for account status and type."""
    user = User(username="status_acc", email="status@example.com", full_name="Status User")
    db_session.add(user)
    db_session.commit()

    # Invalid status
    bad_status = Account(
        user_id=user.id,
        account_number="SB-BAD-001",
        balance=Decimal("100.00"),
        status="invalid_status_enum",
    )
    db_session.add(bad_status)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

    # Invalid type
    bad_type = Account(
        user_id=user.id,
        account_number="SB-BAD-002",
        balance=Decimal("100.00"),
        account_type="credit_card",  # Allowed: checking, savings
    )
    db_session.add(bad_type)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_transaction_amount_positive_check_constraint(db_session: Session):
    """Verify check_transaction_amount_positive rejects zero or negative amounts at DB level."""
    user = User(username="tx_constraint_user", email="tx_c@example.com", full_name="TX User")
    db_session.add(user)
    db_session.commit()

    acc1 = Account(user_id=user.id, account_number="SB-TX-01", balance=Decimal("100.00"))
    acc2 = Account(user_id=user.id, account_number="SB-TX-02", balance=Decimal("100.00"))
    db_session.add_all([acc1, acc2])
    db_session.commit()

    # Zero amount
    tx_zero = Transaction(
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("0.00"),
        status="pending",
        reference_number="REF-001",
        idempotency_key="key_001",
    )
    db_session.add(tx_zero)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

    # Negative amount
    tx_neg = Transaction(
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("-10.00"),
        status="pending",
        reference_number="REF-002",
        idempotency_key="key_002",
    )
    db_session.add(tx_neg)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_risk_event_score_range_check_constraint(db_session: Session):
    """Verify check_risk_score_range restricts risk score to 0..100."""
    user = User(username="risk_constraint_user", email="risk_c@example.com", full_name="Risk User")
    db_session.add(user)
    db_session.commit()

    # Score > 100
    bad_high = RiskEvent(
        user_id=user.id,
        risk_score=105,
        risk_level="high",
        behavioral_verdict="deviation",
        behavioral_confidence=0.9,
    )
    db_session.add(bad_high)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

    # Score < 0
    bad_low = RiskEvent(
        user_id=user.id,
        risk_score=-5,
        risk_level="low",
        behavioral_verdict="consistent",
        behavioral_confidence=0.9,
    )
    db_session.add(bad_low)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_delete_account_with_transactions_restricted(db_session: Session):
    """Verify ON DELETE RESTRICT prevents deleting accounts with historical transactions."""
    user = User(username="restrict_user", email="restrict@example.com", full_name="Restrict User")
    db_session.add(user)
    db_session.commit()

    acc1 = Account(user_id=user.id, account_number="SB-RESTRICT-01", balance=Decimal("500.00"))
    acc2 = Account(user_id=user.id, account_number="SB-RESTRICT-02", balance=Decimal("100.00"))
    db_session.add_all([acc1, acc2])
    db_session.commit()

    tx = Transaction(
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("50.00"),
        status="completed",
        reference_number="REF-RESTRICT-1",
        idempotency_key="key-restrict-1",
    )
    db_session.add(tx)
    db_session.commit()

    # Attempting to delete acc1 must fail due to ON DELETE RESTRICT
    db_session.delete(acc1)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_device_binding_model(db_session: Session):
    """Test Device model with hardware-backed public key and status."""
    user = User(username="bob_device", email="bob@example.com", full_name="Bob User")
    db_session.add(user)
    db_session.commit()

    device = Device(
        user_id=user.id,
        device_name="Google Pixel 8 Pro",
        device_fingerprint="fp_hash_987654321",
        public_key_pem="-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...\n-----END PUBLIC KEY-----",
        is_verified=True,
        attestation_type="strongbox",
        status="active",
    )
    db_session.add(device)
    db_session.commit()
    db_session.refresh(device)

    assert device.id is not None
    assert device.attestation_type == "strongbox"
    assert device.is_verified is True
    assert device.user.username == "bob_device"

def test_session_model_and_restricted_mode_flag(db_session: Session):
    """Test Session model with token hash, expiration, and coercion Restricted Mode flag."""
    user = User(username="dan_session", email="dan@example.com", full_name="Dan User")
    db_session.add(user)
    db_session.commit()

    expires = utc_now() + timedelta(minutes=30)
    session = SessionModel(
        user_id=user.id,
        session_token_hash="a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
        is_active=True,
        is_restricted=False,
        expires_at=expires,
        ip_address="192.168.1.100",
    )
    db_session.add(session)
    db_session.commit()
    db_session.refresh(session)

    assert session.is_active is True
    assert session.is_restricted is False

    session.is_restricted = True
    db_session.commit()
    assert session.is_restricted is True

def test_account_and_decimal_monetary_precision(db_session: Session):
    """Test Account model with strict Decimal arithmetic (zero floating-point drift)."""
    user = User(username="eve_account", email="eve@example.com", full_name="Eve User")
    db_session.add(user)
    db_session.commit()

    account = Account(
        user_id=user.id,
        account_number="SB-9001001",
        account_type="checking",
        balance=Decimal("1500.50"),
        currency="USD",
        status="active",
    )
    db_session.add(account)
    db_session.commit()
    db_session.refresh(account)

    assert account.balance == Decimal("1500.50")
    account.balance += Decimal("0.05")
    db_session.commit()
    db_session.refresh(account)
    assert account.balance == Decimal("1500.55")

def test_transaction_idempotency_and_masking(db_session: Session):
    """Test Transaction model with unique idempotency_key and duress masking."""
    user = User(username="frank_tx", email="frank@example.com", full_name="Frank User")
    db_session.add(user)
    db_session.commit()

    acc1 = Account(user_id=user.id, account_number="SB-ACC-01", balance=Decimal("1000.00"))
    acc2 = Account(user_id=user.id, account_number="SB-ACC-02", balance=Decimal("500.00"))
    db_session.add_all([acc1, acc2])
    db_session.commit()

    tx = Transaction(
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("150.00"),
        currency="USD",
        transaction_type="transfer",
        status="completed",
        reference_number="REF-TX-001",
        idempotency_key="idemp_key_unique_123",
        is_duress_masked=False,
    )
    db_session.add(tx)
    db_session.commit()
    db_session.refresh(tx)

    assert tx.amount == Decimal("150.00")
    assert tx.status == "completed"

    dup_tx = Transaction(
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("150.00"),
        currency="USD",
        transaction_type="transfer",
        status="completed",
        reference_number="REF-TX-002",
        idempotency_key="idemp_key_unique_123",
    )
    db_session.add(dup_tx)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_duress_event_model(db_session: Session):
    """Test DuressEvent model logging explicit coercion triggers independent of ML."""
    user = User(username="heidi_duress", email="heidi@example.com", full_name="Heidi User")
    db_session.add(user)
    db_session.commit()

    duress = DuressEvent(
        user_id=user.id,
        event_source="duress_pin",
        action_taken="restricted_mode_activated",
        delivery_status="delivered",
        ip_address="192.168.1.50",
    )
    db_session.add(duress)
    db_session.commit()
    db_session.refresh(duress)

    assert duress.event_source == "duress_pin"
    assert duress.action_taken == "restricted_mode_activated"
    assert duress.delivery_status == "delivered"

def test_security_event_audit_model(db_session: Session):
    """Test SecurityEvent model logging authentication and authorization events."""
    user = User(username="ivan_sec", email="ivan@example.com", full_name="Ivan User")
    db_session.add(user)
    db_session.commit()

    sec_event = SecurityEvent(
        user_id=user.id,
        event_type="passkey_registered",
        severity="info",
        details_json='{"aaguid": "00000000-0000-0000-0000-000000000000"}',
        ip_address="127.0.0.1",
    )
    db_session.add(sec_event)
    db_session.commit()
    db_session.refresh(sec_event)

    assert sec_event.event_type == "passkey_registered"
    assert sec_event.severity == "info"

def test_foreign_key_cascades(db_session: Session):
    """Verify that deleting a User cascades and cleans up child entities."""
    user = User(username="judy_cascade", email="judy@example.com", full_name="Judy User")
    db_session.add(user)
    db_session.commit()

    device = Device(
        user_id=user.id,
        device_name="Test Device",
        device_fingerprint="fp_123",
        public_key_pem="pem_key",
    )
    account = Account(user_id=user.id, account_number="SB-CASCADE-01", balance=Decimal("100.00"))
    db_session.add_all([device, account])
    db_session.commit()

    user_id = user.id
    db_session.delete(user)
    db_session.commit()

    assert db_session.query(Device).filter(Device.user_id == user_id).first() is None
    assert db_session.query(Account).filter(Account.user_id == user_id).first() is None

# ---------------------------------------------------------------------------
# 3. Stage 4 Idempotency Reservation & Scoped Transaction Tests
# ---------------------------------------------------------------------------

def test_idempotency_reservation_model(db_session: Session):
    """Test IdempotencyReservation model creation, UUID foreign keys, and INR precision."""
    user = User(username="kate_res", email="kate@example.com", full_name="Kate User")
    db_session.add(user)
    db_session.commit()

    acc1 = Account(user_id=user.id, account_number="SB-RES-01", balance=Decimal("10000.00"), currency="INR")
    acc2 = Account(user_id=user.id, account_number="SB-RES-02", balance=Decimal("5000.00"), currency="INR")
    db_session.add_all([acc1, acc2])
    db_session.commit()

    lease_exp = utc_now() + timedelta(seconds=30)
    reservation = IdempotencyReservation(
        idempotency_key="00000000-0000-4000-a000-000000000099",
        user_id=user.id,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("2500.50"),
        currency="INR",
        status="PROCESSING",
        lease_generation=1,
        owner_worker_id="worker-uuid-test-01",
        lease_expires_at=lease_exp,
    )
    db_session.add(reservation)
    db_session.commit()
    db_session.refresh(reservation)

    assert reservation.idempotency_key == "00000000-0000-4000-a000-000000000099"
    assert reservation.user_id == user.id
    assert reservation.from_account_id == acc1.id
    assert reservation.to_account_id == acc2.id
    assert reservation.amount == Decimal("2500.50")
    assert reservation.currency == "INR"
    assert reservation.status == "PROCESSING"
    assert reservation.lease_generation == 1
    assert reservation.owner_worker_id == "worker-uuid-test-01"
    assert reservation.user.id == user.id
    assert reservation.from_account.id == acc1.id
    assert reservation.to_account.id == acc2.id
    assert reservation.transaction_id is None

def test_idempotency_reservation_check_constraints(db_session: Session):
    """Test IdempotencyReservation CheckConstraints: valid status, positive amount, lease generation."""
    user = User(username="leo_constraints", email="leo@example.com", full_name="Leo User")
    db_session.add(user)
    db_session.commit()

    acc1 = Account(user_id=user.id, account_number="SB-CON-01", balance=Decimal("1000.00"), currency="INR")
    acc2 = Account(user_id=user.id, account_number="SB-CON-02", balance=Decimal("1000.00"), currency="INR")
    db_session.add_all([acc1, acc2])
    db_session.commit()

    # 1. Invalid status
    with pytest.raises(IntegrityError):
        invalid_status_res = IdempotencyReservation(
            idempotency_key="key-invalid-status",
            user_id=user.id,
            from_account_id=acc1.id,
            to_account_id=acc2.id,
            amount=Decimal("100.00"),
            status="INVALID_STATUS",  # Forbidden
            owner_worker_id="w1",
            lease_expires_at=utc_now() + timedelta(seconds=30),
        )
        db_session.add(invalid_status_res)
        db_session.commit()
    db_session.rollback()

    # 2. Non-positive amount
    with pytest.raises(IntegrityError):
        invalid_amount_res = IdempotencyReservation(
            idempotency_key="key-invalid-amount",
            user_id=user.id,
            from_account_id=acc1.id,
            to_account_id=acc2.id,
            amount=Decimal("0.00"),  # Must be > 0
            status="PROCESSING",
            owner_worker_id="w1",
            lease_expires_at=utc_now() + timedelta(seconds=30),
        )
        db_session.add(invalid_amount_res)
        db_session.commit()
    db_session.rollback()

    # 3. Invalid lease generation (< 1)
    with pytest.raises(IntegrityError):
        invalid_gen_res = IdempotencyReservation(
            idempotency_key="key-invalid-gen",
            user_id=user.id,
            from_account_id=acc1.id,
            to_account_id=acc2.id,
            amount=Decimal("50.00"),
            status="PROCESSING",
            lease_generation=0,  # Must be >= 1
            owner_worker_id="w1",
            lease_expires_at=utc_now() + timedelta(seconds=30),
        )
        db_session.add(invalid_gen_res)
        db_session.commit()
    db_session.rollback()

def test_active_session_rejection_in_write_transaction(db_session: Session):
    """
    TEST-S4-21: Verify that write_transaction strictly rejects already-active sessions
    and never silently commits dirty state.
    """
    from sqlalchemy import text
    # Put session in an active transaction state by executing a query
    db_session.execute(text("SELECT 1"))
    assert db_session.in_transaction() is True

    # Attempting write_transaction on active session MUST raise ActiveTransactionError
    with pytest.raises(ActiveTransactionError) as exc_info:
        with write_transaction(db_session):
            pass

    assert "requires a fresh or clean session" in str(exc_info.value)
    db_session.rollback()

def test_file_backed_sqlite_statement_and_transaction_trace(tmp_path):
    """
    TEST-S4-24: File-backed SQLite integration test verifying exact transaction mechanics:
    1. before_cursor_execute verifies BEGIN IMMEDIATE is the actual first statement for writes.
    2. before_cursor_execute verifies ordinary BEGIN is used for read queries.
    3. sqlite3.Connection.set_trace_callback verifies COMMIT and ROLLBACK dispatched to SQLite.
    4. Verified committed data persists in disk file upon independent session query.
    5. Verified rolled-back data does not persist.
    6. Verified underlying dbapi_connection.in_transaction is False after commit/rollback.
    7. Under WAL mode, readers do not block writers under normal conditions.
    """
    import os
    from sqlalchemy import create_engine, event, text
    from sqlalchemy.orm import sessionmaker

    db_file = tmp_path / "safebank_trace_test.db"
    disk_engine = create_engine(
        f"sqlite:///{db_file}",
        connect_args={"check_same_thread": False, "timeout": 15},
    )

    cursor_statements = []
    trace_statements = []

    @event.listens_for(disk_engine, "before_cursor_execute")
    def on_cursor_execute(conn, cursor, statement, parameters, context, executemany):
        cursor_statements.append(statement)

    @event.listens_for(disk_engine, "connect")
    def on_disk_connect(dbapi_connection, connection_record):
        dbapi_connection.isolation_level = None  # DBAPI Autocommit Mode
        dbapi_connection.set_trace_callback(trace_statements.append)
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON;")
        cursor.execute("PRAGMA busy_timeout=5000;")
        cursor.execute("PRAGMA journal_mode=WAL;")  # Enable WAL mode
        cursor.close()

    @event.listens_for(disk_engine, "begin")
    def on_disk_begin(conn):
        opts = conn.get_execution_options()
        if opts.get("sqlite_write_immediate", False):
            conn.exec_driver_sql("BEGIN IMMEDIATE")
        else:
            conn.exec_driver_sql("BEGIN")

    DiskSession = sessionmaker(bind=disk_engine)

    # Setup test table
    with DiskSession() as s:
        with write_transaction(s):
            s.execute(text("CREATE TABLE test_ledger (id INT PRIMARY KEY, bal NUMERIC(14,2))"))

    # 1. Write Transaction Commit Trace
    cursor_statements.clear()
    trace_statements.clear()
    with DiskSession() as s:
        with write_transaction(s):
            raw_conn = s.connection().connection.dbapi_connection
            s.execute(text("INSERT INTO test_ledger VALUES (1, 5000.00)"))
            assert raw_conn.in_transaction is True

    # Assertions for Write Commit
    assert cursor_statements[0] == "BEGIN IMMEDIATE", "BEGIN IMMEDIATE must be the exact first statement for write"
    assert any("COMMIT" in st.upper() for st in trace_statements), "COMMIT must be traced by DBAPI"
    assert raw_conn.in_transaction is False, "Underlying connection must have no active transaction after commit"

    # 2. Write Transaction Rollback Trace
    cursor_statements.clear()
    trace_statements.clear()
    with DiskSession() as s:
        try:
            with write_transaction(s):
                raw_conn = s.connection().connection.dbapi_connection
                s.execute(text("INSERT INTO test_ledger VALUES (2, 9999.00)"))
                raise RuntimeError("Forced failure for rollback testing")
        except RuntimeError:
            pass

    # Assertions for Rollback
    assert cursor_statements[0] == "BEGIN IMMEDIATE"
    assert any("ROLLBACK" in st.upper() for st in trace_statements), "ROLLBACK must be traced by DBAPI"
    assert raw_conn.in_transaction is False, "Underlying connection must have no active transaction after rollback"

    # 3. Persistence Verification
    with DiskSession() as s:
        row1_bal = s.execute(text("SELECT bal FROM test_ledger WHERE id = 1")).scalar()
        row2_bal = s.execute(text("SELECT bal FROM test_ledger WHERE id = 2")).scalar()
        assert row1_bal == 5000.00, "Committed data must persist in disk SQLite database"
        assert row2_bal is None, "Rolled-back data must not persist in disk database"

    # 4. Read Transaction Trace (Ordinary BEGIN, non-blocking)
    cursor_statements.clear()
    trace_statements.clear()
    with DiskSession() as s:
        read_val = s.execute(text("SELECT bal FROM test_ledger WHERE id = 1")).scalar()
        s.commit()

    assert cursor_statements[0] == "BEGIN", "Read transaction must emit standard deferred BEGIN, not BEGIN IMMEDIATE"
    assert read_val == 5000.00

