"""混合评审服务测试：确定性规则 + 工具问题 + LLM 语义 findings 的合并与评分封顶。"""

import asyncio

from app.core.config import Settings
from app.schemas.code import CodeFinding, CodeReviewDraft, CodeReviewRequest
from app.schemas.materials import SourceFile, SourceIssue
from app.services.code_service import CodeReviewService
from tests.llm_doubles import FakeLlm

SETTINGS = Settings()


def _request(code: str, issues: list[SourceIssue] | None = None) -> CodeReviewRequest:
    return CodeReviewRequest(
        submission_id="s1",
        java_files=[SourceFile(path="src/main/java/com/example/A.java", content=code)],
        issues=issues or [],
    )


def _draft(design: float = 80, doc: float = 70, findings: list[CodeFinding] | None = None) -> CodeReviewDraft:
    return CodeReviewDraft(
        design_score=design,
        design_comment="分层清晰",
        doc_score=doc,
        doc_comment="注释一般",
        suggestions=["补充异常处理"],
        summary="整体尚可",
        findings=findings or [],
    )


def test_rules_cap_score_and_are_exposed_as_findings() -> None:
    code = (
        "public class A {\n"
        "  public void run() throws Exception {\n"
        "    try { work(); } catch (Exception e) { }\n"
        '    String password = "root123";\n'
        '    System.out.println("debug");\n'
        "  }\n"
        "}\n"
    )
    fake = FakeLlm(_draft(design=90, doc=85))
    response = asyncio.run(CodeReviewService(SETTINGS, fake).review(_request(code)))

    rule_ids = {item.rule_id for item in response.findings}
    assert "HARDCODED_SECRET" in rule_ids
    assert "EMPTY_CATCH" in rule_ids
    assert "SYSTEM_OUT" in rule_ids
    assert response.rule_summary.critical >= 1
    assert response.verdict == "BLOCK"
    # 90 * 0.6 + 85 * 0.4 = 88，但 CRITICAL 规则把总分封顶到 60（课程设计尺度从宽）
    assert response.score == 60.0
    assert "封顶" in response.summary
    # 提示词里确实带上了确定性扫描结果
    assert fake.prompts and "确定性扫描结果" in fake.prompts[-1]
    assert "HARDCODED_SECRET" in fake.prompts[-1]


def test_llm_semantic_findings_are_merged_and_prefixed() -> None:
    semantic = CodeFinding(
        rule_id="NAMING",
        title="变量命名语义不清",
        message="字段 x 无法表达业务含义",
        severity="MEDIUM",
        file="src/main/java/com/example/A.java",
        line=2,
    )
    fake = FakeLlm(_draft(design=80, doc=70, findings=[semantic]))
    response = asyncio.run(
        CodeReviewService(SETTINGS, fake).review(_request("public class A {\n  private int x;\n}\n"))
    )

    llm_findings = [item for item in response.findings if item.source == "LLM"]
    assert len(llm_findings) == 1
    assert llm_findings[0].rule_id == "SEMANTIC_NAMING"
    assert llm_findings[0].severity == "MEDIUM"
    # 76 分没有触发封顶（仅 1 个 MEDIUM）
    assert response.score == 76.0
    assert response.verdict == "PASS"


def test_tool_issues_are_merged_into_findings() -> None:
    issue = SourceIssue(
        tool="PMD",
        rule="UnusedLocalVariable",
        severity="MEDIUM",
        file="src/main/java/com/example/A.java",
        line=12,
        message="局部变量未使用",
    )
    fake = FakeLlm(_draft(design=80, doc=80))
    response = asyncio.run(
        CodeReviewService(SETTINGS, fake).review(_request("public class A { }\n", issues=[issue]))
    )
    tool_findings = [item for item in response.findings if item.source == "TOOL"]
    assert tool_findings and tool_findings[0].rule_id == "PMD:UnusedLocalVariable"
    assert response.rule_summary.medium == 1
    assert response.score == 80.0


def test_clean_code_has_no_findings_and_keeps_score() -> None:
    fake = FakeLlm(_draft(design=80, doc=70))
    response = asyncio.run(
        CodeReviewService(SETTINGS, fake).review(_request("public class A { public int add(int a, int b) { return a + b; } }\n"))
    )
    assert response.findings == []
    assert response.rule_summary.total == 0
    assert response.verdict == "PASS"
    assert response.score == 76.0


def test_code_snippet_only_payload_is_scanned() -> None:
    request = CodeReviewRequest(
        submission_id="s1",
        code_snippet='public class A { void f() { System.exit(1); } }',
    )
    fake = FakeLlm(_draft(design=80, doc=70))
    response = asyncio.run(CodeReviewService(SETTINGS, fake).review(request))
    assert any(item.rule_id == "SYSTEM_EXIT" for item in response.findings)


def test_code_snippet_with_archive_guard_markers_keeps_real_paths() -> None:
    """ArchiveGuard 的 `// 文件路径` 头必须拆回原文件，否则前端行级展示定位不到源码。"""
    snippet = (
        "// src/main/java/com/example/BadService.java\n"
        "public class BadService {\n"
        "  void f() { System.exit(1); }\n"
        "}\n"
    )
    request = CodeReviewRequest(submission_id="s1", code_snippet=snippet)
    fake = FakeLlm(_draft(design=80, doc=70))
    response = asyncio.run(CodeReviewService(SETTINGS, fake).review(request))
    exit_finding = next(item for item in response.findings if item.rule_id == "SYSTEM_EXIT")
    assert exit_finding.file == "src/main/java/com/example/BadService.java"
    assert exit_finding.line == 2
