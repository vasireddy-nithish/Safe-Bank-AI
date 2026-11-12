"""User self-registration with demo enrolment code validation."""

import random
import uuid
from decimal import Decimal
from typing import Optional

from argon2 import PasswordHasher
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field, SecretStr
from sqlalchemy.orm import Session as DbSession

from app.db.session import get_db
from app.models.account import Account
from app.models.device import Device
from app.models.security_event import SecurityEvent
from app.models.user import User

router = APIRouter(prefix="/auth", tags=["registration"])
_hasher = PasswordHasher()

VALID_ENROLMENT_CODES = {"SAFEBANK2024", "DEMO2024", "PS05HACK"}


class RegisterRequest(BaseModel):
    full_name: str = Field(min_length=2, max_length=100)
    username: str = Field(min_length=3, max_length=50, pattern=r"^[a-zA-Z0-9_]+$")
    enrolment_code: str = Field(min_length=4, max_length=20)
    duress_pin_opt_in: bool = False
    duress_pin: Optional[SecretStr] = None


class RegisterResponse(BaseModel):
    user_id: str
    username: str
    duress_configured: bool = False
    message: str


@router.post("/register", response_model=RegisterResponse, status_code=status.HTTP_201_CREATED)
def register_user(payload: RegisterRequest, db: DbSession = Depends(get_db)):
    """
    Register a new demo user account after validating the demo bank enrolment code.
    Seeds a simulated unverified device and two demo INR accounts (checking & savings).
    """
    if payload.enrolment_code.strip().upper() not in VALID_ENROLMENT_CODES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="INVALID_ENROLMENT_CODE: Invalid bank enrolment code.",
        )

    existing = db.query(User).filter(User.username == payload.username.strip()).first()
    if existing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="USERNAME_TAKEN: Username already registered.",
        )

    duress_hash: Optional[str] = None
    duress_configured = False
    if payload.duress_pin is not None:
        raw_pin = payload.duress_pin.get_secret_value()
        if raw_pin:
            if not (6 <= len(raw_pin) <= 12 and raw_pin.isascii() and raw_pin.isdigit()):
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail="INVALID_DURESS_PIN: Use a PIN of 6 to 12 digits.",
                )
            duress_hash = _hasher.hash(raw_pin)
            duress_configured = True

    user_id = str(uuid.uuid4())
    user = User(
        id=user_id,
        username=payload.username.strip(),
        email=f"{payload.username.strip()}@demo.safebank.test",
        full_name=payload.full_name.strip(),
        is_active=True,
        duress_pin_hash=duress_hash,
        duress_configured=duress_configured,
    )
    db.add(user)
    db.flush()

    device = Device(
        id=str(uuid.uuid4()),
        user_id=user.id,
        device_name="Registered Android Device (Demo)",
        device_fingerprint="self-registered-demo",
        public_key_pem="DEMO-SELF-REGISTERED",
        is_verified=False,
        attestation_type="software",
        status="active",
    )
    db.add(device)

    for account_type in ("checking", "savings"):
        # Ensure unique 12-digit demo account numbers
        acc_num = str(random.randint(100000000000, 999999999999))
        while db.query(Account).filter(Account.account_number == acc_num).first():
            acc_num = str(random.randint(100000000000, 999999999999))
        db.add(
            Account(
                user_id=user.id,
                account_number=acc_num,
                account_type=account_type,
                balance=Decimal("10000.00"),
                currency="INR",
                status="active",
            )
        )

    db.add(
        SecurityEvent(
            user_id=user.id,
            event_type="user_registered",
            severity="info",
            details_json='{"simulated": true}',
        )
    )
    if duress_configured:
        db.add(
            SecurityEvent(
                user_id=user.id,
                event_type="duress_configured",
                severity="info",
            )
        )

    db.commit()

    return RegisterResponse(
        user_id=str(user.id),
        username=user.username,
        duress_configured=duress_configured,
        message="Registration successful. Use your username to sign in.",
    )

