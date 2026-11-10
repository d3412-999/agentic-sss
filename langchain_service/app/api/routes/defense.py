"""答辩接口：生成答辩题、答辩评分。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_defense_service
from app.schemas.defense import (
    DefenseEvaluateRequest,
    DefenseEvaluationResponse,
    DefenseQuestionRequest,
    DefenseQuestionResponse,
)
from app.services.defense_service import DefenseService

router = APIRouter(prefix="/defense", tags=["答辩"])


@router.post("/questions", response_model=DefenseQuestionResponse)
async def generate_questions(
    request: DefenseQuestionRequest,
    service: DefenseService = Depends(get_defense_service),
) -> DefenseQuestionResponse:
    """根据题目、源码与报告生成答辩题（对应 API 文档 21.1）。"""
    return await service.generate_questions(request)


@router.post("/evaluate", response_model=DefenseEvaluationResponse)
async def evaluate_defense(
    request: DefenseEvaluateRequest,
    service: DefenseService = Depends(get_defense_service),
) -> DefenseEvaluationResponse:
    """根据答辩转写稿与音频元信息评分（对应 API 文档 21.2）。"""
    return await service.evaluate(request)
