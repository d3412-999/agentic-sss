# langchain_service · AI 能力服务

课程设计智能答辩与评分系统的 Python 侧服务，负责：

- **答辩出题**：根据题目、源码、报告生成有针对性的答辩题；
- **答辩评分**：根据语音转写稿与评分要点给出维度分、逐题得分与异常标记；
- **报告评审**：对课程设计报告做结构化评分、亮点与问题分析；
- **源码语义评审**：静态工具之外，评价设计合理性与注释文档；
- **一致性检查**：基于 RAG 检索，核对报告中提到的类 / 方法是否真的存在于源码中。

技术栈：FastAPI + Pydantic + LangChain（OpenAI 兼容接口）+ LightRAG（可选）。

接口契约见根目录 [API接口文档.md](../API接口文档.md) 第 21 章；路径与 Java 侧
`AiServiceClient.PREFIX = /internal/ai` 严格对应。

## 目录结构

```text
langchain_service/
├── app/
│   ├── main.py                     # FastAPI 应用、异常处理、生命周期
│   ├── core/
│   │   ├── config.py               # 配置（环境变量 / .env）
│   │   ├── exceptions.py           # 业务异常
│   │   └── security.py             # X-Internal-Token 校验
│   ├── api/
│   │   ├── deps.py                 # 业务服务单例
│   │   ├── router.py               # 路由聚合 + 内部令牌挂载
│   │   └── routes/
│   │       ├── health.py           # 健康检查
│   │       ├── defense.py          # 答辩出题 / 评分
│   │       ├── report.py           # 报告评审
│   │       ├── code.py             # 源码评审
│   │       └── consistency.py      # 一致性检查
│   ├── schemas/                    # 请求 / 响应模型（对外 camelCase）
│   ├── prompts/                    # 中文提示词，一个能力一个文件
│   ├── services/                   # 大模型调用与业务编排
│   ├── rag/                        # 文档构建、本地检索、LightRAG 客户端
│   └── utils/                      # JSON 提取、文本截断、标识符提取
├── tests/                          # 9 个接口冒烟测试
├── main.py                         # 本地启动入口
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
AI_INTERNAL_TOKEN=change-me
```

```powershell
python main.py
```

所有接口都会返回结构完整、内容固定的示例数据，适合前后端联调。

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

除 `/health` 外都需要请求头（默认值与后端 `AI_INTERNAL_TOKEN` 一致）：

```http
X-Internal-Token: change-me
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 服务状态、大模型是否就绪、LightRAG 是否启用 |
| POST | `/internal/ai/defense/questions` | 生成答辩题 |
| POST | `/internal/ai/defense/evaluate` | 答辩评分 |
| POST | `/internal/ai/report/evaluate` | 报告评审 |
| POST | `/internal/ai/code/review` | 源码语义评审 |
| POST | `/internal/ai/consistency` | 报告-源码一致性检查 |
| POST | `/internal/ai/template/index` | 把优秀作品写入模板库（LightRAG + 本地） |
| POST | `/internal/ai/template/search` | 检索优秀作品模板 |
| DELETE | `/internal/ai/template/{docId}` | 删除模板 |

### 兼容 Java 后端的简化入参

后端 `AiEvaluationService` 目前发送的是简化结构：

```json
{
  "submissionId": "123",
  "selectionId": "456",
  "topicTitle": "图书管理系统",
  "buildTool": "MAVEN",
  "codeSnippet": "public class BookService { ... }",
  "reportText": ""
}
```

- `/code/review`：把 `codeSnippet` 包装成一个虚拟源码文件参与评审，返回顶层 `score`、
  `dimensions`、`suggestions`、`summary`、`model`；
- `/report/evaluate`：若 `report.sections` 为空而 `reportText` 有内容，会按章节标题或固定长度
  自动切分成章节再评审；
- 同时继续支持 API 文档里的完整结构（`javaFiles`、`report.sections` 等）。

### 出题请求示例

```bash
curl -X POST http://127.0.0.1:8000/internal/ai/defense/questions \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: change-me" \
  -d '{
    "requestId": "req-1",
    "topics": [{"title": "图书管理系统", "requirements": "使用 JavaFX 与 JDBC"}],
    "source": {"submissionId": "s1", "javaFiles": [{"path": "BookService.java", "content": "public class BookService {}"}]},
    "report": {"submissionId": "r1", "sections": [{"title": "4.1 类设计", "content": "BookService 负责借阅流程"}]},
    "config": {"questionCount": 6, "focus": ["DESIGN", "CODE"]}
  }'
```

## RAG 设计

1. `document_builder` 把每个 Java 文件、每个报告章节转成检索文档；
2. 默认使用 `local_index`：中英文混合分词 + 简化 BM25 打分 + 标识符精确匹配，不依赖外部服务；
3. 若 `LIGHTRAG_ENABLED=true`，`rag_service` 会先把文档写入 LightRAG（按内容哈希去重），
   再用 `mix` 模式查询；任何异常都会自动回退到本地检索，不影响主流程；
4. 一致性检查用本地检索做**标识符精确匹配**：报告中出现的类 / 方法名在源码中找不到定义时，
   生成 `REPORT_ENTITY_MISSING` 问题；分数 = 命中实体数 / 检查实体数。

LightRAG 是可选增强项，未启动时服务仍可完整工作。

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `AI_HOST` / `AI_PORT` | `127.0.0.1` / `8000` | 监听地址与端口 |
| `AI_INTERNAL_TOKEN` | `change-me` | 内部令牌，与后端一致 |
| `LLM_PROVIDER` | `openai` | `openai` / `mock` |
| `LLM_BASE_URL` | DeepSeek | OpenAI 兼容地址 |
| `LLM_API_KEY` | 空 | 未配置时接口返回 503 |
| `LLM_MODEL` | `deepseek-chat` | 模型名 |
| `LLM_MAX_RETRIES` | `1` | JSON 校验失败后的重试次数 |
| `LIGHTRAG_ENABLED` | `false` | 是否启用 LightRAG |
| `LIGHTRAG_BASE_URL` | `http://127.0.0.1:9621` | LightRAG 服务地址 |
| `RAG_TOP_K` | `5` | 检索返回条数 |
| `RAG_MAX_CODE_CHARS` | `30000` | 提示词中源码总字符上限 |

## 测试

```powershell
.venv\Scripts\python -m pytest -q
```

测试使用 `LLM_PROVIDER=mock`，不依赖网络与大模型 Key，覆盖健康检查、鉴权、五个业务接口、
后端简化入参兼容和一致性检查，共 9 个用例。

## 错误处理

| HTTP | code | 场景 |
|---|---|---|
| 401 | FastAPI detail | 内部令牌缺失或错误 |
| 400 | `INVALID_REQUEST` | 请求缺少必要字段 |
| 502 | `LLM_INVOCATION_FAILED` | 大模型网络 / 超时 / 限流 |
| 502 | `LLM_RESPONSE_INVALID` | 大模型输出不是合法 JSON 或不符合结构 |
| 503 | `LLM_NOT_CONFIGURED` | 未配置 API Key 且未使用 mock |

## 说明

- 真实大模型链路需要配置 `LLM_API_KEY`；未配置时建议先用 mock 模式联调。
- 后端已用 PDFBox / POI 抽取报告正文，扫描版 PDF 无文本层时会按空内容处理；
  接入 PDFBox / pypdf 后本服务无需修改即可按章节评审。
- 答辩出题与评分接口已实现，但 Java 侧的 WebSocket 语音链路尚未接入。
