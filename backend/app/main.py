"""SafeBank AI FastAPI Application Entry Point."""

from contextlib import asynccontextmanager
import logging

try:
    from fastapi import FastAPI
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.responses import JSONResponse
    from app.core.config import settings
    from app.api.v1.router import api_router

    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger("safebank-ai")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        logger.info("Initializing SafeBank AI Backend service (Stage 1 Foundation)...")
        yield
        logger.info("Shutting down SafeBank AI Backend service...")

    app = FastAPI(
        title=settings.PROJECT_NAME,
        version=settings.VERSION,
        description="Privacy-First, Passwordless & Coercion-Aware Authentication Layer for Mobile Banking",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    # CORS configuration for mobile clients & development emulators
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/", tags=["root"])
    async def root():
        return {
            "name": settings.PROJECT_NAME,
            "version": settings.VERSION,
            "status": "online",
            "message": "Welcome to SafeBank AI Backend Core",
            "docs": "/docs",
        }

    @app.get("/health", tags=["system"])
    async def health():
        return {
            "status": "healthy",
            "stage": "Stage 1: Foundation",
            "database": "sqlite",
        }

    if api_router is not None:
        app.include_router(api_router, prefix=settings.API_V1_STR)

except ImportError as e:
    # Minimal WSGI/ASGI runner fallback before dependencies are installed
    class MinimalApp:
        async def __call__(self, scope, receive, send):
            if scope["type"] == "http":
                body = b'{"status":"healthy","message":"SafeBank AI minimal ASGI ready","stage":"Stage 1"}'
                await send({
                    "type": "http.response.start",
                    "status": 200,
                    "headers": [[b"content-type", b"application/json"]],
                })
                await send({"type": "http.response.body", "body": body})

    app = MinimalApp()
