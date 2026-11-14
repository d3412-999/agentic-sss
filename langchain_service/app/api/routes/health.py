"""健康检查：同时暴露大模型、ASR、人脸检测与 LightRAG 的配置状态。"""

from fastapi import APIRouter

from app import __version__
from app.core.config import get_settings

router = APIRouter(tags=["健康检查"])


@router.get("/health")
async def health() -> dict[str, object]:
    """返回服务状态与依赖配置情况（provider 为固定真实实现）。"""
    settings = get_settings()
    return {
        "status": "UP",
        "app": settings.app_name,
        "environment": settings.environment,
        "version": __version__,
        "llmProvider": "openai",
        "llmModel": settings.llm_model,
        "llmReady": settings.llm_ready,
        "asrProvider": "sherpa_onnx",
        "faceProvider": "opencv",
        "lightragEnabled": settings.lightrag_enabled,
    }