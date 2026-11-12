"""Duress must be explicit, durable, authenticated for setup, and PIN verified."""
from app.models.user import User
from app.models.duress_event import DuressEvent
from app.models.security_event import SecurityEvent
from app.services.session_service import SessionService
from app.models.account import Account
from decimal import Decimal
import uuid


def enroll(db):
    user = User(username="duress_test", email="duress@example.test", full_name="Demo User")
    db.add(user)
    db.commit()
    session, token = SessionService.create_session(db, user.id)
    return user, session, {"Authorization": f"Bearer {token}"}


def test_setup_requires_session(client):
    assert client.put("/api/v1/duress/pin", json={"pin": "654321"}).status_code == 401


def test_activation_restricts_existing_and_future_sessions(client, db_session):
    user, session, headers = enroll(db_session)
    assert client.put("/api/v1/duress/pin", headers=headers, json={"pin": "654321"}).status_code == 200
    db_session.refresh(user)
    assert user.duress_pin_hash.startswith("$argon2id$")
    assert "654321" not in user.duress_pin_hash
    # Deliberately no session token supplied during activation.
    response = client.post("/api/v1/duress/activate", json={"username": user.username, "pin": "654321"})
    assert response.status_code == 200
    db_session.refresh(session)
    assert session.is_restricted
    new_session, _ = SessionService.create_session(db_session, user.id)
    assert new_session.is_restricted
    assert not SessionService.check_sensitive_action_allowed(new_session)
    assert db_session.query(DuressEvent).count() == 1
    assert db_session.query(SecurityEvent).filter_by(severity="critical").count() == 1


def test_wrong_pin_does_not_activate_and_is_rate_limited(client, db_session):
    user, session, headers = enroll(db_session)
    client.put("/api/v1/duress/pin", headers=headers, json={"pin": "654321"})
    payload = {"username": user.username, "pin": "123456"}
    for _ in range(5):
        assert client.post("/api/v1/duress/activate", json=payload).status_code == 403
    assert client.post("/api/v1/duress/activate", json=payload).status_code == 429
    db_session.refresh(session)
    assert not session.is_restricted
    assert db_session.query(DuressEvent).count() == 0


def test_explicit_duress_blocks_payment_and_reaches_console(client, db_session, monkeypatch):
    user, _, headers = enroll(db_session)
    accounts = [Account(user_id=user.id, account_number=str(81000+i), account_type=kind,
                        balance=Decimal("1000.00"), currency="INR", status="active")
                for i, kind in enumerate(("checking", "savings"))]
    db_session.add_all(accounts)
    db_session.commit()
    ids = [a.id for a in accounts]
    assert client.put("/api/v1/duress/pin", headers=headers, json={"pin": "654321"}).status_code == 200
    assert client.post("/api/v1/duress/activate", json={"username": user.username, "pin": "654321"}).status_code == 200
    response = client.post("/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={"from_account_id": ids[0], "to_account_id": ids[1], "amount": "100.00", "currency": "INR"})
    assert response.status_code == 422
    db_session.expire_all()
    assert all(db_session.get(Account, aid).balance == Decimal("1000.00") for aid in ids)
    monkeypatch.setenv("SAFEBANK_ADMIN_PASSWORD", "test-console-only")
    summary = client.get("/api/v1/admin/summary", auth=("admin", "test-console-only")).json()
    assert summary["duress_incidents"] == 1
    assert any(a["event"] == "duress_activated" and a["severity"] == "critical" for a in summary["alerts"])
