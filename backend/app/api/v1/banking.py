"""Banking API endpoints for accounts, internal transfers, and reconciliation."""

from decimal import Decimal
from typing import List, Optional
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session as DbSession

from app.api.deps import get_current_session
from app.db.base import utc_now
from app.db.session import get_db
from app.models.account import Account
from app.models.session import Session as AppSession
from app.schemas.banking import (
    AccountResponse,
    ReconcileResponse,
    TransferRequest,
    TransferResponse,
)
from app.services.banking_service import BankingService

router = APIRouter(prefix="/banking", tags=["banking"])


@router.get("/accounts", response_model=List[AccountResponse], status_code=status.HTTP_200_OK)
async def get_user_accounts(
    session: AppSession = Depends(get_current_session),
    db: DbSession = Depends(get_db),
):
    """
    Retrieve authenticated user's accounts.
    Privacy-preserving: exposes only masked account numbers, never raw account numbers.
    Monetary values serialized as fixed 2-decimal strings.
    Read-only scoped: operates on a standard deferred read transaction.
    """
    accounts = (
        db.query(Account)
        .filter(Account.user_id == session.user_id)
        .order_by(Account.created_at.asc())
        .all()
    )

    response_items = []
    for acc in accounts:
        raw_num = acc.account_number or ""
        masked = f"•••• {raw_num[-4:]}" if len(raw_num) >= 4 else f"•••• {raw_num}"
        response_items.append(
            AccountResponse(
                id=str(acc.id),
                masked_account_number=masked,
                account_type=acc.account_type,
                balance=f"{Decimal(str(acc.balance)):.2f}",
                currency=acc.currency,
                status=acc.status,
                is_primary=(acc.account_type == "checking"),
            )
        )

    return response_items


@router.post("/transfers", response_model=TransferResponse)
async def execute_transfer(
    payload: TransferRequest,
    request: Request,
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
    session: AppSession = Depends(get_current_session),
    db: DbSession = Depends(get_db),
):
    """
    Execute an internal transfer between the authenticated user's accounts.
    
    Security & Business Rules:
    - Requires valid server-verified session.
    - Strictly internal transfers: both source and destination must belong to authenticated user.
    - Strictly INR currency and positive Decimal amounts.
    - Enforces daily limits, solvency, and account active status.
    - Requires UUIDv4 Idempotency-Key.
    - Reuses BankingService two-phase durable reservation and fencing engine.
    - Preserves Coercion Isolation in duress mode (silently protects funds, masks status).
    """
    # 1. Validate Idempotency-Key Header
    if not idempotency_key or not idempotency_key.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="IDEMPOTENCY_KEY_INVALID: Idempotency-Key header is required.",
        )

    try:
        parsed_uuid = uuid.UUID(idempotency_key.strip())
        if parsed_uuid.version != 4:
            raise ValueError("Not UUIDv4")
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="IDEMPOTENCY_KEY_INVALID: Valid UUIDv4 Idempotency-Key header is required.",
        )
    validated_key = str(parsed_uuid)

    # 2. Financial Parameter Validations
    if payload.currency != "INR":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="INVALID_CURRENCY: Only INR (₹) transactions are supported.",
        )

    if payload.amount <= Decimal("0.00"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="INVALID_AMOUNT: Transfer amount must be strictly positive.",
        )

    if payload.from_account_id == payload.to_account_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="SAME_ACCOUNT_TRANSFER: Source and destination accounts must be distinct.",
        )

    # 3. Ownership and Internal Scope Pre-checks
    from_account = db.query(Account).filter(Account.id == payload.from_account_id).first()
    if not from_account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="SOURCE_ACCOUNT_NOT_FOUND: Source account does not exist.",
        )
    if from_account.user_id != session.user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="CROSS_USER_TRANSFER_UNAUTHORIZED: Source account does not belong to the authenticated user.",
        )
    if from_account.status != "active":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"ACCOUNT_INACTIVE: Source account is {from_account.status}.",
        )

    to_account = db.query(Account).filter(Account.id == payload.to_account_id).first()
    if not to_account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="DESTINATION_ACCOUNT_NOT_FOUND: Destination account does not exist.",
        )
    if to_account.user_id != session.user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="CROSS_USER_TRANSFER_UNAUTHORIZED: Destination account belongs to a different user. Only internal transfers between your own accounts are permitted.",
        )
    if to_account.status != "active":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"DESTINATION_ACCOUNT_INACTIVE: Destination account is {to_account.status}.",
        )

    # 4. Delegate to BankingService Engine
    client_ip = request.client.host if request.client else None
    result = BankingService.execute_transfer(
        db=db,
        from_account_id=payload.from_account_id,
        to_account_id=payload.to_account_id,
        amount=payload.amount,
        idempotency_key=validated_key,
        currency=payload.currency,
        description=payload.description,
        is_restricted=session.is_restricted,
        session_id=session.id,
        client_ip=client_ip,
        user_id=session.user_id,
        enforce_internal_only=True,
    )

    # 5. Handle Banking Engine Outcomes
    if result.status == "conflict" or result.error_code == "IDEMPOTENCY_PAYLOAD_MISMATCH":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=result.error_message or "IDEMPOTENCY_PAYLOAD_MISMATCH: The provided idempotency key was previously submitted with different financial parameters.",
        )

    if result.status == "processing":
        return JSONResponse(
            status_code=status.HTTP_202_ACCEPTED,
            content={
                "detail": "PROCESSING: Transfer is currently processing. Reconcile or retry with backoff.",
                "state": "PROCESSING",
                "retryable": True,
                "idempotency_key": validated_key,
            },
        )

    if result.status == "contention_retryable":
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            headers={"Retry-After": "1"},
            content={
                "detail": "DATABASE_CONTENTION_RETRYABLE: Temporary database lock contention. Please retry.",
                "retryable": True,
                "idempotency_key": validated_key,
            },
        )

    if not result.success:
        if result.error_code == "DAILY_LIMIT_EXCEEDED":
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=result.error_message,
            )
        if result.error_code == "INSUFFICIENT_FUNDS":
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="INSUFFICIENT_FUNDS: Insufficient available balance to complete transfer.",
            )
        if result.error_code == "CROSS_USER_TRANSFER_UNAUTHORIZED":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=result.error_message,
            )
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=result.error_message or "TRANSFER_FAILED",
        )

    # 6. Build Successful Receipt Response
    tx = result.transaction
    http_status = status.HTTP_200_OK if result.is_replay else status.HTTP_201_CREATED
    completed_time = tx.completed_at.isoformat() if (tx and tx.completed_at) else utc_now().isoformat()

    return JSONResponse(
        status_code=http_status,
        content={
            "success": True,
            "transaction_id": str(tx.id) if tx else "",
            "reference_number": tx.reference_number if tx else "",
            "status": "completed",  # Always masked as 'completed' in response to client
            "from_account_id": str(tx.from_account_id) if tx else payload.from_account_id,
            "to_account_id": str(tx.to_account_id) if tx else payload.to_account_id,
            "amount": f"{Decimal(str(tx.amount if tx else payload.amount)):.2f}",
            "currency": tx.currency if tx else "INR",
            "idempotency_key": validated_key,
            "completed_at": completed_time,
            "message": "Internal transfer completed successfully.",
        },
    )


