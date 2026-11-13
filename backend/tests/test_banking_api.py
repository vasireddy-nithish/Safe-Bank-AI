"""Comprehensive integration test suite for Stage 4 Banking API Endpoints:
Authentication, account privacy, internal transfer execution, idempotency replay/conflict,
four-state reconciliation, coercion isolation, and cross-user authorization.
"""

from datetime import datetime, timedelta, timezone
from decimal import Decimal
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session as DbSession

from app.core.config import settings
from app.db.base import utc_now
from app.models.account import Account
from app.models.duress_event import DuressEvent
from app.models.idempotency_reservation import IdempotencyReservation
from app.models.security_event import SecurityEvent
from app.models.session import Session as AppSession
from app.models.transaction import Transaction
from app.models.user import User
from app.services.session_service import SessionService



def assessed_post(client, url, **kwargs):
    """Financial/idempotency tests explicitly satisfy the new risk precondition.
    Risk-denial cases are covered separately with the unwrapped client.
    """
    if url == "/api/v1/banking/transfers":
        body = kwargs.get("json", {})
        from app.services.risk_policy import transaction_context
        if all(k in body for k in ("from_account_id", "to_account_id", "amount")):
            client.post("/api/v1/risk/submit", headers=kwargs.get("headers", {}), json={
                "genuine_probability": .98, "uncertain_probability": .01, "anomaly_probability": .01,
                "risk_score": 2, "is_from_live_model": True, "session_data_points": 30,
                "contract_version": "safebank.behaviour.v2",
                "transaction_context": transaction_context(body["from_account_id"], body["to_account_id"],
                    body["amount"], body.get("currency", "INR"), body.get("description"))})
    return client.post(url, **kwargs)

@pytest.fixture
def banking_users(db_session: DbSession):
    """Seed two distinct users (Alex Chen and Bob Smith) each with checking and savings accounts."""
    # User A: Alex Chen
    user_a = User(
        id=str(uuid.uuid4()),
        username="alex_chen_api",
        email="alex.api@example.com",
        full_name="Alex Chen",
        is_active=True,
    )
    chk_a = Account(
        id=str(uuid.uuid4()),
        user_id=user_a.id,
        account_number="100000009481",
        account_type="checking",
        balance=Decimal("124500.80"),
        currency="INR",
        status="active",
    )
    sav_a = Account(
        id=str(uuid.uuid4()),
        user_id=user_a.id,
        account_number="200000009482",
        account_type="savings",
        balance=Decimal("350000.00"),
        currency="INR",
        status="active",
    )

    # User B: Bob Smith
    user_b = User(
        id=str(uuid.uuid4()),
        username="bob_smith_api",
        email="bob.api@example.com",
        full_name="Bob Smith",
        is_active=True,
    )
    chk_b = Account(
        id=str(uuid.uuid4()),
        user_id=user_b.id,
        account_number="300000008881",
        account_type="checking",
        balance=Decimal("50000.00"),
        currency="INR",
        status="active",
    )
    sav_b = Account(
        id=str(uuid.uuid4()),
        user_id=user_b.id,
        account_number="400000008882",
        account_type="savings",
        balance=Decimal("75000.00"),
        currency="INR",
        status="active",
    )

    db_session.add_all([user_a, chk_a, sav_a, user_b, chk_b, sav_b])
    db_session.commit()

    # Create active sessions
    _, token_a = SessionService.create_session(db_session, user_id=user_a.id)
    _, token_a_duress = SessionService.create_session(db_session, user_id=user_a.id, is_restricted=True)
    _, token_b = SessionService.create_session(db_session, user_id=user_b.id)

    return {
        "user_a": user_a,
        "chk_a": chk_a,
        "sav_a": sav_a,
        "token_a": token_a,
        "token_a_duress": token_a_duress,
        "user_b": user_b,
        "chk_b": chk_b,
        "sav_b": sav_b,
        "token_b": token_b,
    }


# ==============================================================================
# 1. Authentication & Session Validation Tests
# ==============================================================================
def test_accounts_endpoint_unauthorized_without_token(client: TestClient):
    """Calling /api/v1/banking/accounts without Authorization header returns 401."""
    res = client.get("/api/v1/banking/accounts")
    assert res.status_code == 401
    assert "NOT_AUTHENTICATED" in res.json()["detail"]


