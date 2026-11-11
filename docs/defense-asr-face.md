# 答辩子系统：语音转写（ASR）与摄像头在场检测

> 覆盖「教师发起答辩 → AI 出题 → 学生逐题录音 → ASR 转写 → 教师 AI 评分 → 成绩」整条链路。
> **人脸只做「摄像头画面里有没有人」（在场检测），不做身份识别、不落库原图。**
> 实时流式代理、人脸身份识别/活体检测属于下一阶段（见文末）。

## 1. 角色与流程

```
教师                                    学生/答辩现场
────                                    ──────────────
开课工作台 →「答辩」页签
  选择 ✅已选题学生 → 发起答辩  ──▶  AI 出题（/internal/ai/defense/questions）
  会话 READY，学生收到通知
  进入答辩页 → 开始  ────────────▶  答辩页（/defense/:sessionId）
                                    摄像头预览 + 每 3s 在场检测
                                    逐题录音 → WAV 上传 → ASR 转写
                                    全部答完 → 结束答辩（COMPLETED）
  点「AI 评分」 ────────────────▶  /internal/ai/defense/evaluate
  查看 score/grade/summary           学生看到评分结果
```

状态机：

| 状态 | 触发 | 说明 |
|---|---|---|
| `READY` | 教师发起并出题成功 | 题目已落库，等待开始 |
| `IN_PROGRESS` | 开始答辩 | 才允许上传作答 |
| `COMPLETED` | 结束答辩 | 已记录时长，等待评分 |
| `EVALUATED` | 教师评分成功 | 分数进入成绩计算（`GradeService` 读取） |

## 2. 接口

### 2.1 Java 后端（前端调用）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/teacher/defense/sessions` | 教师 | body `{selectionId, questionCount?}`，AI 出题并创建会话 |
| GET | `/api/v1/teacher/defense/sessions?offeringId=` | 教师 | 会话列表 |
| GET | `/api/v1/teacher/offerings/{offeringId}/defense/candidates` | 教师 | 可发起答辩的选题/学生/会话状态 |
| GET | `/api/v1/defense/sessions/{sessionId}` | 教师/学生本人 | 详情（学生视图隐藏评分要点/参考答案） |
| POST | `/api/v1/defense/sessions/{sessionId}/start` | 教师/学生本人 | READY → IN_PROGRESS |
| POST | `/api/v1/defense/sessions/{sessionId}/questions/{questionId}/answer` | 教师/学生本人 | multipart `file`（WAV）+ query `durationMs`，同步转写 |
| POST | `/api/v1/defense/sessions/{sessionId}/face-check` | 教师/学生本人 | body `{imageBase64}`，返回 `{present, faceCount, message}` |
| POST | `/api/v1/defense/sessions/{sessionId}/finish` | 教师/学生本人 | IN_PROGRESS → COMPLETED |
| POST | `/api/v1/defense/sessions/{sessionId}/evaluate` | 教师 | COMPLETED/EVALUATED → AI 评分（失败 50301，不伪造分数） |
| GET | `/api/v1/student/defense/selections/{selectionId}` | 学生 | 按选题查自己的会话（无则 40401） |

鉴权：`/teacher/**` 需 `TEACHER`、`/student/**` 需 `STUDENT`、`/defense/**` 需登录；会话级归属（开课所有者或学生本人）在 Service 内校验。

