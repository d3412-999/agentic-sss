"""骨架阶段占位响应的统一构造。"""

from fastapi.responses import JSONResponse

STAGE = "skeleton"


def not_implemented(endpoint: str, hint: str) -> JSONResponse:
    """返回 501，让 Java 侧 AiServiceClient 统一降级为 50301 AI_UNAVAILABLE。

    刻意不返回 200 + 假数据：调用方需要能明确区分「已实现」与「占位」，
    否则上层会把空评分当成真实结果落库。
    """
    return JSONResponse(
        status_code=501,
        content={
            "code": 501,
            "message": "not implemented",
            "data": {"endpoint": endpoint, "stage": STAGE, "hint": hint},
        },
    )
