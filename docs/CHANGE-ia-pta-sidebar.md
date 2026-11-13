# 从「工作台 + Tab」改为 PTA 式三级结构

> 本轮目标：不再以「开课工作台 + 顶部平铺 tab」组织教师端，改为
> **一级功能集块（可折叠）+ 二级纵向目录 + 右侧内容区**。
>
> 三条底线未破：色值 token 零修改、业务逻辑零改动、路由路径零改动。

---

## 1. 现状盘点（改造前）

### 路由（`vue/src/router/index.js`）

| 路径 | 组件 | 角色 |
|---|---|---|
| `/` | `DashboardView.vue`（564 行，16 处角色分叉） | 全部 |
| `/offerings` | `OfferingsView.vue`（7 列表格） | 教师 |
| `/offerings/:offeringId` | `OfferingWorkbenchView.vue`（195 行） | 教师 |
| `/courses` | `CoursesView.vue` | 学生 |
| `/courses/:offeringId/topics` | `TopicSelectView.vue` | 学生 |
| `/selections` | `SelectionsView.vue`（卡片网格 + 抽屉） | 学生 |
| `/grades` | `GradesView.vue`（薄壳 + 两个角色组件） | 全部 |
| `/tasks` `/notifications` `/settings` | 对应 View | 全部 |
| `/defense/:sessionId` | `DefenseView.vue`（2359 行） | 全部 |
| `/admin/teachers` | `AdminTeachersView.vue` | 管理员 |

### 问题定位

1. **课程详情页把 8 个功能塞进顶部平铺 tab**（`WorkspaceNav` 分 3 组），tab 既是导航也是分区，横向空间被压缩，8 项排两行。
2. **一级导航与页面内容争抢注意力**：侧栏 244px、顶栏 62px + 大标题，首屏有效内容区被挤到 1320px。
3. **`/` 是 KPI + 待办 + 流程说明的大卡片堆叠**，进来先看数据，而不是先看到「我该处理哪些教学班」。
4. 学生提交页的详情藏在右侧抽屉里，关掉就丢上下文。

---

## 2. 信息架构对比

### 改造前

```
侧栏（244px，按角色过滤一张表）
├─ 教学 / 学习
│   ├─ 概览                        ← 默认落地
│   ├─ 教学班（教师） / 我的课程（学生）
│   └─ 我的选题（学生）
├─ 学业 ─ 成绩中心
├─ 协作 ─ 任务中心 / 站内通知
└─ 个人 ─ 设置

页面：/offerings/:id = 顶部 PageHeader + WorkspaceNav（8 tab，3 组）+ 下方内容
```

### 改造后

```
一级栏（220px，可折叠至 64px）
├─ 教学班        → 我的教学班                    ← 教师默认落地
├─ 成绩          → 成绩中心
├─ 协作          → 任务中心 / 站内通知
└─ 个人          → 账号设置

学生端
├─ 我的学习      → 我的课程 / 我的选题          ← 学生默认落地
├─ 我的成绩      → 成绩中心
├─ 协作          → 任务中心 / 站内通知
└─ 个人          → 账号设置

课程详情 /offerings/:id
├─ 顶部：课程名 + 状态 + 学期/班级/人数/题数/已选 + 发布·关闭·归档
└─ 左目录（200px）        右内容
   ├─ 教学准备：概览 / 学生 / 题目
   ├─ 教学执行：提交评审 / 成绩 / 答辩
   └─ 课程沉淀：优秀作品 / 设置
```

---

## 3. 旧入口 → 新位置映射（逐条覆盖）

### 教师端

