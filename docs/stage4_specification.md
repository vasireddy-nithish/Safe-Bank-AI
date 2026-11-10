# Stage 4 Specification: Mock Banking Operations & Resilience Layer

> **SafeBank AI — PS-05 Passwordless & Coercion-Aware Banking Prototype**  
> **Status: REVISED SPECIFICATION (Addressing All Final Verification, Serialization & Reconciliation Requirements)**

---

## 1. System Overview & Core Principles

Stage 4 delivers the complete mock banking functionality connecting the Android client and the FastAPI backend. It incorporates all security, privacy, resilience, and concurrency requirements:

1. **Currency & Domain Model Alignment**:
   - Currency is exclusively **Indian Rupee (INR / ₹)**.
   - Account types are strictly aligned with the backend database schema: `checking` and `savings`.
   - Transaction types are strictly aligned with the backend schema: `transfer`, `payment`, `deposit`.
   - **Internal Transfer Scope**: Stage 4 strictly supports internal transfers between the same authenticated user's own `checking` and `savings` accounts. Third-party payees, external banks, and cross-user transfers are explicitly rejected.
2. **Account Privacy & Masking**:
   - Ordinary account list responses (`GET /api/v1/banking/accounts`) **never** expose raw full account numbers.
   - Accounts are identified by immutable 36-character UUIDv4 identifiers and masked display strings (e.g. `"•••• 9481"`).
3. **Strict Monetary Precision**:
   - Backend: Python `decimal.Decimal`.
   - Database: SQLite `NUMERIC(14, 2)` with check constraints (`balance >= 0`, `amount > 0`).
   - Android: `java.math.BigDecimal` and validated 2-decimal strings (`"500.00"`).
   - JSON Transport: Monetary amounts transmitted strictly as fixed-scale decimal strings (e.g. `"1500.50"`).
   - `Float` and `Double` are strictly forbidden for financial calculations.
4. **Durable Two-Phase Idempotency Reservation with Lease Recovery**:
   - Exactly one UUIDv4 `idempotency_key` generated per logical user transfer intent.
   - **Phase 1 (Reservation Commit)**: Before executing the financial transaction, the backend inserts and commits an `IdempotencyReservation` record with `status='PROCESSING'` and an active lease (`lease_expires_at = utc_now() + 30s`).
   - **Observable In-Flight State**: Concurrent reconciliation queries observe the committed `PROCESSING` state rather than returning a false `NOT_FOUND`.
   - **Phase 2 (Financial Execution Commit)**: Outermost atomic financial transaction executes debit/credit, inserts the transaction record, transitions the reservation to `COMPLETED`, and commits.
   - **Complete Payload Validation**: Backend compares all **4 financially relevant request fields** (`from_account_id`, `to_account_id`, `amount`, and `currency`). Mismatches return `HTTP 409 Conflict`.
   - **Safe Lease-Based Crash Recovery**: Abandoned `PROCESSING` records after a server crash inspect whether funds were actually moved before safely recovering without double-debit.
5. **Four-State Safe Reconciliation Lifecycle**:
   - The reconciliation endpoint (`GET /api/v1/banking/transfers/reconcile/{idempotency_key}`) distinguishes:
     - `COMPLETED`: The transfer committed successfully $\rightarrow$ returns `HTTP 200 OK` with full committed receipt.
     - `PROCESSING`: The transfer request is currently in flight or within active lease $\rightarrow$ returns `HTTP 202 Accepted` with polling advisory; client does not duplicate.
     - `FAILED`: The transfer failed permanently (e.g., insufficient funds, daily limit exceeded) $\rightarrow$ returns `HTTP 200 OK` with terminal error details.
     - `NOT_FOUND`: No reservation or transaction record exists with this key $\rightarrow$ returns `HTTP 404 Not Found`; safe to retry using the original key.
6. **SQLite Transaction Serialization (`BEGIN IMMEDIATE`)**:
   - All outermost write transactions explicitly begin with `BEGIN IMMEDIATE` via SQLAlchemy connection listeners.
   - Eliminates deferred lock escalation deadlocks between concurrent worker sessions.
   - Concurrent workers that race on reservation insertion catch `IntegrityError`, execute rollback, clear session cache, and re-read the committed reservation state from a fresh session.
