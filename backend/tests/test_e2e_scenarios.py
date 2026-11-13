"""End-to-end verification of Scenarios A, B, C, D and Security Console."""

import uuid
from decimal import Decimal

from app.core.config import settings
from app.models.account import Account
from app.models.duress_event import DuressEvent


def test_end_to_end_four_demo_scenarios(client, db_session, monkeypatch):
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)
    monkeypatch.setenv("SAFEBANK_ADMIN_PASSWORD", "e2e-console-secret")

    # Scenario A: Genuine user registers -> signs in -> low-risk ML -> transfer succeeds
    reg = client.post(
        "/api/v1/auth/register",
        json={
            "full_name": "E2E User",
            "username": "e2e_user",
            "enrolment_code": "SAFEBANK2024",
            "duress_pin_opt_in": True,
            "duress_pin": "654321",
        },
    )
    assert reg.status_code == 201

    login = client.post("/api/v1/auth/dev-session/by-username", json={"username": "e2e_user"})
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    accounts = client.get("/api/v1/banking/accounts", headers=headers).json()
    assert len(accounts) == 2
    src_id, dst_id = accounts[0]["id"], accounts[1]["id"]

    low_risk = client.post(
        "/api/v1/risk/submit",
        headers=headers,
        json={
            "genuine_probability": 0.94,
            "uncertain_probability": 0.04,
            "anomaly_probability": 0.02,
            "risk_score": 10,
            "is_from_live_model": True,
            "session_data_points": 30,
            "contract_version": "safebank.behaviour.v2",
            "transaction_context": {"from_account_id": src_id,"to_account_id": dst_id,"amount":"500.00","currency":"INR","description":"Internal transfer"},
        },
    )
    assert low_risk.status_code == 200
    assert low_risk.json()["action"] == "allow"

    tx_a = client.post(
        "/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={"from_account_id": src_id, "to_account_id": dst_id, "amount": "500.00", "currency": "INR"},
    )
    assert tx_a.status_code == 201
    updated_accounts = client.get("/api/v1/banking/accounts", headers=headers).json()
    balances = {a["id"]: Decimal(a["balance"]) for a in updated_accounts}
    assert balances[src_id] == Decimal("9500.00")
    assert balances[dst_id] == Decimal("10500.00")

    # Scenario B: Suspicious behaviour -> step_up -> transfer held until BiometricPrompt step-up
    susp = client.post("/api/v1/security/demo-scenario", headers=headers, json={"scenario": "suspicious"})
    assert susp.status_code == 200
    assert susp.json()["action"] == "step_up"

    tx_b_blocked = client.post(
        "/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={"from_account_id": src_id, "to_account_id": dst_id, "amount": "200.00", "currency": "INR"},
    )
    assert tx_b_blocked.status_code == 422

    # Scenario D: Device / replay attack simulation -> high risk restrict + console alert
    dev_atk = client.post("/api/v1/security/demo-scenario", headers=headers, json={"scenario": "device"})
    assert dev_atk.status_code == 200
    assert dev_atk.json()["action"] == "restrict"

    # Scenario C: Explicit duress PIN activation -> restricted session + blocked payment + dashboard alert
    duress = client.post("/api/v1/duress/activate", json={"username": "e2e_user", "pin": "654321"})
    assert duress.status_code == 200
    assert duress.json()["status"] == "restricted"

    status_resp = client.get("/api/v1/security/status", headers=headers)
    assert status_resp.status_code == 200
    assert status_resp.json()["restricted"] is True

    tx_c_blocked = client.post(
        "/api/v1/banking/transfers",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={"from_account_id": src_id, "to_account_id": dst_id, "amount": "100.00", "currency": "INR"},
    )
    assert tx_c_blocked.status_code == 422

    # Bank Security Console verification
    console = client.get("/api/v1/admin/summary", auth=("admin", "e2e-console-secret"))
    assert console.status_code == 200
    summary = console.json()
    assert summary["duress_incidents"] == 1
    assert summary["restricted_sessions"] >= 1
    assert summary["high_risk_assessments"] >= 1
    alert_types = {a["event"] for a in summary["alerts"]}
    assert "duress_activated" in alert_types
    assert "demo_suspicious" in alert_types
    assert "demo_device" in alert_types

