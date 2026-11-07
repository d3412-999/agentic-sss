# langchain_service · AI 能力服务

课程设计智能答辩与评分系统的 Python 侧服务，负责：

- **答辩出题**：根据题目、源码、报告生成有针对性的答辩题；
- **答辩评分**：根据语音转写稿与评分要点给出维度分、逐题得分与异常标记；
- **报告评审**：对课程设计报告做结构化评分、亮点与问题分析；
- **源码语义评审**：静态工具之外，评价设计合理性与注释文档；
- **一致性检查**：基于 RAG 检索，核对报告中提到的类 / 方法是否真的存在于源码中。

技术栈：FastAPI + Pydantic + LangChain（OpenAI 兼容接口）+ LightRAG（可选）。

接口契约见根目录 [API接口文档.md](../API接口文档.md) 第 21 章。

## 目录结构

```text
langchain_service/
├── app/
│   ├── main.py                     # FastAPI 应用、异常处理、生命周期
│   ├── core/
│   │   ├── config.py               # 配置（环境变量 / .env）
│   │   └── exceptions.py           # 业务异常
│   ├── api/
│   │   ├── deps.py                 # 内部令牌校验、服务单例
│   │   ├── router.py               # 路由聚合
│   │   └── routes/
│   │       ├── health.py           # 健康检查
│   │       ├── defense.py          # 答辩出题 / 评分
│   │       ├── report.py           # 报告评审
│   │       ├── code.py             # 源码评审
│   │       └── consistency.py      # 一致性检查
│   ├── schemas/                    # 请求 / 响应模型（对外 camelCase）
│   │   ├── common.py               # 通用模型与别名转换
│   │   ├── materials.py            # 题目 / 源码 / 报告材料
│   │   ├── defense.py
│   │   ├── report.py
│   │   ├── code.py
│   │   └── consistency.py
│   ├── prompts/                    # 中文提示词，一个能力一个文件
│   │   ├── defense_questions.py
│   │   ├── defense_evaluation.py
│   │   ├── report_evaluation.py
│   │   └── code_review.py
│   ├── services/                   # 业务编排
│   │   ├── llm_service.py          # 大模型调用 + JSON 校验重试
│   │   ├── defense_service.py
│   │   ├── report_service.py
│   │   ├── code_service.py
│   │   ├── consistency_service.py
│   │   ├── scoring.py              # 维度权重、等级、总分
│   │   └── mock_responses.py       # mock 模式的确定性数据
│   ├── rag/                         # 检索增强
│   │   ├── document_builder.py      # 源码 / 报告 -> 检索文档
│   │   ├── local_index.py           # 本地 BM25 简化检索 + 标识符匹配
│   │   ├── lightrag_client.py       # LightRAG HTTP 客户端
│   │   └── rag_service.py           # LightRAG 优先、失败回退本地
│   └── utils/
│       ├── json_utils.py            # 从大模型输出中提取 JSON
│       └── text_utils.py            # 截断、标识符提取
├── tests/
│   ├── conftest.py                  # 测试环境变量
│   └── test_endpoints.py            # 7 个接口冒烟测试
├── main.py                          # 本地启动入口
├── .env.example
└── requirements.txt
```

## 快速开始

```powershell
cd langchain_service
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
```

### 方式一：没有大模型 Key，先用 mock 跑通

```dotenv
LLM_PROVIDER=mock
INTERNAL_TOKEN=change-me
```

```powershell
python main.py
```

此时所有接口都会返回结构完整、内容固定的示例数据，适合前后端联调。

### 方式二：接入真实大模型

```dotenv
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-xxxx
LLM_MODEL=deepseek-chat
```

也可以指向任何 OpenAI 兼容服务（DashScope、Moonshot、vLLM、Ollama 的 `/v1` 等）。

启动后：

- 健康检查：<http://127.0.0.1:8000/health>
- Swagger：<http://127.0.0.1:8000/docs>

## 接口

除 `/health` 外都需要请求头：

