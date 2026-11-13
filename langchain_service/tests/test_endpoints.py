"""AI 服务接口冒烟测试：使用 mock 模式，不依赖真实大模型。"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


def _source_payload() -> dict:
    """构造源码材料。"""
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
    """构造报告材料。"""
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


def test_generate_defense_questions() -> None:
    """出题接口返回指定数量的计分题 + 1 道加分题，且每题带维度标签。"""
    payload = {
        "requestId": "req-1",
        "offering": {"courseName": "Java 课程设计", "semester": "2026-2027-1"},
        "topics": [{"title": "图书管理系统", "requirements": "使用 JavaFX 与 JDBC", "difficulty": "MEDIUM"}],
        "source": _source_payload(),
        "report": _report_payload(),
        "config": {"questionCount": 6, "focus": ["DESIGN", "CODE"], "language": "zh-CN"},
    }
    response = client.post("/internal/ai/defense/questions", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    questions = data["questions"]
    scored = [item for item in questions if not item.get("bonus")]
    bonus = [item for item in questions if item.get("bonus")]
    assert len(scored) == 6
    assert len(bonus) == 1
    assert bonus[0]["dimension"] == "IS_BONUS"
    # 三个维度都要覆盖到，否则「两项不通过」的判定会失真
    assert {item["dimension"] for item in scored} == {"P1_RETELL", "P2_REASON", "P3_MODIFY"}
    assert "expectedPoints" in questions[0]
    assert questions[0]["maxAnswerSeconds"] >= 60
    assert data["model"] == "mock"


def test_evaluate_defense() -> None:
    """答辩评分返回四个维度和逐题得分。"""
    payload = {
        "defenseId": "d1",
        "questions": [
            {"id": "q1", "question": "为什么这样分层？", "expectedPoints": ["职责边界", "可维护性"]},
            {"id": "q2", "question": "如何处理空指针？", "expectedPoints": ["判空", "Optional"]},
        ],
        "transcript": [
            {"questionId": "q1", "text": "我把业务放在 Service 层，这样控制器只负责参数校验。" * 3, "durationMs": 60000},
            {"questionId": "q2", "text": "可以在调用前判空，或者使用 Optional。" * 2, "durationMs": 45000},
        ],
        "audio": [
            {"questionId": "q1", "url": "http://example.com/1.webm", "asrConfidence": 0.93},
            {"questionId": "q2", "url": "http://example.com/2.webm", "asrConfidence": 0.88},
        ],
    }
    response = client.post("/internal/ai/defense/evaluate", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert 0 <= data["score"] <= 100
    assert len(data["dimensions"]) == 4
    assert len(data["questionScores"]) == 2
    assert data["asr"]["averageConfidence"] > 0


def test_bonus_question_is_excluded_from_total() -> None:
    """加分题答得再好也不改变总分：只有非加分题参与维度汇总。"""
    questions = [
        {"id": "q1", "question": "说明分层", "expectedPoints": ["职责"], "dimension": "P1_RETELL"},
        {"id": "q2", "question": "为什么改", "expectedPoints": ["理由"], "dimension": "P2_REASON"},
        {
            "id": "bonus",
            "question": "迁移到离线场景",
            "expectedPoints": ["取舍"],
            "dimension": "IS_BONUS",
            "bonus": True,
        },
    ]
    transcript = [
        {"questionId": "q1", "text": "业务放在 Service 层，控制器只做校验。" * 3, "durationMs": 30000},
        {"questionId": "q2", "text": "我改了事务边界，因为原来的写法会重复提交。" * 3, "durationMs": 30000},
    ]
    base = {"defenseId": "d2", "questions": questions, "transcript": transcript, "audio": []}
    plain = client.post("/internal/ai/defense/evaluate", json=base, headers=HEADERS).json()
    with_bonus = client.post(
        "/internal/ai/defense/evaluate",
        json={**base, "transcript": transcript + [{"questionId": "bonus", "text": "我会加一条出站队列。" * 5, "durationMs": 20000}]},
        headers=HEADERS,
    ).json()
    assert plain["score"] == with_bonus["score"]
    bonus_row = next(item for item in with_bonus["questionScores"] if item["questionId"] == "bonus")
    assert bonus_row["score"] > 0


def test_evaluate_report() -> None:
    """报告评审返回六个维度。"""
    payload = {
        "submissionId": "s1",
        "topic": {"title": "图书管理系统", "requirements": "使用 JavaFX 与 JDBC"},
        "report": _report_payload(),
        "requirements": "",
    }
    response = client.post("/internal/ai/report/evaluate", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert len(data["dimensions"]) == 6
    assert 0 <= data["score"] <= 100


def test_review_code() -> None:
    """源码评审返回设计与注释两个维度。"""
    payload = {
        "submissionId": "s1",
        "javaFiles": _source_payload()["javaFiles"],
        "metrics": {"codeLines": 120},
        "issues": _source_payload()["issues"],
    }
    response = client.post("/internal/ai/code/review", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert len(data["dimensions"]) == 2
    assert data["dimensions"][0]["key"] == "DESIGN"


def test_check_consistency() -> None:
    """一致性检查能发现报告中提到但源码不存在的类。"""
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


def test_review_code_accepts_backend_payload() -> None:
    """兼容 Java 后端 AiServiceClient 的 codeSnippet 简化入参。"""
    payload = {
        "submissionId": "s1",
        "selectionId": "sel-1",
        "topicTitle": "图书管理系统",
        "buildTool": "MAVEN",
        "codeSnippet": "public class BookService { public void borrow() {} }",
        "reportText": "",
    }
    response = client.post("/internal/ai/code/review", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert 0 <= data["score"] <= 100
    assert data["dimensions"][0]["key"] == "DESIGN"
    assert data["model"]


def test_evaluate_report_accepts_backend_payload() -> None:
    """兼容 Java 后端 AiServiceClient 的 reportText 简化入参。"""
    payload = {
        "submissionId": "s1",
        "topicTitle": "图书管理系统",
        "reportText": "第1章 需求分析\n系统需要支持借阅与归还。\n第2章 系统设计\n采用分层架构。",
        "codeSnippet": "",
    }
    response = client.post("/internal/ai/report/evaluate", json=payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert 0 <= data["score"] <= 100
    assert len(data["dimensions"]) == 6
    assert data["model"]
