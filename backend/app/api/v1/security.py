"""Authenticated security activity and explicitly isolated demo risk signals."""
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session as DbSession
from app.api.deps import get_current_session
from app.core.config import settings
from app.db.session import get_db
from app.models.session import Session
from app.models.risk_event import RiskEvent
from app.models.security_event import SecurityEvent
from app.services.session_service import SessionService

router = APIRouter(prefix="/security", tags=["security"])


@router.get("/status")
def status(session: Session = Depends(get_current_session), db: DbSession = Depends(get_db)):
    risk = db.query(RiskEvent).filter_by(session_id=session.id).order_by(RiskEvent.created_at.desc()).first()
    return {"restricted": session.is_restricted, "risk_level": risk.risk_level if risk else "unknown",
            "action": "restrict" if session.is_restricted else risk.action_taken if risk else "unassessed"}


@router.get("/events")
def events(session: Session = Depends(get_current_session), db: DbSession = Depends(get_db)):
    rows = db.query(SecurityEvent).filter_by(user_id=session.user_id).order_by(SecurityEvent.created_at.desc()).limit(100).all()
    return [{"id": row.id, "type": row.event_type, "severity": row.severity,
             "timestamp": row.created_at.isoformat()} for row in rows]


class Scenario(BaseModel):
    scenario: Literal["suspicious", "replay", "device"]


@router.post("/demo-scenario")
def scenario(payload: Scenario, session: Session = Depends(get_current_session), db: DbSession = Depends(get_db)):
    if settings.ENVIRONMENT not in ("development", "test") or not settings.ENABLE_DEV_SESSION_ENDPOINT:
        raise HTTPException(404, "Not found")
    score = 50 if payload.scenario == "suspicious" else 90
    action = "step_up" if score < 70 else "restrict"
    db.add(RiskEvent(user_id=session.user_id, session_id=session.id, risk_score=score,
                     risk_level="medium" if score < 70 else "high", behavioral_verdict="uncertain",
                     behavioral_confidence=0, action_taken=action, factors_json='{"simulated":true}'))
    db.add(SecurityEvent(user_id=session.user_id, event_type="demo_" + payload.scenario,
                         severity="warning", details_json='{"simulated":true}'))
    db.commit()
    return {"simulated": True, "action": action}


@router.post("/logout")
def logout(session: Session = Depends(get_current_session), db: DbSession = Depends(get_db)):
    if not SessionService.revoke_session(db, session.id):
        raise HTTPException(503, "Unable to revoke session")
    return {"revoked": True}
