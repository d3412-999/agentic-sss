"""评分维度定义与计算工具。"""

import math
from dataclasses import dataclass

from app.schemas.common import DimensionScore


@dataclass(frozen=True)
class DimensionSpec:
    """评分维度定义。"""

    key: str
    name: str
    weight: float


# 答辩评分维度（权重合计 100）
DEFENSE_DIMENSION_SPECS: tuple[DimensionSpec, ...] = (
    DimensionSpec("ACCURACY", "内容准确性", 40),
    DimensionSpec("DEPTH", "理解深度", 25),
    DimensionSpec("EXPRESSION", "表达流畅度", 20),
    DimensionSpec("INTERACTION", "应变与追问表现", 15),
)

# 报告评分维度（权重合计 100）
REPORT_DIMENSION_SPECS: tuple[DimensionSpec, ...] = (
    DimensionSpec("STRUCTURE", "结构完整性", 15),
    DimensionSpec("REQUIREMENT", "需求分析", 20),
    DimensionSpec("DESIGN", "系统设计", 25),
    DimensionSpec("IMPLEMENTATION", "实现说明", 20),
    DimensionSpec("TESTING", "测试与验证", 10),
    DimensionSpec("FORMAT", "格式规范", 10),
)

# 源码语义评审维度（AI 只负责这两项，其余由静态工具计算）
CODE_DIMENSION_SPECS: tuple[DimensionSpec, ...] = (
    DimensionSpec("DESIGN", "设计合理性", 15),
    DimensionSpec("DOC", "注释与文档", 10),
)


def normalize_dimensions(provided: list[DimensionScore], specs: tuple[DimensionSpec, ...]) -> list[DimensionScore]:
    """按固定维度定义整理大模型输出：补齐缺失项、限制分数范围、统一权重。"""
    provided_map = {dimension.key: dimension for dimension in provided}
    normalized: list[DimensionScore] = []
    for spec in specs:
        source = provided_map.get(spec.key)
        score = clamp_score(source.score) if source else 0.0
        comment = source.comment if source else ""
        normalized.append(
            DimensionScore(
                key=spec.key,
                name=spec.name,
                weight=spec.weight,
                score=score,
                comment=comment,
            )
        )
    return normalized


def weighted_total(dimensions: list[DimensionScore]) -> float:
    """按权重计算百分制总分（每个维度与总分都限制在 0~100）。"""
    total = sum(clamp_score(dimension.score) * dimension.weight / 100 for dimension in dimensions)
    return clamp_score(total)


def grade_of(score: float | None) -> str:
    """根据百分制分数返回等级（先做 0~100 归一，避免越界值落到错误等级）。"""
    value = clamp_score(score)
    if value >= 90:
        return "优秀"
    if value >= 80:
        return "良好"
    if value >= 70:
        return "中等"
    if value >= 60:
        return "及格"
    return "不及格"


def clamp_score(score: float | None) -> float:
    """限制分数在 0~100 之间并保留一位小数。

    容错：None / 非数值 / NaN 统一按 0 处理（绝不能因为异常值把总分抬高）；
    ±Infinity 按边界处理；字符串数字（如 "85"）仍可正常转换。
    """
    if score is None:
        return 0.0
    try:
        value = float(score)
    except (TypeError, ValueError):
        return 0.0
    if math.isnan(value):
        return 0.0
    if value == math.inf:
        return 100.0
    if value == -math.inf:
        return 0.0
    return round(max(0.0, min(100.0, value)), 1)
