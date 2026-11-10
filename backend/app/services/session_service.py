"""Session lifecycle management service: creation, validation, expiry, and revocation."""

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Optional, Tuple, List
from sqlalchemy.orm import Session as DbSession
from app.core.config import settings
from app.db.base import utc_now
from app.models.session import Session
from app.models.user import User

class SessionService:

    @staticmethod
    def hash_token(raw_token: str) -> str:
        """Compute SHA-256 hash of a bearer token."""
        return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

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
        """Create a new session, persist its token hash, and return (session, raw_token)."""
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
            ip_address=ip_address,
            user_agent=user_agent,
        )

        db.add(db_session)
        db.commit()
        db.refresh(db_session)
        return db_session, raw_token

    @classmethod
    def validate_session(cls, db: DbSession, raw_token: str) -> Optional[Session]:
        """Validate bearer token, check expiration, and return active session or None."""
        token_hash = cls.hash_token(raw_token)
        session = db.query(Session).filter(
            Session.session_token_hash == token_hash,
            Session.is_active == True,
        ).first()

        if not session:
            return None

        # Check expiration against timezone-aware UTC
        expires_at = session.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)

        if expires_at <= utc_now():
            session.is_active = False
            session.revoked_at = utc_now()
            session.revocation_reason = "expired"
            db.commit()
            return None

        # Check user status
        user = db.query(User).filter(User.id == session.user_id, User.is_active == True).first()
        if not user:
            session.is_active = False
            session.revoked_at = utc_now()
            session.revocation_reason = "user_inactive"
            db.commit()
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
        db.commit()
        return True

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
        db.commit()
        return True

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
        db.commit()
        db.refresh(session)
        return session

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

        db.commit()
        return len(active_sessions)
