"""Read-only simulated bank console; credentials supplied through environment."""
import os
import secrets
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from sqlalchemy.orm import Session as DbSession
from app.db.session import get_db
from app.models.security_event import SecurityEvent
from app.models.duress_event import DuressEvent
from app.models.session import Session
from app.models.risk_event import RiskEvent

router = APIRouter(prefix="/admin", tags=["simulated bank console"])
basic = HTTPBasic()


def authorize(credentials: HTTPBasicCredentials = Depends(basic)):
    expected = os.environ.get("SAFEBANK_ADMIN_PASSWORD", "")
    if not expected or not (secrets.compare_digest(credentials.username.encode(), b"admin")
                            and secrets.compare_digest(credentials.password.encode(), expected.encode())):
        raise HTTPException(401, "Console authentication required", headers={"WWW-Authenticate": "Basic"})


@router.get("/summary", dependencies=[Depends(authorize)])
def summary(db: DbSession = Depends(get_db)):
    rows = db.query(SecurityEvent).order_by(SecurityEvent.created_at.desc()).limit(100).all()
    return {"events": db.query(SecurityEvent).count(),
            "restricted_sessions": db.query(Session).filter_by(is_active=True, is_restricted=True).count(),
            "duress_incidents": db.query(DuressEvent).count(),
            "high_risk_assessments": db.query(RiskEvent).filter_by(risk_level="high").count(),
            "alerts": [{"time": r.created_at.isoformat(), "severity": r.severity, "event": r.event_type} for r in rows]}


@router.get("/dashboard", response_class=HTMLResponse, dependencies=[Depends(authorize)])
def dashboard():
    return HTMLResponse('''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SafeBank Security</title>
<style>body{font:16px system-ui;background:#eef4f5;color:#122c43;margin:2rem auto;max-width:960px;padding:1rem}h1{color:#12394b}button,select{padding:.7rem;margin:.5rem}table{width:100%;background:white;border-collapse:collapse}td,th{text-align:left;padding:1rem;border-bottom:1px solid #ddd}.critical{color:#a32121}#counts{padding:1rem;background:#d8eeea}</style>
<h1>SafeBank AI</h1><p>Simulated bank security console · No real banking operations</p>
<button onclick="refresh()">Refresh</button><label>Severity <select id="filter" onchange="render()"><option>all</option><option>info</option><option>warning</option><option>critical</option></select></label>
<p id="counts" role="status">Loading…</p><table><thead><tr><th>Time</th><th>Severity</th><th>Event</th></tr></thead><tbody id="rows"></tbody></table>
<script>let data={alerts:[]};async function refresh(){try{const r=await fetch('summary',{cache:'no-store'});if(!r.ok)throw Error('Unable to load console: '+r.status);data=await r.json();document.getElementById('counts').textContent=`Events: ${data.events} · Restricted sessions: ${data.restricted_sessions} · Duress incidents: ${data.duress_incidents} · High-risk assessments: ${data.high_risk_assessments}`;render()}catch(e){document.getElementById('counts').textContent=e.message}}function render(){const rows=document.getElementById('rows');rows.replaceChildren();for(const a of data.alerts){if(document.getElementById('filter').value!=='all'&&a.severity!==document.getElementById('filter').value)continue;const tr=document.createElement('tr');tr.className=a.severity;for(const value of [a.time,a.severity,a.event]){const td=document.createElement('td');td.textContent=value;tr.append(td)}rows.append(tr)}}refresh();</script></html>''', headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
