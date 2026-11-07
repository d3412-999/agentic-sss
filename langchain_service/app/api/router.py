"""聚合所有路由。

对外只有 GET /health（免鉴权）；
/internal/ai/** 是后端专用契约，路径与 Java 侧 AiServiceClient.PREFIX
严格对应，并统一加 X-Internal-Token 校验。
"""

from fastapi import APIRouter, Depends

from app.api.routes import defense, health, review
from app.core.security import verify_internal_token

api_router = APIRouter()

# 探活：不挂 token 依赖，方便 docker / 运维 / README 验证清单直接访问
api_router.include_router(health.router)

internal_router = APIRouter(
    prefix="/internal/ai",
    dependencies=[Depends(verify_internal_token)],
)
internal_router.include_router(defense.router)
internal_router.include_router(review.router)
api_router.include_router(internal_router)
