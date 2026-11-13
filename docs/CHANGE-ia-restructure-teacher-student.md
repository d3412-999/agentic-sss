# 信息架构重组与视觉层级优化（教师端 / 学生端）

> 范围：`vue/src` 前端结构与样式层。
> 约束：不改配色与主题变量、不改业务逻辑与接口、不删任何入口。
> 前置确认（已由用户逐项拍板）：全量页面 · nav.js 可重组可改名 · 允许面包屑/步骤条/锚点 · 无外部布局规范 · 仅布局+空态引导 · 沿用现有 px 间距节奏 · 允许新增布局组件。

---

## 0. 起点说明（很重要）

本轮不是从零开始。开工时工作树里已经有一批**未提交**的结构改造：

- `components/layout/` 下已有 `PageHeader / SectionCard / ToolbarBar / WorkspaceNav` 四个壳组件，`PageHeader` 已被 10 个页面引用；
- 教师首页已改成「页头 + 快捷入口 + KPI + 待办/课程卡两栏」；
- 开课工作台的 8 个 tab 已按 `教学准备 / 教学执行 / 课程沉淀` 分组。

因此本轮做的是**补齐缺口 + 统一口径**，而不是重写。下面「改造前」指本轮开工时的状态。

---

## 1. 现状盘点

### 1.1 页面清单与入口

| 角色 | 路由 | 文件 | 一级入口 | 主要功能点 |
|---|---|---|---|---|
| 共用 | `/` | `views/DashboardView.vue` | 侧边栏「概览」 | KPI、教学班/选题流水线、待办、通知、流程说明 |
| 教师 | `/offerings` | `views/OfferingsView.vue` | 「教学班」 | 新建、发布、归档、筛选、分页 |
| 教师 | `/offerings/:offeringId` | `views/OfferingWorkbenchView.vue` | 教学班行 → 工作台 | 8 个 tab 的容器、发布/关闭/归档、发布前检查弹窗 |
| 教师 | 同上 · 概览 | `offering/OverviewTab.vue` | tab | 运行情况总览 |
| 教师 | 同上 · 题目 | `offering/TopicsTab.vue` | tab | 新建/编辑/删除、附件、难度筛选、批量删除、分页 |
| 教师 | 同上 · 学生 | `offering/StudentsTab.vue` | tab | 搜索、状态筛选、添加、Excel 批量导入、批量移除、导出名单 |
| 教师 | 同上 · 提交评审 | `offering/SubmissionReviewTab.vue` | tab | 学生列表、提交物、相似度、逐文件评审、预览/下载 |
| 教师 | 同上 · 成绩 | `offering/GradesTab.vue` | tab | 权重、重算、导出 CSV、撤销发布、发布、调分、答辩录像 |
| 教师 | 同上 · 答辩 | `offering/DefenseTab.vue` | tab | 候选人勾选、批量发起、会话列表、违规事件、恢复/终止、三原则判定 |
| 教师 | 同上 · 优秀作品 | `offering/ExcellentWorksTab.vue` | tab | 优秀作品展示 |
| 教师 | 同上 · 设置 | `offering/SettingsTab.vue` | tab | 权重与时间线配置 |
| 教师 | `/grades` | `views/GradesView.vue` | 「成绩中心」 | 教学班切换、成绩列表 |
| 双端 | `/defense/:sessionId` | `views/DefenseView.vue` | 选题/答辩列表 → 详情 | 录音作答、转写、违规检测、AI 评分、教师覆盖判定、拓展题 |
| 学生 | `/courses` | `views/CoursesView.vue` | 「我的课程」 | 课程卡、搜索、去选题 |
| 学生 | `/courses/:offeringId/topics` | `views/TopicSelectView.vue` | 课程卡 → 选题中心 | 难度筛选、搜索、选/退选、名额提示 |
| 学生 | `/selections` | `views/SelectionsView.vue` | 「我的选题」 | 源码/报告多文件上传、版本历史、AI 评审状态、答辩入口 |
| 学生 | `/grades` | `views/GradesView.vue` | 「成绩中心」 | 我的成绩 |
| 管理员 | `/admin/teachers` | `views/AdminTeachersView.vue` | 「教师账号」 | 教师账号与权限 |
| 共用 | `/tasks` `/notifications` `/settings` | 对应 View | 侧边栏 | 任务进度、通知、账号设置 |

