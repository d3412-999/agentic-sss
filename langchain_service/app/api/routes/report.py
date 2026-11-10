"""报告评审接口。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_report_service
from app.schemas.report import ReportEvaluateRequest, ReportEvaluationResponse
from app.services.report_service import ReportService

router = APIRouter(prefix="/report", tags=["报告评审"])


@router.post("/evaluate", response_model=ReportEvaluationResponse)
async def evaluate_report(
    request: ReportEvaluateRequest,
    service: ReportService = Depends(get_report_service),
) -> ReportEvaluationResponse:
    """评审课程设计报告（对应 API 文档 21.3）。"""
    return await service.evaluate(request)
