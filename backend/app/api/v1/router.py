"""SafeBank AI API v1 Root Router."""

try:
    from fastapi import APIRouter

    api_router = APIRouter()

    @api_router.get("/health", tags=["system"])
    async def health_check():
        """Basic system health check endpoint."""
        return {
            "status": "healthy",
            "service": "SafeBank AI Backend",
            "stage": "Stage 1: Foundation",
            "security_pillars": ["Identity", "Device", "Freedom to Act"],
        }

except ImportError:
    api_router = None  # type: ignore