| 旧入口 | 新位置 | 可达点击数 |
|---|---|---|
| `/` 概览页（KPI / 待办 / 通知 / 流程说明） | 我的教学班列表页顶部**摘要条**（教学班数·在册学生·选题·待答辩 + 可折叠待办 + 未读通知），流程说明随「概览」分区保留 | 1 |
| 侧栏「教学班」 | 一级栏「教学班」集块 → 我的教学班 | 1 |
| `/offerings` 表格的「工作台」按钮 | 行内「进入课程」按钮 + 整行点击 | 1 |
| 表格「发布 / 关闭 / 归档」 | 行内操作组（原样保留，`@click.stop` 不触发跳转） | 1 |
| `/offerings/:id` 顶部 tab「概览」 | 左目录「教学准备 → 概览」 | 1 |
| tab「题目」 | 左目录「教学准备 → 题目」 | 1 |
| tab「学生」 | 左目录「教学准备 → 学生」 | 1 |
| tab「提交评审」 | 左目录「教学执行 → 提交评审」 | 1 |
| tab「成绩」 | 左目录「教学执行 → 成绩」 | 1 |
| 顶部「成绩」快捷按钮 | 同上（另一入口，未删） | 1 |
| tab「答辩」 | 左目录「教学执行 → 答辩」 | 1 |
| tab「优秀作品」 | 左目录「课程沉淀 → 优秀作品」 | 1 |
| tab「设置」 | 左目录「课程沉淀 → 设置」 | 1 |
| 顶部「发布教学班」 | 课程详情页头右侧主操作 | 1 |
| 顶部「关闭 / 归档」 | 课程详情页头右侧次操作 | 1 |
| 侧栏「成绩中心」 | 一级栏「成绩」集块 → 成绩中心 | 1 |
| 侧栏「任务中心」 | 一级栏「协作」→ 任务中心（角标保留） | 2 |
| 侧栏「站内通知」 | 一级栏「协作」→ 站内通知（角标保留） | 2 |
| 顶栏通知铃 | 原样保留 | 1 |
| 侧栏「设置」 | 一级栏「个人」→ 账号设置 | 2 |
| 用户菜单（设置 / 任务 / API 文档 / 退出） | 原样保留 | 1 |
| `Ctrl+K` 命令面板 | 原样保留（`navItemsForRole` 已适配两级结构） | 1 |

### 学生端

| 旧入口 | 新位置 | 可达点击数 |
|---|---|---|
| `/` 学生概览（KPI / 待办 / 截止提醒） | 我的课程列表页顶部摘要条（可选课程·已选·材料齐全·已出成绩 + 「下一步」一句话 + 待办 + 未读） | 1 |
| 侧栏「我的课程」 | 一级栏「我的学习」→ 我的课程 | 2 |
| 侧栏「我的选题」 | 一级栏「我的学习」→ 我的选题 | 2 |
| `/courses/:id/topics` 顶部难度 tab | 选题页**左侧难度目录**，右侧为搜索 + 题目列表 | 1 |
| 选题卡片「加入选题 / 取消」 | 原样保留在右侧卡片上 | 1 |
| 底部「已选 N 道题」固定条 | 原样保留（`left` 跟随 `--sidebar-w`，折叠态自动适配） | 1 |
| `/selections` 卡片 → 抽屉详情 | **左侧题目目录 + 右侧常驻详情**，上传 / 评审 / 版本 / 预览全部保留 | 1 |
| 抽屉底部「退选 / 进入答辩 / 刷新状态」 | 详情区底部操作条 | 1 |
| 抽屉「关闭」按钮 | 取消（改由左侧切换题目，等价且少一次点击） | — |
| 侧栏「成绩中心」 | 一级栏「我的成绩」→ 成绩中心 | 2 |
| 任务 / 通知 / 设置 | 协作 / 个人集块 | 2 |
| 答辩页 | 沉浸模式（隐藏一级栏），页头面包屑保留返回 | 1 |

### 管理员

| 旧入口 | 新位置 |
|---|---|
| `/admin/teachers` | 一级栏「平台」→ 教师账号（唯一集块，默认落地） |

---

## 4. 布局方案（文字线框）

```
┌──────────────┬──────────────────────────────────────────────┐
│ 品牌 S  logo  │ 教师端 › 我的教学班        [搜索] [主题] [铃] [头像] │ 52px
├──────────────┼──────────────────────────────────────────────┤
│ ▸ 教学班   ⌄ │  我的教学班                        [＋新建教学班] │
│    我的教学班 │  按「创建 → 补充名单与题目 → 发布」…            │
│ ▸ 成绩     ⌄ │  ┌──────────────────────────────────────────┐ │
│    成绩中心   │  │ 4 教学班 · 186 在册学生 · 42 选题 · 8 待答辩 │ │ 摘要条
│ ▸ 协作     ⌄ │  │ ⌄ 2 条待办   未读通知 3                    │ │
│    任务中心 ② │  └──────────────────────────────────────────┘ │
│    站内通知 ① │  [全部][草稿][已发布][已关闭][归档]  [搜索][查询] │ 工具栏
│ ▸ 个人     ⌄ │  📖 数据结构 · 计科2301        [已发布]  ⋯      │ 行列表 44px
│    账号设置   │     2026-1 · 42 名学生 · 8 道题 · 38 人已选       │
│              │  📖 算法设计 · 计科2302        [草稿]   ⋯        │
│ 220px 可折叠  │                                                │
└──────────────┴──────────────────────────────────────────────┘

课程详情 /offerings/12?section=students
┌──────────────┬──────────┬─────────────────────────────────────┐
│ 一级栏 220px  │ 目录 200 │ 数据结构 · 计科2301  [已发布]  [发布] │
│              │ 教学准备  │ 2026-1 · 计科2301 · 42 人 · 8 题      │
│              │ ▏概览     ├─────────────────────────────────────┤
│              │  学生 ←── │  学生名单              [＋添加学生]    │
│              │  题目     │  [搜索] [批量移除]                    │
│              │ 教学执行  │  ┌───────────────────────────────┐   │
│              │  提交评审 │  │ ☐ 学号 姓名 状态 …             │   │
│              │  成绩     │  └───────────────────────────────┘   │
│              │  答辩     │                                      │
│              │ 课程沉淀  │                                      │
└──────────────┴──────────┴─────────────────────────────────────┘
                 ↑ 选中项左侧 2px 主色条，独立滚动，其余内容区滚动
```

