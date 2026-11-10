"""通用数据模型与序列化约定。

对外字段使用 camelCase（与 API 文档一致），内部 Python 字段使用 snake_case，
通过 alias_generator 自动转换。
"""

from pydantic import BaseModel, ConfigDict


def to_camel(value: str) -> str:
    """将 snake_case 转为 camelCase。"""
    first, *rest = value.split("_")
    return first + "".join(word[:1].upper() + word[1:] for word in rest)


class CamelModel(BaseModel):
    """统一使用 camelCase 别名的基类。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )


class DimensionScore(CamelModel):
    """单个评分维度的结果。"""

    key: str
    name: str
    weight: float
    score: float
    comment: str = ""


class SourceRef(CamelModel):
    """答辩题目的来源引用，指向源码文件或报告章节。"""

    type: str
    path: str | None = None
    line: int | None = None
    section: str | None = None