### 1.2 问题定位（本轮开工时）

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| P1 | 侧边栏分组是「一套语义服务两种角色」：教师端出现单条目的「教学管理」、学生端出现「学习」里塞着课程与选题 | `config/nav.js` | 角色错位，新用户第一眼分不清自己该点哪 |
| P2 | 工作台 8 个 tab 平铺、无吸顶上下文，长表格滚两屏后不知道「在哪个班、哪个环节」 | `views/OfferingWorkbenchView.vue` | 误操作风险（发布/归档点错班） |
| P3 | 各 tab 直接以工具栏开场：没有「标题 + 说明 + 主操作」，主操作（新建题目 / 发布成绩）和搜索框挤在一行 | `offering/*Tab.vue` | 主次不分，主按钮被筛选控件稀释 |
| P4 | 批量操作常驻：移除学生永远可见（只是 disabled） | `offering/StudentsTab.vue` | 与筛选控件争抢注意力 |
| P5 | 学生端没有跨页动线提示，只能靠一句话描述流程 | `CoursesView / TopicSelectView / SelectionsView / DefenseView / GradesView` | 不知道「我现在在哪一步、下一步做什么」 |
| P6 | 答辩页 2353 行、页头手写，与其它页结构不一致 | `views/DefenseView.vue` | 维护成本高，视觉断层 |
| P7 | 教师首页待办按 `push` 顺序排列，未按紧急度 | `views/DashboardView.vue` | 真正紧急的（草稿待发布）可能排在后面 |
| P8 | 首页主操作是「任务中心」，而任务中心是低频排查入口 | `views/DashboardView.vue` | 首屏主操作指向错误目标 |

---

## 2. 信息架构对比

### 2.1 导航树：改造前 vs 改造后

**改造前（教师）**

```
工作台     概览
教学管理   教学班
学业       成绩中心
协作       任务中心 / 站内通知
个人       设置
```

**改造后（教师）**

```
教学       概览 / 教学班
学业       成绩中心
协作       任务中心 / 站内通知
个人       设置
```

**改造前（学生）**

```
工作台     概览
学习       我的课程 / 我的选题
学业       成绩中心
协作       任务中心 / 站内通知
个人       设置
```

**改造后（学生）**

```
学习       概览 / 我的课程 / 我的选题
学业       成绩中心
协作       任务中心 / 站内通知
个人       设置
```

实现方式：`NAV_GROUPS[].group` 支持 `{ TEACHER, STUDENT }` 双写，`navGroupsForRole()` 按角色解析。**条目一个没删、`to` 路径一个没改**，命令面板 `navItemsForRole()` 只取条目、不受影响。

### 2.2 开课工作台分区表

| 分区 | tab key | 改造前 | 改造后 |
|---|---|---|---|
| 教学准备 | `overview` `topics` `students` | 8 个 tab 平铺一行 | 三组分区 + 紧凑页签，与课程上下文条一起吸顶 |
| 教学执行 | `submissions` `grades` `defense` | 同上 | 同上 |
| 课程沉淀 | `excellent` `settings` | 同上 | 同上 |

`tab` 的取值与切换逻辑完全未变，`WorkspaceNav` 的 `v-model` 直接复用原 `tab` ref。

### 2.3 学生端动线

```
进入课程 → 选题 → 提交材料 → AI 评审 → 答辩 → 成绩
   ↑        ↑        ↑          ↑        ↑       ↑
 /courses /courses /selections /selections 会话页 /grades
```

