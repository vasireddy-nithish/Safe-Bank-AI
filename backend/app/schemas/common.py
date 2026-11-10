"""Common schema models and standard API response formats."""

from typing import Generic, Optional, TypeVar, Any
from datetime import datetime, timezone
import uuid

try:
    from pydantic import BaseModel, Field

    DataT = TypeVar("DataT")

    class ResponseMetadata(BaseModel):
        request_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
        timestamp: str = Field(
            default_factory=lambda: datetime.now(timezone.utc).isoformat()
        )
        version: str = "v1"

    class SuccessResponse(BaseModel, Generic[DataT]):
        success: bool = True
        data: DataT
        metadata: ResponseMetadata = Field(default_factory=ResponseMetadata)

    class ErrorDetail(BaseModel):
        code: str
        message: str
        details: Optional[Any] = None

    class ErrorResponse(BaseModel):
        success: bool = False
        error: ErrorDetail
        metadata: ResponseMetadata = Field(default_factory=ResponseMetadata)

except ImportError:
    # Minimal fallback schema
    class ResponseMetadata:  # type: ignore
        def __init__(self):
            self.request_id = str(uuid.uuid4())
            self.timestamp = datetime.now(timezone.utc).isoformat()
            self.version = "v1"

        def dict(self):
            return {
                "request_id": self.request_id,
                "timestamp": self.timestamp,
                "version": self.version,
            }
