"""源码语义评审的数据模型。

兼容两种入参：
1. 内部完整结构：javaFiles / metrics / issues；
2. Java 后端 AiServiceClient 的简化结构：codeSnippet / buildTool / topicTitle。
"""

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore
from app.schemas.materials import SourceMaterial, SubmissionFileBrief


class TopicAttachmentBrief(CamelModel):
    """教师题目附件摘要（PDF/DOCX 已抽正文，其他格式只有文件名）。"""

    name: str = ""
    text: str = ""


class CodeReviewRequest(SourceMaterial):
    """源码语义评审请求（对应 API 文档 21.4）。"""

    selection_id: str = ""
    topic_title: str = ""
    # 教师发布的题目描述与附件正文：课程设计只按这些要求评价，不套企业级标准
    topic_description: str = ""
    topic_attachments: list[TopicAttachmentBrief] = Field(default_factory=list)
    build_tool: str = "PLAIN"
    # Java 侧把源码 zip 中的 Java 文件拼成一段文本后通过 codeSnippet 传入
    code_snippet: str = ""
    # 多文件提交时的文件清单（1~9 份源码包）。旧调用方不传，保持空列表即可。
    source_files: list[SubmissionFileBrief] = Field(default_factory=list)


class CodeFinding(CamelModel):
    """一条可定位的代码问题（混合评审：规则/工具/LLM 三路共用同一结构）。

    与 open-code-review 的 line-level finding 对齐：文件 + 行号 + 严重度 + 证据 + 修复建议，
    规则侧置信度固定为 1.0，启发式/LLM 侧可低于 1.0。
    """

    rule_id: str = ""
    title: str = ""
    message: str = ""
    severity: str = "MEDIUM"
    file: str = ""
    line: int | None = None
    evidence: str = ""
    suggestion: str = ""
    # RULE（确定性规则）/ TOOL（PMD/Checkstyle 等）/ LLM（语义评审补充）
    source: str = "RULE"
    confidence: float = 1.0


class RuleSummary(CamelModel):
    """确定性扫描汇总：计数 + 分数上限 + 阻断建议。"""

    total: int = 0
    critical: int = 0
    high: int = 0
    medium: int = 0
    low: int = 0
    by_rule: dict[str, int] = Field(default_factory=dict)
    score_cap: float | None = None
    verdict: str = "PASS"


class CodeReviewDraft(CamelModel):
    """大模型输出的源码评审草稿。"""

    design_score: float = 0
    design_comment: str = ""
    doc_score: float = 0
    doc_comment: str = ""
    suggestions: list[str] = Field(default_factory=list)
    summary: str = ""
    # 混合架构：LLM 在规则已命中的问题上不再重复，可补充语义类 finding（可选、允许为空）
    findings: list[CodeFinding] = Field(default_factory=list)


class FileReviewBrief(CamelModel):
    """单个文件的评审摘要，作为汇总（reduce）阶段的输入。

    只送摘要不送原始代码：9 份工程拼原文必然爆上下文，
    而汇总阶段真正需要的是「每份是什么水平、有哪些问题」。
    """

    name: str = ""
    score: float | None = None
    summary: str = ""
    problems: list[str] = Field(default_factory=list)
    java_files: int = 0
    code_lines: int = 0
    # 该文件的分片评审是否失败/被截断，汇总时要据此降低置信度
    degraded: bool = False


class CodeAggregateRequest(CamelModel):
    """多文件源码评审的汇总请求（map-reduce 的 reduce 阶段）。"""

    submission_id: str = ""
    selection_id: str = ""
    topic_title: str = ""
    build_tool: str = "PLAIN"
    files: list[FileReviewBrief] = Field(default_factory=list)


class CodeReviewResponse(CamelModel):
    """源码语义评审结果。

    score 是后端落库用的综合分（设计 60% + 注释 40%），
    dimensions 仍然保留两个维度的明细。
    """

    submission_id: str = ""
    score: float = 0
    model: str = ""
    dimensions: list[DimensionScore] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    problems: list[str] = Field(default_factory=list)
    # 混合评审产出：规则 + 工具 + LLM 合并后的行级 findings 与确定性汇总
    findings: list[CodeFinding] = Field(default_factory=list)
    rule_summary: RuleSummary = Field(default_factory=RuleSummary)
    verdict: str = "PASS"
    summary: str = ""
