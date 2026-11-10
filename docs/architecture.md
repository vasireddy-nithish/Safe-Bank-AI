# SafeBank AI System Architecture

## 1. Architectural Philosophy: The Three Security Pillars

SafeBank AI is structured around answering three distinct questions on every interaction:

```
                  +--------------------------------------------------+
                  |               INCOMING USER SESSION              |
                  +--------------------------------------------------+
                                           |
             +-----------------------------+-----------------------------+
             |                             |                             |
             v                             v                             v
   [ 1. IDENTITY ]                [ 2. DEVICE BINDING ]           [ 3. FREEDOM TO ACT ]
   - Passkey (WebAuthn/FIDO2)     - Hardware Keystore / StrongBox - Explicit Duress PIN
   - Android Credential Manager   - Asymmetric challenge-response - Independent of ML
   - BiometricPrompt              - Play Integrity verification   - Silent Restricted Mode
             |                             |                             |
             +-----------------------------+-----------------------------+
                                           |
                                           v
                          +----------------------------------+
                          |        HYBRID RISK ENGINE        |
                          |  - On-Device Behavioural ML      |
                          |  - Cold-start baseline weighting |
                          |  - Server-side authoritative     |
                          |    risk scoring (0 - 100)        |
                          +----------------------------------+
                                           |
                                           v
                          +----------------------------------+
                          |       DECISION & EXECUTION       |
                          |  - Normal Banking                |
                          |  - Step-Up Verification          |
                          |  - Restricted Mode (Silent)      |
                          +----------------------------------+
```

### 1.1 Coercion vs. Behavioural Anomaly Separation
A critical security principle is that **ML behavioural anomalies do not prove coercion**.
- Behavioural biometrics (typing cadence, swipe curvature, micro-tremor) fluctuate naturally due to stress, fatigue, injury, walking, or changing hands.
- Treating high anomaly as coercion causes dangerous false positives (e.g. blocking a user when they are rushing).
- Conversely, an attacker forcing a user at gunpoint cannot be reliably detected by statistical thresholds alone without high false rejection risk.
- **Solution**: Coercion protection is handled by an explicit, deterministic duress mechanism (Duress PIN) that silently triggers server-side restricted mode. Behavioural ML provides continuous passive posture scoring to trigger step-up authentication, but never replaces explicit duress or acts as an autonomous transaction arbiter.

---

## 2. Component Architecture

### 2.1 Backend (FastAPI + SQLAlchemy)
- **Authoritative Enforcement**: All financial rules, transfer limits, and access permissions are enforced on the backend. The client UI is purely a reflection of the server's authoritative state.
- **Stateless Authentication & Stateful Sessions**: Short-lived cryptographic tokens accompanied by a server-side session registry allowing immediate session revocation across devices.
- **Idempotency & Atomicity**: Transfers use transactional isolation with unique `Idempotency-Key` headers to guarantee against double-spending or replay attacks.
- **Zero Plaintext Storage**: Passkey public keys only (no private keys ever leave the device); Duress PIN stored via salted Argon2id hashes; no raw biometric or touch data stored.

### 2.2 Android Client (Jetpack Compose + MVVM)
- **NeoSecure UI Design System**: High-contrast, dark-mode (#0B1020, #171B35, #6A00FF) interface tailored for clarity under cognitive stress.
- **Android Credential Manager**: Standards-compliant passkey registration and sign-in flow.
- **Hardware-Backed Device Key**: Generated inside the Android Keystore (`KeyGenParameterSpec`) with `PURPOSE_SIGN`, backed by StrongBox where available.
- **Local Feature Pipeline**: Captures touch, typing, and accelerometer/gyroscope signals directly in Compose interaction interceptors. Computes a 48-feature normalised vector on-device.
- **On-Device LiteRT (TFLite)**: Evaluates the behavioural vector locally without sending raw sensor telemetry to the backend.

---

## 3. Data Protection & Privacy Guarantees

1. **Raw Sensor Isolation**: Touch coordinates, inter-keystroke timings, and device motion series are ephemeral. They exist only in volatile memory during feature extraction and are wiped after vector computation.
2. **Derived Metadata Only**: If the client shares risk telemetry with the backend, it sends only derived summary metrics (e.g., categorical classification and model confidence), never raw sensor logs.
3. **Restricted Mode Sanitisation**: When in duress restricted mode, the backend returns simulated balances and mock transactions to protect the user from physical danger, while completely disabling outward transaction execution.
