import json
import uuid
from datetime import timedelta
import pytest
from app.db.base import utc_now
from app.models.user import User
from app.models.account import Account
from app.models.risk_event import RiskEvent
from app.services.session_service import SessionService
from app.services.risk_policy import transaction_context, permits_transfer

@pytest.fixture
def setup_account(db_session):
    u=User(username="riskv2",email="riskv2@test.local",full_name="Risk V2")
    db_session.add(u);db_session.commit()
    accounts=[Account(user_id=u.id,account_number=str(77000+i),balance=1000,currency="INR",status="active") for i in range(2)]
    db_session.add_all(accounts);db_session.commit()
    session,token=SessionService.create_session(db_session,u.id)
    body=dict(from_account_id=accounts[0].id,to_account_id=accounts[1].id,amount="10.00",currency="INR")
    return u.id,session.id,{"Authorization":"Bearer "+token},body

def assessment(body, **changes):
    result=dict(genuine_probability=.98,uncertain_probability=.01,anomaly_probability=.01,risk_score=2,
        is_from_live_model=True,session_data_points=30,contract_version="safebank.behaviour.v2",
        transaction_context=transaction_context(**dict(source=body["from_account_id"],destination=body["to_account_id"],amount=body["amount"],currency="INR")))
    result.update(changes);return result

@pytest.mark.parametrize("case",["missing","stale","wrong_amount","wrong_session","fallback","legacy","invalid_sum","high","medium"])
def test_required_assessment_denies_unsafe_transfer(client,db_session,setup_account,case):
    uid,sid,headers,body=setup_account
    if case!="missing":
        changes={}
        if case=="fallback":changes["is_from_live_model"]=False
        if case=="legacy":changes["contract_version"]="legacy"
        if case=="invalid_sum":changes["genuine_probability"]=.1
        if case=="high":changes["risk_score"]=90
        if case=="medium":changes["risk_score"]=50
        assert client.post("/api/v1/risk/submit",headers=headers,json=assessment(body,**changes)).status_code==200
        if case=="stale":
            event=db_session.query(RiskEvent).filter_by(session_id=sid).order_by(RiskEvent.created_at.desc()).first();event.created_at=utc_now()-timedelta(seconds=121);db_session.commit()
        if case=="wrong_amount":body["amount"]="11.00"
        if case=="wrong_session":
            _,token=SessionService.create_session(db_session,uid);headers={"Authorization":"Bearer "+token}
    response=client.post("/api/v1/banking/transfers",headers={**headers,"Idempotency-Key":str(uuid.uuid4())},json=body)
    assert response.status_code==422,response.text
    db_session.expire_all()
    assert float(db_session.get(Account,body["from_account_id"]).balance)==1000


def test_assessment_consumed_once_and_replay_remains_idempotent(client,db_session,setup_account):
    _,_,headers,body=setup_account
    risk=client.post("/api/v1/risk/submit",headers=headers,json=assessment(body))
    assert risk.json()["action"]=="allow"
    assert client.get("/api/v1/security/status",headers=headers).json()["server_score"]==risk.json()["server_score"]
    key=str(uuid.uuid4())
    first=client.post("/api/v1/banking/transfers",headers={**headers,"Idempotency-Key":key},json=body)
    assert first.status_code==201,first.text
    replay=client.post("/api/v1/banking/transfers",headers={**headers,"Idempotency-Key":key},json=body)
    assert replay.status_code==200
    duplicate=client.post("/api/v1/banking/transfers",headers={**headers,"Idempotency-Key":str(uuid.uuid4())},json=body)
    assert duplicate.status_code==422
    db_session.expire_all()
    assert float(db_session.get(Account,body["from_account_id"]).balance)==990


def test_client_boolean_cannot_authorize(client,setup_account):
    _,_,headers,_=setup_account
    assert client.post("/api/v1/risk/step-up",headers=headers,json={"verified":True}).status_code==501


def test_invalid_submission_invalidates_previous_permission(client,setup_account):
    _,_,headers,body=setup_account
    assert client.post("/api/v1/risk/submit",headers=headers,json=assessment(body)).json()["action"]=="allow"
    assert client.post("/api/v1/risk/submit",headers=headers,json={"risk_score":-1}).status_code==422
    result=client.post("/api/v1/banking/transfers",headers={**headers,"Idempotency-Key":str(uuid.uuid4())},json=body)
    assert result.status_code==422


@pytest.mark.parametrize("points", [None, "many", [], {}, True])
def test_malformed_stored_assessment_fails_closed(db_session, setup_account, points):
    uid, sid, _, body = setup_account
    context = assessment(body)["transaction_context"]
    risk = RiskEvent(user_id=uid, session_id=sid, risk_score=2, risk_level="low",
        action_taken="allow", created_at=utc_now(), factors_json=json.dumps({
            "contract_version":"safebank.behaviour.v2", "live_model":True,
            "data_points":points, "transaction_context":context}))
    assert not permits_transfer(risk, uid, sid, context, "key", utc_now())
