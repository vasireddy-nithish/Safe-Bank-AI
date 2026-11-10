"""Pytest configuration and shared fixtures for SafeBank AI backend."""

import pytest
from fastapi.testclient import TestClient
from app.main import app

@pytest.fixture(scope="session")
def client():
    """Provides a TestClient instance for testing API endpoints."""
    with TestClient(app) as test_client:
        yield test_client