@router.get("/transfers/reconcile/{idempotency_key}")
async def reconcile_transfer(
    idempotency_key: str,
    session: AppSession = Depends(get_current_session),
    db: DbSession = Depends(get_db),
):
    """
    Reconcile a transfer by idempotency key.
    Read-only scoped: operates on a standard read transaction (no BEGIN IMMEDIATE).
    Guarantees authorization: cannot inspect or discover another user's transfers.
    
    Returns one of four states:
    - COMPLETED (200 OK): full committed receipt.
    - PROCESSING (202 Accepted): actively in progress.
    - FAILED (200 OK): terminal failure details.
    - NOT_FOUND (404 Not Found): unknown key, safe to retry.
    """
    try:
        parsed_uuid = uuid.UUID(idempotency_key.strip())
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="IDEMPOTENCY_KEY_INVALID: Valid UUIDv4 idempotency key required.",
        )
    validated_key = str(parsed_uuid)

    # Read-only query execution
    reconcile_res = BankingService.reconcile_transfer(
        db=db,
        idempotency_key=validated_key,
        user_id=session.user_id,
    )

    if reconcile_res.status == "NOT_FOUND":
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={
                "state": "NOT_FOUND",
                "retryable": True,
                "idempotency_key": validated_key,
                "detail": "No reservation was visible at query time (NOT_FOUND). Safe to retry ONLY with original idempotency key and identical financial payload.",
            },
        )

    # Privacy & Authorization check: do not expose another user's reservation
    if reconcile_res.reservation and reconcile_res.reservation.user_id != session.user_id:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={
                "state": "NOT_FOUND",
                "retryable": True,
                "idempotency_key": validated_key,
                "detail": "No reservation was visible at query time (NOT_FOUND). Safe to retry ONLY with original idempotency key and identical financial payload.",
            },
        )

    if reconcile_res.status == "PROCESSING":
        return JSONResponse(
            status_code=status.HTTP_202_ACCEPTED,
            content={
                "state": "PROCESSING",
                "retryable": True,
                "idempotency_key": validated_key,
                "detail": reconcile_res.error_message or "Transfer is actively processing. Please check back shortly.",
            },
        )

    if reconcile_res.status == "FAILED":
        return JSONResponse(
            status_code=status.HTTP_200_OK,
            content={
                "state": "FAILED",
                "success": False,
                "idempotency_key": validated_key,
                "error_code": reconcile_res.error_code,
                "detail": reconcile_res.error_message,
            },
        )

    if reconcile_res.status == "COMPLETED":
        tx = reconcile_res.transaction
        return JSONResponse(
            status_code=status.HTTP_200_OK,
            content={
                "state": "COMPLETED",
                "success": True,
                "transaction_id": str(tx.id) if tx else None,
                "reference_number": tx.reference_number if tx else None,
                "status": "completed",
                "from_account_id": str(tx.from_account_id) if tx else None,
                "to_account_id": str(tx.to_account_id) if tx else None,
                "amount": f"{Decimal(str(tx.amount)):.2f}" if tx else None,
                "currency": tx.currency if tx else "INR",
                "idempotency_key": validated_key,
                "completed_at": tx.completed_at.isoformat() if (tx and tx.completed_at) else None,
                "message": "Transfer already completed.",
            },
        )

    return JSONResponse(
        status_code=status.HTTP_404_NOT_FOUND,
        content={
            "state": "NOT_FOUND",
            "retryable": True,
            "idempotency_key": validated_key,
            "detail": "No transfer record found for idempotency key.",
        },
    )
