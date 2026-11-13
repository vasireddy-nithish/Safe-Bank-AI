"""Authenticated enrolment progress and idempotent accepted-sample receipts."""
from typing import Annotated, Literal
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, FiniteFloat
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.orm import Session as DbSession
from app.api.deps import get_current_user, get_current_session
from app.db.session import get_db
from app.models.behaviour_sample import BehaviourSample
from app.models.duress_event import DuressEvent
from app.models.user import User
from app.models.session import Session

router = APIRouter(prefix="/auth/behaviour-enrolment", tags=["registration"])
STAGES = ("TYPING", "TAPPING", "SWIPING", "MOTION")


class SampleRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contract_version: Literal["safebank.behaviour.v2"]
    stage: Literal["TYPING", "TAPPING", "SWIPING", "MOTION"]
    ordinal: int = Field(ge=1, le=3)
    features: list[Annotated[FiniteFloat, Field(ge=-3.4e38, le=3.4e38)]] = Field(min_length=48, max_length=48)
    data_points: int = Field(ge=1, le=10000)


def progress(db, user_id):
    samples = db.query(BehaviourSample).filter_by(user_id=user_id).all()
    counts = {stage: sum(s.stage == stage for s in samples) for stage in STAGES}
    current = next((s for s in STAGES if counts[s] < 3), "COMPLETE")
    # Completion is derived from durable receipts, so it cannot disagree with stage progress.
    baseline = [0.0] * 48
    for start, end, stages in ((0, 20, ("TAPPING", "SWIPING")), (20, 32, ("TYPING",)), (32, 48, ("MOTION",))):
        group = [s.features for s in samples if s.stage in stages]
        if group:
            for i in range(start, end):
                baseline[i] = sum(v[i] for v in group) / len(group)
    return {"contract_version": "safebank.behaviour.v2", "user_id": user_id, "counts": counts, "current_stage": current,
            "completed": current == "COMPLETE", "baseline_features": baseline if samples else None}


@router.get("")
def get_progress(user: User = Depends(get_current_user), db: DbSession = Depends(get_db)):
    return progress(db, user.id)


@router.post("/samples")
def accept_sample(payload: SampleRequest, user: User = Depends(get_current_user),
                  session: Session = Depends(get_current_session), db: DbSession = Depends(get_db)):
    if session.is_restricted or db.query(DuressEvent).filter_by(user_id=user.id).first():
        raise HTTPException(403, "Restricted sessions cannot modify a behavioural baseline")
    minimum = {"TYPING": 21, "TAPPING": 9, "SWIPING": 1, "MOTION": 2}[payload.stage]
    if payload.data_points < minimum or not any(payload.features):
        raise HTTPException(422, "Insufficient behavioural sample")
    state = progress(db, user.id)
    # A sample's stage + ordinal is its durable idempotency key, including after restart.
    if payload.ordinal <= state["counts"][payload.stage]:
        return state
    if payload.stage != state["current_stage"] or payload.ordinal != state["counts"][payload.stage] + 1:
        raise HTTPException(409, "Sample is out of sequence; reload enrolment progress")
    db.execute(insert(BehaviourSample).values(user_id=user.id, stage=payload.stage,
        ordinal=payload.ordinal, features=payload.features).on_conflict_do_nothing())
    db.commit()
    return progress(db, user.id)
