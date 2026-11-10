# SafeBank AI

> **A Privacy-First, Passwordless & Coercion-Aware Authentication Layer for Mobile Banking**
> Problem Statement: PS-05 — Passwordless, Coercion-Aware Authentication

---

## 1. Executive Overview

SafeBank AI is a modern mobile banking prototype designed to resolve the three fundamental questions of secure user interactions:
1. **Identity**: Is the user authenticating with a valid registered credential? (Passwordless via WebAuthn/FIDO2 & Android Credential Manager Passkeys + BiometricPrompt)
2. **Device**: Is the active session cryptographically bound to a registered, verified hardware-backed device? (Android Keystore / StrongBox asymmetric keypairs & Play Integrity)
3. **Freedom to Act**: Is there an explicit indication that the user is activating a duress mechanism? (Independent dual-PIN duress mechanism isolated from ML heuristics)

### Key Architectural Tenet: The Coercion vs. Anomaly Separation
Behavioural anomalies indicate atypical touch, typing, or motion patterns, but **anomalies do not prove coercion**. Coercion defence requires an explicit, deterministic duress mechanism (e.g. Duress PIN) that silently switches the account into a cryptographically isolated and server-enforced **Restricted Mode**, while the on-device behavioural ML model acts purely as an assistive risk signal.

---

## 2. Technology Stack

### Android Client
- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (Material 3, Dark Mode NeoSecure theme)
- **Architecture**: MVVM + Clean Architecture with Coroutines & StateFlow
- **Authentication**: Android Credential Manager (Passkeys/FIDO2), BiometricPrompt
- **Device Security**: Android Keystore (StrongBox Keymaster fallback), Play Integrity API
- **On-Device ML**: TensorFlow Lite / LiteRT runtime
- **Networking**: Retrofit 2 + OkHttp 4
- **Persistence**: DataStore Preferences (encrypted credentials/tokens) + Room (audit metadata)

### Backend
- **Language**: Python 3.11 - 3.14
- **Framework**: FastAPI (async ASGI)
- **Database**: SQLite (prototype stage) with SQLAlchemy 2.0 ORM & Alembic migrations
- **Data Validation**: Pydantic v2
- **FIDO2 / WebAuthn**: PyWebAuthn / fido2 library
- **Security & Cryptography**: Argon2id for duress PIN hashing, JWT/Fernet for session signing
- **Testing**: Pytest + HTTPX TestClient

### Machine Learning Engine
- **Features**: Privacy-first 48-feature behavioural vector (Touch Dynamics, Typing Dynamics, Motion Dynamics)
- **Architecture**: Compact Multi-Layer Perceptron (MLP: 48 -> 64 -> 32 -> 3)
- **Runtime**: Quantised TensorFlow Lite (`.tflite`) running strictly on-device. Raw touch/sensor traces never leave the client device.

---

## 3. Project Structure

```
safebank-ai/
├── README.md                          # Project documentation and roadmap
├── .gitignore                         # Comprehensive Git ignore rules
├── backend/                           # FastAPI Backend application
│   ├── app/
│   │   ├── __init__.py
│   │   ├── main.py                    # Application entry point & lifespan
│   │   ├── core/                      # Configuration, security utils, logging
│   │   │   ├── config.py
│   │   │   └── security.py
│   │   ├── api/                       # Versioned API routes (/api/v1)
│   │   │   └── v1/
│   │   │       ├── router.py
│   │   │       ├── endpoints/
│   │   │       │   ├── auth.py
│   │   │       │   ├── users.py
│   │   │       │   ├── devices.py
│   │   │       │   ├── sessions.py
│   │   │       │   ├── banking.py
│   │   │       │   ├── risk.py
│   │   │       │   └── duress.py
│   │   ├── models/                    # SQLAlchemy ORM models
│   │   ├── schemas/                   # Pydantic request/response schemas
│   │   ├── services/                  # Business logic & risk policy engine
│   │   └── db/                        # Database session & engine setup
│   ├── tests/                         # Pytest automated test suite
│   ├── requirements.txt               # Backend dependencies
│   ├── pyproject.toml                 # Packaging & tool configurations
│   └── .env.example                   # Environment configuration template
├── android/                           # Android Studio / Gradle project
│   ├── build.gradle.kts               # Root Gradle build script
│   ├── settings.gradle.kts            # Gradle settings & plugin management
│   ├── gradle.properties              # JVM & Gradle build configurations
│   ├── app/
│   │   ├── build.gradle.kts           # Application module build script
│   │   ├── proguard-rules.pro
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── java/com/safebank/ai/
│   │       │   │   ├── SafeBankApplication.kt
│   │       │   │   ├── MainActivity.kt
│   │       │   │   ├── ui/
│   │       │   │   │   ├── theme/     # NeoSecure Dark Theme tokens
│   │       │   │   │   ├── components/# Reusable Compose components
│   │       │   │   │   └── screens/   # 16 Compose screens
│   │       │   │   ├── data/          # Repositories & DataStore
│   │       │   │   ├── domain/        # Use cases & business logic
│   │       │   │   ├── security/      # Keystore, Credential Manager & Duress
│   │       │   │   └── ml/            # LiteRT behavioural feature pipeline
│   │       │   └── res/               # Android drawables, values, layouts
│   │       └── test/                  # Android unit tests
│   └── gradle/wrapper/
├── ml/                                # Model training & evaluation pipeline
│   ├── README.md                      # Feature engineering & dataset specs
│   ├── notebooks/                     # Exploratory analysis
│   ├── scripts/                       # Training, evaluation & TFLite export
│   └── models/                        # Quantised TFLite artifacts
└── docs/                              # Architecture and security specifications
    └── architecture.md
```

---

## 4. Staged Implementation Plan

- **Stage 1 (Current)**: Environment inspection, project scaffolding, foundational contracts, build verification.
- **Stage 2**: Backend foundation, SQLite database schema, Alembic migrations, session management.
- **Stage 3**: Android UI foundation, NeoSecure design system tokens, core reusable Compose components.
- **Stage 4**: Mock banking operations (accounts, atomic transfers, idempotency, transaction logs).
- **Stage 5**: Authentication & device security (Passkeys/WebAuthn, Keystore hardware binding, Biometrics).
- **Stage 6**: Behavioural ML pipeline (48-feature extraction, synthetic/benchmark evaluation, TFLite conversion).
- **Stage 7**: Hybrid risk engine (multi-factor client-server risk scoring, step-up challenges).
- **Stage 8**: Duress protection & Restricted Mode (silent dual-PIN activation, offline queue, server enforcement).
- **Stage 9**: End-to-end integration, security penetration simulations, adversarial test suite.
- **Stage 10**: Final verification, demo guides, and documentation review.

---

## 5. Development & Running (Stage 1 Setup)

### Backend Quickstart
```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --reload --port 8000
```

### Android Quickstart
Open the `android/` directory in Android Studio (Jellyfish / Koala or newer) configured with JDK 21. Sync Gradle and run the `:app` configuration on an emulator or physical device running Android 14+ (API level 34+).
