# Stage 4 Specification: Mock Banking Operations & Resilience Layer

> **SafeBank AI — PS-05 Passwordless & Coercion-Aware Banking Prototype**  
> **Status: PROPOSED PLAN (Awaiting User Approval)**

---

## 1. System Overview & Core Principles

Stage 4 delivers the complete mock banking functionality connecting the Android client and the FastAPI backend. It adheres strictly to five core principles:

1. **Currency & Domain Model Alignment**:
   - Currency is exclusively **Indian Rupee (INR / ₹)**.
   - Account types are strictly aligned with the backend database schema: `checking` and `savings`.
   - Transaction types are strictly aligned with the backend schema: `transfer`, `payment`, `deposit`.
2. **Strict Monetary Precision**:
   - Backend: Python `decimal.Decimal`.
   - Database: SQLite `NUMERIC(14, 2)` with check constraints (`balance >= 0`, `amount > 0`).
   - Android: `java.math.BigDecimal` and validated 2-decimal strings (`"500.00"`).
   - JSON Transport: Monetary amounts transmitted strictly as decimal strings (e.g. `"1500.50"`).
   - **Zero Floating-Point Drift**: `Float` and `Double` are strictly forbidden for financial calculations.
3. **Robust Idempotency Lifecycle**:
   - Exactly one UUIDv4 `idempotency_key` generated per logical user transfer intent.
   - Reused across retries, reconnects, and timeout reconciliations.
   - Backend enforces payload consistency: repeated idempotency keys with mismatched payloads are rejected with `409 Conflict`.
4. **Explicit Banking Data-Source Modes**:
   - Client defines two mutually exclusive modes: `DEMO_MOCK` and `BACKEND_CONNECTED`.
   - The active mode is prominently indicated in the Android UI.
   - **No Silent Fallback**: Network failures in `BACKEND_CONNECTED` mode never silently fall back to mock data or execute phantom transactions. Explicit safe retry and reconciliation workflows are provided.
5. **Development-Only Authorization Bridge**:
   - Gated behind explicit environment configuration (`ENVIRONMENT=development`).
   - Every banking endpoint strictly validates session tokens against the database and evaluates `is_restricted` status.
   - Client-supplied authentication flags are never trusted.

---

## 2. Architecture & Data-Source Modes

```
+-----------------------------------------------------------------------------------+
|                              ANDROID PRESENTATION LAYER                           |
|       HomeScreen               TransfersScreen                 HistoryScreen      |
|    (Balances in ₹)        (Input in ₹, Review Modal)       (Filtered Tx in ₹)     |
+-----------------------------------------------------------------------------------+
                                          │
                                          ▼
+-----------------------------------------------------------------------------------+
|                            DOMAIN LAYER (USE CASES)                               |
|   GetAccountsUseCase         ExecuteTransferUseCase       GetTransactionsUseCase  |
|   (Validates ₹ limits)       (Generates Idempotency UUID) (Filter & Reconciliation|
+-----------------------------------------------------------------------------------+
                                          │
                                          ▼
+-----------------------------------------------------------------------------------+
|                         BANKING REPOSITORY INTERFACE                              |
|     getActiveMode(): BankingMode (DEMO_MOCK | BACKEND_CONNECTED)                  |
|     executeTransfer(request: TransferRequest): Result<TransferReceipt>            |
|     reconcileTransfer(idempotencyKey: String): Result<TransferReceipt>            |
+-----------------------------------------------------------------------------------+
                       │                                             │
      [DEMO_MOCK Mode] │                            [BACKEND_CONNECTED Mode]
                       ▼                                             ▼
+-----------------------------+               +-------------------------------------+
|    InMemoryMockDataSource   |               |         Retrofit API Client         |
|  - In-memory atomic state   |               |  - Transmits Authorization header   |
|  - Validates limits & ₹     |               |  - Transmits Idempotency-Key header |
|  - Enforces idempotency     |               |  - Decimal amounts in JSON strings  |
+-----------------------------+               +-------------------------------------+
                                                                 │
                                                                 ▼
                                              +-------------------------------------+
                                              |       FASTAPI BACKEND ENGINE        |
                                              |  1. Validate Bearer Session Token   |
                                              |  2. Check Sensitive Action Gate     |
                                              |  3. Enforce Daily Limit (₹50,000)   |
                                              |  4. Idempotency Payload Hash Match  |
                                              |  5. Atomic DB Transaction (NUMERIC) |
                                              |  6. Duress Mode Honeypot Isolation  |
                                              +-------------------------------------+
```

