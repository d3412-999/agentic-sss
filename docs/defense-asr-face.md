# 答辩子系统：语音转写（ASR）与摄像头在场检测

> 覆盖「教师发起答辩 → AI 出题 → 学生逐题录音 → ASR 转写 → 教师 AI 评分 → 成绩」整条链路；
> 学生端整场答辩会同时**录像（画面 + 声音，webm）**留存，教师可回放/下载做二次检查。
> **人脸只做「摄像头画面里有没有人」（在场检测），不做身份识别；录像仅用于教学复核。**
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
                                    整场录像（右下角小窗可看到实际录制画面）
                                    逐题录音 → WAV 上传 → ASR 转写
                                    全部答完 → 结束答辩（停录 + 上传录像）
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
| POST | `/api/v1/defense/sessions/{sessionId}/video` | 教师/学生本人 | multipart `file`（webm）+ query `durationMs`，保存整场答辩录像（IN_PROGRESS/COMPLETED 可传） |
| GET | `/api/v1/defense/sessions/{sessionId}/video` | 教师所有者/学生本人 | 流式返回录像（Content-Disposition: inline），教师回放/下载 |
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

## 4. 答辩录像与教师回放

- **学生端（答辩进行中）**：同一路 `getUserMedia({video: 640×360, audio: true})` 三用——
  ① 每 3s 抓帧做在场检测；② 页面右下角**录像小窗**实时显示正在录制的画面（红点 REC + 计时 +
  「正在录像 · 仅供教师复核」）；③ `MediaRecorder` 录制整场（`video/webm;codecs=vp8,opus`，
  1s 分片，结束时合成 webm Blob）。
- **结束答辩**：先停录并上传录像（`POST /api/v1/defense/sessions/{id}/video`，multipart `file` + `durationMs`），
  再调 `finish`；上传失败不会丢录像——Blob 保留在内存并提供「重试上传」（COMPLETED 状态仍可补传）。
- **教师端**：答辩详情右侧「答辩录像」卡片显示 `已保存` + 时长/大小，播放器
  （`GET /api/v1/defense/sessions/{id}/video`，前端用带 `Authorization` 的 fetch 转 blob URL 播放，
  不走 `<video src>` 直链）+「下载录像」按钮；学生本人可只读回看。
- **存储**：录像走现有 `FileStorageService`，`purpose=DEFENSE_VIDEO`，落 `data/objects/yyyy/MM/<uuid>-<filename>.webm`，
  `file_object` 记元数据；`defense_session.video_file_id/video_duration_ms/video_size_bytes/video_uploaded_at`
  指向录像（V6 迁移）。单条上限 300MB（service 常量），Spring multipart 上限 320/340MB。
- **范围限制**：一次答辩一个录像（整场）；重复上传会生成新文件（旧文件不自动清理）；
  GET 为全量 200（无 Range/206），回放需等加载完成。

## 5. 隐私与合规

- **在场检测帧**只在内存中检测，不落库；数据库只记录最近一次 `present`、检测时间与累计未检测次数
  （`defense_session.face_present/face_checked_at/face_miss_count`）。
- **答辩录像（画面 + 声音）会留存**，仅用于教师二次检查：存于 `data/objects`（`purpose=DEFENSE_VIDEO`），
  访问接口做会话归属校验（教师所有者或学生本人），答辩页固定提示
  「摄像头与麦克风用于在场检测和整场答辩录像，录像仅教师可复核，不做身份识别」；
  采集前应由组织方在课程层面告知并获得同意，课程结束后按学校数据管理要求清理。
- 语音音频作为作答材料存 `data/objects`（`purpose=DEFENSE_AUDIO`），与报告/源码同属教学材料。
- 当前**不做人脸身份识别与活体检测**，不应用于替考判定；如需身份核验，请走单独评审后再引入。

## 6. 本地验证

- 不装任何模型：`pytest -q` 应 43 passed / 1 skipped（opencv 未装时跳过真实检测用例）。
- 端到端联调：AI 服务 mock provider + 后端 + 前端，教师端发起答辩 → 开始 → 录音（mock 转写固定文本）→ 结束 → 录像上传 → AI 评分（mock LLM 或真实 LLM）→ 教师回放。
- 自动化测试新增：`langchain_service/tests/test_asr.py`（9）、`tests/test_face_presence.py`（6）。

## 7. 下一阶段

1. 实时流式：浏览器 AudioWorklet/PCM 流 → Java WebSocket 代理 → `/internal/ai/asr/stream`，实现边说边出字；
2. 人脸身份核验 + 活体检测（可复用 `sys_user.avatar_file_id`），仅用于答辩身份确认并保留教师人工确认通道；
3. 说话人/声纹作为替考检测的第二信号；
4. 录像分片/断点续传与历史录像清理任务（当前整场录像驻留内存后一次性上传，10 分钟约 75MB）。
