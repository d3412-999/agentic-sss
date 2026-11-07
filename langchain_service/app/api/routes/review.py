"""评审链路占位：路径与 Java AiServiceClient 21.3 / 21.4 / 21.5 一一对应。"""

from typing import Any

from fastapi import APIRouter

from app.api.routes.common import not_implemented

router = APIRouter(tags=["review"])


@router.post("/report/evaluate")
async def evaluate_report(payload: dict[str, Any]):
    """课程设计报告评审（API 文档 21.3）。"""
    return not_implemented(
        "/internal/ai/report/evaluate",
        "需先实现 PDF/DOCX 解析与评分要点抽取",
    )


@router.post("/code/review")
async def review_code(payload: dict[str, Any]):
    """源码语义评审（API 文档 21.4）。"""
    return not_implemented(
        "/internal/ai/code/review",
        "需先接入 Checkstyle / PMD / SpotBugs 静态分析结果",
    )


@router.post("/consistency")
async def check_consistency(payload: dict[str, Any]):
    """报告-源码一致性检查（API 文档 21.5，规划用 LightRAG）。"""
    return not_implemented(
        "/internal/ai/consistency",
        "需先启动 LightRAG 服务并灌入报告与源码索引",
    )