7. **Strict 36-Character UUIDv4 Identifier Standard**:
   - All database primary keys (`User.id`, `Account.id`, `Transaction.id`, `Device.id`, `Session.id`, `IdempotencyReservation.id`) are canonical **36-character UUIDv4 strings** (`String(36)`).
   - Human-readable identifiers (e.g. `usr_dev_alex_chen`) are defined strictly as seed aliases mapped to deterministic 36-character UUIDv4 strings.
8. **Configurable Daily Transfer Limit with Concurrency Protection**:
   - Server-side limit configured via `settings.DAILY_TRANSFER_LIMIT_INR` (default: `Decimal("50000.00")`).
   - Cumulative calculation executed within `BEGIN IMMEDIATE` write lock to prevent concurrent over-limit race conditions.
   - **Non-Duress Accounting**: Only `status == 'completed'` transactions consume the daily limit; `simulated_duress` transactions **never** consume the limit because zero money moved.
9. **Secure Development Session Bridge**:
   - Disabled by default and unavailable outside `development` or `test` environments (`ENVIRONMENT != "development"` $\rightarrow$ `HTTP 404`).
   - Restrained to loopback/local development networks (`127.0.0.1`, `10.0.2.2`).
   - Only pre-seeded development entities can obtain a session.
10. **Explicit Banking Data-Source Modes**:
    - Client defines two mutually exclusive modes: `DEMO_MOCK` and `BACKEND_CONNECTED`.
    - Active mode is prominently indicated in the Android UI.
    - **No Silent Fallback**: Network failures in `BACKEND_CONNECTED` mode never silently fall back to mock data or execute phantom transactions.

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
                                              |  3. Phase 1: Reserve Key & Commit   |
                                              |     (State: PROCESSING, Lease 30s)  |
                                              |  4. Phase 2: BEGIN IMMEDIATE Lock   |
                                              |     - Verify Account Ownership      |
                                              |     - Check Solvency & Daily Limit  |
                                              |     - Debit/Credit or Duress Mask   |
                                              |     - Update State to COMPLETED     |
                                              |  5. Commit Outermost Transaction    |
                                              +-------------------------------------+
```

---

## 3. Data Models & API Contracts

### 3.1 Strict Identifier Formats & Deterministic Seed Aliases

All database tables enforce canonical 36-character UUIDv4 strings. For developer ergonomics and deterministic automated testing, human-readable seed aliases map directly to fixed UUIDv4 constants:

| Entity | Human Seed Alias | Canonical Database UUIDv4 (`String(36)`) |
|---|---|---|
| **User (Alex Chen)** | `usr_dev_alex_chen` | `00000000-0000-4000-a000-000000000001` |
| **Checking Account** | `acc-checking-01` | `00000000-0000-4000-a000-000000000011` |
| **Savings Account** | `acc-savings-02` | `00000000-0000-4000-a000-000000000012` |
| **Device (Pixel 8 Pro)** | `dev_pixel_8_pro_01` | `00000000-0000-4000-a000-000000000021` |

The development session bridge accepts either the canonical UUIDv4 or the known seed alias, resolving it to the canonical UUIDv4 before database querying. All API responses, Android DTOs, and foreign keys strictly transmit canonical 36-character UUIDv4 strings.

---

### 3.2 Account Schema & Privacy Model
- **Account Model**:
  - `id`: String (UUIDv4: `00000000-0000-4000-a000-000000000011`)
  - `masked_account_number`: String (e.g. `"•••• 9481"`) — **full account number is hidden**
  - `account_type`: `"checking"` or `"savings"`
  - `balance`: Decimal string (e.g. `"124500.80"`)
  - `currency`: `"INR"`
  - `status`: `"active"`
  - `is_primary`: Boolean

---

### 3.3 Durable Idempotency Reservation Schema

The `IdempotencyReservation` table guarantees cross-session observability and crash recovery:

```python
class IdempotencyReservation(Base, TimestampMixin):
    __tablename__ = "idempotency_reservations"
    __table_args__ = (
        CheckConstraint(
            "status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED_ABANDONED')",
            name="check_reservation_status_valid"
        ),
    )

    idempotency_key: Mapped[str] = mapped_column(String(128), primary_key=True, index=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    from_account_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), nullable=False)
    to_account_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.id", ondelete="RESTRICT"), nullable=False)
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="INR", nullable=False)

    status: Mapped[str] = mapped_column(String(32), default="PROCESSING", index=True, nullable=False)
    error_code: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    error_message: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)

    transaction_id: Mapped[Optional[str]] = mapped_column(String(36), ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True)
    lease_expires_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
