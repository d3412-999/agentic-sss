"""FastAPI 依赖：业务服务单例。

内部令牌校验见 app/core/security.py，由路由聚合层统一挂载。
"""

from functools import lru_cache

from app.core.config import get_settings
from app.rag.lightrag_client import LightRagClient
from app.rag.rag_service import RagService
from app.services.asr_service import AsrService
from app.services.code_service import CodeReviewService
from app.services.consistency_service import ConsistencyService
from app.services.defense_service import DefenseService
from app.services.face_presence_service import FacePresenceService
from app.services.llm_service import LlmService
from app.services.report_service import ReportService
from app.services.template_service import TemplateService


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
def get_template_service() -> TemplateService:
    """获取优秀作品模板库服务单例。"""
    return TemplateService(get_settings())


@lru_cache
def get_report_service() -> ReportService:
    """获取报告评审服务单例。"""
    return ReportService(get_settings(), get_llm_service(), get_template_service())


@lru_cache
def get_code_service() -> CodeReviewService:
    """获取源码评审服务单例。"""
    return CodeReviewService(get_settings(), get_llm_service())


@lru_cache
def get_consistency_service() -> ConsistencyService:
    """获取一致性检查服务单例。"""
    return ConsistencyService(get_settings(), get_rag_service())


@lru_cache
def get_asr_service() -> AsrService:
    """获取语音转写服务单例（sherpa_onnx 在首次识别时懒加载）。"""
    return AsrService(get_settings())


@lru_cache
def get_face_presence_service() -> FacePresenceService:
    """获取人脸在场检测服务单例（opencv 在首次检测时懒加载）。"""
    return FacePresenceService(get_settings())