def test_accounts_endpoint_invalid_token(client: TestClient):
    """Calling /api/v1/banking/accounts with invalid token returns 401."""
    res = client.get("/api/v1/banking/accounts", headers={"Authorization": "Bearer invalid_garbage_token"})
    assert res.status_code == 401
    assert "SESSION_INVALID_OR_EXPIRED" in res.json()["detail"]


def test_accounts_endpoint_expired_session(client: TestClient, db_session: DbSession, banking_users):
    """Expired session token is rejected with 401."""
    user_a = banking_users["user_a"]
    # Create expired session
    session_obj, token = SessionService.create_session(db_session, user_id=user_a.id)
    session_obj.expires_at = utc_now() - timedelta(minutes=5)
    db_session.commit()

    res = client.get("/api/v1/banking/accounts", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 401
    assert "SESSION_INVALID_OR_EXPIRED" in res.json()["detail"]


def test_accounts_endpoint_revoked_session(client: TestClient, db_session: DbSession, banking_users):
    """Revoked session token is rejected with 401."""
    user_a = banking_users["user_a"]
    session_obj, token = SessionService.create_session(db_session, user_id=user_a.id)
    session_obj.is_active = False
    session_obj.revoked_at = utc_now()
    session_obj.revocation_reason = "security_logout"
    db_session.commit()

    res = client.get("/api/v1/banking/accounts", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 401
    assert "SESSION_INVALID_OR_EXPIRED" in res.json()["detail"]


# ==============================================================================
# 2. Account Privacy & Decimal Serialization Tests
# ==============================================================================
def test_get_accounts_privacy_and_decimal_serialization(client: TestClient, banking_users):
    """
    Verify GET /api/v1/banking/accounts:
    - Returns only the authenticated user's accounts.
    - Raw account numbers are completely omitted; only masked_account_number is exposed.
    - Balances are serialized strictly as fixed 2-decimal strings.
    - is_primary is correctly flagged for checking.
    """
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]

    res = client.get("/api/v1/banking/accounts", headers={"Authorization": f"Bearer {token_a}"})
    assert res.status_code == 200
    data = res.json()

    assert len(data) == 2
    account_ids = {item["id"] for item in data}
    assert account_ids == {chk_a.id, sav_a.id}

    for item in data:
        # Full raw account numbers (e.g. "100000009481") must NEVER be exposed
        assert "account_number" not in item
        assert item["masked_account_number"].startswith("•••• ")
        assert len(item["masked_account_number"]) == 9  # "•••• 9481"
        assert item["currency"] == "INR"
        # Balance must be a decimal string with two fractional digits
        assert isinstance(item["balance"], str)
        assert "." in item["balance"]
        parts = item["balance"].split(".")
        assert len(parts[1]) == 2

    # Check checking account specifics
    chk_item = next(i for i in data if i["account_type"] == "checking")
    assert chk_item["masked_account_number"] == "•••• 9481"
    assert chk_item["balance"] == "124500.80"
    assert chk_item["is_primary"] is True

    # Check savings account specifics
    sav_item = next(i for i in data if i["account_type"] == "savings")
    assert sav_item["masked_account_number"] == "•••• 9482"
    assert sav_item["balance"] == "350000.00"
    assert sav_item["is_primary"] is False


def test_get_accounts_does_not_leak_other_user_accounts(client: TestClient, banking_users):
    """User B's accounts are strictly isolated and never returned to User A."""
    token_a = banking_users["token_a"]
    chk_b = banking_users["chk_b"]
    sav_b = banking_users["sav_b"]

    res = client.get("/api/v1/banking/accounts", headers={"Authorization": f"Bearer {token_a}"})
    assert res.status_code == 200
    account_ids = [item["id"] for item in res.json()]

    assert chk_b.id not in account_ids
    assert sav_b.id not in account_ids


# ==============================================================================
# 3. Valid Internal Transfer & Idempotency Replay
# ==============================================================================
def test_execute_internal_transfer_success_and_replay(client: TestClient, db_session: DbSession, banking_users):
    """
    Execute valid internal transfer:
    - First submission returns 201 Created with full receipt.
    - Balances are updated accurately.
    - Replay with identical payload and key returns 200 OK with identical receipt.
    - Balances are NOT debited a second time.
    """
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    transfer_key = str(uuid.uuid4())

    payload = {
        "from_account_id": chk_a.id,
        "to_account_id": sav_a.id,
        "amount": "5000.00",
        "currency": "INR",
        "description": "Internal transfer to savings",
    }

    # 1. Initial Submission -> 201 Created
    res1 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": transfer_key},
        json=payload,
    )
    assert res1.status_code == 201
    data1 = res1.json()
    assert data1["success"] is True
    assert data1["status"] == "completed"
    assert data1["amount"] == "5000.00"
    assert data1["currency"] == "INR"
    assert data1["idempotency_key"] == transfer_key
    assert "TX-" in data1["reference_number"]

    # Verify balances updated
    db_session.expire_all()
    db_session.refresh(chk_a)
    db_session.refresh(sav_a)
    assert chk_a.balance == Decimal("124500.80") - Decimal("5000.00")
    assert sav_a.balance == Decimal("350000.00") + Decimal("5000.00")

    # 2. Replay Submission with Identical Key and Payload -> 200 OK
    res2 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": transfer_key},
        json=payload,
    )
    assert res2.status_code == 200
    data2 = res2.json()
    assert data2["success"] is True
    assert data2["transaction_id"] == data1["transaction_id"]
    assert data2["reference_number"] == data1["reference_number"]

    # Balances must NOT have changed on replay
    db_session.expire_all()
    db_session.refresh(chk_a)
    db_session.refresh(sav_a)
    assert chk_a.balance == Decimal("124500.80") - Decimal("5000.00")
    assert sav_a.balance == Decimal("350000.00") + Decimal("5000.00")


