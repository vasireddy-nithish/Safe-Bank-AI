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
)

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
