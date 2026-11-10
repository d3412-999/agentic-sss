"""聚合所有路由。

对外只有 GET /health（免鉴权）；
/internal/ai/** 是后端专用契约，路径与 Java 侧 AiServiceClient.PREFIX 严格对应，
并在聚合层统一挂载 X-Internal-Token 校验。
"""

from fastapi import APIRouter, Depends

from app.api.routes import code, consistency, defense, health, report, template
from app.core.security import verify_internal_token

api_router = APIRouter()

# 探活：不挂令牌依赖，方便 Docker / 运维直接访问
api_router.include_router(health.router)

internal_router = APIRouter(
    prefix="/internal/ai",
    dependencies=[Depends(verify_internal_token)],
)
internal_router.include_router(defense.router)
internal_router.include_router(report.router)
internal_router.include_router(code.router)
internal_router.include_router(consistency.router)
internal_router.include_router(template.router)

api_router.include_router(internal_router)