# ==============================================================================
# 4. Idempotency Mismatch (409 Conflict)
# ==============================================================================
def test_execute_transfer_mismatched_idempotency_key(client: TestClient, banking_users):
    """
    Submitting the same idempotency key with a different financial parameter
    returns HTTP 409 Conflict with IDEMPOTENCY_PAYLOAD_MISMATCH.
    """
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    key = str(uuid.uuid4())

    # First request: ₹1,000.00
    res1 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "1000.00",
            "currency": "INR",
        },
    )
    assert res1.status_code == 201

    # Second request with same key but ₹2,000.00
    res2 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "2000.00",
            "currency": "INR",
        },
    )
    assert res2.status_code == 409
    assert "IDEMPOTENCY_PAYLOAD_MISMATCH" in res2.json()["detail"]


# ==============================================================================
# 5. Financial Validation Errors (400 / 422)
# ==============================================================================
def test_transfer_missing_or_invalid_idempotency_header(client: TestClient, banking_users):
    """Missing or non-UUIDv4 Idempotency-Key header returns 400 Bad Request."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    payload = {
        "from_account_id": chk_a.id,
        "to_account_id": sav_a.id,
        "amount": "100.00",
        "currency": "INR",
    }

    # Missing header
    r1 = assessed_post(client, "/api/v1/banking/transfers", headers={"Authorization": f"Bearer {token_a}"}, json=payload)
    assert r1.status_code == 400
    assert "IDEMPOTENCY_KEY_INVALID" in r1.json()["detail"]

    # Invalid non-UUID string
    r2 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": "not-a-uuid"},
        json=payload,
    )
    assert r2.status_code == 400
    assert "IDEMPOTENCY_KEY_INVALID" in r2.json()["detail"]


def test_transfer_invalid_currency(client: TestClient, banking_users):
    """Non-INR currency is rejected with 422 Unprocessable Entity."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]

    res = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "100.00",
            "currency": "USD",
        },
    )
    assert res.status_code == 422
    assert "INVALID_CURRENCY" in res.json()["detail"]


def test_transfer_zero_or_negative_amount(client: TestClient, banking_users):
    """Zero or negative transfer amount is rejected with 422 Unprocessable Entity."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]

    res1 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "0.00",
            "currency": "INR",
        },
    )
    assert res1.status_code == 422
    assert "INVALID_AMOUNT" in res1.json()["detail"]

    res2 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "-50.00",
            "currency": "INR",
        },
    )
    assert res2.status_code == 422
    assert "INVALID_AMOUNT" in res2.json()["detail"]


def test_transfer_same_account_rejected(client: TestClient, banking_users):
    """Transfer where source and destination are identical is rejected with 422."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]

    res = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": chk_a.id,
            "amount": "100.00",
            "currency": "INR",
        },
    )
    assert res.status_code == 422
    assert "SAME_ACCOUNT_TRANSFER" in res.json()["detail"]


