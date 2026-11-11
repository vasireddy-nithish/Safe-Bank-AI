"""Pydantic schemas for Banking API endpoints."""

from decimal import Decimal
from typing import Optional
from pydantic import BaseModel, Field


class AccountResponse(BaseModel):
    id: str = Field(..., description="Account UUIDv4 identifier")
    masked_account_number: str = Field(..., description="Privacy-preserving masked account number, e.g. '•••• 9481'")
    account_type: str = Field(..., description="Account type: checking or savings")
    balance: str = Field(..., description="Account balance serialized as a fixed 2-decimal string, e.g. '124500.80'")
    currency: str = Field(default="INR", description="Three-letter ISO currency code, strictly INR")
    status: str = Field(default="active", description="Account status: active, frozen, or restricted")
    is_primary: bool = Field(default=False, description="Whether this is the user's primary checking account")


class TransferRequest(BaseModel):
    from_account_id: str = Field(..., description="Source account UUIDv4")
    to_account_id: str = Field(..., description="Destination account UUIDv4")
    amount: Decimal = Field(..., description="Monetary transfer amount (strictly positive)")
    currency: str = Field(default="INR", description="Currency code, strictly INR")
    description: Optional[str] = Field(default=None, description="Optional transfer description")


class TransferResponse(BaseModel):
    success: bool = True
    transaction_id: str
    reference_number: str
    status: str
    from_account_id: str
    to_account_id: str
    amount: str
    currency: str = "INR"
    idempotency_key: str
    completed_at: Optional[str] = None
    message: str = "Internal transfer completed successfully."


class ReconcileResponse(BaseModel):
    state: str = Field(..., description="State: COMPLETED, PROCESSING, FAILED, or NOT_FOUND")
    success: Optional[bool] = None
    retryable: Optional[bool] = None
    idempotency_key: str
    transaction_id: Optional[str] = None
    reference_number: Optional[str] = None
    status: Optional[str] = None
    from_account_id: Optional[str] = None
    to_account_id: Optional[str] = None
    amount: Optional[str] = None
    currency: Optional[str] = None
    completed_at: Optional[str] = None
    error_code: Optional[str] = None
    detail: Optional[str] = None
    message: Optional[str] = None


class TransactionResponse(BaseModel):
    id: str
    reference_number: str
    from_account_id: str
    to_account_id: Optional[str] = None
    amount: str
    currency: str = "INR"
    transaction_type: str = "transfer"
    status: str
    description: Optional[str] = None
    idempotency_key: str
    is_duress_masked: bool = False
    completed_at: Optional[str] = None
    created_at: str
