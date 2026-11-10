"""优秀作品模板库接口测试。"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


def test_template_index_search_delete() -> None:
    """模板库能写入、检索并删除文档。"""
    doc_id = "ew-test-001"
    index_payload = {
        "docId": doc_id,
        "title": "图书管理系统 · 张三",
        "content": "第1章 需求分析\n系统支持图书借阅、归还与逾期提醒。\n第2章 系统设计\n采用 Controller-Service-Mapper 分层，"
        "数据库使用 MySQL，借阅流程通过事务保证一致性。",
        "category": "EXCELLENT_WORK",
        "metadata": {"topicTitle": "图书管理系统", "studentNo": "2023010101"},
    }
    response = client.post("/internal/ai/template/index", json=index_payload, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "INDEXED"
    assert data["method"] == "local"
    assert data["docId"] == doc_id

    search_payload = {"query": "图书借阅 事务 分层", "topK": 5}
    response = client.post("/internal/ai/template/search", json=search_payload, headers=HEADERS)
    assert response.status_code == 200
    hits = response.json()["hits"]
    assert any(hit["docId"] == doc_id for hit in hits)
    assert hits[0]["excerpt"]

    response = client.delete(f"/internal/ai/template/{doc_id}", headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["deleted"] is True

    response = client.post("/internal/ai/template/search", json=search_payload, headers=HEADERS)
    assert all(hit["docId"] != doc_id for hit in response.json()["hits"])