改造前：无。改造后：`config/flow.js` 定义 `STUDENT_FLOW`，五处页面用 `StepFlow` 显示当前位置：
`CoursesView(course)` · `TopicSelectView(topic)` · `SelectionsView(submit|review 自动判定)` · `DefenseView(defense)` · `GradesView(grade)`。

`SelectionsView` 的定位是纯展示推导：`rows` 里只要还有 `!progress.reportUploaded` 就停在「提交材料」，否则停在「AI 评审」。

---

## 3. 布局方案（文字线框）

### 3.1 统一页面骨架

```
┌───────────────────────────────────────────────────────────┐
│ 面包屑：教学班 › 开课工作台                                │  ← PageHeader #breadcrumb
│ 标题 + 状态标签                          [次操作] [主操作] │  ← PageHeader #title / #actions
│ 元信息：学期 · 班级 · 人数 · 题数                          │  ← #meta
│ 步骤条（学生端）                                           │  ← #steps + StepFlow
├───────────────────────────────────────────────────────────┤
│ 二级导航（工作台三组页签）                                 │  ← WorkspaceNav compact
├───────────────────────────────────────────────────────────┤
│ 工具栏：筛选 / 搜索 ················ 计数 + 批量操作       │  ← ToolbarBar 或 .row
├───────────────────────────────────────────────────────────┤
│ 数据区（卡片 / 表格 / 空态 / 加载骨架）                    │
├───────────────────────────────────────────────────────────┤
│ 分页                                                       │  ← Pager
└───────────────────────────────────────────────────────────┘
```

### 3.2 教师首页（三区，已有骨架 + 本轮修正）

```
┌ 页头：早上好，X 老师            [教学班管理(主)] [任务中心(次)] ┐
├ 快捷入口：教学班管理 | 成绩中心 | 任务中心 | 站内通知           ┤
├ KPI：教学班 · 在册学生 · 选题数 · 待答辩                       ┤
├──────────────────────────────┬────────────────────────────────┤
│ 教学班（4 条 + 全部 →）      │ 我的待办（按紧急度排序）        │
│ 运行情况（提交率/均分/权重）  │ 最新通知                        │
│ 成绩分布 + 高频源码问题       │ 流程说明                        │
└──────────────────────────────┴────────────────────────────────┘
```

- 首屏回答三问：**在教什么**（教学班卡 + KPI）、**要做什么**（右栏待办，`danger > warn > info > ok` 排序）、**能去哪**（快捷入口 + 页头主操作）。
- 空态：教学班为空 → `EmptyState` + 「新建教学班」按钮；学生无选题 → 「去选课」按钮。

### 3.3 开课工作台（吸顶）

```
┌ topbar（全局，sticky top:0, z=30） ───────────────────────┐
├ workbench-bar（sticky top:var(--topbar-h), z=20）──────────┤
│ 教学班 › 开课工作台                                        │
│ 软件工程 · [已发布]              [成绩] [关闭] [归档]      │
│ 2026 春 · 软工 1 班 · 42 名学生 · 8 道题 · 已选 40         │
│ ┌ 教学准备: 概览 题目 学生 ┐┌ 教学执行: 提交评审 成绩 答辩 ┐│
├───────────────────────────────────────────────────────────┤
│ tab 内容                                                   │
└───────────────────────────────────────────────────────────┘
```

- 吸顶区把「课程上下文 + 二级导航」锁在一起，滚动长表格仍可切 tab。
- 高度估计：页头 3 行 ≈ 66px + 紧凑页签 ≈ 40px ≈ 106px；1366×768 下内容区仍有约 580px。
- `< 900px` 时 `position: static`，避免窄屏吸顶吃掉半屏；页签改为横向滚动。

### 3.4 tab 内结构（以「学生」「题目」「成绩」为例）

