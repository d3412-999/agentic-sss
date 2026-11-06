"""报告 / 源码评审内部接口（骨架，暂未实现）。"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.api.deps import require_internal_token

router = APIRouter(
    prefix="/internal/ai",
    tags=["review"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/report/evaluate", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def evaluate_report(payload: dict[str, Any]) -> None:
    """课程设计报告评审（对应 API 文档 21.3）。"""
    raise HTTPException(status_code=501, detail="report evaluation is not implemented yet")


@router.post("/code/review", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def review_code(payload: dict[str, Any]) -> None:
    """源码语义评审（对应 API 文档 21.4）。"""
    raise HTTPException(status_code=501, detail="code review is not implemented yet")


@router.post("/consistency", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def check_consistency(payload: dict[str, Any]) -> None:
    """报告-源码一致性检查（对应 API 文档 21.5）。"""
    raise HTTPException(status_code=501, detail="consistency check is not implemented yet")