```

---

### 3.4 JSON REST API Endpoints (`/api/v1/banking`)

#### A. Secure Development Session Creation
- **Endpoint**: `POST /api/v1/auth/dev-session`
- **Security Gates**:
  1. `settings.ENVIRONMENT in ("development", "test")` (if production $\rightarrow$ `HTTP 404 Not Found`).
  2. `settings.ENABLE_DEV_SESSION_ENDPOINT == True`.
  3. `client.host in ("127.0.0.1", "::1", "10.0.2.2", "localhost")`.
  4. Whitelist verification: `user_id` and `device_id` match pre-seeded entities.
- **Request Body**:
  ```json
  {
    "user_id": "00000000-0000-4000-a000-000000000001",
    "device_id": "00000000-0000-4000-a000-000000000021"
  }
  ```
- **Response** (`HTTP 200 OK`):
  ```json
  {
    "access_token": "sb_session_32byte_secure_hex...",
    "token_type": "bearer",
    "expires_in": 3600,
    "user_id": "00000000-0000-4000-a000-000000000001",
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
      "id": "00000000-0000-4000-a000-000000000011",
      "masked_account_number": "•••• 9481",
      "account_type": "checking",
      "balance": "124500.80",
      "currency": "INR",
      "status": "active",
      "is_primary": true
    },
    {
      "id": "00000000-0000-4000-a000-000000000012",
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
    "from_account_id": "00000000-0000-4000-a000-000000000011",
    "to_account_id": "00000000-0000-4000-a000-000000000012",
    "amount": "5000.00",
    "currency": "INR",
    "description": "Internal transfer to savings"
  }
  ```
- **Response Matrix**:
  - **Success** (`HTTP 201 Created` or `HTTP 200 OK` on replay):
    ```json
    {
      "success": true,
      "transaction_id": "11111111-2222-4000-a000-333333333333",
      "reference_number": "TX-20261110-8F92A",
      "status": "completed",
      "from_account_id": "00000000-0000-4000-a000-000000000011",
      "to_account_id": "00000000-0000-4000-a000-000000000012",
      "amount": "5000.00",
      "currency": "INR",
      "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567",
      "completed_at": "2026-11-10T15:45:00Z",
      "message": "Internal transfer completed successfully."
    }
    ```
  - **Mismatched Idempotency Key** (`HTTP 409 Conflict`):
    ```json
    {
      "detail": "IDEMPOTENCY_PAYLOAD_MISMATCH: The provided idempotency key was previously submitted with different financial parameters."
    }
    ```
  - **Daily Limit Exceeded** (`HTTP 422 Unprocessable Entity`):
    ```json
    {
      "detail": "DAILY_LIMIT_EXCEEDED: Daily transfer limit of ₹50,000.00 exceeded. Cumulative completed today: ₹48,000.00, requested: ₹5,000.00."
    }
    ```

#### D. Four-State Reconciliation Endpoint
- **Endpoint**: `GET /api/v1/banking/transfers/reconcile/{idempotency_key}`
- **Headers**: `Authorization: Bearer <token>`
- **Response Matrix**:
  - **State 1: `COMPLETED`** (`HTTP 200 OK`):
    ```json
    {
      "state": "COMPLETED",
      "receipt": {
        "transaction_id": "11111111-2222-4000-a000-333333333333",
        "reference_number": "TX-20261110-8F92A",
        "status": "completed",
        "from_account_id": "00000000-0000-4000-a000-000000000011",
        "to_account_id": "00000000-0000-4000-a000-000000000012",
        "amount": "5000.00",
        "currency": "INR",
        "completed_at": "2026-11-10T15:45:00Z"
      }
    }
    ```
  - **State 2: `PROCESSING`** (`HTTP 202 Accepted`):
    ```json
    {
      "state": "PROCESSING",
      "message": "Transfer request is currently in progress. Do not submit a duplicate transfer."
    }
    ```
  - **State 3: `FAILED`** (`HTTP 200 OK`):
    ```json
    {
      "state": "FAILED",
      "error_code": "INSUFFICIENT_FUNDS",
      "error_message": "Insufficient available balance to complete transfer."
    }
    ```
  - **State 4: `NOT_FOUND`** (`HTTP 404 Not Found`):
    ```json
    {
      "state": "NOT_FOUND",
      "message": "No transfer record found for this idempotency key. It is safe to retry."
    }
    ```

---

## 4. Server-Side Security, Concurrency & Lifecycle Rules

### 4.1 Two-Phase Durable Idempotency Lifecycle & Reconciliation Race Prevention

To resolve the reconciliation race condition where uncommitted transactions in progress are invisible to concurrent sessions, SafeBank AI employs a durable two-phase commit protocol:

```
CLIENT                              BACKEND ENGINE                           DATABASE
  │                                       │                                      │
  │─── POST /transfers (Key K1) ─────────>│                                      │
  │                                       │─── Dedicated Short Tx:               │
  │                                       │    INSERT Reservation (PROCESSING) ──>│
  │                                       │    COMMIT (Phase 1) ─────────────────>│ [COMMITTED]
  │                                       │                                      │
  │   [Network Timeout on Client]         │                                      │
  │                                       │─── BEGIN IMMEDIATE (Phase 2):        │
  │─── GET /reconcile/K1 (Session 2) ────>│    Lock Acquired                     │
  │                                       │    Debit/Credit Balances             │
  │<── HTTP 202 (state: PROCESSING) ──────│    INSERT Transaction                │
  │    (Reads COMMITTED Phase 1 row!)     │    UPDATE Reservation (COMPLETED)    │
  │                                       │    COMMIT (Phase 2) ─────────────────>│ [COMMITTED]
  │                                       │                                      │
  │─── GET /reconcile/K1 (Retry) ────────>│                                      │
  │<── HTTP 200 (state: COMPLETED) ───────│─── SELECT Reservation (COMPLETED) ───>│