### 2.2 AI 服务内部接口（Java → FastAPI，`X-Internal-Token`）

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/internal/ai/asr/transcribe` | multipart `file`（WAV 16k mono PCM16），可选 `language` | `{text, segments:[{start,end,text}], durationMs, confidence, provider, model}` |
| WS | `/internal/ai/asr/stream` | 握手头 `X-Internal-Token`；文本 `{"type":"config"}`/`{"type":"end"}` + 二进制 PCM16 帧 | `{"type":"partial"}` / `{"type":"final"}` |
| POST | `/internal/ai/face/presence` | JSON `{imageBase64, minFaceSize?}` | `{present, faceCount, boxes, provider, model}` |
| POST | `/internal/ai/defense/questions` | 已有接口（Java `generateDefenseQuestions`） | 题目列表 |
| POST | `/internal/ai/defense/evaluate` | 已有接口（Java `evaluateDefense`） | score/grade/summary/维度/逐题分 |

错误码：`ASR_NOT_CONFIGURED` / `FACE_NOT_CONFIGURED`（503，模型或可选依赖缺失）、`INVALID_AUDIO` / `INVALID_IMAGE`（400）、`ASR_FAILED`（500）。Java 侧统一转成 `50301 AI_UNAVAILABLE`。

## 3. 配置与真实模型

`langchain_service/.env`：

```dotenv
# 默认 mock，不装模型即可联调整条答辩链路
ASR_PROVIDER=mock          # mock | sherpa_onnx
ASR_MODEL_DIR=./models/sherpa-onnx-paraformer-zh
ASR_LANGUAGE=zh-CN
FACE_PROVIDER=mock         # mock | opencv
FACE_MIN_SIZE=60           # 最小人脸像素，只判"有没有人"
FACE_MODEL_DIR=./models/face   # 预留
```

启用真实能力：

```bash
cd langchain_service
pip install -r requirements-optional.txt   # sherpa-onnx + opencv-python-headless
# 1) 中文 ASR：从 https://k2-fsa.github.io/sherpa/onnx/pretrained_models/ 下载
#    paraformer / zipformer 中文模型（目录内需 tokens.txt 与 *.onnx），放到 ./models/
# 2) .env 改为 ASR_PROVIDER=sherpa_onnx，ASR_MODEL_DIR 指向模型目录
# 3) 人脸：FACE_PROVIDER=opencv 即可（haarcascade 随 OpenCV 自带，无需额外模型文件）
python main.py
```

说明：
- sherpa-onnx 模式**只接受 WAV 16k mono PCM16**（前端答辩页已自动生成标准 WAV），非 WAV 返回 400；离线部署把模型目录一起拷走即可。
- OpenCV 模式用内置 `haarcascade_frontalface_default.xml` 做正脸检测，`FACE_MIN_SIZE` 控制最小人脸像素；只判"有人/无人"，不识别是谁。
- mock 模式：任意音频/任意图片返回确定性结果，用于前后端联调与自动化测试。

## 4. 隐私与合规

- 摄像头帧**只在内存中检测，不落库、不写磁盘**；数据库只记录最近一次 `present`、检测时间与累计未检测次数（`defense_session.face_present/face_checked_at/face_miss_count`）。
- 答辩页固定提示「摄像头仅用于在场检测，画面不留存」；采集前应由组织方在课程层面告知并获得同意。
- 语音音频作为作答材料存 `data/objects`（`purpose=DEFENSE_AUDIO`），与报告/源码同属教学材料，按现有数据管理策略清理。
- 当前**不做人脸身份识别与活体检测**，不应用于替考判定；如需身份核验，请走单独评审后再引入。

## 5. 本地验证

- 不装任何模型：`pytest -q` 应 43 passed / 1 skipped（opencv 未装时跳过真实检测用例）。
- 端到端联调：AI 服务 mock provider + 后端 + 前端，教师端发起答辩 → 开始 → 录音（mock 转写固定文本）→ 结束 → AI 评分（mock LLM 或真实 LLM）。
- 自动化测试新增：`langchain_service/tests/test_asr.py`（9）、`tests/test_face_presence.py`（6）。

## 6. 下一阶段

1. 实时流式：浏览器 AudioWorklet/PCM 流 → Java WebSocket 代理 → `/internal/ai/asr/stream`，实现边说边出字；
2. 人脸身份核验 + 活体检测（可复用 `sys_user.avatar_file_id`），仅用于答辩身份确认并保留教师人工确认通道；
3. 说话人/声纹作为替考检测的第二信号；
4. 答辩音频归档与回放、评分维度落库（`score` 表接入）。
