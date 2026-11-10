"""Unit tests for all nine SafeBank AI SQLAlchemy ORM models."""

import pytest
import uuid
from decimal import Decimal
from datetime import datetime, timedelta, timezone
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from app.db.base import utc_now
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
    assert len(user.id) == 36  # Standard UUID string length
    assert user.username == "alice_banking"
    assert user.is_active is True
    assert user.duress_configured is True

    # Test unique constraint on username
    duplicate_user = User(
        username="alice_banking",
        email="other@example.com",
        full_name="Alice Duplicate",
    )
    db_session.add(duplicate_user)
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

def test_passkey_credential_model(db_session: Session):
    """Test Credential model for WebAuthn passkeys (no private key, sign count tracking)."""
    user = User(username="carol_passkey", email="carol@example.com", full_name="Carol User")
    db_session.add(user)
    db_session.commit()

    cred = Credential(
        user_id=user.id,
        credential_id="base64url_credential_id_abc123",
        public_key="p256_cose_public_key_bytes",
        sign_count=1,
        aaguid="00000000-0000-0000-0000-000000000000",
        transports="internal,hybrid",
        is_backup_eligible=True,
        is_backed_up=False,
    )
    db_session.add(cred)
    db_session.commit()
    db_session.refresh(cred)

    assert cred.sign_count == 1
    assert cred.is_backup_eligible is True
    assert cred.user.email == "carol@example.com"

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

    # Simulate Restricted Mode activation under duress
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
    # Verify decimal arithmetic integrity: adding 0.05 must equal exactly 1500.55
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

    # Enforce unique idempotency_key
    dup_tx = Transaction(
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("150.00"),
        currency="USD",
        transaction_type="transfer",
        status="completed",
        reference_number="REF-TX-002",
        idempotency_key="idemp_key_unique_123",  # Duplicate key
    )
    db_session.add(dup_tx)
    with pytest.raises(IntegrityError):
        db_session.commit()
    db_session.rollback()

def test_risk_event_model(db_session: Session):
    """Test RiskEvent recording behavioral verdict and hybrid risk score."""
    user = User(username="grace_risk", email="grace@example.com", full_name="Grace User")
    db_session.add(user)
    db_session.commit()

    event = RiskEvent(
        user_id=user.id,
        risk_score=25,
        risk_level="low",
        behavioral_verdict="consistent",
        behavioral_confidence=0.94,
        factors_json='{"device_binding": true, "session_age_minutes": 5}',
        action_taken="allow",
    )
    db_session.add(event)
    db_session.commit()
    db_session.refresh(event)

    assert event.risk_score == 25
    assert event.risk_level == "low"
    assert event.behavioral_verdict == "consistent"
    assert event.behavioral_confidence == pytest.approx(0.94)

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

    # Children should be deleted via ondelete="CASCADE"
    assert db_session.query(Device).filter(Device.user_id == user_id).first() is None
    assert db_session.query(Account).filter(Account.user_id == user_id).first() is None