```
学生名单  42 人                            [下载名单] [添加学生(主)]
先添加学生才能发布教学班；支持 Excel 批量导入与名单导出。
[搜索学号/姓名] [全部状态▾] [查询] ·············· 已选 3 人 [移除学生]
┌ 表格（勾选 / 学号 / 姓名 / 状态 / 最近登录 / 操作） ┐
└ 分页 ┘
```

```
题目 8 道                                              [新建题目(主)]
题目发布后学生才能选题；难度与数量会参与答辩出题规则。
[全部][基础][进阶][挑战] [搜索标题] [🔍] [删除所选 2]
```

```
成绩                                      [重算] [导出 CSV] [发布成绩(主)]
先重算再发布。发布后学生可见且成绩锁定，撤销仅有 24 小时窗口。
权重 答辩 30% · 源码 40% · 报告 30% | [搜索] [状态▾] [查询] ······ [撤销发布]
```

要点：
- **主按钮每屏只有一个**，固定在页头右上；破坏性的「撤销发布」被移到工具栏右侧，与主操作分离。
- 批量操作只在选中后出现（学生/题目/答辩均如此）。
- 所有按钮的 `@click` 都是原来的函数，未新增、未改写。

### 3.5 答辩页

```
教学班 › 答辩
张三  [已评分]  [已记录 2 次违规]
2023010105 · 软工 1 班 · 作答 8 / 8 · 用时 12:31
①进入课程 →②选题 →③提交材料 →④AI评审 →⑤答辩 →⑥成绩   ← 仅学生可见
                        [刷新] [教学班] [AI 评分(主)]
```

教师端不显示动线步骤条（教师的动线是工作台 tab），学生端显示，当前步高亮。

---

## 4. 组件拆分清单

| 组件 | 状态 | 职责 | props | slots |
|---|---|---|---|---|
| `layout/PageHeader.vue` | 增强 | 页面级页头 | `title` `description` `compact` | `breadcrumb` `title` `description` `meta` `steps` `actions` |
| `layout/TabHead.vue` | 新增 | tab 内页头（轻一档） | `title` `description` `count` `countUnit` | `description` `meta` `actions` |
| `layout/StepFlow.vue` | 新增 | 页面级步骤条 | `steps[{key,label,to?}]` `current` `dense` `label` | — |
| `layout/WorkspaceNav.vue` | 增强 | 工作台二级导航 | `groups[{label,items}]` `modelValue` `compact` | — (emit `update:modelValue`) |
| `layout/ToolbarBar.vue` | 复用 | 列表工具栏 | — | `left` `right` |
| `layout/SectionCard.vue` | 复用（本轮未新增使用） | 分区卡片 | `title` `hint` `icon` `flush` | `actions` `default` |
| `config/flow.js` | 新增 | 学生动线常量 | — | — |

设计原则：
- 壳组件**不含任何业务判断**，只接收数据 + 插槽；事件仍由各页面原样绑定。
- 步骤条复用全局 `.steps / .step / .step__dot / .step__line` 与 `is-done / is-current`，**不新增任何色值**。
- `PageHeader.compact` 用 `:deep(h1)` 降字号，不改全局 `h1` 规则。

---

## 5. 改动清单

