# langchain_service · AI 能力服务（骨架）

课程设计智能答辩与评分系统的 AI 侧服务：答辩题生成、答辩评分、报告评审、源码语义评审、报告-源码一致性检查。

当前为**骨架阶段**：应用可启动、健康检查可用，业务接口以 `501 Not Implemented` 占位，接口契约见根目录 [API接口文档.md](../API接口文档.md) 第 21 章。

技术栈：FastAPI + Pydantic Settings + LangChain + LightRAG（依赖已在 `requirements.txt` 中）。

## 运行

```bash
cd langchain_service
python -m venv .venv
source .venv/Scripts/activate        # Windows Git Bash；PowerShell 用 .venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env               # 按需填写 LLM_API_KEY / INTERNAL_TOKEN
python main.py                       # 或 uvicorn app.main:app --reload --port 8000
```

启动后：

- 健康检查：<http://127.0.0.1:8000/health>
- Swagger：<http://127.0.0.1:8000/docs>

## 接口

| 方法 | 路径 | 状态 |
|---|---|---|
| GET | `/health` | ✅ 已实现 |
| POST | `/internal/ai/defense/questions` | 🚧 501 占位 |
| POST | `/internal/ai/defense/evaluate` | 🚧 501 占位 |
| POST | `/internal/ai/report/evaluate` | 🚧 501 占位 |
| POST | `/internal/ai/code/review` | 🚧 501 占位 |
| POST | `/internal/ai/consistency` | 🚧 501 占位 |

除 `/health` 外均需请求头：

```http
X-Internal-Token: <INTERNAL_TOKEN>
```

## 目录

```text
langchain_service/
├── app/
│   ├── main.py             # FastAPI 应用
│   ├── api/
│   │   ├── router.py       # 路由聚合
│   │   ├── deps.py         # 内部令牌校验
│   │   └── routes/         # health / defense / review
│   └── core/config.py      # 配置（环境变量 / .env）
├── main.py                 # 本地启动入口
├── .env.example
└── requirements.txt
```

## 下一步

1. `app/services/` 接入 LangChain：题目生成、报告评审、答辩评分三条链；
2. `app/clients/` 封装 LightRAG 检索与对象存储读取；
3. 把 501 占位替换为真实实现，并按 API 文档返回 `{score, dimensions, ...}`；
4. 增加结构化输出校验（Pydantic）与失败重试。
