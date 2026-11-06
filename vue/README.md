# 课程设计智能答辩与评分系统 · 前端（界面阶段）

Vue 3 + Vite 实现的教师端 / 学生端界面，当前为**纯静态界面**：交互、动效、状态切换均已可用，数据来自 `src/data/mock.js`，尚未接入后端接口。

## 运行

```bash
npm install     # 首次
npm run dev     # http://localhost:5173
npm run build   # 产物在 dist/
npm run preview # 预览构建产物
```

演示账号：学生 `2023010101` / 教师 `T2019008`，密码任意，登录页选择身份后直接进入。

## 页面地图

| 角色 | 路由 | 页面 |
|---|---|---|
| 公共 | `/login` | 登录（学生 / 教师切换） |
| 学生 | `/student` | 学习台：进度、待办、时间节点、AI 反馈 |
| 学生 | `/student/courses` | 选择课程 |
| 学生 | `/student/courses/:id/topics` | 选择题目（多选、名额、确认弹窗） |
| 学生 | `/student/selections` | 我的选题 |
| 学生 | `/student/selections/:id` | 提交源码 / 报告、源码分析、报告评审 |
| 学生 | `/student/defense` | 在线答辩（准备 → 语音作答 → 评分结果） |
| 学生 | `/student/grades` | 我的成绩 |
| 教师 | `/teacher` | 自动跳转到「我的开课」 |
| 教师 | `/teacher/offerings` | 开课列表（教师端首页） |
| 教师 | `/teacher/offerings/new` | 新建开课（权重、时间、答辩配置） |
| 教师 | `/teacher/offerings/:id` | 开课概览（阶段进度、动态、权重） |
| 教师 | `/teacher/offerings/:id/students` | 学生名单（Excel 导入弹窗） |
| 教师 | `/teacher/offerings/:id/topics` | 题目管理（新建 / 发布 / 下架） |
| 教师 | `/teacher/offerings/:id/defenses` | 答辩复核列表 |
| 教师 | `/teacher/defenses/:id` | 答辩详情（录音播放、转写、改分、异常标记） |
| 教师 | `/teacher/offerings/:id/grades` | 成绩管理（调整、发布、导出） |

## 设计语言

- 米色偏白：`#fffdf8` 卡片 + `#f4efe6` 背景，主色 `#1a73e8`，圆角 10 / 16 / 999，阴影带暖调。
- 扁平化：静态区块与背景同色、无描边；只有可点击的卡片保留浅底与描边，状态标签只保留彩色文字。
- 字号整体比常规后台大一档：正文 15px，页面标题 29px，表格 14.5px；按钮、链接、导航等可点元素加粗。
- 字体栈：Google Sans → Roboto → PingFang SC → 微软雅黑。
- 所有动效统一 `cubic-bezier(.2, 0, 0, 1)`；切页为淡入 + 内容分块错峰上浮（约 0.45s），顶部有 3px 路由进度条，顶栏滚动后出现阴影，按钮悬停轻抬、按下回落。
- 遵守 `prefers-reduced-motion`：系统开启「减少动态效果」时自动关闭位移动画。
- 图标为内置 SVG（`AppIcon.vue`），不依赖图标库与网络字体。
- 响应式：≤1080px 侧边栏抽屉化，≤720px 单列布局。

## 目录

```text
src/
├── components/     AppShell 布局、AppIcon、StatusChip、StatCard、
│                   ProgressRing、ModalDialog
├── views/
│   ├── student/    7 个学生端页面
│   └── teacher/    8 个教师端页面
├── data/mock.js    演示数据（接入 API 后整体替换）
├── router/         路由与角色守卫
├── stores/session  登录态（localStorage）
└── styles/base.css 设计令牌与通用样式
```

## 接入后端时

1. `src/api/` 增加 axios 实例：注入 `Authorization`，统一处理 `code !== 0` 与 401 刷新。
2. 用真实请求替换 `data/mock.js`，页面数据结构已按接口文档字段命名（`offering` / `topics` / `selections` / `defenses` / `grades`）。
3. 答辩页的模拟计时与波形替换为 `MediaRecorder + WebSocket`（协议见 `API接口文档.md` 第 17 章）。
4. 上传区接入分片上传与 SHA-256 秒传。

## 已知限制

- 未接入后端，刷新后上传/调整等操作会回到初始 mock 状态。
- 录音播放、Excel 导入、导出为界面演示，没有真实文件处理。