```

#### Detailed Execution Sequence:
1. **Phase 1: Reserve Key & Immediate Commit**:
   - In a short, dedicated database transaction, query `IdempotencyReservation` by `idempotency_key`.
   - **If Existing Reservation Found**:
     - Compare the 4 financially relevant fields: `from_account_id`, `to_account_id`, `amount`, and `currency`.
     - **Mismatch**: If any field differs, immediately return `HTTP 409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`).
     - **Match**:
       - If `status == "COMPLETED"`: return original committed transaction receipt (`HTTP 200 OK`).
       - If `status == "FAILED"`: return recorded failure receipt (`HTTP 200 OK` or `HTTP 422`).
       - If `status == "PROCESSING"`:
         - **Active Lease** (`utc_now() < lease_expires_at`): return `HTTP 202 Accepted` (`state: PROCESSING`).
         - **Expired Lease** (`utc_now() >= lease_expires_at`): trigger **Abandoned Reservation Recovery**:
           - Check if a committed `Transaction` row exists for `idempotency_key`.
           - If found: update reservation to `COMPLETED` and return receipt.
           - If not found: previous worker crashed before financial commit; renew lease (`lease_expires_at = utc_now() + 30s`) and proceed to Phase 2. Zero money moved, so double-debit is physically impossible.
   - **If No Reservation Found**:
     - Insert `IdempotencyReservation(idempotency_key=key, status='PROCESSING', lease_expires_at=utc_now() + 30s, ...)`.
     - **Commit immediately**.
2. **Phase 2: Outermost Financial Execution**:
   - Begin outermost write transaction with `BEGIN IMMEDIATE`.
   - Validate account ownership, active status, solvency, and cumulative daily limit.
   - **If Validation Fails**:
     - Update reservation to `status='FAILED'`, record `error_code`, and commit.
     - Return appropriate error response (`HTTP 422`).
   - **If Duress Mode** (`session.is_restricted == True`):
     - Balances are untouched (zero movement).
     - Insert `Transaction(status='simulated_duress')`, `DuressEvent`, and `SecurityEvent`.
     - Update reservation to `status='COMPLETED'`, `transaction_id=tx.id`, and commit.
     - Return simulated success receipt.
   - **If Normal Mode**:
     - Debit sender account (`from_account.balance -= amount`).
     - Credit receiver account (`to_account.balance += amount`).
     - Insert `Transaction(status='completed')`.
     - Update reservation to `status='COMPLETED'`, `transaction_id=tx.id`, and commit.
     - Return completed receipt (`HTTP 201 Created`).

---

### 4.2 SQLite Transaction Serialization (`BEGIN IMMEDIATE`)

In multi-threaded or multi-process environments, SQLite's default deferred transaction mode (`BEGIN DEFERRED`) allows concurrent readers to start transactions that subsequently fail with `sqlite3.OperationalError: database is locked` upon upgrade to write locks.

SafeBank AI mandates `BEGIN IMMEDIATE` serialization:
1. **Engine Listener**:
   ```python
   @event.listens_for(engine, "begin")
   def do_begin(conn):
       conn.exec_driver_sql("BEGIN IMMEDIATE")
   ```
2. **Busy Timeout**:
   - Connection URL sets `?timeout=15` and executes `PRAGMA busy_timeout = 5000;`.
3. **Concurrent Worker Handling**:
   - If two concurrent threads simultaneously attempt Phase 1 reservation with the same `idempotency_key`:
     - Thread 1 succeeds and commits the `PROCESSING` reservation.
     - Thread 2 hits the SQLite primary key / unique index constraint on `idempotency_reservations.idempotency_key` and raises SQLAlchemy `IntegrityError`.
     - Thread 2 catches `IntegrityError`, executes `db.rollback()`, clears/refreshes its database session, and queries the database.
     - Thread 2 immediately sees Thread 1's committed reservation and returns `HTTP 202 Accepted` (`PROCESSING`) or `HTTP 200 OK` (`COMPLETED`) without moving funds.

---

### 4.3 Daily-Limit Accounting & Concurrency Protection

- **Rule**: Only transfers with `status == 'completed'` that genuinely move funds consume the configurable daily limit (`settings.DAILY_TRANSFER_LIMIT_INR`, default `₹50,000.00`).
- **Duress Isolation**: `simulated_duress` transactions **never** consume the daily limit because no money moves.
- **Immediate Write Lock Protection**:
  The cumulative sum is calculated within the `BEGIN IMMEDIATE` write lock:
  ```python
  today_start = utc_now().replace(hour=0, minute=0, second=0, microsecond=0)
  daily_sum = db.query(func.coalesce(func.sum(Transaction.amount), Decimal("0.00"))).filter(
      Transaction.from_account_id == from_account_id,
      Transaction.status == "completed",  # Strictly 'completed' only!
      Transaction.created_at >= today_start
  ).scalar()
  ```
  If `daily_sum + requested_amount > settings.DAILY_TRANSFER_LIMIT_INR`:
  - Outermost transaction updates reservation to `status='FAILED'`, `error_code='DAILY_LIMIT_EXCEEDED'`.
  - Transaction commits and returns `HTTP 422 Unprocessable Entity`.
  - Concurrent requests evaluate sequentially against the lock: Request 1 commits ₹30,000; Request 2 evaluates ₹30,000 + ₹30,000 = ₹60,000 > ₹50,000 and is rejected.

---

## 5. Comprehensive Verification Test Plan

| Test ID | Test Category | Detailed Verification Scenario & Acceptance Criteria |
|---|---|---|
| **TEST-S4-01** | **INR Decimal Precision** | Verify operations with fractional amounts (e.g. `₹1,24,500.80` minus `₹49.99` equals `₹1,24,450.81`). Proves zero floating-point rounding errors across Python `Decimal`, SQLite `NUMERIC(14,2)`, and Android `BigDecimal`. |
| **TEST-S4-02** | **Identical Idempotency Replay** | Submit transfer with key `K1`, then re-submit exact same 4 financial fields with key `K1`. Verifies `HTTP 200` returns original receipt, and database balances are debited/credited exactly once. |
| **TEST-S4-03** | **Mismatched Idempotency Replay** | Submit transfer of `₹500.00` with key `K2`. Submit second transfer of `₹1,000.00` using same key `K2`. Verifies server rejects request with `HTTP 409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`). |
| **TEST-S4-04** | **Reconciliation In Flight (`PROCESSING`)** | Using a separate database session, trigger a transfer and inspect the reconcile endpoint while reservation is in committed `PROCESSING` state. Verifies returns `HTTP 202 Accepted` with `state: PROCESSING` and polling advisory. |
| **TEST-S4-05** | **Server Interruption & Abandoned Lease Recovery** | Simulate server crash after Phase 1 commit leaving an abandoned `PROCESSING` reservation. Advance time past `lease_expires_at` (30s). Submit retry / reconciliation. Verifies recovery detects no financial transaction was committed, renews lease, executes safely, and moves funds exactly once without double-debit. |
| **TEST-S4-06** | **Concurrent Duplicate Race with File-Backed SQLite** | Using file-backed SQLite and independent worker sessions, launch 5 concurrent threads simultaneously submitting the exact same transfer payload and key `K1`. Verifies `IntegrityError` is handled, exactly 1 transaction commits in the database, and all 5 threads receive the identical receipt. |
| **TEST-S4-07** | **Client Retry After Uncertain Outcome** | Client submits transfer, simulates a network drop before receiving HTTP response. Client retries with identical payload and key. Verifies server returns original receipt with zero balance change on retry. |
| **TEST-S4-08** | **Concurrent Daily Limit Verification** | Configure limit to ₹50,000. Launch two concurrent transfers of ₹30,000 each. Verify serialized database locking ensures exactly one succeeds and the second is rejected with `HTTP 422` (`DAILY_LIMIT_EXCEEDED`). Directly inspect database balances to verify only ₹30,000 was debited. |
| **TEST-S4-09** | **Duress Operations Excluded From Daily Limit** | Under a restricted session, execute a simulated duress transfer of ₹45,000. Verify database balances remain untouched and status is `simulated_duress`. Then execute a normal transfer of ₹30,000 in a normal session. Verifies normal transfer succeeds because duress operations consume zero daily limit. |
| **TEST-S4-10** | **Reconciliation: COMPLETED & FAILED** | Query reconcile endpoint for completed and failed transfers. Verifies returns `state: COMPLETED` with receipt, or `state: FAILED` with error code. |
| **TEST-S4-11** | **Reconciliation: NOT_FOUND** | Query reconcile endpoint for an unrecorded idempotency key. Verifies returns `HTTP 404 Not Found` with `state: NOT_FOUND`, confirming the key is safe to retry. |
| **TEST-S4-12** | **Dev Session Bridge Security & Whitelist** | 1. Set `ENVIRONMENT="production"`; verify `/api/v1/auth/dev-session` returns `HTTP 404`.<br>2. In development, submit arbitrary unseeded `user_id`; verify returns `HTTP 403 Forbidden`.<br>3. Submit pre-seeded Alex Chen UUID (`00000000-0000-4000-a000-000000000001`); verify returns valid session token. |
| **TEST-S4-13** | **Account Privacy Verification** | Call `GET /api/v1/banking/accounts`. Inspect JSON output. Verify raw full account numbers are omitted; only `masked_account_number` (`"•••• 9481"`) and UUID are returned. |
| **TEST-S4-14** | **Strict Internal Transfer Scope** | Attempt transfer where `to_account_id` belongs to another user. Server rejects with `HTTP 403 Forbidden` (`CROSS_USER_TRANSFER_UNAUTHORIZED`). |
| **TEST-S4-15** | **No Silent Mode Switch** | In `BACKEND_CONNECTED` mode, simulate network failure/timeout. Verify Android client does NOT fall back to mock data or execute a local transfer; displays explicit retry/reconcile UI. |