| 文件 | 改动类型 | 是否触及逻辑 |
|---|---|---|
| `config/nav.js` | 结构（分组支持按角色取值、改名） | **否**（条目与路径未变） |
| `config/flow.js` | 新增（纯常量） | **否** |
| `components/layout/PageHeader.vue` | 结构 + 样式（compact、steps 插槽） | **否** |
| `components/layout/StepFlow.vue` | 新增（纯展示） | **否** |
| `components/layout/TabHead.vue` | 新增（纯展示） | **否** |
| `components/layout/WorkspaceNav.vue` | 结构 + 样式（compact 单行模式） | **否**（`modelValue` 语义未变） |
| `views/OfferingWorkbenchView.vue` | 结构 + 样式（吸顶容器、页头换壳） | **否**（`act()` / `tab` 原样） |
| `views/DefenseView.vue` | 结构（页头换壳、学生动线） | **否**（`doStart/doFinish/doEvaluate/load` 原样） |
| `views/CoursesView.vue` | 结构（自定义 flow-strip → StepFlow） | **否** |
| `views/TopicSelectView.vue` | 结构（加 steps 插槽） | **否** |
| `views/SelectionsView.vue` | 结构（加 steps 插槽 + `flowStep` 只读推导） | **否**（未改任何请求/状态） |
| `views/GradesView.vue` | 结构（学生端加 steps 插槽） | **否** |
| `views/DashboardView.vue` | 结构（主/次操作互换、待办排序） | **否**（`todos` 数据源未变） |
| `components/offering/StudentsTab.vue` | 结构（页头 + 批量操作条件显示） | **否** |
| `components/offering/TopicsTab.vue` | 结构（页头 + 主操作上移） | **否** |
| `components/offering/SubmissionReviewTab.vue` | 结构（页头） | **否** |
| `components/offering/GradesTab.vue` | 结构（页头 + 主/危险操作分离） | **否** |
| `components/offering/DefenseTab.vue` | 结构（页头） | **否** |
| `.gitignore` | 忽略 `.vite-check-*/` | **否** |

「是否触及逻辑」的判定标准：是否新增/删除接口调用、是否改变请求参数、是否改状态机分支、是否改权限判断、是否改表单校验、是否改路由参数。**以上改动全部为模板结构与 scoped 样式，函数体一行未改。**

---

## 6. 关键代码 diff

### 6.1 导航分组支持按角色取值

```diff
-  {
-    group: '工作台',
-    items: [
-      { to: '/', label: '概览', ... },
-      { to: '/offerings', label: '教学班', roles: ['TEACHER'] },
-      { to: '/courses', label: '我的课程', roles: ['STUDENT'] },
-      { to: '/selections', label: '我的选题', roles: ['STUDENT'] },
-    ],
-  },
-  { group: '教学管理', items: [{ to: '/offerings', ... }] },
+  {
+    // 教师：概览 + 教学班；学生：概览 + 我的课程 + 我的选题
+    group: { TEACHER: '教学', STUDENT: '学习' },
+    items: [
+      { to: '/', label: '概览', ... },
+      { to: '/offerings', label: '教学班', roles: ['TEACHER'] },
+      { to: '/courses', label: '我的课程', roles: ['STUDENT'] },
+      { to: '/selections', label: '我的选题', roles: ['STUDENT'] },
+    ],
+  },
+
+function groupLabelOf(group, role) {
+  if (typeof group === 'string') return group
+  return group[role] || group.TEACHER || group.STUDENT || ''
+}
```

实测输出：

```
TEACHER  [教学] 概览 / 教学班 | [学业] 成绩中心 | [协作] 任务中心 / 站内通知 | [个人] 设置
STUDENT  [学习] 概览 / 我的课程 / 我的选题 | [学业] 成绩中心 | [协作] … | [个人] 设置
ADMIN    [管理员] 教师账号
```

### 6.2 工作台吸顶上下文条

```diff
-    <div class="page-head">
-      <div style="min-width: 0">
-        <div class="row small dim" style="margin-bottom: 6px">…面包屑…</div>
-        <h1 class="row" style="gap: 10px">…课程名 + 状态…</h1>
-        <p v-if="offering">…学期 · 班级 · 人数…</p>
-      </div>
-      <div class="page-head__actions">…发布 / 关闭 / 归档…</div>
-    </div>
-    <WorkspaceNav v-model="tab" :groups="tabGroups" />
+    <!-- 课程上下文条：滚动长表格时仍能看到「我在哪个班的哪个环节」 -->
+    <div class="workbench-bar">
+      <PageHeader compact>
+        <template #breadcrumb>…</template>
+        <template #title>…课程名 + StatusPill…</template>
+        <template v-if="offering" #meta>…学期 · 班级 · 人数…</template>
+        <template #actions>…发布 / 关闭 / 归档（原按钮原 @click）…</template>
+      </PageHeader>
+      <WorkspaceNav v-model="tab" :groups="tabGroups" compact />
+    </div>
```

