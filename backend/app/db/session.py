"""Database engine, connection lifecycle, and session dependency."""

from contextlib import contextmanager
from typing import Generator
from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import sessionmaker, Session
from app.core.config import settings

class ActiveTransactionError(RuntimeError):
    """Raised when write_transaction is invoked on an already-active transaction."""
    pass

# SQLite connection arguments: check_same_thread=False allows multi-threaded requests in FastAPI
engine = create_engine(
    settings.DATABASE_URL,
    connect_args={"check_same_thread": False, "timeout": 15} if "sqlite" in settings.DATABASE_URL else {},
    echo=settings.DEBUG and False,  # Enable SQL query logging only if explicitly requested
)

# Enforce SQLite foreign key constraints, busy timeout, and DBAPI autocommit
@event.listens_for(Engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    if "sqlite" in settings.DATABASE_URL:
        # DBAPI Autocommit Mode: disables pysqlite implicit transaction emission
        dbapi_connection.isolation_level = None
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.close()

# Scoped BEGIN IMMEDIATE dispatch hook based on connection execution options
@event.listens_for(engine, "begin")
def do_begin(conn):
    opts = conn.get_execution_options()
    if opts.get("sqlite_write_immediate", False):
        conn.exec_driver_sql("BEGIN IMMEDIATE")
    else:
        conn.exec_driver_sql("BEGIN")

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

@contextmanager
def write_transaction(session: Session):
    """
    Scoped SQLite write transaction context manager.
    Acquires BEGIN IMMEDIATE exclusively for write operations.

    Safety Preconditions:
    1. Requires that NO transaction is active before issuing BEGIN IMMEDIATE.
    2. If session.in_transaction() is True, raises ActiveTransactionError explicitly.
       Silent commits of existing work are strictly forbidden.
    3. Dedicated, fresh sessions must be used for Phase 1 and Phase 2.
    """
    if session.in_transaction():
        raise ActiveTransactionError(
            "write_transaction requires a fresh or clean session with no active transaction. "
            "Automatic commit of active transactions is forbidden to preserve atomicity."
        )

    # Check out connection with sqlite_write_immediate=True execution option
    session.connection(execution_options={"sqlite_write_immediate": True})
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise

def get_db() -> Generator[Session, None, None]:
    """FastAPI dependency that yields an independent database session per request."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
