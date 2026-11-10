# Stage 4 Specification: Mock Banking Operations & Resilience Layer

> **SafeBank AI — PS-05 Passwordless & Coercion-Aware Banking Prototype**  
> **Status: REVISED SPECIFICATION (Addressing DBAPI Autocommit, Scoped BEGIN IMMEDIATE Execution Options & Statement Tracing)**

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
4. **Durable Two-Phase Idempotency Reservation with Lease Fencing Tokens**:
   - Exactly one UUIDv4 `idempotency_key` generated per logical user transfer intent.
   - **Phase 1 (Reservation Commit)**: Before executing the financial transaction, the backend inserts and commits an `IdempotencyReservation` record with `status='PROCESSING'`, an initial `lease_generation = 1`, a unique `owner_worker_id`, and an active lease (`lease_expires_at = utc_now() + 30s`).
   - **Observable In-Flight State**: Concurrent reconciliation queries observe the committed `PROCESSING` state rather than returning a false `NOT_FOUND`.
   - **Atomic Lease Takeover & Fencing Tokens**: If a worker crashes or pauses beyond lease expiry, another worker claims ownership via an atomic Compare-And-Swap (CAS) incrementing `lease_generation`.
   - **Phase 2 Complete 5-Point Fencing & Lease Expiry Check**: Inside `BEGIN IMMEDIATE`, the worker validates all 5 conditions before any balance changes:
     1. Matching `idempotency_key`
     2. Matching `lease_generation`
     3. Matching `owner_worker_id`
     4. `status == 'PROCESSING'`
     5. `lease_expires_at > utc_now()` (strictly active and unexpired).
   - If any condition fails (including an expired lease with no competing worker), the worker rolls back immediately without touching balances.
   - **Complete Payload Validation**: Backend compares all **4 financially relevant request fields** (`from_account_id`, `to_account_id`, `amount`, and `currency`). Mismatches return `HTTP 409 Conflict`.
5. **Four-State Safe Reconciliation Lifecycle**:
   - The reconciliation endpoint (`GET /api/v1/banking/transfers/reconcile/{idempotency_key}`) distinguishes:
     - `COMPLETED`: The transfer committed successfully $\rightarrow$ returns `HTTP 200 OK` with full committed receipt.
     - `PROCESSING`: The transfer request is currently in flight or within active lease $\rightarrow$ returns `HTTP 202 Accepted` with polling advisory; client does not duplicate.
     - `FAILED`: The transfer failed permanently (e.g., insufficient funds, daily limit exceeded) $\rightarrow$ returns `HTTP 200 OK` with terminal error details.
     - `NOT_FOUND`: No reservation or transaction record exists with this key $\rightarrow$ returns `HTTP 404 Not Found`; safe to retry using the original key.
6. **Robust SQLite Engine Configuration (`DBAPI Autocommit` + Scoped `BEGIN IMMEDIATE`)**:
   - **DBAPI Autocommit Mode**: `dbapi_connection.isolation_level = None` is set on connect to completely disable Python `sqlite3` implicit transaction emission (`BEGIN`).
   - **First Statement Guarantee**: `BEGIN IMMEDIATE` is emitted by the SQLAlchemy engine `begin` listener driven by execution options (`sqlite_write_immediate=True`), guaranteeing it is the **actual first SQL statement** on the connection without nested transactions.
   - **Zero Silent Commits**: `write_transaction(session)` strictly forbids invocation on an active transaction, raising `ActiveTransactionError` if `session.in_transaction()` is `True`.
   - **Read-Only Concurrency**: Standard read transactions run under deferred `BEGIN`, preserving lock-free concurrency for readers.
7. **SQLite Lock Contention & `SQLITE_BUSY` Resilience**:
   - Connection timeout set to 15s with `PRAGMA busy_timeout = 5000;`.
   - Handles `sqlite3.OperationalError` (`database is locked` / `SQLITE_BUSY`) with bounded retries (max 3 attempts) and jittered exponential backoff.
   - Rolls back, clears session identity map, and re-reads the reservation via a fresh session using the **preserved original idempotency key**.
   - If contention persists beyond all retries, returns a controlled, retryable `HTTP 503 Service Unavailable` with `Retry-After: 1`.