### 2.1 Mode Transition & Reconciliation Rules
- In `DEMO_MOCK` mode: All transactions operate against an isolated in-memory ledger. Useful for offline demos, design reviews, and isolated UI tests.
- In `BACKEND_CONNECTED` mode: All operations require network connectivity to the backend. The backend is the sole source of truth.
- **Fail-Safe Timeout Reconciliation**:
  If a client initiates a transfer and experiences an `OkHttp` socket timeout, it enters the **Uncertain State**. The UI presents two actions:
  1. **Safe Retry**: Resends the exact same `idempotency_key` and payload to the backend. If the backend already processed the previous attempt, it returns the existing receipt without duplicate debit.
  2. **Reconcile**: Queries `GET /api/v1/banking/transfers/reconcile/{idempotency_key}`. If recorded on server, displays confirmed receipt; if not found, allows safe cancellation.

---

## 3. Data Models & API Contracts

### 3.1 Currency & Account Schema
- **Supported Currency**: `"INR"` (Indian Rupee, symbol: `₹`).
- **Account Types**: Strictly `checking` and `savings`.
- **Transaction Types**: Strictly `transfer`, `payment`, `deposit`.
- **Transaction Statuses**: `completed`, `pending`, `failed`, `simulated_duress`.

### 3.2 JSON REST API Endpoints (`/api/v1/banking`)

#### A. Development Session Creation (Stage 4 Bridge)
- **Endpoint**: `POST /api/v1/auth/dev-session`
- **Gate**: Enabled only when `settings.ENVIRONMENT == "development"`. Disabled in production.
- **Request Body**:
  ```json
  {
    "user_id": "usr_dev_mock_01",
    "device_id": "dev_pixel_8_pro_01"
  }
  ```
- **Response** (HTTP 200):
  ```json
  {
    "access_token": "sb_session_32byte_secure_hex...",
    "token_type": "bearer",
    "expires_in": 3600,
    "user_id": "usr_dev_mock_01",
    "is_restricted": false
  }
  ```

#### B. List Accounts
- **Endpoint**: `GET /api/v1/banking/accounts`
- **Headers**: `Authorization: Bearer <token>`
- **Response** (HTTP 200):
  ```json
  [
    {
      "id": "acc-checking-01",
      "account_number": "SBIN-0012-9481",
      "masked_account_number": "•••• 9481",
      "account_type": "checking",
      "balance": "124500.80",
      "currency": "INR",
      "status": "active",
      "is_primary": true
    },
    {
      "id": "acc-savings-02",
      "account_number": "SBIN-0012-9482",
      "masked_account_number": "•••• 9482",
      "account_type": "savings",
      "balance": "350000.00",
      "currency": "INR",
      "status": "active",
      "is_primary": false
    }
  ]
  ```

#### C. Execute Transfer
- **Endpoint**: `POST /api/v1/banking/transfers`
- **Headers**:
  - `Authorization: Bearer <token>`
  - `Idempotency-Key: <uuid-v4>`
- **Request Body**:
  ```json
  {
    "from_account_id": "acc-checking-01",
    "to_account_id": "acc-savings-02",
    "amount": "5000.00",
    "currency": "INR",
    "description": "Savings allocation"
  }
  ```
- **Success Response** (HTTP 201 Created / 200 OK on idempotent replay):
  ```json
  {
    "success": true,
    "transaction_id": "tx-8f92-uuid",
    "reference_number": "TX-20261110-8F92A",
    "status": "completed",
    "from_account_id": "acc-checking-01",
    "to_account_id": "acc-savings-02",
    "amount": "5000.00",
    "currency": "INR",
    "idempotency_key": "c4b8-uuid-v4",
    "completed_at": "2026-11-10T15:45:00Z",
    "message": "Transfer executed successfully."
  }
  ```
- **Idempotency Conflict Response** (HTTP 409 Conflict):
  Triggered when the same `Idempotency-Key` is re-sent with different transfer parameters (e.g. altered amount or different destination account):
  ```json
  {
    "detail": "IDEMPOTENCY_PAYLOAD_MISMATCH: The provided idempotency key was previously used with different request parameters."
  }
  ```
- **Daily Limit Exceeded Response** (HTTP 422 Unprocessable Entity):
  ```json
  {
    "detail": "DAILY_LIMIT_EXCEEDED: Daily transfer limit of ₹50,000.00 exceeded. Cumulative today: ₹48,000.00, requested: ₹5,000.00."
  }
  ```
- **Simulated Duress Response** (HTTP 200 OK):
  When the session has `is_restricted=True`, the response returns a success-like receipt to protect the user, but the status is recorded as `simulated_duress` with zero ledger modification:
  ```json
  {
    "success": true,
    "transaction_id": "tx-duress-uuid",
    "reference_number": "TX-20261110-DUR01",
    "status": "simulated_duress",
    "from_account_id": "acc-checking-01",
    "to_account_id": "acc-savings-02",
    "amount": "5000.00",
    "currency": "INR",
    "idempotency_key": "c4b8-uuid-v4",
    "completed_at": "2026-11-10T15:45:00Z",
    "message": "Transfer processed successfully."
  }
  ```

#### D. Reconcile Transfer
- **Endpoint**: `GET /api/v1/banking/transfers/reconcile/{idempotency_key}`
- **Headers**: `Authorization: Bearer <token>`
- **Response**: Returns existing transaction details if recorded, or HTTP 404 if not found (confirming transfer never completed).

