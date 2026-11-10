"""Application configuration and settings."""

from typing import List
import os

try:
    from pydantic_settings import BaseSettings
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

        # Security
        SECRET_KEY: str = os.getenv("SECRET_KEY", "dev-secret-key-replace-in-production-1234567890")
        ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
        SESSION_EXPIRE_HOURS: int = 24

        # WebAuthn
        RP_ID: str = "localhost"
        RP_NAME: str = "SafeBank AI"
        RP_ORIGINS: List[str] = [
            "https://localhost",
            "http://localhost:8000",
            "http://10.0.2.2:8000",
        ]

        class Config:
            env_file = ".env"
            case_sensitive = True

except ImportError:
    # Minimal fallback when pydantic_settings is not yet installed
    class Settings:  # type: ignore
        PROJECT_NAME: str = "SafeBank AI"
        VERSION: str = "0.1.0"
        API_V1_STR: str = "/api/v1"
        ENVIRONMENT: str = "development"
        DEBUG: bool = True
        HOST: str = "0.0.0.0"
        PORT: int = 8000
        DATABASE_URL: str = "sqlite:///./safebank.db"
        SECRET_KEY: str = "dev-secret-key-replace-in-production-1234567890"
        ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
        SESSION_EXPIRE_HOURS: int = 24
        RP_ID: str = "localhost"
        RP_NAME: str = "SafeBank AI"
        RP_ORIGINS: List[str] = [
            "https://localhost",
            "http://localhost:8000",
            "http://10.0.2.2:8000",
        ]


settings = Settings()
