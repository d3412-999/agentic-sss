"""确定性 Java 规则扫描测试（混合评审架构的规则侧）。"""

from app.review.java_rules import (
    format_for_prompt,
    merge_findings,
    scan_java_files,
    summarize,
    tool_findings,
)
from app.schemas.code import CodeFinding
from app.schemas.materials import SourceFile, SourceIssue


def _scan(code: str, path: str = "src/main/java/com/example/A.java") -> list[CodeFinding]:
    return scan_java_files([SourceFile(path=path, content=code)])


def test_sql_concat_and_hardcoded_secret_are_critical() -> None:
    findings = _scan(
        'class A {\n'
        '  void f() throws Exception {\n'
        '    String password = "root123";\n'
        '    stmt.executeQuery("select * from t where id=" + id);\n'
        "  }\n"
        "}\n"
    )
    ids = {item.rule_id for item in findings}
    assert "SQL_CONCAT" in ids
    assert "HARDCODED_SECRET" in ids
    critical = [item for item in findings if item.severity == "CRITICAL"]
    assert {item.rule_id for item in critical} == {"SQL_CONCAT", "HARDCODED_SECRET"}
    assert all(item.line and item.file for item in critical)


def test_empty_catch_and_broad_catch_are_reported() -> None:
    findings = _scan(
        "class A {\n"
        "  void f() {\n"
        "    try { run(); } catch (Exception e) { }\n"
        "    try { run(); } catch (Throwable t) { log(t); }\n"
        "  }\n"
        "}\n"
    )
    ids = {item.rule_id for item in findings}
    assert "EMPTY_CATCH" in ids
    assert "BROAD_CATCH" in ids
    # 空 catch 是 HIGH，宽泛捕获是 MEDIUM
    by_rule = {item.rule_id: item for item in findings}
    assert by_rule["EMPTY_CATCH"].severity == "HIGH"
    assert by_rule["BROAD_CATCH"].severity == "MEDIUM"


def test_style_rules_and_line_numbers() -> None:
    findings = _scan(
        "class A {\n"
        "  // TODO: 记得改\n"
        "  void f() {\n"
        '    System.out.println("debug");\n'
        "    Thread.sleep(1000);\n"
        "    e.printStackTrace();\n"
        "  }\n"
        "}\n"
    )
    by_rule = {item.rule_id: item for item in findings}
    assert by_rule["TODO_FIXME"].line == 2
    assert by_rule["SYSTEM_OUT"].line == 4
    assert by_rule["THREAD_SLEEP"].line == 5
    assert by_rule["PRINT_STACK_TRACE"].line == 6


def test_system_exit_is_high() -> None:
    findings = _scan("class A { void f() { System.exit(1); } }")
    assert [(item.rule_id, item.severity) for item in findings] == [("SYSTEM_EXIT", "HIGH")]


def test_test_files_skip_style_rules() -> None:
    findings = _scan(
        "class ATest { void f() { System.out.println(1); } }",
        path="src/test/java/com/example/ATest.java",
    )
    assert findings == []


def test_tool_issues_mapped_to_findings() -> None:
    findings = tool_findings(
        [
            SourceIssue(
                tool="PMD",
                rule="UnusedLocalVariable",
                severity="low",
                file="src/main/java/A.java",
                line=12,
                message="局部变量未使用",
            ),
            SourceIssue(tool="SpotBugs", rule="NP_NULL", severity="CRITICAL", message="可能空指针"),
        ]
    )
    assert {item.source for item in findings} == {"TOOL"}
    by_rule = {item.rule_id: item for item in findings}
    assert by_rule["PMD:UnusedLocalVariable"].severity == "LOW"
    assert by_rule["SpotBugs:NP_NULL"].severity == "CRITICAL"
    assert by_rule["SpotBugs:NP_NULL"].line is None


def test_merge_dedupes_same_source_file_line_rule() -> None:
    first = CodeFinding(rule_id="SQL_CONCAT", severity="CRITICAL", file="A.java", line=3, source="RULE")
    duplicate = CodeFinding(rule_id="SQL_CONCAT", severity="MEDIUM", file="A.java", line=3, source="RULE")
    other = CodeFinding(rule_id="TODO_FIXME", severity="LOW", file="A.java", line=9, source="RULE")
    merged = merge_findings([first, other], [duplicate])
    assert len(merged) == 2
    assert merged[0].rule_id == "SQL_CONCAT"
    assert merged[0].severity == "CRITICAL"
    assert merged[1].rule_id == "TODO_FIXME"


def test_score_cap_and_verdict_follow_severity() -> None:
    critical = [CodeFinding(rule_id="SQL_CONCAT", severity="CRITICAL", file="A.java", line=1)]
    summary = summarize(critical)
    assert summary.verdict == "BLOCK"
    assert summary.score_cap == 45.0
    assert summary.by_rule == {"SQL_CONCAT": 1}

    clean = summarize([CodeFinding(rule_id="TODO_FIXME", severity="LOW", file="A.java", line=2)])
    assert clean.verdict == "PASS"
    assert clean.score_cap is None

    mediums = [
        CodeFinding(rule_id=f"R{i}", severity="MEDIUM", file="A.java", line=i) for i in range(2)
    ]
    medium_summary = summarize(mediums)
    assert medium_summary.verdict == "WARN"
    assert medium_summary.score_cap == 88.0


def test_format_for_prompt_contains_location_and_severity() -> None:
    findings = _scan('class A { void f() { System.exit(1); } }')
    text = format_for_prompt(findings)
    assert "[HIGH]" in text
    assert "SYSTEM_EXIT" in text
    assert "A.java:1" in text