---

## 4. Server-Side Security & Business Rules

1. **Session & Token Verification**:
   - Every banking request extracts `Bearer <token>`.
   - Computes `SHA-256(token)` and looks up active session in SQLite.
   - Enforces expiration (`expires_at < utc_now()`) and revocation (`revoked_at is not null`).
2. **Account Ownership Verification**:
   - `from_account.user_id` must strictly equal `session.user_id`. A user cannot debit another customer's account.
   - Both `from_account.status` and `to_account.status` must be `"active"`.
3. **Daily Transfer Limit Enforcement**:
   - System computes:
     $$\sum_{\text{completed today}} \text{amount} + \text{requested amount} \le \text{₹50,000.00}$$
   - Window evaluated based on UTC calendar day (`completed_at >= start_of_day_utc`).
4. **Idempotency Key & Payload Hash Verification**:
   - If `idempotency_key` exists in database:
     - Verify: `(tx.from_account_id == from_id) && (tx.to_account_id == to_id) && (tx.amount == amount)`.
     - If matched: returns existing transaction record (safe replay).
     - If mismatched: rejects with `HTTP 409 Conflict`.
5. **Atomic Transaction Management**:
   - Debits and credits executed within an atomic transaction context.
   - Any failure rolls back both accounts and logs failure.
6. **Coercion Restricted Mode Isolation**:
   - If `session.is_restricted == True`:
     - Balances are untouched (zero debit, zero credit).
     - Transaction logged with `status="simulated_duress"` and `is_duress_masked=True`.
     - Internal `DuressEvent` and `SecurityEvent` generated for audit.

---

## 5. Android Client Presentation & Data Layer

1. **BigDecimal & Currency Formatting**:
   - Monetary amounts parsed via `BigDecimal(str)`.
   - Display formatted via `NumberFormat.getCurrencyInstance(Locale("en", "IN"))` producing proper INR formatting (e.g. `₹1,24,500.80`).
2. **Explicit Mode UI Toggle**:
   - Top bar indicates current mode: `[ Demo Mock ]` or `[ Backend Connected ]`.
   - Settings in Profile screen allows switching data sources for evaluation.
3. **Idempotency Lifecycle Manager**:
   - `TransferDraft` maintains a single `idempotencyKey = UUID.randomUUID().toString()`.
   - If transfer submission encounters a network error, the draft keeps the same key.
   - A new key is generated only when the user clears the form or starts a brand new transfer.
4. **Uncertain State & Reconciliation UI**:
   - On network timeout or connection reset:
     - UI displays: *"Transfer status uncertain. Server may have processed this request."*
     - Action buttons: *"Reconcile with Server"* (checks receipt using key) and *"Retry with Same Key"*.

---

## 6. Comprehensive Verification Test Plan

The Stage 4 implementation will be validated against eight explicit verification test suites:

| Test ID | Test Category | Detailed Scenario & Verification Criteria |
|---|---|---|
| **TEST-S4-01** | **INR Decimal Precision** | Verify operations with fractional amounts (e.g. `₹1,24,500.80` minus `₹49.99` equals `₹1,24,450.81`). Proves zero floating point rounding errors in both Python `Decimal` and Android `BigDecimal`. |
| **TEST-S4-02** | **Identical Idempotency Replay** | Submit transfer with key `K1`, then re-submit exact same payload with key `K1`. Verifies that HTTP 200 is returned with existing transaction receipt, and balance is deducted exactly once. |
| **TEST-S4-03** | **Mismatched Idempotency Replay** | Submit transfer of `₹500.00` with key `K2`. Submit second transfer of `₹1,000.00` using same key `K2`. Verifies server rejects request with `HTTP 409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`). |
| **TEST-S4-04** | **Timeout & Safe Reconciliation** | Simulate network drop during transfer; client calls reconcile endpoint with original key; server returns confirmed receipt without duplicate execution. |
| **TEST-S4-05** | **Daily Transfer Limit** | Execute transfers totaling `₹48,000.00`. Attempt additional transfer of `₹5,000.00`. Server rejects with `HTTP 422` (`DAILY_LIMIT_EXCEEDED`). |
| **TEST-S4-06** | **Session Authorization & Gatekeeper** | Verify banking endpoints return `HTTP 401 Unauthorized` for missing/expired/revoked tokens, and `HTTP 403 Forbidden` for restricted administrative actions. |
| **TEST-S4-07** | **No Silent Mode Switch** | In `BACKEND_CONNECTED` mode, force HTTP 500/timeout. Verify client throws explicit `BankingException` and displays retry/reconcile UI; verifies zero silent fallback to mock data. |
| **TEST-S4-08** | **Duress Transfer Isolation** | Execute transfer with restricted session token. Verify server records `simulated_duress`, zero balance change on either account, and logs internal `DuressEvent`. |
