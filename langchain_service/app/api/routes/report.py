"""报告评审接口。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_report_service
from app.schemas.report import (
    ReportAggregateRequest,
    ReportEvaluateRequest,
    ReportEvaluationResponse,
)
from app.services.report_service import ReportService

router = APIRouter(prefix="/report", tags=["报告评审"])


@router.post("/evaluate", response_model=ReportEvaluationResponse)
async def evaluate_report(
    request: ReportEvaluateRequest,
    service: ReportService = Depends(get_report_service),
) -> ReportEvaluationResponse:
    """评审课程设计报告（对应 API 文档 21.3）。"""
    return await service.evaluate(request)


@router.post("/aggregate", response_model=ReportEvaluationResponse)
async def aggregate_report(
    request: ReportAggregateRequest,
    service: ReportService = Depends(get_report_service),
) -> ReportEvaluationResponse:
    """多份报告的汇总评审（map-reduce 的 reduce 阶段）。

    输入只有各份的评审摘要，不含报告原文：9 份报告拼原文必然爆上下文，
    而汇总需要的是「每份是什么水平」。
    """
    return await service.aggregate(request)
