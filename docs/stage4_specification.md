# Stage 4 Specification: Mock Banking Operations & Resilience Layer

> **SafeBank AI — PS-05 Passwordless & Coercion-Aware Banking Prototype**  
> **Status: REVISED PLAN (Addressing All Verification & Security Feedback)**

---

## 1. System Overview & Core Principles

Stage 4 delivers the complete mock banking functionality connecting the Android client and the FastAPI backend. It incorporates all security, privacy, and resilience requirements:

1. **Currency & Domain Model Alignment**:
   - Currency is exclusively **Indian Rupee (INR / ₹)**.
   - Account types are strictly aligned with the backend database schema: `checking` and `savings`.
   - Transaction types are strictly aligned with the backend schema: `transfer`, `payment`, `deposit`.
   - **Internal Transfer Scope**: Stage 4 strictly supports internal transfers between the same user's own `checking` and `savings` accounts. Third-party payees, external banks, and cross-user transfers are explicitly out of scope.
2. **Account Privacy & Masking**:
   - Ordinary account list responses (`GET /api/v1/banking/accounts`) **never** expose raw full account numbers.
   - Accounts are identified by immutable internal UUIDs and masked display strings (e.g. `"•••• 9481"`).
3. **Strict Monetary Precision**:
   - Backend: Python `decimal.Decimal`.
   - Database: SQLite `NUMERIC(14, 2)` with check constraints (`balance >= 0`, `amount > 0`).
   - Android: `java.math.BigDecimal` and validated 2-decimal strings (`"500.00"`).
   - JSON Transport: Monetary amounts transmitted strictly as fixed-scale decimal strings (e.g. `"1500.50"`).
   - `Float` and `Double` are strictly forbidden for financial calculations.
4. **Concurrent Idempotency & Complete Payload Validation**:
   - Exactly one UUIDv4 `idempotency_key` generated per logical user transfer intent.
   - Database unique constraint on `transactions.idempotency_key` ensures atomic concurrency deduplication.
   - The backend compares **all financially relevant request fields** (`from_account_id`, `to_account_id`, `amount`, and `currency`).
   - Same key + identical payload $\rightarrow$ returns original committed receipt.
   - Same key + mismatched payload $\rightarrow$ returns `HTTP 409 Conflict`.
5. **Three-State Safe Reconciliation Lifecycle**:
   - The reconciliation endpoint distinguishes:
     - `COMPLETED`: The transfer committed successfully $\rightarrow$ returns committed receipt.
     - `PROCESSING`: The request is still in progress $\rightarrow$ returns `HTTP 202 Accepted` with polling advisory; client does not duplicate.
     - `NOT_FOUND`: No request record exists with this key $\rightarrow$ returns `HTTP 404 Not Found`; safe to retry using the original key.
6. **Secure Development Session Bridge**:
   - Disabled by default; unavailable outside `development` or `test` environments (`ENVIRONMENT != "development"` $\rightarrow$ `HTTP 404`).
   - Restrained to loopback/local development networks (`127.0.0.1`, `10.0.2.2`).
   - Only pre-seeded, whitelisted development users and devices can obtain a session.
   - Enforces user-device binding and active status checks in the database.
   - Arbitrary or unseeded `user_id`/`device_id` values are rejected with `HTTP 403 Forbidden`.
7. **Configurable Daily Transfer Limit with Concurrency Protection**:
   - Server-side limit configured via `settings.DAILY_TRANSFER_LIMIT_INR` (default: `Decimal("50000.00")`).
   - Cumulative calculation executed within serializable/immediate atomic database transactions to prevent concurrent over-limit race conditions.
8. **Explicit Banking Data-Source Modes**:
   - Client defines two mutually exclusive modes: `DEMO_MOCK` and `BACKEND_CONNECTED`.
   - The active mode is prominently indicated in the Android UI.
   - **No Silent Fallback**: Network failures in `BACKEND_CONNECTED` mode never silently fall back to mock data or execute phantom transactions. Explicit safe retry and reconciliation workflows are provided.

---

## 2. Architecture & Concurrency Model

