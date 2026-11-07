"""探活接口：免鉴权，供运维与 README 验证清单使用。"""

from datetime import datetime, timezone

from fastapi import APIRouter

from app import __version__

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    return {
        "status": "UP",
        "app": "sss-ai-service",
        "version": __version__,
        "time": datetime.now(timezone.utc).isoformat(),
    }