---

## 5. 组件拆分与 nav.js 新结构

### 新增组件

| 组件 | 职责 | Props / Emits |
|---|---|---|
| `layout/SubNav.vue` | 二级纵向目录，可分组 | `groups: [{label, items:[{key,label,icon,count}]}]`、`modelValue` / `update:modelValue` |
| `summary/TeacherSummary.vue` | 教师端列表页顶部摘要条（原 KPI + 待办 + 通知） | 无（自取数） |
| `summary/StudentSummary.vue` | 学生端列表页顶部摘要条（含「下一步」引导） | 无（自取数） |

### 删除

- `layout/WorkspaceNav.vue`（grep 确认零引用后删除）
- `views/DashboardView.vue`、`views/home/TeacherHome.vue`、`views/home/StudentHome.vue`
  （概览页的职责被摘要条 + 课程详情「概览」分区接管）

### nav.js 新结构

```js
export const TEACHER_NAV = [
  { group: '教学班', icon: 'book',  children: [{ to: '/offerings', label: '我的教学班' }] },
  { group: '成绩',   icon: 'chart', children: [{ to: '/grades',    label: '成绩中心' }] },
  { group: '协作',   icon: 'clock', children: [
      { to: '/tasks', label: '任务中心', badge: 'tasks' },
      { to: '/notifications', label: '站内通知', badge: 'unread' },
  ]},
  { group: '个人',   icon: 'gear',  children: [{ to: '/settings', label: '账号设置' }] },
]
```

导出函数：`navGroupsForRole(role)`（一级栏）、`navItemsForRole(role)`（命令面板，拍平并继承集块图标）、
`activeGroupFor(role, path)`（高亮命中，`/offerings/12` → 「教学班」）。

---

## 6. 改动清单

| 文件 | 改动类型 | 触及业务逻辑 |
|---|---|---|
| `styles/main.css` | 布局：`--sidebar-w` 220px / `--sidebar-w-min` 64px、集块行、`.subnav`、`.page-split`、`.summary-bar`、三档响应式、`.is-immersive` | **否**（`:root` 色值 token 零改动） |
| `components/AppLayout.vue` | 结构：一级集块 + 折叠按钮 + localStorage 偏好 + 沉浸模式 | **否**（登录 / 登出 / 通知轮询 / 快捷键原样） |
| `components/Icon.vue` | 新增 `chevron-up` 图标 | **否** |
| `config/nav.js` | 结构：两级 `{group, icon, children}` | **否** |
| `router/index.js` | `/` 改为按角色 redirect；答辩 `meta.immersive` | **否**（路径与守卫规则不变） |
| `components/layout/SubNav.vue` | 新增 | **否** |
| `components/summary/TeacherSummary.vue` | 新增（承接原首页 KPI / 待办 / 通知） | **否** |
| `components/summary/StudentSummary.vue` | 新增 | **否** |
| `views/OfferingWorkbenchView.vue` | 结构：tab → 左目录 + 右内容，`?section=` 同步 | **否**（8 个 Tab 组件的 props / 事件原样） |
| `views/OfferingsView.vue` | 标题改「我的教学班」，插入摘要条 | **否**（`act()` / 草稿 / 阻塞项原样） |
| `views/CoursesView.vue` | 插入摘要条 | **否** |
| `views/SelectionsView.vue` | 结构：卡片网格 + 抽屉 → 左目录 + 右详情 | **否**（上传 / 评审 / 版本 / 预览原样） |
| `views/TopicSelectView.vue` | 难度 tab → 左侧难度目录 | **否** |
| `views/LoginView.vue` / `AdminTeachersView.vue` / `DefenseView.vue` / `stores/theme.js` | 仅文案去「工作台」 | **否** |
| 删除 `layout/WorkspaceNav.vue`、`views/DashboardView.vue`、`views/home/*` | 删除 | **否**（确认零引用） |