```
+-----------------------------------------------------------------------------------+
|                              ANDROID PRESENTATION LAYER                           |
|       HomeScreen               TransfersScreen                 HistoryScreen      |
|    (Balances in ₹)        (Internal Transfers in ₹)       (Filtered Tx in ₹)      |
+-----------------------------------------------------------------------------------+
                                          │
                                          ▼
+-----------------------------------------------------------------------------------+
|                            DOMAIN LAYER (USE CASES)                               |
|   GetAccountsUseCase         ExecuteTransferUseCase       GetTransactionsUseCase  |
|   (Returns masked accts)     (Generates Idempotency UUID) (Filter & Reconciliation|
+-----------------------------------------------------------------------------------+
                                          │
                                          ▼
+-----------------------------------------------------------------------------------+
|                         BANKING REPOSITORY INTERFACE                              |
|     getActiveMode(): BankingMode (DEMO_MOCK | BACKEND_CONNECTED)                  |
|     executeTransfer(request: TransferRequest): Result<TransferReceipt>            |
|     reconcileTransfer(idempotencyKey: String): Result<ReconcileResult>            |
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
                                              |  3. Verify Source & Dest Ownership  |
                                              |  4. Concurrent Daily Limit Check    |
                                              |  5. Compare 4 Financial Fields      |
                                              |  6. Atomic DB Transaction (NUMERIC) |
                                              |  7. Duress Mode Honeypot Isolation  |
                                              +-------------------------------------+
```

---

## 3. Data Models & API Contracts

### 3.1 Account Schema & Privacy Model
- **Account Model**:
  - `id`: String (UUIDv4)
  - `masked_account_number`: String (e.g. `"•••• 9481"`) — **full account number is hidden**
  - `account_type`: `"checking"` or `"savings"`
  - `balance`: Decimal string (e.g. `"124500.80"`)
  - `currency`: `"INR"`
  - `status`: `"active"`
  - `is_primary`: Boolean

### 3.2 JSON REST API Endpoints (`/api/v1/banking`)

#### A. Secure Development Session Creation
- **Endpoint**: `POST /api/v1/auth/dev-session`
- **Security Gates**:
  1. `settings.ENVIRONMENT in ("development", "test")` (if production $\rightarrow$ `HTTP 404 Not Found`).
  2. `settings.ENABLE_DEV_SESSION_ENDPOINT == True`.
  3. `client.host in ("127.0.0.1", "::1", "10.0.2.2", "localhost")`.
  4. Whitelist verification: `user_id` and `device_id` must match pre-seeded development entities:
     - `user_id`: `usr_dev_alex_chen` (Alex Chen)
     - `device_id`: `dev_pixel_8_pro_01` (Pixel 8 Pro Hardware Authenticator)
  5. Database integrity: queries database to ensure `user.is_active == True`, `device.status == "active"`, and `device.user_id == user.id`.
- **Request Body**:
  ```json
  {
    "user_id": "usr_dev_alex_chen",
    "device_id": "dev_pixel_8_pro_01"
  }
  ```
- **Response** (`HTTP 200 OK`):
  ```json
  {
    "access_token": "sb_session_32byte_secure_hex...",
    "token_type": "bearer",
    "expires_in": 3600,
    "user_id": "usr_dev_alex_chen",
    "is_restricted": false
  }
  ```

#### B. Get User Accounts (Privacy-Preserving)
- **Endpoint**: `GET /api/v1/banking/accounts`
- **Headers**: `Authorization: Bearer <token>`
- **Response** (`HTTP 200 OK`):
  ```json
  [
    {
      "id": "acc-checking-01",
      "masked_account_number": "•••• 9481",
      "account_type": "checking",
      "balance": "124500.80",
      "currency": "INR",
      "status": "active",
      "is_primary": true
    },
    {
      "id": "acc-savings-02",
      "masked_account_number": "•••• 9482",
      "account_type": "savings",
      "balance": "350000.00",
      "currency": "INR",
      "status": "active",
      "is_primary": false
    }
  ]
  ```

