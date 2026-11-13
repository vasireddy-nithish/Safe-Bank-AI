"""Security and authorization tests for the development session bridge endpoint."""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session as DbSession
from app.core.config import settings
from app.models.user import User
from app.models.device import Device

ALEX_CHEN_USER_ID = "00000000-0000-4000-a000-000000000001"
PIXEL_8_PRO_DEVICE_ID = "00000000-0000-4000-a000-000000000021"

@pytest.fixture
def seeded_alex_chen(db_session: DbSession):
    """Seed Alex Chen user and Pixel 8 Pro device."""
    user = User(
        id=ALEX_CHEN_USER_ID,
        username="alex_chen_dev",
        email="alex.chen@example.com",
        full_name="Alex Chen",
        is_active=True,
    )
    device = Device(
        id=PIXEL_8_PRO_DEVICE_ID,
        user_id=ALEX_CHEN_USER_ID,
        device_name="Google Pixel 8 Pro",
        device_fingerprint="fp_pixel_8_pro_dev_secure",
        public_key_pem="-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...\n-----END PUBLIC KEY-----",
        status="active",
        attestation_type="strongbox",
    )
    db_session.add_all([user, device])
    db_session.commit()
    return user, device

def test_default_configuration_disables_dev_session(client: TestClient, monkeypatch):
    """Verify that by default (ENABLE_DEV_SESSION_ENDPOINT=False), the dev session endpoint returns 404."""
    # Ensure flag is disabled
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", False)

    response = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert response.status_code == 404
    assert response.json()["detail"] == "Not Found"

def test_development_with_flag_disabled_returns_404(client: TestClient, monkeypatch):
    """Verify that in development environment with flag disabled, endpoint returns 404."""
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", False)

    response = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert response.status_code == 404
    assert response.json()["detail"] == "Not Found"

def test_production_returns_404_even_when_flag_is_true(client: TestClient, monkeypatch):
    """
    CRITICAL SECURITY TEST:
    Verify that production ALWAYS returns 404, even if ENABLE_DEV_SESSION_ENDPOINT is accidentally True.
    """
    monkeypatch.setattr(settings, "ENVIRONMENT", "production")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)

    response = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert response.status_code == 404
    assert response.json()["detail"] == "Not Found"

def test_development_with_flag_enabled_allows_preseeded_entities(
    client: TestClient,
    seeded_alex_chen,
    monkeypatch,
):
    """
    Verify that in development with flag enabled:
    - Pre-seeded Alex Chen alias and Pixel 8 Pro alias return valid session token.
    - Canonical UUIDv4 identifiers also return valid session token.
    """
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)

    # 1. Test using human seed aliases
    res1 = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert res1.status_code == 200
    data1 = res1.json()
    assert "access_token" in data1
    assert data1["token_type"] == "bearer"
    assert data1["user_id"] == ALEX_CHEN_USER_ID
    assert data1["is_restricted"] is False
    assert data1["expires_in"] == 1800

    # 2. Test using canonical UUIDv4 strings
    res2 = client.post("/api/v1/auth/dev-session", json={
        "user_id": ALEX_CHEN_USER_ID,
        "device_id": PIXEL_8_PRO_DEVICE_ID,
    })
    assert res2.status_code == 200
    data2 = res2.json()
    assert "access_token" in data2
    assert data2["user_id"] == ALEX_CHEN_USER_ID

def test_dev_session_rejects_unseeded_entities(
    client: TestClient,
    seeded_alex_chen,
    monkeypatch,
):
    """Verify that arbitrary or unseeded user_id/device_id are rejected with 403 Forbidden."""
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)

    # Unseeded user
    res1 = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_hacker",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert res1.status_code == 403
    assert "DEV_SESSION_ENTITY_NOT_WHITELISTED" in res1.json()["detail"]

    # Unseeded device
    res2 = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_untrusted_device_99",
    })
    assert res2.status_code == 403
    assert "DEV_SESSION_ENTITY_NOT_WHITELISTED" in res2.json()["detail"]

def test_dev_session_checks_database_active_status_and_ownership(
    client: TestClient,
    db_session: DbSession,
    seeded_alex_chen,
    monkeypatch,
):
    """Verify that inactive user or unbound device is rejected with 403."""
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)

    user, device = seeded_alex_chen

    # Case A: Deactivate user in database
    user.is_active = False
    db_session.commit()

    res1 = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert res1.status_code == 403
    assert "USER_NOT_ACTIVE" in res1.json()["detail"]

    # Re-activate user, deactivate device
    user.is_active = True
    device.status = "revoked"
    db_session.commit()

    res2 = client.post("/api/v1/auth/dev-session", json={
        "user_id": "usr_dev_alex_chen",
        "device_id": "dev_pixel_8_pro_01",
    })
    assert res2.status_code == 403
    assert "DEVICE_NOT_ACTIVE_OR_BOUND" in res2.json()["detail"]

def test_spoofed_headers_rejected_for_untrusted_client(
    seeded_alex_chen,
    monkeypatch,
):
    """
    SECURITY TEST:
    Verify that spoofing 'X-Forwarded-For: 127.0.0.1' and 'Host: localhost' from an
    untrusted client IP does NOT bypass the network check. The server inspects
    request.client.host from the ASGI transport layer and rejects with 403.
    """
    from app.main import app
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)

    untrusted_client = TestClient(app, client=("198.51.100.25", 50000))
    res = untrusted_client.post(
        "/api/v1/auth/dev-session",
        json={
            "user_id": "usr_dev_alex_chen",
            "device_id": "dev_pixel_8_pro_01",
        },
        headers={
            "X-Forwarded-For": "127.0.0.1",
            "Host": "localhost",
            "X-Real-IP": "127.0.0.1",
        },
    )
    assert res.status_code == 403
    assert "DEV_SESSION_NETWORK_RESTRICTED" in res.json()["detail"]