8. **Strict 36-Character UUIDv4 Identifier Standard**:
   - All database primary keys (`User.id`, `Account.id`, `Transaction.id`, `Device.id`, `Session.id`, `IdempotencyReservation.id`) are canonical **36-character UUIDv4 strings** (`String(36)`).
   - Human-readable identifiers (e.g. `usr_dev_alex_chen`) are defined strictly as seed aliases mapped to deterministic 36-character UUIDv4 strings.
9. **Configurable Daily Transfer Limit with Concurrency Protection**:
   - Server-side limit configured via `settings.DAILY_TRANSFER_LIMIT_INR` (default: `Decimal("50000.00")`).
   - Cumulative calculation executed within `write_transaction(session)` (`BEGIN IMMEDIATE`) to prevent concurrent over-limit race conditions.
   - **Non-Duress Accounting**: Only `status == 'completed'` transactions consume the daily limit; `simulated_duress` transactions **never** consume the limit because zero money moved.
10. **Secure Development Session Bridge**:
    - Disabled by default and unavailable outside `development` or `test` environments (`ENVIRONMENT != "development"` $\rightarrow$ `HTTP 404`).
    - Restrained to loopback/local development networks (`127.0.0.1`, `10.0.2.2`).
    - Only pre-seeded development entities can obtain a session.
11. **Explicit Banking Data-Source Modes**:
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
                                              |  3. Read-Only Queries:              |
                                              |     - Standard Deferred Read Tx     |
                                              |  4. Write Operations (Transfers):   |
                                              |     - Dedicated Session 1 (Phase 1):|
                                              |       * write_transaction()         |
                                              |       * Reserve Key (PROCESSING)    |
                                              |       * Commit & Close Session 1    |
                                              |     - Dedicated Session 2 (Phase 2):|
                                              |       * write_transaction()         |
                                              |       * 5-Point Fencing & Expiry    |
                                              |       * Solvency & Daily Limit      |
                                              |       * Debit/Credit or Duress Mask |
                                              |       * Mark COMPLETED              |
                                              |       * Commit & Close Session 2    |
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

### 3.3 Durable Idempotency Reservation Schema with Fencing Tokens

The `IdempotencyReservation` table guarantees cross-session observability, atomic lease claims, and fencing against stale paused workers:

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

    # Fencing and Lease Ownership
    lease_generation: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    owner_worker_id: Mapped[str] = mapped_column(String(64), nullable=False)
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

#### B. Get User Accounts (Privacy-Preserving & Read-Only Scoped)
- **Endpoint**: `GET /api/v1/banking/accounts`
- **Headers**: `Authorization: Bearer <token>`
- **Database Transaction**: Standard deferred read transaction (no `BEGIN IMMEDIATE`).
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
  - **Temporary Database Contention** (`HTTP 503 Service Unavailable`):
    Returned after bounded retry exhaustion on persistent SQLite locks.
    ```json
    {
      "detail": "DATABASE_CONTENTION_RETRYABLE: Temporary database lock contention. Please retry.",
      "retryable": true,
      "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567"
    }
    ```

#### D. Four-State Reconciliation Endpoint (Read-Only Scoped)
- **Endpoint**: `GET /api/v1/banking/transfers/reconcile/{idempotency_key}`
- **Headers**: `Authorization: Bearer <token>`
- **Database Transaction**: Standard deferred read transaction (no `BEGIN IMMEDIATE`).
- **Response Matrix**:
  - **State 1: `COMPLETED`** (`HTTP 200 OK`): Full committed receipt.
  - **State 2: `PROCESSING`** (`HTTP 202 Accepted`): In-progress advisory.
  - **State 3: `FAILED`** (`HTTP 200 OK`): Terminal failure details.
  - **State 4: `NOT_FOUND`** (`HTTP 404 Not Found`): Unseen key, safe to retry.

---

## 4. Server-Side Security, Concurrency & Lifecycle Rules

### 4.1 Two-Phase Durable Idempotency Lifecycle, Fencing Tokens & Pre-Execution Expiry Handling

To eliminate both the reconciliation race condition and the paused-worker double-debit vulnerability, SafeBank AI implements a two-phase protocol with monotonically increasing fencing tokens:

```
CLIENT                            WORKER 1 (Initial)                     WORKER 2 (Takeover)              DATABASE
  │                                       │                                       │                          │
  │─── POST /transfers (Key K1) ─────────>│                                       │                          │
  │                                       │─── Session 1 (write_transaction):     │                          │
  │                                       │    INSERT Reservation                 │                          │
  │                                       │    (status=PROCESSING, gen=1) ────────┼─────────────────────────>│ [COMMITTED]
  │                                       │    Worker 1 token: gen=1              │                          │
  │                                       │    Close Session 1                    │                          │
  │                                       │                                       │                          │
  │   [Worker 1 Pauses / Delay 35s]       │ [PAUSED / DELAYED]                    │                          │
  │                                       │                                       │                          │
  │   [Client Reconciles / Retries]       │                                       │                          │
  │─── GET /reconcile/K1 ─────────────────┼──────────────────────────────────────>│                          │
  │                                       │                                       │─── Inspect Reservation:  │
  │                                       │                                       │    Lease expired (>30s)  │
  │                                       │                                       │─── Atomic CAS Claim:     │
  │                                       │                                       │    UPDATE ... SET gen=2  │
  │                                       │                                       │    WHERE gen=1 ─────────>│ [COMMITTED gen=2]
  │                                       │                                       │    Worker 2 token: gen=2 │
  │                                       │                                       │                          │
  │                                       │                                       │─── Session 2 (Phase 2):  │
  │                                       │                                       │    Verify 5 conditions   │
  │                                       │                                       │    Debit/Credit Balances │
  │                                       │                                       │    INSERT Transaction    │
  │                                       │                                       │    UPDATE COMPLETED ─────>│ [COMMITTED]
  │                                       │                                       │<── HTTP 200 OK ──────────│
  │                                       │                                       │    Close Session 2       │
  │                                       │                                       │                          │
  │   [Worker 1 Unpauses and resumes]     │ [RESUMES]                             │                          │
  │                                       │─── Session 2 (Phase 2):               │                          │
  │                                       │    Verify 5 conditions:               │                          │
  │                                       │    DB has gen=2 & COMPLETED!          │                          │
  │                                       │    >>> FENCING CHECK FAILS <<<        │                          │
  │                                       │    ROLLBACK & ABORT! ─────────────────┼─────────────────────────>│ [ZERO TOUCH]
  │                                       │    Close Session 2                    │                          │
```

#### Detailed Two-Phase Execution Protocol:

1. **Phase 1: Reserve Key & Initial Commit**:
   - Worker generates a unique `worker_id = str(uuid.uuid4())`.
   - Opens a dedicated, fresh session `session1 = SessionLocal()`.
   - Wrapped inside `with write_transaction(session1):`
     - Query `IdempotencyReservation` by `idempotency_key`.
     - **If Existing Reservation Found**:
       - Compare the 4 financially relevant fields: `from_account_id`, `to_account_id`, `amount`, `currency`.
       - **Mismatch**: Abort immediately and return `HTTP 409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`).
       - **Match**:
         - If `status == "COMPLETED"`: return original committed transaction receipt (`HTTP 200 OK`).
         - If `status == "FAILED"`: return recorded failure receipt (`HTTP 200 OK` or `HTTP 422`).
         - If `status == "PROCESSING"`:
           - **Active Lease** (`utc_now() < lease_expires_at`): return `HTTP 202 Accepted` (`state: PROCESSING`).
           - **Expired Lease** (`utc_now() >= lease_expires_at`):
             - Inspect if a committed `Transaction` row already exists for `idempotency_key`.
             - If found: update reservation to `COMPLETED` and return receipt.
             - If not found: execute **Atomic CAS Lease Claim**:
               ```sql
               UPDATE idempotency_reservations
               SET lease_generation = lease_generation + 1,
                   owner_worker_id = :worker_id,
                   lease_expires_at = :new_lease_expiry
               WHERE idempotency_key = :key
                 AND status = 'PROCESSING'
                 AND lease_generation = :current_observed_generation
                 AND lease_expires_at <= :now;
               ```
               - If `rows_affected == 1`: worker acquired new generation (e.g. `generation = 2`). Worker proceeds to Phase 2 with `fencing_token = 2`.
               - If `rows_affected == 0`: another worker won the race or transaction finished; rollback and re-read.
     - **If No Reservation Found**:
       - Insert `IdempotencyReservation(idempotency_key=key, status='PROCESSING', lease_generation=1, owner_worker_id=worker_id, lease_expires_at=utc_now() + 30s, ...)`.
       - Commit immediately. Worker holds `fencing_token = 1`.
   - `session1.close()` cleanly disposes Phase 1 resources.