#### C. Execute Internal Transfer
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
    "description": "Internal transfer to savings"
  }
  ```
- **Response Matrix**:
  - **Success** (`HTTP 201 Created` or `HTTP 200 OK` on identical replay):
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
      "message": "Internal transfer completed successfully."
    }
    ```
  - **Mismatched Idempotency Key** (`HTTP 409 Conflict`):
    Triggered when `idempotency_key` matches an existing record but ANY of `from_account_id`, `to_account_id`, `amount`, or `currency` differs:
    ```json
    {
      "detail": "IDEMPOTENCY_PAYLOAD_MISMATCH: The provided idempotency key was previously submitted with different financial parameters."
    }
    ```
  - **Cross-User Debit/Credit Attempt** (`HTTP 403 Forbidden`):
    ```json
    {
      "detail": "CROSS_USER_TRANSFER_UNAUTHORIZED: Stage 4 strictly supports internal transfers between user's own checking and savings accounts."
    }
    ```
  - **Daily Limit Exceeded** (`HTTP 422 Unprocessable Entity`):
    ```json
    {
      "detail": "DAILY_LIMIT_EXCEEDED: Daily transfer limit of ₹50,000.00 exceeded. Cumulative completed today: ₹48,000.00, requested: ₹5,000.00."
    }
    ```
  - **Simulated Duress** (`HTTP 200 OK`):
    Under restricted mode (`session.is_restricted == True`), returns simulated receipt to protect user, maintains zero balance change, and records internal `DuressEvent`.

#### D. Three-State Reconciliation Endpoint
- **Endpoint**: `GET /api/v1/banking/transfers/reconcile/{idempotency_key}`
- **Headers**: `Authorization: Bearer <token>`
- **Response Matrix**:
  - **State 1: `COMPLETED`** (`HTTP 200 OK`):
    The transfer was committed. Returns the full original transaction receipt.
    ```json
    {
      "state": "COMPLETED",
      "receipt": {
        "transaction_id": "tx-8f92-uuid",
        "reference_number": "TX-20261110-8F92A",
        "status": "completed",
        "amount": "5000.00",
        "currency": "INR",
        "completed_at": "2026-11-10T15:45:00Z"
      }
    }
    ```
  - **State 2: `PROCESSING`** (`HTTP 202 Accepted`):
    The transfer request is currently being processed by the atomic engine. Client must wait/poll; it **must not** retry or cancel.
    ```json
    {
      "state": "PROCESSING",
      "message": "Transfer request is currently in progress. Do not submit a duplicate transfer."
    }
    ```
  - **State 3: `NOT_FOUND`** (`HTTP 404 Not Found`):
    No request record exists with this idempotency key. Safe for the client to retry using the original key or cancel.
    ```json
    {
      "state": "NOT_FOUND",
      "message": "No transfer record found for this idempotency key. It is safe to retry."
    }
    ```

---

## 4. Server-Side Security & Concurrency Rules

1. **Concurrent Idempotency Enforcement**:
   - `Transaction.idempotency_key` carries a SQLite `UNIQUE` index constraint.
   - When a transfer begins:
     1. The service checks for an existing record with `idempotency_key`.
     2. If found:
        - Compares all 4 financial fields: `from_account_id`, `to_account_id`, `amount`, `currency`.
        - If identical and status is `completed` / `simulated_duress` $\rightarrow$ returns existing receipt.
        - If identical and status is `pending` $\rightarrow$ returns processing advisory.
        - If any field differs $\rightarrow$ raises `HTTP 409 Conflict`.
     3. If not found:
        - The atomic block attempts insertion. If two concurrent workers race with the same key, SQLite's unique constraint causes the second worker to raise `IntegrityError`.
        - The second worker catches the integrity error, rolls back its nested transaction, and re-reads the committed row, guaranteeing exactly one transfer execution.
2. **Account Ownership Verification**:
   - `from_account.user_id == session.user_id` (sender owns source).
   - `to_account.user_id == session.user_id` (sender owns destination).
   - `from_account.account_type != to_account.account_type` (e.g. checking $\rightarrow$ savings).
   - Both accounts have `status == "active"`.
