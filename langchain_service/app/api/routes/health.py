"""健康检查。"""

from fastapi import APIRouter

from app import __version__
from app.core.config import get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict[str, str]:
    settings = get_settings()
    return {
        "status": "UP",
        "app": settings.app_name,
        "environment": settings.environment,
        "version": __version__,
    }
