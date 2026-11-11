"""聚合所有路由。

对外只有 GET /health（免鉴权）；
/internal/ai/** 是后端专用契约，路径与 Java 侧 AiServiceClient.PREFIX 严格对应，
并在聚合层统一挂载 X-Internal-Token 校验。

例外：WebSocket 路由（/internal/ai/asr/stream）不走 HTTP 依赖链，
握手令牌在 handler 内手动校验，失败以 4401 关闭（见 app/api/routes/asr.py）。
"""

from fastapi import APIRouter, Depends

from app.api.routes import asr, code, consistency, defense, face, health, report, template
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
internal_router.include_router(asr.router)
internal_router.include_router(face.router)
internal_router.include_router(template.router)

api_router.include_router(internal_router)

# 语音转写 WebSocket：路径已含 /internal/ai 前缀，手动校验令牌（见 routes/asr.py）
api_router.include_router(asr.ws_router)