---

## 7. 关键代码 diff

### 7.1 分区与 URL 双向同步

```js
const VALID_SECTIONS = new Set(SECTIONS.map((s) => s.key))
const DEFAULT_SECTION = 'overview'

const section = computed({
  get() {
    const raw = String(route.query.section || '')
    return VALID_SECTIONS.has(raw) ? raw : DEFAULT_SECTION   // 非法值静默回退
  },
  set(value) {
    const next = { ...route.query }
    if (value === DEFAULT_SECTION) delete next.section        // 默认值不留 query，旧 URL 依然干净
    else next.section = value
    router.replace({ query: next })                          // replace：不污染前进后退栈
  },
})
```

### 7.2 默认落地页

```js
{
  path: '/',
  // 教师进教学班、学生进我的课程；管理员由 beforeEach 统一拦到 /admin/teachers
  redirect: () => (currentRole.value === 'TEACHER' ? '/offerings' : '/courses'),
},
```

### 7.3 折叠偏好（纯展示层）

```js
const COLLAPSE_KEY = 'sss.nav.collapsed'
const OPEN_KEY = 'sss.nav.closed-groups'
// 默认展开：只有显式收起的集块才记 true，清缓存即回到全展开
function isGroupOpen(group) {
  return closedGroups.value[group.group] !== true
}
```

### 7.4 二级目录选中态

```css
.subnav__item.is-active::before {
  content: '';
  position: absolute;
  left: 0; top: 50%;
  width: 2px; height: 16px;
  margin-top: -8px;
  border-radius: 2px;
  background: var(--brand);   /* 复用主色 token，未新增色值 */
}
```

---

## 8. 回归验证

| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `vite build`（`.vite-check-8` → `-12`，共 5 次） | 全部通过，最终 2.13s |
| 单元测试 | `npm run test` | 37 passed / 0 fail |
| 导航树 | `node` 实跑 `navGroupsForRole` | 教师 4 集块 5 入口 / 学生 4 集块 6 入口 / 管理员 1 集块 1 入口 |
| 集块命中 | `activeGroupFor` | `/offerings/12` → 教学班；`/selections` → 我的学习 |
| 命令面板 | `navItemsForRole('TEACHER')` | 我的教学班 / 成绩中心 / 任务中心 / 站内通知 / 账号设置 |
| 「工作台」字样 | 全量 grep | **0 处** |
| `WorkspaceNav` 残留 | 全量 grep | **0 处** |
| 色值 token | `git diff main.css` 过滤 `--brand/--bg/--surface/--sidebar/--line/--text/--ok…` | 仅有 `--sidebar-w`（尺寸），**无任何色值改动** |
| 路由路径 | `router/index.js` 比对 | 所有路径未变，仅 `/` 增加 redirect、答辩加 `meta.immersive` |

### 未完成：响应式截图检查

后端与 AI 服务未启动，登录态无法建立，**1440 / 1366 / 1024 / 768 四档截图对比未执行**。
断点规则已按需求实现（见第 6 节与 `main.css` 的 `@media` 段），但视觉效果与「无溢出无遮挡」需实机复核。

---

## 9. 风险与未覆盖项

1. **`SelectionsView` 的详情从抽屉改为常驻**，`open` 变量保留但不再控制显隐；
   抽屉的「关闭」按钮已移除（改由左侧切换题目达成同样效果）。
   若你希望保留「关闭后回到空态」，可加一个清除选中的按钮。
2. **答辩页（`DefenseView.vue`）只做了沉浸外壳，未拆分角色逻辑**。
   2359 行 / 31 处 `isTeacher` 分叉仍在，原因是录音、摄像头、心跳、在场检测、
   违规计数与轮询共享同一套会话状态，强拆会导致状态重复实现。
3. **侧栏折叠按钮在 1023px 以下无效**（CSS 强制图标栏），这是有意的窄屏策略。
4. **`OfferingWorkbenchView.vue` 文件名与路由 `name: 'offering-workbench'` 未改**：
   两者都不出现在 UI 上，改名会波及所有 `router.push({ name })` 调用，收益不足以覆盖风险。
5. **`?section=` 使用 `replace`**：切换分区不产生历史记录（与改造前 tab 切换一致），
   但意味着从「学生」分区按后退会直接离开课程详情页，而非回到「概览」。
6. 移动端抽屉打开后，点遮罩关闭依赖点击页面其余区域（沿用原有 `navOpen` 行为），未新增遮罩层组件。
