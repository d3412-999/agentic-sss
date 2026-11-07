"""FastAPI 依赖：内部令牌校验与服务单例。"""

from functools import lru_cache

from fastapi import Header, HTTPException, status

from app.core.config import get_settings
from app.rag.lightrag_client import LightRagClient
from app.rag.rag_service import RagService
from app.services.code_service import CodeReviewService
from app.services.consistency_service import ConsistencyService
from app.services.defense_service import DefenseService
from app.services.llm_service import LlmService
from app.services.report_service import ReportService


async def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    """校验 Java 后端传入的内部令牌；健康检查不经过此依赖。"""
    settings = get_settings()
    if x_internal_token != settings.internal_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="内部令牌无效",
        )


@lru_cache
def get_llm_service() -> LlmService:
    """获取大模型服务单例。"""
    return LlmService(get_settings())


@lru_cache
def get_rag_service() -> RagService:
    """获取 RAG 服务单例；仅在启用时创建 LightRAG 客户端。"""
    settings = get_settings()
    client = LightRagClient(settings) if settings.lightrag_enabled else None
    return RagService(settings, client)


@lru_cache
def get_defense_service() -> DefenseService:
    """获取答辩服务单例。"""
    return DefenseService(get_settings(), get_llm_service(), get_rag_service())


@lru_cache
def get_report_service() -> ReportService:
    """获取报告评审服务单例。"""
    return ReportService(get_settings(), get_llm_service())


@lru_cache
def get_code_service() -> CodeReviewService:
    """获取源码评审服务单例。"""
    return CodeReviewService(get_settings(), get_llm_service())


@lru_cache
def get_consistency_service() -> ConsistencyService:
    """获取一致性检查服务单例。"""
    return ConsistencyService(get_settings(), get_rag_service())