2. **Phase 2: Atomic 5-Point Fencing & Financial Execution**:
   - Opens a separate, fresh session `session2 = SessionLocal()`.
   - Wrapped inside `with write_transaction(session2):` (`BEGIN IMMEDIATE`):
   - **Guarded 5-Point Fencing Verification**:
     Before modifying any account balance or inserting any financial record, the worker executes a strict verification query:
     ```python
     now_utc = utc_now()
     reservation = session2.query(IdempotencyReservation).filter(
         IdempotencyReservation.idempotency_key == idempotency_key,
         IdempotencyReservation.lease_generation == my_fencing_token,
         IdempotencyReservation.owner_worker_id == my_worker_id,
         IdempotencyReservation.status == "PROCESSING",
         IdempotencyReservation.lease_expires_at > now_utc,  # Strictly active and unexpired!
     ).first()
     if not reservation:
         # Fencing or Expiry check failed! Abort immediately without touching balances
         session2.rollback()
         return cls._resolve_fencing_failure(session2, idempotency_key, my_worker_id, my_fencing_token)
     ```
   - **Handling Lease Expiry Just Before Phase 2 Begins (Even With No Competing Worker)**:
     - If a worker's lease expires (`lease_expires_at <= utc_now()`) before Phase 2 acquires the lock, **even if no other worker has claimed the lease yet**, condition 5 fails!
     - The worker **CANNOT** proceed with financial execution. It must roll back immediately.
     - *Why?* Because once `lease_expires_at` passes, the worker's exclusive window has lapsed; another worker or reconciliation process could claim ownership at any moment. Proceeding without an active lease violates mutual exclusion.
     - *Remedy*: The worker must attempt an atomic CAS lease renewal:
       ```sql
       UPDATE idempotency_reservations
       SET lease_generation = lease_generation + 1,
           owner_worker_id = :my_worker_id,
           lease_expires_at = :new_lease_expiry
       WHERE idempotency_key = :key
         AND status = 'PROCESSING'
         AND lease_generation = :my_fencing_token
         AND lease_expires_at <= :now;
       ```
       - If the renewal succeeds, the worker increments its fencing token and safely enters Phase 2.
       - If renewal fails (e.g. another worker claimed the lease or the transfer already completed), the worker aborts Phase 2 and re-reads the committed result.
   - **Execute Financial Operation (Only After 5-Point Verification Passes)**:
     - Validate account ownership, active status, available balance, and cumulative daily limit.
     - If validation fails: update reservation `status='FAILED'`, `error_code=...`, commit, return `HTTP 422`.
     - If duress mode (`session.is_restricted == True`):
       - Balances are untouched.
       - Insert `Transaction(status='simulated_duress')`, `DuressEvent`, `SecurityEvent`.
       - Update reservation: `status='COMPLETED'`, `transaction_id=tx.id`, commit.
     - If normal mode:
       - Debit sender: `from_account.balance -= amount`.
       - Credit receiver: `to_account.balance += amount`.
       - Insert `Transaction(status='completed', idempotency_key=key)`.
       - Update reservation: `status='COMPLETED'`, `transaction_id=tx.id`, commit.
   - Return receipt.
   - `session2.close()` cleanly disposes Phase 2 resources.

---

### 4.2 Exact SQLAlchemy, SQLite Dialect & Python `sqlite3` Transaction Architecture

To guarantee that `BEGIN IMMEDIATE` is reliably the first SQL statement without conflicts with SQLAlchemy autobegin or Python `sqlite3` driver transaction tracking, the system relies on the following explicit configuration:

#### A. Database Engine & Connection Hook Configuration
```python
from sqlalchemy import create_engine, event

engine = create_engine(
    settings.DATABASE_URL,
    connect_args={"check_same_thread": False, "timeout": 15},
)

@event.listens_for(engine, "connect")
def set_sqlite_connection_pragmas(dbapi_connection, connection_record):
    """
    Configure SQLite connection:
    1. DBAPI Autocommit Mode: Sets isolation_level = None to completely disable
       Python sqlite3 implicit BEGIN emission.
    2. Sets busy_timeout = 5000ms.
    """
    dbapi_connection.isolation_level = None  # DBAPI Autocommit Mode
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA busy_timeout = 5000;")
    cursor.close()

@event.listens_for(engine, "begin")
def do_begin(conn):
    """
    SQLAlchemy begin dispatch listener:
    - Inspects connection execution options.
    - If 'sqlite_write_immediate' is True: emits 'BEGIN IMMEDIATE'.
    - Otherwise (read queries): emits 'BEGIN' (deferred read transaction).
    """
    opts = conn.get_execution_options()
    if opts.get("sqlite_write_immediate", False):
        conn.exec_driver_sql("BEGIN IMMEDIATE")
    else:
        conn.exec_driver_sql("BEGIN")
```

