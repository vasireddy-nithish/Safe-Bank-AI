# Verified development state

The original `.venv` uses Python 3.14.3. Code Integrity events 3077/3033
identify its SQLAlchemy `_collections_cy.cp314-win_amd64.pyd` as rejected by
Smart App Control signing policy. No security policy was changed.
The separate `.venv312` uses the installed Python 3.12 interpreter and loads
the project's backend dependencies successfully. The original environment is preserved.

An inherited `DEBUG=release` variable is incompatible with the backend's boolean
setting. Set `DEBUG=false` in the shell running the backend.

## Windows local demo commands

From the project directory, in PowerShell:

```powershell
$env:DEBUG='false'
$env:ENVIRONMENT='development'
$env:ENABLE_DEV_SESSION_ENDPOINT='true'
cd backend
..\.venv312\Scripts\python.exe -m alembic upgrade head
..\.venv312\Scripts\python.exe -m app.seed_demo
..\.venv312\Scripts\python.exe -m pytest -q
..\.venv312\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Before starting Uvicorn, optionally set `SAFEBANK_ADMIN_PASSWORD` to a private
password in the process environment. Open `/api/v1/admin/dashboard`, username
`admin`. Use HTTPS for any network deployment; HTTP Basic is suitable only for
loopback development without TLS. No console password is supplied by default.

Use `/docs` to obtain a development session from `/api/v1/auth/dev-session`
with user `usr_dev_alex_chen` and device `dev_pixel_8_pro_01`. Authorize using
the returned bearer token. This is a simulated login, not passkey authentication.
List `/api/v1/banking/accounts`; transfer between those account IDs with a fresh
UUIDv4 `Idempotency-Key` and a decimal string amount.

Set a 6–12 digit PIN through `PUT /api/v1/duress/pin`. Activate through
`POST /api/v1/duress/activate` with username `usr_dev_alex_chen` and that PIN;
activation does not need a login session. Explicit incidents persist across new
sessions; there is no client recovery endpoint. Transfers are rejected and the
console records the incident. Do not use real banking PINs.

`POST /api/v1/security/demo-scenario` accepts `suspicious`, `device`, or `replay`.
These are explicitly simulated signals. Medium/high assessments block transfers;
real step-up and attestation are not implemented. Logout revokes the server session.

## Remaining limitations

- Existing legacy restricted-session tests simulate receipts without moving money;
  explicit PIN incidents use the new rejection path.
- Duress rate limiting needs concurrent-request hardening and an approved recovery
  design; PIN setup currently accepts the existing demo session.
- No completed registration/passkey flow, behavioural collection, trained model,
  TFLite artifact, or Android security-flow integration is claimed.
- Console HTTP responses were tested, but browser rendering was not inspected.
- Android build is now verified with the user-installed Temurin JDK 21.0.13.
  `assembleDebug` and `testDebugUnitTest` succeeded: 66 tests, zero failures,
  errors, or skips. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
  No physical-device or emulator execution has been verified in this build run.
- Database migration and seed commands are provided but not yet executed against
  a persistent user database. Tests use isolated databases.