def test_transfer_insufficient_funds(client: TestClient, banking_users):
    """Transfer exceeding account balance is rejected with 422 INSUFFICIENT_FUNDS."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]

    res = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "9999999.00",
            "currency": "INR",
        },
    )
    assert res.status_code == 422
    assert "INSUFFICIENT_FUNDS" in res.json()["detail"]


def test_transfer_daily_limit_exceeded(client: TestClient, banking_users, monkeypatch):
    """Transfer exceeding ₹50,000 daily limit is rejected with 422 DAILY_LIMIT_EXCEEDED."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    monkeypatch.setattr(settings, "DAILY_TRANSFER_LIMIT_INR", Decimal("50000.00"))

    # Initial transfer of ₹30,000 succeeds
    res1 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "30000.00",
            "currency": "INR",
        },
    )
    assert res1.status_code == 201

    # Second transfer of ₹25,000 brings daily total to ₹55,000 > ₹50,000
    res2 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "25000.00",
            "currency": "INR",
        },
    )
    assert res2.status_code == 422
    assert "DAILY_LIMIT_EXCEEDED" in res2.json()["detail"]


# ==============================================================================
# 6. Cross-User Transfer Prevention (403 Forbidden)
# ==============================================================================
def test_transfer_cross_user_source_unauthorized(client: TestClient, banking_users):
    """Attempting to transfer funds from another user's account is rejected with 403."""
    token_a = banking_users["token_a"]
    chk_b = banking_users["chk_b"]  # Belongs to Bob, not Alex
    sav_a = banking_users["sav_a"]

    res = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_b.id,
            "to_account_id": sav_a.id,
            "amount": "100.00",
            "currency": "INR",
        },
    )
    assert res.status_code == 403
    assert "CROSS_USER_TRANSFER_UNAUTHORIZED" in res.json()["detail"]


def test_transfer_cross_user_destination_unauthorized(client: TestClient, banking_users):
    """Attempting to transfer funds to another user's account (external payee) is rejected with 403."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_b = banking_users["sav_b"]  # Belongs to Bob, not Alex

    res = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": str(uuid.uuid4())},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_b.id,
            "amount": "100.00",
            "currency": "INR",
        },
    )
    assert res.status_code == 403
    assert "CROSS_USER_TRANSFER_UNAUTHORIZED" in res.json()["detail"]


# ==============================================================================
# 7. Coercion Restricted Mode & Duress Isolation
# ==============================================================================
def test_transfer_under_coercion_restricted_mode(
    client: TestClient,
    db_session: DbSession,
    banking_users,
):
    """
    Under Coercion Restricted Mode:
    - Transfer returns 201 Created with status 'completed' in response envelope.
    - Balances are 100% untouched.
    - Database record is strictly 'simulated_duress', NEVER 'completed'.
    - DuressEvent and SecurityEvent audit entries are logged.
    """
    token_duress = banking_users["token_a_duress"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    user_a = banking_users["user_a"]

    initial_chk_balance = chk_a.balance
    initial_sav_balance = sav_a.balance
    duress_key = str(uuid.uuid4())

    res = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_duress}", "Idempotency-Key": duress_key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "4500.00",
            "currency": "INR",
        },
    )

    # 1. Client receives masked success receipt to protect physical safety
    assert res.status_code == 201
    data = res.json()
    assert data["success"] is True
    assert data["status"] == "completed"  # Masked in response
    assert data["amount"] == "4500.00"

    # 2. Database balances are 100% untouched
    db_session.expire_all()
    db_session.refresh(chk_a)
    db_session.refresh(sav_a)
    assert chk_a.balance == initial_chk_balance
    assert sav_a.balance == initial_sav_balance

    # 3. Database record is strictly 'simulated_duress'
    tx = db_session.query(Transaction).filter_by(idempotency_key=duress_key).first()
    assert tx is not None
    assert tx.status == "simulated_duress"
    assert tx.is_duress_masked is True

    # 4. Auditable events logged
    duress_ev = db_session.query(DuressEvent).filter_by(user_id=user_a.id).first()
    assert duress_ev is not None
    assert duress_ev.action_taken == "restricted_simulation_funds_protected"

    sec_ev = db_session.query(SecurityEvent).filter_by(user_id=user_a.id).first()
    assert sec_ev is not None
    assert sec_ev.severity == "critical"


# ==============================================================================
# 8. Reconciliation Lifecycle Tests
# ==============================================================================
def test_reconciliation_completed_transfer(client: TestClient, banking_users):
    """Reconciling a completed transfer returns 200 OK with full receipt."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    key = str(uuid.uuid4())

    # Execute transfer
    assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "1200.00",
            "currency": "INR",
        },
    )

    # Reconcile
    rec = client.get(f"/api/v1/banking/transfers/reconcile/{key}", headers={"Authorization": f"Bearer {token_a}"})
    assert rec.status_code == 200
    rec_data = rec.json()
    assert rec_data["state"] == "COMPLETED"
    assert rec_data["success"] is True
    assert rec_data["amount"] == "1200.00"
    assert rec_data["idempotency_key"] == key