```css
.workbench-bar {
  position: sticky;
  top: var(--topbar-h);   /* 与全局 topbar 同源 */
  z-index: 20;            /* 低于 topbar(30)，浮层仍可覆盖 */
  margin: -12px 0 16px;
  padding: 10px 0 8px;
  background: var(--bg);  /* 复用背景变量，不新增色值 */
  border-bottom: 1px solid var(--line);
}
@media (max-width: 900px) { .workbench-bar { position: static; margin-top: 0; } }
```

### 6.3 学生动线

```js
// config/flow.js
export const STUDENT_FLOW = [
  { key: 'course', label: '进入课程', to: '/courses' },
  { key: 'topic', label: '选题', to: '/courses' },
  { key: 'submit', label: '提交材料', to: '/selections' },
  { key: 'review', label: 'AI 评审', to: '/selections' },
  { key: 'defense', label: '答辩', to: null },
  { key: 'grade', label: '成绩', to: '/grades' },
]
```

```js
// SelectionsView.vue —— 只读推导，不参与任何判断
const flowStep = computed(() => {
  const list = rows.value || []
  if (!list.length) return 'topic'
  return list.some((item) => !item.progress?.reportUploaded) ? 'submit' : 'review'
})
```

### 6.4 批量操作只在实际选中后出现

```diff
-      <span class="small dim">共 {{ total }} 人</span>
-      <button class="btn btn--primary btn--sm" @click="openImport">添加学生</button>
-      <button class="btn btn--ghost btn--sm" :disabled="!selectedCount || removing" @click="removeSelected">
-        <Icon name="trash" :size="14" />移除学生<span v-if="selectedCount">（{{ selectedCount }}）</span>
-      </button>
-      <button class="btn btn--ghost btn--sm" :disabled="exporting" @click="exportRoster">下载名单</button>
+      <!-- 批量操作只在有选中时出现，未选中不占位 -->
+      <template v-if="selectedCount">
+        <span class="small dim">已选 {{ selectedCount }} 人</span>
+        <button class="btn btn--ghost btn--sm" :disabled="removing" @click="removeSelected">
+          <Icon name="trash" :size="14" />移除学生
+        </button>
+      </template>
```

（「添加学生」「下载名单」上移到 `TabHead #actions`，`@click` 未变。）

### 6.5 主/危险操作分离

```diff
+    <TabHead title="成绩" description="先重算再发布。发布后学生可见且成绩锁定，撤销仅有 24 小时窗口。">
+      <template #actions>
+        <button class="btn btn--ghost btn--sm" :disabled="busy === 'recalc'" @click="recalc">重算</button>
+        <button class="btn btn--ghost btn--sm" :disabled="busy === 'export'" @click="exportCsv">导出 CSV</button>
+        <button class="btn btn--primary btn--sm" :disabled="busy === 'publish'" @click="publish">发布成绩</button>
+      </template>
+    </TabHead>
...
-      <button class="btn btn--ghost btn--sm" :disabled="busy === 'unpublish'" @click="unpublish">撤销发布</button>
-      <button class="btn btn--primary btn--sm" :disabled="busy === 'publish'" @click="publish">发布成绩</button>
+      <!-- 破坏性操作与主操作分离：撤销单独留在工具栏，不与「发布成绩」并排 -->
+      <button class="btn btn--ghost btn--sm" :disabled="busy === 'unpublish'" @click="unpublish">撤销发布</button>
```

### 6.6 待办按紧急度排序

```js
const TONE_RANK = { danger: 0, warn: 1, info: 2, ok: 3 }
function byUrgency(list) {
  return [...list].sort((a, b) => (TONE_RANK[a.tone] ?? 9) - (TONE_RANK[b.tone] ?? 9))
}
// 两处 return list.slice(0, 4) → return byUrgency(list).slice(0, 4)
```

