"""Pytest configuration and shared fixtures for SafeBank AI backend."""

import pytest
from typing import Generator
from decimal import Decimal
from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import sessionmaker, Session as DbSession
from fastapi.testclient import TestClient
from app.db.base import Base
import app.models  # Register all 9 models
from app.main import app
from app.db.session import get_db

# Use an in-memory SQLite database for isolated test execution
TEST_DATABASE_URL = "sqlite:///:memory:"

test_engine = create_engine(
    TEST_DATABASE_URL,
    connect_args={"check_same_thread": False},
)

# Enforce foreign key constraints in SQLite test engine
@event.listens_for(Engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.close()

TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)

@pytest.fixture(scope="function")
def db_session() -> Generator[DbSession, None, None]:
    """Provides a fresh, isolated database session per test with all 9 tables."""
    Base.metadata.create_all(bind=test_engine)
    session = TestingSessionLocal()
    try:
        yield session
    finally:
        session.close()
        Base.metadata.drop_all(bind=test_engine)

@pytest.fixture(scope="function")
def client(db_session: DbSession) -> Generator[TestClient, None, None]:
    """Provides a TestClient instance with the test database session overridden."""
    def override_get_db():
        try:
            yield db_session
        finally:
            pass

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
