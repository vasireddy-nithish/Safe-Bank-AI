"""FastAPI dependencies for authentication, sessions, and database access."""

from typing import Optional
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session as DbSession

from app.db.session import get_db
from app.models.session import Session as AppSession
from app.models.user import User
from app.services.session_service import SessionService

# auto_error=False allows returning custom structured 401 errors
bearer_scheme = HTTPBearer(auto_error=False)


def get_current_session(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(bearer_scheme),
    db: DbSession = Depends(get_db),
) -> AppSession:
    """
    Validates the Bearer session token.
    Enforces token hash verification, active status, expiration, and revocation.
    Returns the active database Session model.
    """
    if not credentials or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="NOT_AUTHENTICATED: Valid Bearer token required in Authorization header.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    raw_token = credentials.credentials
    session = SessionService.validate_session(db=db, raw_token=raw_token)
    if not session:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="SESSION_INVALID_OR_EXPIRED: The provided session token is invalid, expired, or revoked.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return session


def get_current_user(
    session: AppSession = Depends(get_current_session),
    db: DbSession = Depends(get_db),
) -> User:
    """Retrieves the authenticated User associated with the active session."""
    user = db.query(User).filter(User.id == session.user_id, User.is_active == True).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="USER_NOT_FOUND_OR_INACTIVE: User associated with session is not found or inactive.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user