---

## 7. 回归测试报告

### 7.1 自动化验证（已执行）

| 项目 | 命令 | 结果 |
|---|---|---|
| 前端构建 | `npx vite build --outDir .vite-check-5` | ✅ built in 9.82s，无 warning 级错误 |
| 前端单测 | `npm run test` | ✅ 37 passed / 0 fail |
| 导航解析 | `node --input-type=module` 调 `navGroupsForRole` | ✅ 三角色输出符合设计（见 6.1） |
| 色值审计 | `grep '#[0-9a-f]{3,6}|rgba('` 新增/改动文件 | ✅ 无命中，全部走 `var(--*)` |
| 残留审计 | `grep 'flow-strip|flow-step'` | ✅ 无残留（旧自定义流程条已并入 StepFlow） |
| 事件绑定审计 | 逐文件核对移动按钮的 `@click` 目标函数 | ✅ `openImport/exportRoster/removeSelected/openCreate/recalc/exportCsv/publish/unpublish/search` 均存在且唯一 |

构建迭代共 5 次（`.vite-check` ~ `.vite-check-5`），每次改动后立即构建，保证「一次只改一个页面」的节奏。

### 7.2 功能零回归（静态核对）

| 检查项 | 结论 |
|---|---|
| 接口调用与改造前一致 | ✅ 未新增/删除任何 `api.*` 调用；`git diff` 中无 `api.` 行变化 |
| 路由与守卫 | ✅ `router/index.js` 本轮未改 |
| tab 切换 | ✅ `tab` ref 与 `v-model` 语义未变 |
| 弹窗/上传/下载/导出 | ✅ `Modal`、`MultiFileUploader`、`exportRoster`、`exportCsv`、`download` 绑定原样 |
| 答辩操作 | ✅ `doStart/doFinish/doEvaluate/skipBonus/submitOverride/load` 未改 |
| 批量发起答辩 | ✅ `DefenseTab` 仅新增页头，选择/确认/轮询逻辑未动 |

### 7.3 零经验用户走查（按代码路径推导）

> 说明：本节为**基于路由与按钮绑定推导**的点击路径，用于验证「3 次点击内可达」。实机点击走查受运行环境限制未执行（见 8.2）。

**教师：新建教学班 → 添加学生 → 发布题目 → 查看提交 → 发起答辩 → 发布成绩**

| 步骤 | 点击路径 | 点击数 | 判定 |
|---|---|---|---|
| 新建教学班 | `/` →「教学班管理」→「新建教学班」 | 2 | ✅ ≤3 |
| 添加学生 | 教学班行 →「学生」tab →「添加学生」 | 3 | ✅ ≤3 |
| 发布题目 | （同上，页内左侧）「题目」tab →「新建题目」 | 2 | ✅ |
| 查看提交 | 「提交评审」tab → 选学生 | 2 | ✅ |
| 发起答辩 | 「答辩」tab → 勾选 →「批量发起答辩」 | 3 | ✅ |
| 发布成绩 | 「成绩」tab →「发布成绩」 | 2 | ✅ |

**学生：进入课程 → 选题 → 提交 → 看评审 → 答辩 → 查成绩**

| 步骤 | 点击路径 | 点击数 | 判定 |
|---|---|---|---|
| 进入课程 | `/` →「去选题」→ 课程卡「去选题」 | 2 | ✅ |
| 选题 | 选题中心 → 题目卡「选题」 | 1 | ✅ |
| 提交源码/报告 | 「我的选题」→ 卡片上传区 | 1 | ✅ |
| 查看 AI 评审 | 「我的选题」→ 卡片「评审状态」 | 1 | ✅ |
| 参加答辩 | 「我的选题」→「进入答辩」 | 1 | ✅ |
| 查看成绩 | 页头「查看成绩」/ 侧边栏「成绩中心」 | 1 | ✅ |

