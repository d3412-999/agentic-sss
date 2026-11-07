"""源码语义评审接口。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_code_service, require_internal_token
from app.schemas.code import CodeReviewRequest, CodeReviewResponse
from app.services.code_service import CodeReviewService

router = APIRouter(
    prefix="/internal/ai/code",
    tags=["源码评审"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/review", response_model=CodeReviewResponse)
async def review_code(
    request: CodeReviewRequest,
    service: CodeReviewService = Depends(get_code_service),
) -> CodeReviewResponse:
    """评审源码的设计合理性与注释文档（对应 API 文档 21.4）。"""
    return await service.review(request)
