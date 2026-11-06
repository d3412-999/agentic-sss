"""FastAPI 依赖。"""

from fastapi import Header, HTTPException, status

from app.core.config import get_settings


async def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    """校验 Java 后端传入的内部令牌。健康检查不经过此依赖。"""
    settings = get_settings()
    if x_internal_token != settings.internal_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid internal token",
        )
