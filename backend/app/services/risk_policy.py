"""Versioned demo policy. Client model telemetry is not cryptographic attestation."""
import json
from decimal import Decimal

CONTRACT = "safebank.behaviour.v2"
MAX_AGE_SECONDS = 120


def transaction_context(source, destination, amount, currency, description=None):
    return dict(from_account_id=source, to_account_id=destination,
                amount=format(Decimal(str(amount)).quantize(Decimal("0.01")), ".2f"),
                currency=currency, description=description or "Internal transfer")


def permits_transfer(risk, user_id, session_id, context, key, now):
    if not risk or risk.user_id != user_id or risk.session_id != session_id:
        return False
    if risk.action_taken != "allow" or risk.risk_level != "low" or not 0 <= risk.risk_score < 30:
        return False
    if not 0 <= (now - risk.created_at).total_seconds() <= MAX_AGE_SECONDS:
        return False
    try:
        factors = json.loads(risk.factors_json or "{}")
    except (ValueError, TypeError):
        return False
    return (isinstance(factors, dict) and factors.get("contract_version") == CONTRACT
            and factors.get("live_model") is True
            and type(factors.get("data_points")) is int and factors["data_points"] >= 5
            and factors.get("transaction_context") == context
            and factors.get("consumed_by") in (None, key))
