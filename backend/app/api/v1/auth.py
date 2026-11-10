"""Authentication API endpoints including secure development session bridge."""

from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session as DbSession
from app.core.config import settings
from app.db.session import get_db
from app.models.user import User
from app.models.device import Device
from app.services.session_service import SessionService

router = APIRouter(prefix="/auth", tags=["authentication"])

# Approved Stage 4 deterministic whitelist constants
DEV_WHITELIST_USERS = {
    "usr_dev_alex_chen": "00000000-0000-4000-a000-000000000001",
    "00000000-0000-4000-a000-000000000001": "00000000-0000-4000-a000-000000000001",
}

DEV_WHITELIST_DEVICES = {
    "dev_pixel_8_pro_01": "00000000-0000-4000-a000-000000000021",
    "00000000-0000-4000-a000-000000000021": "00000000-0000-4000-a000-000000000021",
}

ALLOWED_DEV_HOSTS = {"127.0.0.1", "::1", "localhost", "10.0.2.2", "testclient"}

class DevSessionRequest(BaseModel):
    user_id: str = Field(..., description="Pre-seeded user UUID or dev alias")
    device_id: str = Field(..., description="Pre-seeded device UUID or dev alias")

class DevSessionResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int
    user_id: str
    is_restricted: bool = False

@router.post("/dev-session", response_model=DevSessionResponse, status_code=status.HTTP_200_OK)
async def create_dev_session(
    payload: DevSessionRequest,
    request: Request,
    db: DbSession = Depends(get_db),
):
    """
    Secure development session bridge.
    
    Security Controls:
    1. Strictly forbidden in production: returns HTTP 404 if ENVIRONMENT is not development or test.
    2. Strictly disabled by default: returns HTTP 404 if ENABLE_DEV_SESSION_ENDPOINT is False.
    3. Restricted to local loopback / emulator development hosts.
    4. Whitelist verification: only pre-seeded entities (Alex Chen / Pixel 8 Pro) permitted.
    5. Database verification: user and device must be active and bound in the database.
    """
    # Gate 1: Environment check (Production ALWAYS returns 404, even if flag is True)
    if settings.ENVIRONMENT.lower() not in ("development", "test"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Not Found",
        )

    # Gate 2: Explicit flag enablement check (Disabled by default)
    if not settings.ENABLE_DEV_SESSION_ENDPOINT:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Not Found",
        )

    # Gate 3: Network host restriction
    client_host = request.client.host if request.client else None
    if client_host and client_host not in ALLOWED_DEV_HOSTS:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="DEV_SESSION_NETWORK_RESTRICTED: Dev session endpoint is only accessible on local development loopback networks.",
        )

    # Gate 4: Whitelist verification
    resolved_user_id = DEV_WHITELIST_USERS.get(payload.user_id)
    resolved_device_id = DEV_WHITELIST_DEVICES.get(payload.device_id)

    if not resolved_user_id or not resolved_device_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="DEV_SESSION_ENTITY_NOT_WHITELISTED: Only pre-seeded development entities can obtain development sessions.",
        )

    # Gate 5: Database integrity & active-status check
    user = db.query(User).filter(User.id == resolved_user_id, User.is_active == True).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="USER_NOT_ACTIVE: Whitelisted development user not found or inactive in database.",
        )

    device = db.query(Device).filter(
        Device.id == resolved_device_id,
        Device.user_id == resolved_user_id,
        Device.status == "active",
    ).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="DEVICE_NOT_ACTIVE_OR_BOUND: Whitelisted device not found, not active, or not bound to user.",
        )

    # Generate cryptographically secure session
    session, raw_token = SessionService.create_session(
        db=db,
        user_id=resolved_user_id,
        device_id=resolved_device_id,
        ip_address=client_host,
        user_agent=request.headers.get("user-agent"),
        is_restricted=False,
    )

    return DevSessionResponse(
        access_token=raw_token,
        token_type="bearer",
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user_id=resolved_user_id,
        is_restricted=session.is_restricted,
    )