```http
X-Internal-Token: <INTERNAL_TOKEN>
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 服务状态、大模型是否就绪、LightRAG 是否启用 |
| POST | `/internal/ai/defense/questions` | 生成答辩题 |
| POST | `/internal/ai/defense/evaluate` | 答辩评分 |
| POST | `/internal/ai/report/evaluate` | 报告评审 |
| POST | `/internal/ai/code/review` | 源码语义评审 |
| POST | `/internal/ai/consistency` | 报告-源码一致性检查 |

### 出题请求示例

```bash
curl -X POST http://127.0.0.1:8000/internal/ai/defense/questions \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: change-me" \
  -d '{
    "requestId": "req-1",
    "offering": {"courseName": "Java 课程设计", "semester": "2026-2027-1"},
    "topics": [{"title": "图书管理系统", "requirements": "使用 JavaFX 与 JDBC"}],
    "source": {
      "submissionId": "s1",
      "javaFiles": [{"path": "src/main/java/BookService.java", "content": "public class BookService {}"}],
      "metrics": {"codeLines": 120},
      "issues": []
    },
    "report": {
      "submissionId": "r1",
      "sections": [{"title": "4.1 类设计", "content": "BookService 负责借阅流程"}],
      "pageCount": 18
    },
    "config": {"questionCount": 6, "focus": ["DESIGN", "CODE"]}
  }'
```

响应字段与 API 文档一致（camelCase），例如：

```json
{
  "requestId": "req-1",
  "questions": [
    {
      "type": "DESIGN",
      "difficulty": "MEDIUM",
      "question": "……",
      "expectedPoints": ["……"],
      "referenceAnswer": "……",
      "sourceRefs": [{"type": "FILE", "path": "src/main/java/BookService.java", "line": 45}],
      "maxAnswerSeconds": 180
    }
  ],
  "model": "deepseek-chat",
  "tokens": {"input_tokens": 1200, "output_tokens": 800}
}
```

## RAG 设计

一致性检查与出题共用同一套检索层：

1. `document_builder` 把每个 Java 文件、每个报告章节转成检索文档；
2. 默认使用 `local_index`：中英文混合分词 + 简化 BM25 打分，不依赖外部服务；
3. 若 `LIGHTRAG_ENABLED=true`，`rag_service` 会先把文档写入 LightRAG（按内容哈希去重），再用 `mix` 模式查询；任何异常都会自动回退到本地检索，不影响主流程；
4. 一致性检查用本地检索做**标识符精确匹配**：报告中出现的类 / 方法名在源码中找不到定义时，生成 `REPORT_ENTITY_MISSING` 问题；分数 = 命中实体数 / 检查实体数。

LightRAG 是可选增强项，未启动时服务仍可完整工作。若需要独立的 LightRAG 服务，可参考 <https://github.com/HKUDS/LightRAG> 启动 `lightrag-server`，并把 `LIGHTRAG_BASE_URL` 指过去。

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `INTERNAL_TOKEN` | `change-me` | 内部令牌 |
| `LLM_PROVIDER` | `openai` | `openai` / `mock` |
| `LLM_BASE_URL` | DeepSeek | OpenAI 兼容地址 |
| `LLM_API_KEY` | 空 | 未配置时接口返回 503 |
| `LLM_MODEL` | `deepseek-chat` | 模型名 |
| `LLM_TEMPERATURE` | `0.2` | 采样温度 |
| `LLM_MAX_RETRIES` | `1` | JSON 校验失败后的重试次数 |
| `LIGHTRAG_ENABLED` | `false` | 是否启用 LightRAG |
| `LIGHTRAG_BASE_URL` | `http://127.0.0.1:9621` | LightRAG 服务地址 |
| `LIGHTRAG_QUERY_MODE` | `mix` | LightRAG 查询模式 |
| `RAG_TOP_K` | `5` | 检索返回条数 |
| `RAG_MAX_CODE_CHARS` | `30000` | 提示词中源码总字符上限 |
| `RAG_MAX_SECTION_CHARS` | `8000` | 单段报告字符上限 |

## 测试

```powershell
.venv\Scripts\python -m pytest -q
```

测试使用 `LLM_PROVIDER=mock`，不依赖网络与大模型 Key，覆盖健康检查、鉴权、五个业务接口和一致性检查。

## 错误处理

| HTTP | code | 场景 |
|---|---|---|
| 401 | FastAPI detail | 内部令牌缺失或错误 |
| 400 | `INVALID_REQUEST` | 请求缺少必要字段 |
| 502 | `LLM_INVOCATION_FAILED` | 大模型网络 / 超时 / 限流 |
| 502 | `LLM_RESPONSE_INVALID` | 大模型输出不是合法 JSON 或不符合结构 |
| 503 | `LLM_NOT_CONFIGURED` | 未配置 API Key 且未使用 mock |

## 后续可扩展

- 接入真实 ASR 与音频特征（语速、停顿、置信度）参与答辩评分；
- 把源码分析结果（Checkstyle / PMD / SpotBugs）合并进统一的评分模型；
- 为 LightRAG 增加按课程 / 开课隔离的 workspace；
- 增加请求级日志、耗时指标与调用审计。
