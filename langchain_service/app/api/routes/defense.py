"""答辩链路占位：路径与 Java AiServiceClient 21.1 / 21.2 一一对应。"""

from typing import Any

from fastapi import APIRouter

from app.api.routes.common import not_implemented

router = APIRouter(tags=["defense"])


@router.post("/defense/questions")
async def generate_defense_questions(payload: dict[str, Any]):
    """根据题目、源码与报告生成答辩题（API 文档 21.1）。"""
    return not_implemented(
        "/internal/ai/defense/questions",
        "接入 LLM 后按 topic / source / report 生成问题列表",
    )


@router.post("/defense/evaluate")
async def evaluate_defense(payload: dict[str, Any]):
    """根据答辩转写稿与音频给分（API 文档 21.2）。"""
    return not_implemented(
        "/internal/ai/defense/evaluate",
        "需先实现 ASR 转写与 WebSocket 答辩会话",
    )
