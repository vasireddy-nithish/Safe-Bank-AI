# SafeBank AI — End-to-End Demo Guide (Windows)

> **Prototype Transparency:** SafeBank AI is a hackathon security prototype. Account balances, banking ledgers, Play Integrity checks, and demo passkey login are simulated for demonstration. Do not enter real banking credentials or PINs.

---

## 1. Prerequisites

- Windows 10/11
- Existing Python 3.12 virtual environment: `.venv312`
- JDK 21 (Eclipse Adoptium) + Android SDK (for building/running on Android Emulator or device)

---

## 2. Start the Backend & Security Console (PowerShell)

From `C:\Users\hp\Downloads\zup`:

```powershell
$env:DEBUG = "false"
$env:ENVIRONMENT = "development"
$env:ENABLE_DEV_SESSION_ENDPOINT = "true"
$env:SAFEBANK_ADMIN_PASSWORD = "demo-console-password"

cd backend
..\.venv312\Scripts\python.exe -m alembic upgrade head
..\.venv312\Scripts\python.exe -m app.seed_demo
..\.venv312\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

> **Note for physical Android device:** Bind Uvicorn to `0.0.0.0` (over a trusted local network or secure tunnel) and rebuild the APK with `-Psafebank.baseUrl=http://<YOUR_LAN_IP>:8000/api/v1/`. For the Android Emulator, the default `http://10.0.2.2:8000/api/v1/` works automatically.

### Open the Bank Security Console
1. Open a browser to `http://127.0.0.1:8000/api/v1/admin/dashboard`
2. Sign in with HTTP Basic Auth:
   - **Username:** `admin`
   - **Password:** `demo-console-password` (or whatever you set in `$env:SAFEBANK_ADMIN_PASSWORD`)

---

## 3. Build & Install the Android App

From `C:\Users\hp\Downloads\zup\android`:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-debug.ps1
```

Install the generated APK (`android\app\build\outputs\apk\debug\app-debug.apk`) on an Android Emulator (`adb install -r app\build\outputs\apk\debug\app-debug.apk`).

---

## 4. Demo Scenarios Walkthrough

### Scenario A — Genuine User Registration, Enrolment & Transfer
1. Launch **SafeBank AI** on the Android Emulator.
2. On the **Welcome** screen, tap **Register Account** (or tap **Sign In** with the pre-seeded `usr_dev_alex_chen` account).
3. If registering a new user:
   - Enter Full Name, Username, Enrolment Code `SAFEBANK2024`, optional Duress PIN (e.g., `654321`), check both consent boxes, and tap **Register Account**.
4. Complete the 4-step **Behavioural Baseline** enrolment (Typing, 3×3 Tapping, Swipe, Device Motion — 3 reps each).
5. On the **Home** dashboard, verify the backend-loaded balance (₹10,000.00 in Checking & Savings).
6. Go to **Transfers**, select Checking → Savings, enter `500.00`, review, and confirm.
7. Verify the transfer completes and balances update (`₹9,500.00` and `₹10,500.00`).

### Scenario B — Suspicious Behaviour & Step-Up Verification
1. Open the **Security** tab.
2. Tap **Demo: Trigger Suspicious Behaviour (Scenario B)** (calls `POST /api/v1/security/demo-scenario` with `{"scenario": "suspicious"}`).
3. Observe the risk gauge rise to **Medium (50/100)** and a `demo_suspicious` event appear under **Recent Security Events**.
4. Attempt a transfer on the **Transfers** tab: Android triggers `BiometricPrompt` / device-credential step-up (or backend holds the transfer for security review).
5. Refresh the **Bank Security Console** in your browser to see the `demo_suspicious` warning event.

### Scenario C — Deliberate Duress Protection
1. Open the **Profile** tab.
2. Tap **Configure Duress PIN** (if not configured during registration) and set a 6–12 digit PIN (e.g., `654321`).
3. Tap **Demo: Activate Duress PIN (Scenario C)** and enter your username + duress PIN.
4. Immediately:
   - The backend marks all active and future sessions for that user as `is_restricted = True` and records a critical `duress_activated` incident.
   - The app transitions to the **Account Protected** screen without revealing the word "duress" or the PIN trigger.
   - Any transfer attempt against the backend is rejected (`HTTP 422` / `SECURITY_RESTRICTION`) with zero balance movement.
   - Refresh the **Bank Security Console** (`http://127.0.0.1:8000/api/v1/admin/dashboard`) to see **Duress incidents: 1** and the critical `duress_activated` alert.

### Scenario D — Device / Replay Attack Simulation
1. On the **Security** tab, tap **Demo: Trigger Device / Replay Attack (Scenario D)**.
2. Risk jumps to **High (90/100)** (`restrict`), transfers are blocked by the backend, and the alert appears both in **Recent Security Events** and on the **Bank Security Console**.

---

## 5. Automated Verification Commands

```powershell
# Backend tests (110 tests)
cd backend
Remove-Item Env:ENABLE_DEV_SESSION_ENDPOINT -ErrorAction SilentlyContinue
$env:DEBUG = "false"
$env:ENVIRONMENT = "development"
..\.venv312\Scripts\python.exe -m pytest -q

# ML training, evaluation & TFLite export
cd ..
.\.venv312\Scripts\python.exe ml\scripts\run_pipeline.py

# Android APK + unit tests
cd android
powershell -ExecutionPolicy Bypass -File .\build-debug.ps1
```
