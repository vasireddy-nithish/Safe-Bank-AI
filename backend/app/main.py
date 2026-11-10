"""SafeBank AI FastAPI Application Entry Point."""

from contextlib import asynccontextmanager
import logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.core.config import settings
from app.api.v1.router import api_router

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("safebank-ai")

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Initializing SafeBank AI Backend service...")
    yield
    logger.info("Shutting down SafeBank AI Backend service...")

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="A Privacy-First, Passwordless & Coercion-Aware Authentication Layer for Mobile Banking",
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
        "openapi": "/openapi.json",
    }

@app.get("/health", tags=["system"])
async def health():
    return {
        "status": "healthy",
        "service": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "environment": settings.ENVIRONMENT,
        "security_pillars": ["Identity", "Device", "Freedom to Act"],
    }

app.include_router(api_router, prefix=settings.API_V1_STR)
