"""Explicit duress activation, independent of behavioural classification."""

from datetime import timedelta
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, InvalidHashError
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, SecretStr
from sqlalchemy.orm import Session as DbSession
from app.api.deps import get_current_session
from app.db.base import utc_now
from app.db.session import get_db
from app.models.user import User
from app.models.session import Session
from app.models.duress_event import DuressEvent
from app.models.security_event import SecurityEvent

router = APIRouter(prefix="/duress", tags=["duress"])
hasher = PasswordHasher()


class PinRequest(BaseModel):
    pin: SecretStr


class ActivationRequest(PinRequest):
    username: str = Field(min_length=1, max_length=64)


def validate_pin(secret: SecretStr) -> str:
    pin = secret.get_secret_value()
    if not (6 <= len(pin) <= 12 and pin.isascii() and pin.isdigit()):
        raise HTTPException(422, "Use a PIN of 6 to 12 digits")
    return pin


@router.put("/pin")
def configure_pin(payload: PinRequest, session: Session = Depends(get_current_session),
                  db: DbSession = Depends(get_db)):
    if session.is_restricted:
        raise HTTPException(403, "Account unavailable")
    user = db.get(User, session.user_id)
    # Updating an existing PIN needs a future strong reauthentication flow.
    if user.duress_configured:
        raise HTTPException(409, "PIN already configured; secure recovery required")
    user.duress_pin_hash = hasher.hash(validate_pin(payload.pin))
    user.duress_configured = True
    db.add(SecurityEvent(user_id=user.id, event_type="duress_configured", severity="info"))
    db.commit()
    return {"configured": True}


@router.post("/activate")
def activate(payload: ActivationRequest, db: DbSession = Depends(get_db)):
    """Does not require a banking session. Never logs PIN material."""
    pin = validate_pin(payload.pin)
    user = db.query(User).filter(User.username == payload.username, User.is_active == True).first()
    if not user:
        raise HTTPException(403, "Unable to verify request")
    failures = db.query(SecurityEvent).filter(
        SecurityEvent.user_id == user.id,
        SecurityEvent.event_type == "duress_verification_failed",
        SecurityEvent.created_at >= utc_now() - timedelta(minutes=15),
    ).count()
    if failures >= 5:
        raise HTTPException(429, "Please try again later")
    verified = False
    if user.duress_pin_hash:
        try:
            verified = hasher.verify(user.duress_pin_hash, pin)
        except (VerificationError, InvalidHashError):
            pass
    if not verified:
        db.add(SecurityEvent(user_id=user.id, event_type="duress_verification_failed", severity="warning"))
        db.commit()
        raise HTTPException(403, "Unable to verify request")
    # Restrict all sessions, not only the device used to signal distress.
    db.query(Session).filter(Session.user_id == user.id).update({"is_restricted": True})
    if not db.query(DuressEvent).filter(DuressEvent.user_id == user.id).first():
        db.add(DuressEvent(user_id=user.id, event_source="duress_pin",
                           delivery_status="queued"))
        db.add(SecurityEvent(user_id=user.id, event_type="duress_activated", severity="critical"))
    db.commit()
    return {"status": "restricted"}
