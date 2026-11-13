"""Durable enrolment, validation, retry and user-isolation regressions."""
import pytest
from app.core.config import settings
from app.models.behaviour_sample import BehaviourSample
from app.models.user import User
from app.services.session_service import SessionService

URL = "/api/v1/auth/behaviour-enrolment"
STAGES = ("TYPING", "TAPPING", "SWIPING", "MOTION")


def account(db, name="enrolled"):
    user = User(username=name, email=f"{name}@test.local", full_name=name)
    db.add(user)
    db.commit()
    _, token = SessionService.create_session(db, user.id)
    return user.id, {"Authorization": f"Bearer {token}"}


def sample(stage, ordinal):
    return {"contract_version": "safebank.behaviour.v2", "stage": stage, "ordinal": ordinal, "features": [1.0] * 48, "data_points": 25}


def test_three_samples_advance_each_stage_and_final_completion(client, db_session):
    uid, headers = account(db_session)
    for index, stage in enumerate(STAGES):
        for ordinal in (1, 2, 3):
            response = client.post(URL + "/samples", headers=headers, json=sample(stage, ordinal))
            assert response.status_code == 200, response.text
            state = response.json()
            assert state["user_id"] == uid
            assert state["counts"][stage] == ordinal
            expected = stage if ordinal < 3 else (STAGES[index+1] if index < 3 else "COMPLETE")
            assert state["current_stage"] == expected
            assert state["completed"] == (expected == "COMPLETE")
    assert db_session.query(BehaviourSample).filter_by(user_id=uid).count() == 12
    assert client.get(URL, headers=headers).json()["completed"] is True


@pytest.mark.parametrize("changes", [
    {"data_points": 1}, {"features": [0.0] * 48}, {"features": [1.0] * 47},
    {"features": [1e300] * 48}, {"ordinal": 4}, {"stage": "INVALID"}, {"user_id": "another-user"},
])
def test_invalid_sample_does_not_increment(client, db_session, changes):
    _, headers = account(db_session)
    body = sample("TYPING", 1)
    body.update(changes)
    response = client.post(URL + "/samples", headers=headers, json=body)
    assert response.status_code == 422
    assert client.get(URL, headers=headers).json()["counts"]["TYPING"] == 0


def test_resume_and_duplicate_delivery_across_stages(client, db_session):
    _, headers = account(db_session)
    for n in (1, 2, 3):
        for _ in range(3):
            response = client.post(URL + "/samples", headers=headers, json=sample("TYPING", n))
            assert response.status_code == 200
            assert response.json()["counts"]["TYPING"] == n
    resumed = client.get(URL, headers=headers).json()
    assert resumed["current_stage"] == "TAPPING"
    assert resumed["counts"]["TAPPING"] == 0
    assert client.post(URL + "/samples", headers=headers, json=sample("SWIPING", 1)).status_code == 409
    assert client.post(URL + "/samples", headers=headers, json=sample("TAPPING", 2)).status_code == 409
    client.post(URL + "/samples", headers=headers, json=sample("TAPPING", 1))
    assert client.get(URL, headers=headers).json()["counts"]["TAPPING"] == 1


def test_logout_new_login_and_fresh_client_recognize_enrolment(client, db_session, monkeypatch):
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    monkeypatch.setattr(settings, "ENABLE_DEV_SESSION_ENDPOINT", True)
    uid, headers = account(db_session)
    for stage in STAGES:
        for n in (1, 2, 3):
            assert client.post(URL + "/samples", headers=headers, json=sample(stage, n)).status_code == 200
    assert client.post("/api/v1/security/logout", headers=headers).status_code == 200
    assert client.get(URL, headers=headers).status_code == 401
    login = client.post("/api/v1/auth/dev-session/by-username", json={"username": "enrolled"})
    assert login.status_code == 200
    # New session/token, no Android local preferences or previous progress supplied.
    fresh_headers = {"Authorization": "Bearer " + login.json()["access_token"]}
    state = client.get(URL, headers=fresh_headers).json()
    assert state["completed"] is True
    assert state["user_id"] == uid
    assert len(state["baseline_features"]) == 48


def test_users_are_isolated_and_restricted_sessions_cannot_enrol(client, db_session):
    uid, first = account(db_session, "first")
    other, second = account(db_session, "second")
    client.post(URL + "/samples", headers=first, json=sample("TYPING", 1))
    assert client.get(URL, headers=second).json()["counts"]["TYPING"] == 0
    assert client.get(URL, headers=second).json()["baseline_features"] is None
    assert client.get(URL, headers=second).json()["user_id"] == other
    _, restricted = SessionService.create_session(db_session, uid, is_restricted=True)
    assert client.post(URL + "/samples", headers={"Authorization": f"Bearer {restricted}"},
        json=sample("TYPING", 2)).status_code == 403
    assert client.get(URL).status_code == 401


def test_migration_preserves_existing_records_and_progress_survives_reopen(tmp_path, monkeypatch):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import Session
    from app.api.v1.enrolment import progress
    database_url = "sqlite:///" + (tmp_path / "migration.db").as_posix()
    monkeypatch.setattr(settings, "DATABASE_URL", database_url)
    config = Config("alembic.ini")
    command.upgrade(config, "e025a0f96929")
    engine = create_engine(database_url)
    with Session(engine) as db:
        user = User(username="legacy", email="legacy@test.local", full_name="Legacy User")
        db.add(user)
        db.commit()
        uid = user.id
    with engine.connect() as conn:
        before = conn.execute(text("SELECT * FROM users")).all()
    command.upgrade(config, "head")
    command.upgrade(config, "head")
    with engine.connect() as conn:
        assert conn.execute(text("SELECT * FROM users")).all() == before
    with Session(engine) as db:
        for n in (1, 2, 3):
            db.add(BehaviourSample(user_id=uid, stage="TYPING", ordinal=n, features=[1.0] * 48))
        db.add(BehaviourSample(user_id=uid, stage="TAPPING", ordinal=1, features=[2.0] * 48))
        db.commit()
    engine.dispose()
    reopened = create_engine(database_url)
    with Session(reopened) as db:
        state = progress(db, uid)
        assert state["current_stage"] == "TAPPING"
        assert state["counts"]["TYPING"] == 3
        assert state["counts"]["TAPPING"] == 1
        assert state["completed"] is False
    reopened.dispose()
