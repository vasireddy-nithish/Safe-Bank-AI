"""Unit tests for SessionService: creation, validation, expiry, and revocation."""

import pytest
from datetime import timedelta
from sqlalchemy.orm import Session
from app.db.base import utc_now
from app.models.user import User
from app.services.session_service import SessionService

def test_session_creation_and_token_hash(db_session: Session):
    """Verify session creation persists a SHA-256 hash and returns the raw bearer token."""
    user = User(username="sam_session", email="sam@example.com", full_name="Sam User")
    db_session.add(user)
    db_session.commit()

    session, raw_token = SessionService.create_session(
        db=db_session,
        user_id=user.id,
        expire_minutes=15,
        ip_address="10.0.0.1",
        user_agent="SafeBankAndroid/1.0",
    )

    assert session.id is not None
    assert session.user_id == user.id
    assert session.is_active is True
    assert session.is_restricted is False
    assert len(raw_token) > 30

    # The database must store the hash, NOT the raw token
    assert session.session_token_hash == SessionService.hash_token(raw_token)
    assert raw_token not in session.session_token_hash

def test_session_validation_success(db_session: Session):
    """Verify valid token returns active session."""
    user = User(username="tina_session", email="tina@example.com", full_name="Tina User")
    db_session.add(user)
    db_session.commit()

    created_session, raw_token = SessionService.create_session(db=db_session, user_id=user.id)
    validated_session = SessionService.validate_session(db=db_session, raw_token=raw_token)

    assert validated_session is not None
    assert validated_session.id == created_session.id
    assert validated_session.user_id == user.id

def test_session_validation_invalid_token(db_session: Session):
    """Verify forged/invalid token returns None."""
    validated_session = SessionService.validate_session(db=db_session, raw_token="tampered_fake_token")
    assert validated_session is None

def test_session_expiration_enforcement(db_session: Session):
    """Verify expired session is invalidated upon validation and returns None."""
    user = User(username="uma_session", email="uma@example.com", full_name="Uma User")
    db_session.add(user)
    db_session.commit()

    # Create session already in the past
    session, raw_token = SessionService.create_session(db=db_session, user_id=user.id, expire_minutes=-5)
    db_session.refresh(session)

    # Validating expired token must fail and mark session inactive in DB
    validated_session = SessionService.validate_session(db=db_session, raw_token=raw_token)
    assert validated_session is None

    # Check database state
    db_session.refresh(session)
    assert session.is_active is False
    assert session.revocation_reason == "expired"
    assert session.revoked_at is not None

def test_session_revocation_by_id(db_session: Session):
    """Verify explicit session revocation by session ID."""
    user = User(username="victor_session", email="victor@example.com", full_name="Victor User")
    db_session.add(user)
    db_session.commit()

    session, raw_token = SessionService.create_session(db=db_session, user_id=user.id)
    assert session.is_active is True

    revoked = SessionService.revoke_session(db=db_session, session_id=session.id, reason="user_logout")
    assert revoked is True

    db_session.refresh(session)
    assert session.is_active is False
    assert session.revocation_reason == "user_logout"

    # Subsequent validation must fail
    assert SessionService.validate_session(db=db_session, raw_token=raw_token) is None

def test_session_revocation_by_token(db_session: Session):
    """Verify explicit session revocation using the bearer token."""
    user = User(username="walter_session", email="walter@example.com", full_name="Walter User")
    db_session.add(user)
    db_session.commit()

    session, raw_token = SessionService.create_session(db=db_session, user_id=user.id)
    revoked = SessionService.revoke_session_by_token(db=db_session, raw_token=raw_token, reason="security_signout")
    assert revoked is True

    db_session.refresh(session)
    assert session.is_active is False
    assert session.revocation_reason == "security_signout"

def test_revoke_all_user_sessions(db_session: Session):
    """Verify revoking all active sessions for a user."""
    user = User(username="xander_session", email="xander@example.com", full_name="Xander User")
    db_session.add(user)
    db_session.commit()

    s1, _ = SessionService.create_session(db=db_session, user_id=user.id)
    s2, _ = SessionService.create_session(db=db_session, user_id=user.id)
    s3, _ = SessionService.create_session(db=db_session, user_id=user.id)

    revoked_count = SessionService.revoke_all_user_sessions(db=db_session, user_id=user.id, reason="password_reset")
    assert revoked_count == 3

    for s in [s1, s2, s3]:
        db_session.refresh(s)
        assert s.is_active is False
        assert s.revocation_reason == "password_reset"

def test_activate_restricted_mode(db_session: Session):
    """Verify activating Restricted Mode on an active session under duress."""
    user = User(username="yara_duress", email="yara@example.com", full_name="Yara User")
    db_session.add(user)
    db_session.commit()

    session, raw_token = SessionService.create_session(db=db_session, user_id=user.id)
    assert session.is_restricted is False

    updated_session = SessionService.activate_restricted_mode(db=db_session, session_id=session.id)
    assert updated_session is not None
    assert updated_session.is_restricted is True

    # Validating token now returns session with is_restricted == True
    v = SessionService.validate_session(db=db_session, raw_token=raw_token)
    assert v is not None
    assert v.is_restricted is True
