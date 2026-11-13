"""ML risk score ingestion and step-up verification endpoints."""

import json
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session as DbSession

from app.api.deps import get_current_session
from app.db.session import get_db
from app.models.duress_event import DuressEvent
from app.models.risk_event import RiskEvent
from app.models.security_event import SecurityEvent
from app.models.session import Session
from app.services.risk_policy import CONTRACT
from typing import Optional

router = APIRouter(prefix="/risk", tags=["risk"])


class RiskSubmission(BaseModel):
    genuine_probability: float = Field(ge=0.0, le=1.0)
    uncertain_probability: float = Field(ge=0.0, le=1.0)
    anomaly_probability: float = Field(ge=0.0, le=1.0)
    risk_score: int = Field(ge=0, le=100)
    is_from_live_model: bool = False
    session_data_points: int = Field(ge=0, default=0)
    contract_version: str = "legacy"
    transaction_context: Optional[dict] = None


class StepUpRequest(BaseModel):
    method: str = Field(default="biometric_prompt", max_length=64)
    verified: bool = True


def begin_assessment(session: Session = Depends(get_current_session), db: DbSession = Depends(get_db)):
    # Fail closed even if body validation or computation subsequently fails.
    event = RiskEvent(user_id=session.user_id, session_id=session.id, risk_score=30,
        risk_level="medium", behavioral_verdict="uncertain", behavioral_confidence=0,
        action_taken="step_up", factors_json=json.dumps({"assessment_state":"pending_or_failed"}))
    db.add(event)
    db.commit()
    return event


@router.post("/submit")
def submit_risk(
    payload: RiskSubmission,
    attempt: RiskEvent = Depends(begin_assessment),
    session: Session = Depends(get_current_session),
    db: DbSession = Depends(get_db),
):
    """
    Evaluate and record an on-device behavioural ML risk submission.
    Server validates probability distribution, computes policy risk band,
    and respects duress priority (never downgrades an explicit duress restriction).
    """
    if session.is_restricted or db.query(DuressEvent).filter_by(user_id=session.user_id).first():
        return {"risk_level": "high", "action": "restrict", "server_score": 100}

    prob_sum = payload.genuine_probability + payload.uncertain_probability + payload.anomaly_probability
    if not (0.9 <= prob_sum <= 1.1):
        server_score, risk_level, action = 50, "medium", "step_up"
        verdict = "uncertain"
    else:
        # Compute server-side score from probabilities rather than trusting raw score blindly
        computed_score = int((payload.uncertain_probability * 35.0) + (payload.anomaly_probability * 100.0))
        server_score = max(0, min(100, max(computed_score, payload.risk_score)))

        # Cold-start protection: insufficient data points never grants ultra-high confidence
        if payload.session_data_points < 5 and server_score < 20:
            server_score = 20

        if not payload.is_from_live_model or payload.contract_version != CONTRACT or payload.session_data_points < 5:
            server_score = max(30, server_score)

        if server_score < 30:
            risk_level, action = "low", "allow"
        elif server_score < 70:
            risk_level, action = "medium", "step_up"
        else:
            risk_level, action = "high", "restrict"

        if payload.genuine_probability >= payload.uncertain_probability and payload.genuine_probability >= payload.anomaly_probability:
            verdict = "consistent"
        elif payload.anomaly_probability > payload.uncertain_probability:
            verdict = "deviation"
        else:
            verdict = "uncertain"

    factors = json.dumps({
        "consistent": round(payload.genuine_probability, 3),
        "uncertain": round(payload.uncertain_probability, 3),
        "deviation": round(payload.anomaly_probability, 3),
        "live_model": payload.is_from_live_model,
        "data_points": payload.session_data_points,
        "contract_version": payload.contract_version,
        "device_integrity": "unverified",
        "assessment_state": "completed",
        "transaction_context": payload.transaction_context,
    })

    db.add(
        RiskEvent(
            user_id=session.user_id,
            session_id=session.id,
            risk_score=server_score,
            risk_level=risk_level,
            behavioral_verdict=verdict,
            behavioral_confidence=payload.genuine_probability,
            action_taken=action,
            factors_json=factors,
        )
    )

    if risk_level == "high":
        db.add(
            SecurityEvent(
                user_id=session.user_id,
                event_type="behavioral_high_risk",
                severity="warning",
                details_json=json.dumps({"score": server_score, "live_model": payload.is_from_live_model}),
            )
        )
    elif risk_level == "medium":
        db.add(
            SecurityEvent(
                user_id=session.user_id,
                event_type="behavioral_step_up_required",
                severity="info",
                details_json=json.dumps({"score": server_score, "live_model": payload.is_from_live_model}),
            )
        )

    db.commit()
    return {"risk_level": risk_level, "action": action, "server_score": server_score}


@router.post("/step-up")
def record_step_up(
    payload: StepUpRequest,
    session: Session = Depends(get_current_session),
    db: DbSession = Depends(get_db),
):
    """
    Record completion of a BiometricPrompt / device credential step-up challenge.
    Never overrides an explicit duress restriction.
    """
    if session.is_restricted or db.query(DuressEvent).filter_by(user_id=session.user_id).first():
        raise HTTPException(status_code=403, detail="Account access is restricted.")

    raise HTTPException(status_code=501, detail="Cryptographically verified step-up is not configured; client booleans cannot authorize transfers.")
