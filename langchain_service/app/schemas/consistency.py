"""报告与源码一致性检查的数据模型。"""

from pydantic import Field

from app.schemas.common import CamelModel
from app.schemas.materials import ReportMaterial, SourceMaterial


class ConsistencyRequest(CamelModel):
    """一致性检查请求（对应 API 文档 21.5）。"""

    submission_id: str = ""
    report: ReportMaterial = Field(default_factory=ReportMaterial)
    source: SourceMaterial = Field(default_factory=SourceMaterial)
    max_entities: int = 50


class ConsistencyIssue(CamelModel):
    """一致性检查发现的问题。"""

    type: str
    severity: str = "MEDIUM"
    message: str
    report_section: str = ""
    code_ref: str = ""


class ConsistencyResponse(CamelModel):
    """一致性检查结果。"""

    submission_id: str = ""
    score: float
    issues: list[ConsistencyIssue] = Field(default_factory=list)
    checked_entities: int = 0
    method: str = "local-rag"
