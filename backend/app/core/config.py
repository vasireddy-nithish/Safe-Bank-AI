"""Application configuration and settings using Pydantic Settings."""

from decimal import Decimal
from typing import List
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field

class Settings(BaseSettings):
    PROJECT_NAME: str = "SafeBank AI"
    VERSION: str = "0.1.0"
    API_V1_STR: str = "/api/v1"
    ENVIRONMENT: str = "development"
    DEBUG: bool = True
    HOST: str = "0.0.0.0"
    PORT: int = 8000

    # Database
    DATABASE_URL: str = "sqlite:///./safebank.db"

    # Security & Tokens
    SECRET_KEY: str = Field(
        default="dev-secret-key-replace-in-production-1234567890",
        description="Cryptographic secret key for signing sessions and tokens"
    )
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    SESSION_EXPIRE_HOURS: int = 24

    # Banking Limits & Dev Controls
    DAILY_TRANSFER_LIMIT_INR: Decimal = Decimal("50000.00")
    ENABLE_DEV_SESSION_ENDPOINT: bool = False

    # WebAuthn / Passkeys
    # Note: In Android Credential Manager, RP_ID must match the effective domain
    RP_ID: str = "localhost"
    RP_NAME: str = "SafeBank AI"
    RP_ORIGINS: List[str] = [
        "https://localhost",
        "http://localhost:8000",
        "http://10.0.2.2:8000",
    ]

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore"
    )

settings = Settings()