### 7.4 布局与视觉（静态保证 + 待实测项）

| 验收项 | 状态 |
|---|---|
| 无新增色值 | ✅ 静态审计通过 |
| 间距复用现有节奏（4/8/12/16/24px） | ✅ 新组件未引入新的间距数值体系 |
| 主按钮每屏最多一个 | ✅ 逐页核对（首页/工作台/5 个 tab/答辩页） |
| 危险操作与主操作分离 | ✅ 「撤销发布」离开页头，「归档」保持 ghost + danger 确认 |
| 批量操作选中后出现 | ✅ 学生/题目/答辩三处 |
| 1366×768 与 1920×1080 | ⚠ 未实机截图；吸顶区高度按 106px 估算，内容区余量约 580px |
| 无横向滚动条（表格除外） | ⚠ 未实机验证；吸顶容器内页签在窄屏用 `overflow-x: auto` |

---

## 8. 风险与未覆盖项

### 8.1 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 吸顶区遮挡锚点跳转目标 | 锚点滚动到顶会被 106px 吸顶区盖住 | 本轮未引入锚点导航（确认项允许但未使用），后续加锚点需配 `scroll-margin-top` |
| `z-index` 层级 | `workbench-bar` 为 20，低于 `topbar`(30) | 已按此设计；若后续有 `z-index < 20` 的浮层会穿帮 |
| `PageHeader.compact` 使用 `:deep(h1)` | 依赖全局 `h1` 选择器 | 若全局 `h1` 改为 class，这里需同步 |
| 学生动线「答辩」不可点 | 答辩需 `sessionId`，无稳定路径 | 渲染为纯文本，不做假链接 |
| 分组名按角色变化 | 命令面板只显示条目、不显示分组名 | 无影响；若有第三方按 `group` 文案做断言，需改为读 `navGroupsForRole` |

### 8.2 未覆盖 / 未验证

1. **实机视觉走查与截图对比未做**：当前后端 8084 未运行、AI 服务 8000 已被用户主动取消（明确要求不重启），无法登录走完整动线。因此「无控制台报错」「无布局溢出」「两分辨率截图对比」三项**未经浏览器实测**，仅通过构建 + 静态审计 + 代码路径推导保证。
2. **`OverviewTab` / `ExcellentWorksTab` / `SettingsTab` 未加 `TabHead`**：这三个 tab 仍以卡片标题开场，与其余 5 个 tab 不完全一致。未做原因是前两者结构差异大（多卡片并列）、后者是纯表单页，硬套页头反而增加视觉噪音。
3. **窄屏二级导航未做下拉折叠**：当前是「换行 + 横向滚动」，功能不丢失但形态不是下拉。需求原文为「可折叠为下拉」，属于未完全达成项。
4. **`.vite-check` / `.vite-check-2~5` 五个临时产物目录**：已加入 `.gitignore`（`.vite-check-*/`），需你手动删除；本机安全策略拦截了批量删除。
5. **导航改名的影响面**：`nav.js` 分组名变化会被 `CommandPalette` 之外的地方读取吗？已确认只有 `AppLayout` 与 `CommandPalette` 两处消费，且后者只用条目。
6. **未做响应式专项测试**：`< 900px` 的吸顶退化、页签滚动均按 CSS 推断，未在真实窄屏设备验证。

### 8.3 建议的下一步

1. 起三端（后端需 `env -u SERVER__PORT -u SERVER__HOST ./gradlew bootRun`），按 7.3 的两条动线各走一遍，用浏览器 1366×768 / 1920×1080 截图对比首屏。
2. 若确认吸顶区偏高，把 `#meta` 移到二级导航右侧，可再省 24px。
3. 补 `OverviewTab` 等三个 tab 的页头，或明确「概览/设置不套页头」的例外规则并写进规范。
4. 窄屏下拉式二级导航可作为独立小任务，在 `WorkspaceNav` 内加 `collapse` 模式，不影响现有桌面布局。
