"""内部调用鉴权：只认 Java 后端携带的「工牌」。"""

from fastapi import Header, HTTPException, status

from app.core.config import get_settings


async def verify_internal_token(
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    """校验 X-Internal-Token。

    与 Java 侧 AiServiceClient 的 ``defaultHeader("X-Internal-Token", ...)``
    以及 ``sss.ai.internal-token`` 对应；两边默认值都是 ``change-me``。
    AI 服务不对浏览器开放，前端永远不应直接调用 /internal/ai/**。
    """
    expected = get_settings().internal_token
    if expected and x_internal_token != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid or missing X-Internal-Token",
        )
