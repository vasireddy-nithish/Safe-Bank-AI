"""Tests for user self-registration and risk score submission endpoints."""

import uuid
from decimal import Decimal

from app.core.config import settings
from app.models.account import Account
from app.models.duress_event import DuressEvent
from app.models.risk_event import RiskEvent
from app.models.security_event import SecurityEvent
from app.models.user import User
from app.services.session_service import SessionService


def test_register_success_and_login_by_username(client, db_session, monkeypatch):
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)

    resp = client.post(
        "/api/v1/auth/register",
        json={
            "full_name": "Priya Sharma",
            "username": "priya_sharma",
            "enrolment_code": "SAFEBANK2024",
            "duress_pin_opt_in": True,
            "duress_pin": "987654",
        },
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["username"] == "priya_sharma"
    assert data["duress_configured"] is True

    user = db_session.query(User).filter_by(username="priya_sharma").first()
    assert user is not None
    assert user.duress_pin_hash.startswith("$argon2id$")
    assert "987654" not in user.duress_pin_hash
    accounts = db_session.query(Account).filter_by(user_id=user.id).all()
    assert len(accounts) == 2
    assert all(acc.balance == Decimal("10000.00") for acc in accounts)

    # Login via dev-session/by-username
    login_resp = client.post(
        "/api/v1/auth/dev-session/by-username",
        json={"username": "priya_sharma"},
    )
    assert login_resp.status_code == 200
    token = login_resp.json()["access_token"]

    acct_resp = client.get(
        "/api/v1/banking/accounts",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert acct_resp.status_code == 200
    assert len(acct_resp.json()) == 2


def test_register_rejects_invalid_enrolment_code(client):
    resp = client.post(
        "/api/v1/auth/register",
        json={
            "full_name": "Bad Actor",
            "username": "bad_actor",
            "enrolment_code": "WRONGCODE",
        },
    )
    assert resp.status_code == 422


def test_register_rejects_duplicate_username(client):
    payload = {
        "full_name": "Alex One",
        "username": "dup_user_01",
        "enrolment_code": "SAFEBANK2024",
    }
    assert client.post("/api/v1/auth/register", json=payload).status_code == 201
    assert client.post("/api/v1/auth/register", json=payload).status_code == 409


def test_risk_submission_and_step_up_flow(client, db_session):
    user = User(username="risk_user", email="risk@example.test", full_name="Risk User")
    db_session.add(user)
    db_session.flush()
    accounts = [
        Account(
            user_id=user.id,
            account_number=str(91000 + i),
            account_type=kind,
            balance=Decimal("5000.00"),
            currency="INR",
            status="active",
        )
        for i, kind in enumerate(("checking", "savings"))
    ]
    db_session.add_all(accounts)
    db_session.commit()

    session, token = SessionService.create_session(db_session, user.id)
    headers = {"Authorization": f"Bearer {token}"}

    # High risk submission -> restrict -> transfer blocked
    r1 = client.post(
        "/api/v1/risk/submit",
        headers=headers,
        json={
            "genuine_probability": 0.05,
            "uncertain_probability": 0.15,
            "anomaly_probability": 0.80,
            "risk_score": 85,
            "is_from_live_model": True,
            "session_data_points": 25,
        },
    )
    assert r1.status_code == 200
    assert r1.json()["risk_level"] == "high"
    assert r1.json()["action"] == "restrict"
    assert db_session.query(RiskEvent).filter_by(user_id=user.id).count() == 2
    assert db_session.query(SecurityEvent).filter_by(user_id=user.id, event_type="behavioral_high_risk").count() == 1

    # Transfer blocked while high risk
    tx_blocked = client.post(
        "/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": accounts[0].id,
            "to_account_id": accounts[1].id,
            "amount": "100.00",
            "currency": "INR",
        },
    )
    assert tx_blocked.status_code == 422

    # Step-up biometric verification clears behavioural block (when NOT in duress)
    step_up = client.post(
        "/api/v1/risk/step-up",
        headers=headers,
        json={"method": "biometric_prompt", "verified": True},
    )
    assert step_up.status_code == 501
    # An unproven client boolean must leave the high-risk decision in place.

    tx_ok = client.post(
        "/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": accounts[0].id,
            "to_account_id": accounts[1].id,
            "amount": "100.00",
            "currency": "INR",
        },
    )
    assert tx_ok.status_code == 422


def test_step_up_cannot_override_duress(client, db_session):
    user = User(username="duress_risk_user", email="dr@example.test", full_name="Duress Risk")
    db_session.add(user)
    db_session.commit()
    session, token = SessionService.create_session(db_session, user.id, is_restricted=True)
    db_session.add(DuressEvent(user_id=user.id, event_source="duress_pin", delivery_status="queued"))
    db_session.commit()

    resp = client.post(
        "/api/v1/risk/step-up",
        headers={"Authorization": f"Bearer {token}"},
        json={"method": "biometric_prompt", "verified": True},
    )
    assert resp.status_code == 403