#### B. Safe `write_transaction` Context Manager
```python
class ActiveTransactionError(RuntimeError):
    """Raised when write_transaction is invoked on an already-active transaction."""
    pass

@contextmanager
def write_transaction(session: DbSession):
    """
    Scoped SQLite write transaction context manager.
    
    Guarantees:
    1. Precondition Check: Requires that NO transaction is active before issuing BEGIN IMMEDIATE.
       If session.in_transaction() is True, raises ActiveTransactionError explicitly.
       Silent commits of existing work are strictly forbidden.
    2. First Statement Guarantee: Emits BEGIN IMMEDIATE as the very first statement on the
       database connection via the engine 'begin' listener and execution option.
    3. No Implicit BEGIN: DBAPI autocommit mode prevents Python sqlite3 from emitting implicit BEGIN.
    4. No Nested Transaction: Starts a single root transaction on the connection.
    5. Clean Synchronization: session.commit() and session.rollback() directly invoke
       DBAPI connection commit and rollback, keeping SQLAlchemy and SQLite states perfectly aligned.
    """
    if session.in_transaction():
        raise ActiveTransactionError(
            "write_transaction requires a fresh or clean session with no active transaction. "
            "Automatic commit of active transactions is forbidden to preserve atomicity."
        )

    # Check out connection with sqlite_write_immediate=True execution option
    # This triggers do_begin, which executes 'BEGIN IMMEDIATE' as the first statement
    session.connection(execution_options={"sqlite_write_immediate": True})

    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
```

#### C. Session Lifecycle & Cleanup Rules
1. **Fresh Dedicated Sessions**:
   - Each phase creates an isolated session: `db = SessionLocal()`.
   - Never share or reuse sessions across asynchronous boundaries or sequential distinct phases.
2. **Explicit Error Boundaries**:
   - Unhandled exceptions trigger `session.rollback()`.
   - Clean disposal is enforced via `finally: session.close()`.
3. **Read-Only Concurrency**:
   - Read-only endpoints (`GET /api/v1/banking/accounts`, `GET /api/v1/banking/transactions`, read-only reconciliation queries) use standard database sessions without `sqlite_write_immediate=True`.
   - SQLite executes standard deferred transactions (shared read locks in journal mode; lock-free concurrent reads in WAL mode).

---

### 4.3 SQLite Lock Contention, `SQLITE_BUSY` & Bounded Retries

Concurrent requests may encounter SQLite lock busy errors rather than `IntegrityError` when multiple write transactions compete:

1. **Connection Pragma**:
   `PRAGMA busy_timeout = 5000;` ensures the SQLite engine waits up to 5,000ms internally before throwing `SQLITE_BUSY`.

2. **Bounded Retry Loop with Jittered Exponential Backoff**:
   When `sqlite3.OperationalError` (`database is locked` / `SQLITE_BUSY`) is caught during a write operation:
   - Roll back the failed transaction: `session.rollback()`.
   - Clear session cache: `session.expire_all()` to discard stale cached entity states.
   - Retry up to **3 bounded attempts**.
   - Calculate jittered exponential backoff:
     $$\text{backoff} = \min(0.5, 0.05 \times 2^{\text{attempt}}) + \text{random.uniform}(0.01, 0.05)$$
   - **Preserve Client Key**: The original `idempotency_key` is strictly preserved. A new key is **never** created for an automatic retry.
   - Before retrying write, re-read the reservation state through a fresh query: if another worker committed the transfer during the backoff interval, return the completed receipt immediately.

3. **Controlled Retryable Response**:
   If contention persists after all 3 retries are exhausted:
   - Return `HTTP 503 Service Unavailable` with `Retry-After: 1` header:
     ```json
     {
       "detail": "DATABASE_CONTENTION_RETRYABLE: Temporary database lock contention. Please retry.",
       "retryable": true,
       "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567"
     }
     ```
   - The Android client catches `retryable: true` and schedules an automatic backoff retry with the exact same idempotency key.

---

### 4.4 Daily-Limit Accounting & Concurrency Protection

