"""源码语义评审接口。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_code_service
from app.schemas.code import CodeAggregateRequest, CodeReviewRequest, CodeReviewResponse
from app.services.code_service import CodeReviewService

router = APIRouter(prefix="/code", tags=["源码评审"])


@router.post("/review", response_model=CodeReviewResponse)
async def review_code(
    request: CodeReviewRequest,
    service: CodeReviewService = Depends(get_code_service),
) -> CodeReviewResponse:
    """评审源码的设计合理性与注释文档（对应 API 文档 21.4）。"""
    return await service.review(request)


@router.post("/aggregate", response_model=CodeReviewResponse)
async def aggregate_code(
    request: CodeAggregateRequest,
    service: CodeReviewService = Depends(get_code_service),
) -> CodeReviewResponse:
    """多份源码包的汇总评审（map-reduce 的 reduce 阶段）。

    输入只有各份的评审摘要与得分，不含源码原文。
    """
    return await service.aggregate(request)