3. **Configurable Daily Limit with Concurrency Protection**:
   - Configurable setting: `settings.DAILY_TRANSFER_LIMIT_INR` (default: `Decimal("50000.00")`).
   - Cumulative calculation executed within serializable/immediate atomic database transactions (`BEGIN IMMEDIATE` / write lock):
     ```python
     today_start = utc_now().replace(hour=0, minute=0, second=0, microsecond=0)
     daily_sum = db.query(func.coalesce(func.sum(Transaction.amount), Decimal("0.00"))).filter(
         Transaction.from_account_id == from_account_id,
         Transaction.status.in_(["completed", "pending"]),
         Transaction.created_at >= today_start
     ).scalar()
     ```
   - Two concurrent transfers of ₹30,000 each evaluate sequentially against the lock: request 1 succeeds, pushing the daily sum to ₹30,000; request 2 evaluates ₹30,000 + ₹30,000 = ₹60,000 > ₹50,000, and is rejected with `HTTP 422`.

---

## 5. Comprehensive Verification Test Plan

| Test ID | Test Category | Detailed Verification Scenario & Acceptance Criteria |
|---|---|---|
| **TEST-S4-01** | **INR Decimal Precision** | Verify operations with fractional amounts (e.g. `₹1,24,500.80` minus `₹49.99` equals `₹1,24,450.81`). Proves zero floating-point rounding errors across Python `Decimal`, SQLite `NUMERIC`, and Android `BigDecimal`. |
| **TEST-S4-02** | **Identical Idempotency Replay** | Submit transfer with key `K1`, then re-submit exact same 4 financial fields with key `K1`. Verifies `HTTP 200` returns original receipt, and balances are debited/credited exactly once. |
| **TEST-S4-03** | **Mismatched Idempotency Replay** | Submit transfer of `₹500.00` with key `K2`. Submit second transfer of `₹1,000.00` using same key `K2`. Verifies server rejects request with `HTTP 409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`). |
| **TEST-S4-04** | **Concurrent Duplicate-Request Race** | Launch 5 concurrent threads/tasks simultaneously submitting the exact same transfer payload and key `K1`. Verifies SQLite unique constraint prevents double-execution: exactly 1 transaction commits, and all 5 threads receive the identical receipt. |
| **TEST-S4-05** | **Reconciliation: COMPLETED** | Query reconcile endpoint for an existing completed transfer. Verifies returns `state: COMPLETED` with the full committed receipt. |
| **TEST-S4-06** | **Reconciliation: PROCESSING** | Query reconcile endpoint while a transfer transaction is in `pending` status. Verifies returns `HTTP 202 Accepted` with `state: PROCESSING` and polling advisory. |
| **TEST-S4-07** | **Reconciliation: NOT_FOUND** | Query reconcile endpoint for an unrecorded idempotency key. Verifies returns `HTTP 404 Not Found` with `state: NOT_FOUND`, confirming the key is safe to retry. |
| **TEST-S4-08** | **Dev Session Bridge Security** | 1. Set `ENVIRONMENT="production"`; verify `/api/v1/auth/dev-session` returns `HTTP 404`.<br>2. In development, submit arbitrary `user_id="hacker"`; verify returns `HTTP 403 Forbidden`.<br>3. Submit pre-seeded `usr_dev_alex_chen`; verify returns valid session token. |
| **TEST-S4-09** | **Account Privacy Verification** | Call `GET /api/v1/banking/accounts`. Inspect JSON output. Verify raw full account numbers are omitted; only `masked_account_number` (`"•••• 9481"`) and UUID are returned. |
| **TEST-S4-10** | **Strict Internal Transfer Scope** | Attempt transfer where `to_account_id` belongs to another user. Server rejects with `HTTP 403 Forbidden` (`CROSS_USER_TRANSFER_UNAUTHORIZED`). |
| **TEST-S4-11** | **Configurable Daily Limit Race** | Configure limit to ₹50,000. Launch two concurrent transfers of ₹30,000 each. Verify serialized database locking ensures exactly one succeeds and the second is rejected with `HTTP 422` (`DAILY_LIMIT_EXCEEDED`). |
| **TEST-S4-12** | **No Silent Mode Switch** | In `BACKEND_CONNECTED` mode, simulate network failure/timeout. Verify Android client does NOT fall back to mock data or execute a local transfer; displays explicit retry/reconcile UI. |
| **TEST-S4-13** | **Duress Transfer Isolation** | Execute transfer under restricted session (`is_restricted=True`). Verify server records `status="simulated_duress"`, leaves both account balances unchanged, logs internal `DuressEvent`, and returns simulated success receipt. |
