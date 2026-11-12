"""Explicit, non-destructive local seed: python -m app.seed_demo."""
from decimal import Decimal
from app.core.config import settings
from app.db.session import SessionLocal
from app.models.user import User
from app.models.device import Device
from app.models.account import Account


def main():
    if settings.ENVIRONMENT not in ("development", "test"):
        raise RuntimeError("Demo seeding is disabled outside development/test")
    with SessionLocal() as db:
        uid = "00000000-0000-4000-a000-000000000001"
        if db.get(User, uid):
            print("Demo user already exists; no records changed.")
            return
        db.add(User(id=uid, username="usr_dev_alex_chen", email="alex@example.test", full_name="Alex Chen"))
        db.flush()
        db.add(Device(id="00000000-0000-4000-a000-000000000021", user_id=uid,
                      device_name="Simulated demo device", device_fingerprint="demo-only",
                      public_key_pem="DEMO-NOT-A-VERIFIED-KEY", is_verified=False, attestation_type="software"))
        for i, kind in enumerate(("checking", "savings")):
            db.add(Account(user_id=uid, account_number=str(100000009481+i), account_type=kind,
                           balance=Decimal("10000.00"), currency="INR", status="active"))
        db.commit()
        print("Simulated accounts created. Device verification is not implemented.")


if __name__ == "__main__":
    main()