def test_reconciliation_not_found(client: TestClient, banking_users):
    """Reconciling an unknown idempotency key returns 404 Not Found."""
    token_a = banking_users["token_a"]
    unknown_key = str(uuid.uuid4())

    rec = client.get(
        f"/api/v1/banking/transfers/reconcile/{unknown_key}",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert rec.status_code == 404
    rec_data = rec.json()
    assert rec_data["state"] == "NOT_FOUND"
    assert rec_data["retryable"] is True


def test_reconciliation_in_flight_processing(client: TestClient, db_session: DbSession, banking_users):
    """Reconciling an in-flight transfer returns 202 Accepted with retryable true."""
    token_a = banking_users["token_a"]
    user_a = banking_users["user_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    key = str(uuid.uuid4())

    # Insert an in-progress reservation
    res = IdempotencyReservation(
        idempotency_key=key,
        user_id=user_a.id,
        from_account_id=chk_a.id,
        to_account_id=sav_a.id,
        amount=Decimal("1500.00"),
        currency="INR",
        status="PROCESSING",
        lease_generation=1,
        owner_worker_id=str(uuid.uuid4()),
        lease_expires_at=utc_now() + timedelta(seconds=30),
    )
    db_session.add(res)
    db_session.commit()

    rec = client.get(
        f"/api/v1/banking/transfers/reconcile/{key}",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert rec.status_code == 202
    rec_data = rec.json()
    assert rec_data["state"] == "PROCESSING"
    assert rec_data["retryable"] is True


def test_reconciliation_failed_transfer(client: TestClient, banking_users):
    """Reconciling a failed transfer returns 200 OK with FAILED state."""
    token_a = banking_users["token_a"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    key = str(uuid.uuid4())

    # Attempt transfer with excessive amount to trigger failed transaction
    assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "9999999.00",
            "currency": "INR",
        },
    )

    rec = client.get(
        f"/api/v1/banking/transfers/reconcile/{key}",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert rec.status_code == 200
    rec_data = rec.json()
    assert rec_data["state"] == "FAILED"
    assert rec_data["success"] is False
    assert rec_data["error_code"] == "INSUFFICIENT_FUNDS"


def test_reconciliation_cross_user_isolation(client: TestClient, banking_users):
    """User B cannot reconcile User A's transfer; returns 404 Not Found."""
    token_a = banking_users["token_a"]
    token_b = banking_users["token_b"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]
    key = str(uuid.uuid4())

    # User A executes transfer
    assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "800.00",
            "currency": "INR",
        },
    )

    # User B attempts to reconcile User A's transfer
    rec_b = client.get(
        f"/api/v1/banking/transfers/reconcile/{key}",
        headers={"Authorization": f"Bearer {token_b}"},
    )
    assert rec_b.status_code == 404
    assert rec_b.json()["state"] == "NOT_FOUND"


def test_reconciliation_invalid_uuid_key(client: TestClient, banking_users):
    """Reconciling with a non-UUID key returns 400 Bad Request."""
    token_a = banking_users["token_a"]
    rec = client.get(
        "/api/v1/banking/transfers/reconcile/not-a-valid-uuid",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert rec.status_code == 400
    assert "IDEMPOTENCY_KEY_INVALID" in rec.json()["detail"]


# ==============================================================================
# Transaction History Endpoints Tests
# ==============================================================================

def test_transactions_unauthorized_requires_session(client: TestClient):
    """GET /transactions without authorization header returns 401."""
    res = client.get("/api/v1/banking/transactions")
    assert res.status_code == 401


def test_transactions_returns_empty_when_no_history(client: TestClient, banking_users):
    """User with no transfers gets empty array."""
    token_b = banking_users["token_b"]
    res = client.get(
        "/api/v1/banking/transactions",
        headers={"Authorization": f"Bearer {token_b}"},
    )
    assert res.status_code == 200
    assert res.json() == []


def test_transactions_privacy_isolation_and_duress_distinction(client: TestClient, banking_users):
    """
    User A's transaction history returns User A's transactions.
    User B cannot see User A's transactions.
    Duress transaction is returned with status='simulated_duress' and is_duress_masked=True.
    """
    token_a = banking_users["token_a"]
    token_a_duress = banking_users["token_a_duress"]
    token_b = banking_users["token_b"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]

    # 1. User A executes legitimate transfer
    key1 = str(uuid.uuid4())
    res1 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": key1},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "1500.00",
            "currency": "INR",
            "description": "Legit transfer",
        },
    )
    assert res1.status_code == 201

    # 2. User A executes duress transfer
    key2 = str(uuid.uuid4())
    res2 = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a_duress}", "Idempotency-Key": key2},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "25000.00",
            "currency": "INR",
            "description": "Duress transfer under threat",
        },
    )
    assert res2.status_code == 201

    # 3. User A queries transactions: should see both, with duress transaction explicitly marked
    res_a = client.get(
        "/api/v1/banking/transactions",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert res_a.status_code == 200
    txs_a = res_a.json()
    assert len(txs_a) == 2

    # Check the duress transaction
    duress_tx = next(t for t in txs_a if t["idempotency_key"] == key2)
    assert duress_tx["status"] == "simulated_duress"
    assert duress_tx["is_duress_masked"] is True
    assert duress_tx["amount"] == "25000.00"

    # Check the legit transaction
    legit_tx = next(t for t in txs_a if t["idempotency_key"] == key1)
    assert legit_tx["status"] == "completed"
    assert legit_tx["is_duress_masked"] is False
    assert legit_tx["amount"] == "1500.00"

    # 4. User B queries transactions: must see ZERO of User A's transactions
    res_b = client.get(
        "/api/v1/banking/transactions",
        headers={"Authorization": f"Bearer {token_b}"},
    )
    assert res_b.status_code == 200
    assert res_b.json() == []


def test_transfer_balance_refresh_via_accounts_api(client: TestClient, banking_users):
    """
    End-to-End API Balance Refresh & Duress Protection:
    1. GET /api/v1/banking/accounts reflects initial balances.
    2. POST /api/v1/banking/transfers executes legitimate transfer.
    3. GET /api/v1/banking/accounts immediately reflects updated source (-amount) and destination (+amount) balances.
    4. POST /api/v1/banking/transfers under duress restricted mode returns receipt.
    5. GET /api/v1/banking/accounts confirms duress transfer left both balances 100% untouched.
    """
    token_a = banking_users["token_a"]
    token_a_duress = banking_users["token_a_duress"]
    chk_a = banking_users["chk_a"]
    sav_a = banking_users["sav_a"]

    # 1. Initial Accounts Query
    res_init = client.get(
        "/api/v1/banking/accounts",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert res_init.status_code == 200
    init_accounts = {a["id"]: a["balance"] for a in res_init.json()}
    assert init_accounts[chk_a.id] == "124500.80"
    assert init_accounts[sav_a.id] == "350000.00"

    # 2. Legitimate transfer of ₹5,000.00
    transfer_key = str(uuid.uuid4())
    res_tx = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a}", "Idempotency-Key": transfer_key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "5000.00",
            "currency": "INR",
            "description": "Legitimate balance transfer",
        },
    )
    assert res_tx.status_code == 201
    assert res_tx.json()["status"] == "completed"

    # 3. GET /api/v1/banking/accounts reflects refreshed balances immediately
    res_refreshed = client.get(
        "/api/v1/banking/accounts",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert res_refreshed.status_code == 200
    refreshed_accounts = {a["id"]: a["balance"] for a in res_refreshed.json()}
    assert refreshed_accounts[chk_a.id] == "119500.80"
    assert refreshed_accounts[sav_a.id] == "355000.00"

    # 4. Duress restricted transfer of ₹15,000.00
    duress_key = str(uuid.uuid4())
    res_duress = assessed_post(client,
        "/api/v1/banking/transfers",
        headers={"Authorization": f"Bearer {token_a_duress}", "Idempotency-Key": duress_key},
        json={
            "from_account_id": chk_a.id,
            "to_account_id": sav_a.id,
            "amount": "15000.00",
            "currency": "INR",
            "description": "Forced transfer under duress",
        },
    )
    assert res_duress.status_code == 201

    # 5. GET /api/v1/banking/accounts confirms duress transfer left balances 100% untouched
    res_after_duress = client.get(
        "/api/v1/banking/accounts",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert res_after_duress.status_code == 200
    after_duress_accounts = {a["id"]: a["balance"] for a in res_after_duress.json()}
    assert after_duress_accounts[chk_a.id] == "119500.80"
    assert after_duress_accounts[sav_a.id] == "355000.00"


