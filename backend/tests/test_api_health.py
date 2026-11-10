"""Tests for API health check, root info, and OpenAPI documentation."""

from fastapi.testclient import TestClient

def test_root_endpoint(client: TestClient):
    """Verify that root endpoint returns operational metadata."""
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "online"
    assert data["name"] == "SafeBank AI"
    assert "docs" in data

def test_system_health_endpoint(client: TestClient):
    """Verify system health endpoint and security pillars."""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "security_pillars" in data
    assert "Freedom to Act" in data["security_pillars"]

def test_api_v1_health_endpoint(client: TestClient):
    """Verify API v1 prefix health endpoint."""
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert data["stage"] == "Stage 1: Foundation"

def test_openapi_schema_generation(client: TestClient):
    """Verify OpenAPI 3.x documentation schema is correctly generated."""
    response = client.get("/openapi.json")
    assert response.status_code == 200
    data = response.json()
    assert "openapi" in data
    assert data["info"]["title"] == "SafeBank AI"
    assert "/health" in data["paths"]
    assert "/api/v1/health" in data["paths"]

def test_docs_ui_endpoint(client: TestClient):
    """Verify Swagger UI docs endpoint responds with HTML."""
    response = client.get("/docs")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
