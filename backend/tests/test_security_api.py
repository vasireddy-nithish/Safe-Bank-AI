from decimal import Decimal
import uuid
from app.core.config import settings
from app.models.account import Account
from app.models.user import User
from app.services.session_service import SessionService


def test_risk_blocks_transfer_and_logout_revokes(client, db_session, monkeypatch):
    monkeypatch.setattr(settings, "ENVIRONMENT", "test")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)
    user = User(username="risk_test", email="risk@example.test", full_name="Risk Test")
    db_session.add(user)
    db_session.commit()
    accounts = [Account(user_id=user.id, account_number=str(10000+i), account_type=kind,
                        balance=Decimal("1000.00"), currency="INR", status="active")
                for i, kind in enumerate(("checking", "savings"))]
    db_session.add_all(accounts)
    db_session.commit()
    ids = [a.id for a in accounts]
    _, token = SessionService.create_session(db_session, user.id)
    headers = {"Authorization": f"Bearer {token}"}
    assert client.post("/api/v1/security/demo-scenario", headers=headers,
                       json={"scenario": "replay"}).status_code == 200
    response = client.post("/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={"from_account_id": ids[0], "to_account_id": ids[1], "amount": "10.00", "currency": "INR"})
    assert response.status_code == 422
    db_session.expire_all()
    assert db_session.get(Account, ids[0]).balance == Decimal("1000.00")
    assert client.get("/api/v1/security/status", headers=headers).json()["action"] == "restrict"
    assert any(e["type"] == "transfer_blocked" for e in client.get("/api/v1/security/events", headers=headers).json())
    assert client.post("/api/v1/security/logout", headers=headers).status_code == 200
    assert client.get("/api/v1/security/status", headers=headers).status_code == 401


def test_security_requires_auth(client):
    for path in ("status", "events"):
        assert client.get("/api/v1/security/" + path).status_code == 401


def test_console_requires_separate_credentials(client, monkeypatch):
    monkeypatch.setenv("SAFEBANK_ADMIN_PASSWORD", "test-only-console-password")
    assert client.get("/api/v1/admin/summary").status_code == 401
    response = client.get("/api/v1/admin/summary", auth=("admin", "test-only-console-password"))
    assert response.status_code == 200
    assert response.json()["duress_incidents"] == 0
    assert client.get("/api/v1/admin/dashboard", auth=("admin", "test-only-console-password")).status_code == 200
