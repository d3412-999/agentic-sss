"""确定性 Java 规则扫描。

混合评审架构的「确定性一侧」：先用可解释、可复现的规则扫出事实，
再把规则命中作为证据交给 LLM 做语义评审（而不是让 LLM 凭感觉猜问题）。
规则全部基于源码文本与行号，不依赖外部 Java 工具链，保证离线可用。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.schemas.code import CodeFinding, RuleSummary
from app.schemas.materials import SourceFile, SourceIssue

SEVERITIES = ("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")
SEVERITY_RANK = {name: index for index, name in enumerate(SEVERITIES)}
TEST_PATH = re.compile(r"(?i)(^|/)(test|tests)/|Test\.java$")


@dataclass(frozen=True)
class RuleSpec:
    rule_id: str
    title: str
    severity: str
    suggestion: str
    pattern: re.Pattern[str]
    skip_test_files: bool = True


RULES: tuple[RuleSpec, ...] = (
    RuleSpec(
        rule_id="SQL_CONCAT",
        title="SQL 语句字符串拼接（注入风险）",
        severity="CRITICAL",
        suggestion="改用 PreparedStatement 占位符，把参数作为 setXxx 传入，不要拼接到 SQL 字符串里",
        pattern=re.compile(r"(?i)(execute(?:query|update)?|prepareStatement)\s*\([^;\n]*\+"),
    ),
    RuleSpec(
        rule_id="HARDCODED_SECRET",
        title="硬编码口令/密钥",
        severity="CRITICAL",
        suggestion="改为从环境变量或配置中心读取，并轮换已泄露的凭据",
        pattern=re.compile(
            r"(?i)(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token)\s*=\s*\"[^\"]{3,}\""
        ),
    ),
    RuleSpec(
        rule_id="EMPTY_CATCH",
        title="空 catch 块吞掉异常",
        severity="HIGH",
        suggestion="至少记录日志或转换成业务异常向上抛出，避免静默失败",
        pattern=re.compile(r"catch\s*\([^)]*\)\s*\{\s*(?://[^\n]*\s*)?\}", re.S),
    ),
    RuleSpec(
        rule_id="SYSTEM_EXIT",
        title="业务代码调用 System.exit",
        severity="HIGH",
        suggestion="把退出决策交给入口层，业务类抛出异常或返回结果即可",
        pattern=re.compile(r"System\s*\.\s*exit\s*\("),
    ),
    RuleSpec(
        rule_id="BROAD_CATCH",
        title="捕获 Exception/Throwable",
        severity="MEDIUM",
        suggestion="只捕获能处理的具体异常；确实需要兜底时记录日志并说明原因",
        pattern=re.compile(r"catch\s*\(\s*(?:java\.lang\.)?(?:Exception|Throwable)\b"),
    ),
    RuleSpec(
        rule_id="PRINT_STACK_TRACE",
        title="用 printStackTrace 输出异常",
        severity="MEDIUM",
        suggestion="改用日志框架记录，附带上下文与 traceId，便于排查",
        pattern=re.compile(r"\.printStackTrace\s*\("),
    ),
    RuleSpec(
        rule_id="THREAD_SLEEP",
        title="使用 Thread.sleep 做等待/重试",
        severity="MEDIUM",
        suggestion="避免阻塞式 sleep；改用超时、调度器或带退避的重试策略",
        pattern=re.compile(r"Thread\s*\.\s*sleep\s*\("),
    ),
    RuleSpec(
        rule_id="RESOURCE_LEAK",
        title="资源对象可能未关闭",
        severity="MEDIUM",
        suggestion="使用 try-with-resources 或在 finally 中关闭连接/流/ResultSet",
        pattern=re.compile(
            r"(?:FileInputStream|FileOutputStream|FileReader|FileWriter|Socket|ServerSocket|Connection|Statement|PreparedStatement|ResultSet|Scanner)\s+\w+\s*=\s*new\s+"
        ),
    ),
    RuleSpec(
        rule_id="SYSTEM_OUT",
        title="业务代码使用 System.out/System.err",
        severity="LOW",
        suggestion="生产代码改用日志框架；控制台输出保留在调试或测试代码中",
        pattern=re.compile(r"System\s*\.\s*(?:out|err)\s*\.\s*print"),
    ),
    RuleSpec(
        rule_id="TODO_FIXME",
        title="代码中残留 TODO/FIXME",
        severity="LOW",
        suggestion="提交前清理待办标记，或关联任务编号并说明计划",
        pattern=re.compile(r"(?i)(?://|/\*|\*)\s*(?:TODO|FIXME|XXX)\b"),
    ),
)


def _line_of(content: str, index: int) -> int:
    return content.count("\n", 0, index) + 1


def _evidence(line: str, limit: int = 160) -> str:
    text = line.strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


def scan_java_files(files: list[SourceFile]) -> list[CodeFinding]:
    """对提交的源码文本做规则扫描，返回带文件/行号的确定性 findings。"""
    findings: list[CodeFinding] = []
    for source_file in files:
        content = source_file.content or ""
        if not content.strip():
            continue
        path = source_file.path or "(未命名文件)"
        is_test = bool(TEST_PATH.search(path))
        lines = content.splitlines()
        for rule in RULES:
            if rule.skip_test_files and is_test:
                continue
            for match in rule.pattern.finditer(content):
                line_no = _line_of(content, match.start())
                raw_line = lines[line_no - 1] if 0 < line_no <= len(lines) else match.group(0)
                findings.append(
                    CodeFinding(
                        rule_id=rule.rule_id,
                        title=rule.title,
                        message=f"{rule.title}",
                        severity=rule.severity,
                        file=path,
                        line=line_no,
                        evidence=_evidence(raw_line),
                        suggestion=rule.suggestion,
                        source="RULE",
                        confidence=1.0,
                    )
                )
        # 超长文件视为职责过多的弱信号，只给 INFO，避免干扰评分
        if len(lines) > 600 and not is_test:
            findings.append(
                CodeFinding(
                    rule_id="GOD_CLASS",
                    title="单文件过长，类可能承担过多职责",
                    message=f"文件 {path} 共 {len(lines)} 行，建议按职责拆分",
                    severity="INFO",
                    file=path,
                    line=None,
                    evidence=lines[0].strip() if lines else "",
                    suggestion="按单一职责拆分模块/类，控制单文件规模",
                    source="RULE",
                    confidence=0.6,
                )
            )
    return findings


def tool_findings(issues: list[SourceIssue]) -> list[CodeFinding]:
    """把后端传入的静态分析问题（PMD/Checkstyle/SpotBugs 等）规范成同一结构。"""
    findings: list[CodeFinding] = []
    for issue in issues:
        severity = (issue.severity or "").upper()
        if severity not in SEVERITY_RANK:
            severity = "MEDIUM"
        rule = (issue.rule or "").strip() or "static"
        tool = (issue.tool or "TOOL").strip()
        findings.append(
            CodeFinding(
                rule_id=f"{tool}:{rule}",
                title=rule,
                message=issue.message or rule,
                severity=severity,
                file=issue.file or "",
                line=issue.line,
                evidence="",
                suggestion="按静态工具提示修复该项",
                source="TOOL",
                confidence=1.0,
            )
        )
    return findings


def merge_findings(*groups: list[CodeFinding]) -> list[CodeFinding]:
    """合并规则/工具/LLM 三路 findings，按 (source, file, line, ruleId) 去重并排序。"""
    merged: dict[tuple, CodeFinding] = {}
    for group in groups:
        for finding in group:
            key = (
                finding.source,
                finding.file,
                finding.line,
                finding.rule_id.lower(),
            )
            existing = merged.get(key)
            if existing is None or SEVERITY_RANK.get(finding.severity, 9) < SEVERITY_RANK.get(existing.severity, 9):
                merged[key] = finding
    return sorted(
        merged.values(),
        key=lambda item: (
            SEVERITY_RANK.get(item.severity, 9),
            item.file or "",
            item.line or 0,
            item.rule_id,
        ),
    )


def score_cap(findings: list[CodeFinding]) -> float | None:
    """按确定性 findings 给出综合分上限。

    课程设计场景以「完成题目要求」为主，通用规则/安全规范只做提示：
    下限放宽，避免学生只是写了 System.out 或 TODO 就被压到不及格。
    """
    critical = sum(1 for item in findings if item.severity == "CRITICAL")
    high = sum(1 for item in findings if item.severity == "HIGH")
    medium = sum(1 for item in findings if item.severity == "MEDIUM")
    if critical:
        return 60.0
    if high >= 2:
        return 72.0
    if high:
        return 80.0
    if medium >= 4:
        return 88.0
    if medium >= 2:
        return 92.0
    return None


def summarize(findings: list[CodeFinding]) -> RuleSummary:
    """汇总 findings 并按严重度给出 verdict。"""
    counts = {name: 0 for name in SEVERITIES}
    by_rule: dict[str, int] = {}
    for item in findings:
        counts[item.severity if item.severity in counts else "MEDIUM"] += 1
        by_rule[item.rule_id] = by_rule.get(item.rule_id, 0) + 1
    if counts["CRITICAL"] or counts["HIGH"] >= 2:
        verdict = "BLOCK"
    elif counts["HIGH"] or counts["MEDIUM"] >= 2:
        verdict = "WARN"
    else:
        verdict = "PASS"
    return RuleSummary(
        total=len(findings),
        critical=counts["CRITICAL"],
        high=counts["HIGH"],
        medium=counts["MEDIUM"],
        low=counts["LOW"],
        by_rule=by_rule,
        score_cap=score_cap(findings),
        verdict=verdict,
    )


def format_for_prompt(findings: list[CodeFinding], limit: int = 20) -> str:
    """把 findings 格式化成提示词里的「确定性扫描结果」段落。"""
    if not findings:
        return "（确定性规则未发现问题）"
    lines = []
    for item in findings[:limit]:
        location = f"{item.file}:{item.line}" if item.line else (item.file or "(未知位置)")
        source = {"RULE": "规则", "TOOL": "静态工具", "LLM": "语义"}.get(item.source, item.source)
        lines.append(
            f"- [{item.severity}][{source}] {item.rule_id} {location}：{item.message}"
            + (f"（证据：{item.evidence}）" if item.evidence else "")
        )
    if len(findings) > limit:
        lines.append(f"- ……另有 {len(findings) - limit} 条，按严重度已截断")
    return "\n".join(lines)