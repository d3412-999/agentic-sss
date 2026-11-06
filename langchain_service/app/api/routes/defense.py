"""答辩相关内部接口（骨架，暂未实现）。"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.api.deps import require_internal_token

router = APIRouter(
    prefix="/internal/ai",
    tags=["defense"],
    dependencies=[Depends(require_internal_token)],
)


@router.post("/defense/questions", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def generate_defense_questions(payload: dict[str, Any]) -> None:
    """根据题目、源码、报告生成答辩题（对应 API 文档 21.1）。"""
    raise HTTPException(status_code=501, detail="defense question generation is not implemented yet")


@router.post("/defense/evaluate", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def evaluate_defense(payload: dict[str, Any]) -> None:
    """根据转写稿与音频评分（对应 API 文档 21.2）。"""
    raise HTTPException(status_code=501, detail="defense evaluation is not implemented yet")