- **Rule**: Only transfers with `status == 'completed'` that genuinely move funds consume the configurable daily limit (`settings.DAILY_TRANSFER_LIMIT_INR`, default `₹50,000.00`).
- **Duress Isolation**: `simulated_duress` transactions **never** consume the daily limit because no money moves.
- **Immediate Write Lock Protection**:
  The cumulative sum is calculated within `write_transaction(session)` (`BEGIN IMMEDIATE`):
  ```python
  today_start = utc_now().replace(hour=0, minute=0, second=0, microsecond=0)
  daily_sum = session.query(func.coalesce(func.sum(Transaction.amount), Decimal("0.00"))).filter(
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
| **TEST-S4-05** | **Server Interruption & Abandoned Lease Recovery** | Simulate server crash after Phase 1 commit leaving an abandoned `PROCESSING` reservation. Advance time past `lease_expires_at` (30s). Submit retry / reconciliation. Verifies recovery detects no financial transaction was committed, renews lease via CAS, executes safely, and moves funds exactly once. |
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
| **TEST-S4-16** | **Read-Only Scoped Isolation** | Execute concurrent `GET /api/v1/banking/accounts` and reconcile queries while a shared read lock is open. Verifies read queries run on standard deferred read transactions and do NOT issue `BEGIN IMMEDIATE`, allowing full read concurrency. |
| **TEST-S4-17** | **Stale Worker Fencing Rejection** | Worker 1 acquires reservation at `lease_generation = 1`. Worker 2 takes over the expired lease via CAS, incrementing to `lease_generation = 2`, and completes Phase 2. Worker 1 resumes and attempts Phase 2 commit. Verifies Worker 1's fencing check fails, transaction rolls back, and Worker 1 cannot alter account balances. |
| **TEST-S4-18** | **Racing Lease Claim CAS Concurrency** | Two workers simultaneously detect an expired lease and attempt CAS claim (`UPDATE ... SET lease_generation = lease_generation + 1 ...`). Verifies exactly one worker increments the generation and proceeds; the second matches 0 rows, rolls back, and observes the renewed lease without duplicating execution. |
| **TEST-S4-19** | **SQLITE_BUSY Contention & Bounded Retry** | Simulate `sqlite3.OperationalError: database is locked`. Verifies service rolls back, applies jittered backoff, retries up to 3 times preserving the original `idempotency_key`. Verifies if lock clears during retry, transfer completes; if contention persists, returns controlled `HTTP 503` (`DATABASE_CONTENTION_RETRYABLE`). |
| **TEST-S4-20** | **Paused Worker End-to-End Verification** | Worker 1 begins transfer, pauses beyond 30s. Worker 2 takes over, debits ₹5,000, credits ₹5,000, and completes transfer. Worker 1 unpauses and resumes. Direct database inspection verifies: source account debited by exactly ₹5,000 (not ₹10,000), destination credited by ₹5,000, exactly one `Transaction` row exists, and reservation is `COMPLETED`. |
| **TEST-S4-21** | **Active Session Rejection in `write_transaction`** | Pass an active SQLAlchemy session (`session.in_transaction() == True`) with uncommitted changes into `write_transaction`. Verifies `ActiveTransactionError` is explicitly raised and the active transaction is NEVER silently committed. |
| **TEST-S4-22** | **Pre-Execution Lease Expiry Fails Phase 2 Without Worker Claim** | Worker 1 reserves key in Phase 1. Artificially advance clock so `lease_expires_at <= utc_now()`, but no Worker 2 has claimed the lease. Worker 1 attempts Phase 2. Verifies condition 5 (`lease_expires_at > utc_now()`) fails, Phase 2 rolls back, and account balances remain 100% untouched. |
| **TEST-S4-23** | **Valid Current Lease Executes Exactly Once** | Worker 1 reserves key in Phase 1, enters Phase 2 within active lease window (`lease_expires_at > utc_now()`). All 5 fencing conditions pass. Verifies transfer commits successfully; balances debited and credited exactly once. |
| **TEST-S4-24** | **File-Backed SQLite Statement & Transaction Trace** | Configure file-backed SQLite with `dbapi_connection.isolation_level = None` and statement listener. Execute `write_transaction(session)`: verify `BEGIN IMMEDIATE` is emitted as the exact first statement, no implicit `BEGIN` preceded it, and `session.commit()` cleanly commits. Execute read query: verify `BEGIN` is emitted. |
