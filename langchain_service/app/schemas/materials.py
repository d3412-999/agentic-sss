"""课程设计材料模型：题目、源码、报告。

这些模型既是 Java 后端调用本服务的入参，也是内部提示词的数据来源。
"""

from typing import Any

from pydantic import Field

from app.schemas.common import CamelModel


class OfferingBrief(CamelModel):
    """开课信息摘要。"""

    course_name: str = ""
    semester: str = ""


class TopicBrief(CamelModel):
    """题目摘要。"""

    topic_id: str = ""
    title: str
    requirements: str = ""
    difficulty: str = "MEDIUM"


class SourceFile(CamelModel):
    """单个 Java 源文件。"""

    path: str
    content: str = ""


class SourceIssue(CamelModel):
    """静态分析工具发现的源码问题。"""

    tool: str = ""
    rule: str = ""
    severity: str = ""
    file: str = ""
    line: int | None = None
    message: str = ""


class SourceMaterial(CamelModel):
    """源码材料：文件、度量指标与静态分析问题。"""

    submission_id: str = ""
    java_files: list[SourceFile] = Field(default_factory=list)
    metrics: dict[str, Any] = Field(default_factory=dict)
    issues: list[SourceIssue] = Field(default_factory=list)


class ReportSection(CamelModel):
    """报告章节。"""

    title: str
    content: str = ""


class ReportMaterial(CamelModel):
    """课程设计报告材料：章节结构与页数。"""

    submission_id: str = ""
    sections: list[ReportSection] = Field(default_factory=list)
    page_count: int = 0
