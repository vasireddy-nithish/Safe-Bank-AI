"""Session lifecycle management service: creation, validation, expiry, and revocation."""

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Optional, Tuple
from sqlalchemy.orm import Session as DbSession
from app.core.config import settings
from app.db.base import utc_now
from app.models.session import Session
from app.models.user import User
from app.models.duress_event import DuressEvent

class SessionService:

    @staticmethod
    def hash_token(raw_token: str) -> str:
        """Compute SHA-256 hash of a bearer token."""
        return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

    @staticmethod
    def sanitize_client_ip(ip: Optional[str]) -> Optional[str]:
        """Truncate and sanitize IP string to prevent buffer overflows or PII overcollection."""
        if not ip:
            return None
        return ip.strip()[:45]

    @staticmethod
    def sanitize_user_agent(ua: Optional[str]) -> Optional[str]:
        """Truncate user-agent string."""
        if not ua:
            return None
        return ua.strip()[:128]

    @staticmethod
    def check_sensitive_action_allowed(session: Optional[Session]) -> bool:
        """
        Authoritative server-side gatekeeper.
        Blocks sensitive financial actions if session is missing, inactive,
        expired, or running under Coercion Restricted Mode.
        """
        if not session:
            return False
        if not session.is_active:
            return False
        if session.is_restricted:
            return False
        if session.revoked_at is not None:
            return False
        expires_at = session.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        if expires_at <= utc_now():
            return False
        return True

    @classmethod
    def create_session(
        cls,
        db: DbSession,
        user_id: str,
        device_id: Optional[str] = None,
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
        expire_minutes: Optional[int] = None,
        is_restricted: bool = False,
    ) -> Tuple[Session, str]:
        """
        Create a new session, persist its SHA-256 token hash, and return (session, raw_token).
        Raw bearer token is generated with 32 cryptographically secure bytes and never persisted.
        """
        # An incident persists across logout and new authentication. Recovery is
        # intentionally not exposed through an ordinary client session.
        is_restricted = is_restricted or db.query(DuressEvent).filter(
            DuressEvent.user_id == user_id
        ).first() is not None
        raw_token = secrets.token_urlsafe(32)
        token_hash = cls.hash_token(raw_token)

        duration = expire_minutes or settings.ACCESS_TOKEN_EXPIRE_MINUTES
        expires_at = utc_now() + timedelta(minutes=duration)

        db_session = Session(
            user_id=user_id,
            device_id=device_id,
            session_token_hash=token_hash,
            is_active=True,
            is_restricted=is_restricted,
            expires_at=expires_at,
            ip_address=cls.sanitize_client_ip(ip_address),
            user_agent=cls.sanitize_user_agent(user_agent),
        )

        db.add(db_session)
        db.commit()
        db.refresh(db_session)
        return db_session, raw_token

    @classmethod
    def validate_session(cls, db: DbSession, raw_token: str) -> Optional[Session]:
        """
        Validate bearer token, check expiration, and return active session or None.
        Fail-safe design: an expired or revoked session is ALWAYS rejected,
        even if the database write updating its status fails.
        """
        if not raw_token or len(raw_token) < 16:
            return None

        token_hash = cls.hash_token(raw_token)
        session = db.query(Session).filter(
            Session.session_token_hash == token_hash,
            Session.is_active == True,
        ).first()

        if not session:
            return None

        # Fail-safe check 1: Revoked session
        if session.revoked_at is not None:
            session.is_active = False
            try:
                db.commit()
            except Exception:
                db.rollback()
            return None

        # Fail-safe check 2: Check expiration against timezone-aware UTC
        expires_at = session.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)

        if expires_at <= utc_now():
            session.is_active = False
            session.revoked_at = utc_now()
            session.revocation_reason = "expired"
            try:
                db.commit()
            except Exception:
                db.rollback()
            # MUST return None regardless of whether db.commit succeeded
            return None

        # Fail-safe check 3: Check user active status
        user = db.query(User).filter(User.id == session.user_id, User.is_active == True).first()
        if not user:
            session.is_active = False
            session.revoked_at = utc_now()
            session.revocation_reason = "user_inactive"
            try:
                db.commit()
            except Exception:
                db.rollback()
            return None

        return session

    @classmethod
    def revoke_session(
        cls,
        db: DbSession,
        session_id: str,
        reason: str = "user_logout",
    ) -> bool:
        """Revoke a single session by its session ID."""
        session = db.query(Session).filter(Session.id == session_id).first()
        if not session or not session.is_active:
            return False

        session.is_active = False
        session.revoked_at = utc_now()
        session.revocation_reason = reason
        try:
            db.commit()
            return True
        except Exception:
            db.rollback()
            return False

    @classmethod
    def revoke_session_by_token(
        cls,
        db: DbSession,
        raw_token: str,
        reason: str = "user_logout",
    ) -> bool:
        """Revoke a session using its raw bearer token."""
        token_hash = cls.hash_token(raw_token)
        session = db.query(Session).filter(
            Session.session_token_hash == token_hash,
            Session.is_active == True,
        ).first()

        if not session:
            return False

        session.is_active = False
        session.revoked_at = utc_now()
        session.revocation_reason = reason
        try:
            db.commit()
            return True
        except Exception:
            db.rollback()
            return False

    @classmethod
    def activate_restricted_mode(
        cls,
        db: DbSession,
        session_id: str,
    ) -> Optional[Session]:
        """Activate Coercion Restricted Mode on a specific session."""
        session = db.query(Session).filter(Session.id == session_id).first()
        if not session:
            return None

        session.is_restricted = True
        try:
            db.commit()
            db.refresh(session)
            return session
        except Exception:
            db.rollback()
            return None

    @classmethod
    def revoke_all_user_sessions(
        cls,
        db: DbSession,
        user_id: str,
        reason: str = "security_reset",
        except_session_id: Optional[str] = None,
    ) -> int:
        """Revoke all active sessions belonging to a user."""
        query = db.query(Session).filter(
            Session.user_id == user_id,
            Session.is_active == True,
        )
        if except_session_id:
            query = query.filter(Session.id != except_session_id)

        active_sessions = query.all()
        now = utc_now()
        for s in active_sessions:
            s.is_active = False
            s.revoked_at = now
            s.revocation_reason = reason

        try:
            db.commit()
            return len(active_sessions)
        except Exception:
            db.rollback()
            return 0
