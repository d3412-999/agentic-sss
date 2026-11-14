"""接口冒烟测试：健康检查、内部令牌、未配置大模型的错误码与本地一致性检查。

真实大模型的成功路径由 tests/test_llm_services.py 使用测试替身覆盖；
本文件验证没有 LLM_API_KEY 时接口会返回 503，而不是回退到任何占位数据。
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


def _source_payload() -> dict:
    return {
        "submissionId": "s1",
        "javaFiles": [
            {
                "path": "src/main/java/com/example/BookService.java",
                "content": "public class BookService { public void borrow() {} }",
            }
        ],
        "metrics": {"codeLines": 120, "classes": 4},
        "issues": [
            {
                "tool": "PMD",
                "rule": "UnusedLocalVariable",
                "severity": "MEDIUM",
                "file": "src/main/java/com/example/BookService.java",
                "line": 12,
                "message": "局部变量未使用",
            }
        ],
    }


def _report_payload() -> dict:
    return {
        "submissionId": "s1",
        "sections": [
            {"title": "4.1 类设计", "content": "BookService 负责借阅流程，GhostDao 负责数据访问。"},
            {"title": "5.2 测试", "content": "使用 JUnit 编写了 3 条测试用例。"},
        ],
        "pageCount": 18,
    }


def test_health() -> None:
    """健康检查无需令牌。"""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_internal_token_required() -> None:
    """不带内部令牌时应返回 401。"""
    response = client.post("/internal/ai/defense/questions", json={})
    assert response.status_code == 401


def test_code_review_without_llm_key_returns_503() -> None:
    """未配置 LLM_API_KEY 时不能返回占位评审结果，必须 503。"""
    payload = {
        "submissionId": "s1",
        "javaFiles": _source_payload()["javaFiles"],
        "metrics": {"codeLines": 120},
        "issues": _source_payload()["issues"],
    }
    response = client.post("/internal/ai/code/review", json=payload, headers=HEADERS)
    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "LLM_NOT_CONFIGURED"
    assert "LLM_API_KEY" in body["message"]


def test_defense_questions_without_llm_key_returns_503() -> None:
    """未配置大模型时出题接口必须 503，而不是返回固定示例题。"""
    payload = {
        "requestId": "req-1",
        "topics": [{"title": "图书管理系统", "requirements": "使用 JavaFX 与 JDBC"}],
        "source": _source_payload(),
        "report": _report_payload(),
        "config": {"questionCount": 3},
    }
    response = client.post("/internal/ai/defense/questions", json=payload, headers=HEADERS)
    assert response.status_code == 503
    assert response.json()["code"] == "LLM_NOT_CONFIGURED"


def test_check_consistency_uses_local_rag() -> None:
    """一致性检查不依赖大模型：本地检索应能发现报告中提到但源码不存在的类。"""
    payload = {
        "submissionId": "s1",
        "report": _report_payload(),
        "source": _source_payload(),
        "maxEntities": 20,
    }
    response = client.post("/internal/ai/consistency", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert data["checkedEntities"] >= 2
    missing = [issue for issue in data["issues"] if "GhostDao" in issue["message"]]
    assert missing, "应发现 GhostDao 不存在的问题"
    assert data["method"] == "local-rag"