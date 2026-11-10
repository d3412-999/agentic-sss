## 这个 PR 做了什么

<!-- 一句话说明改动目标；如果是修 bug，写清原来错在哪 -->

Closes #

## 改动类型

- [ ] 新功能（feat）
- [ ] 缺陷修复（fix）
- [ ] 重构 / 性能（refactor / perf）
- [ ] 文档（docs）
- [ ] 构建 / CI / 依赖（chore）

## 影响范围

- [ ] 后端 `backend/`：Controller / Service / Mapper / 表结构迁移
- [ ] 前端 `vue/`：页面、组件、接口映射
- [ ] AI 服务 `langchain_service/`
- [ ] 脚本 / CI / 文档

涉及数据库时请补充：

- [ ] 新增了 `V{n}__xxx.sql`，且没有修改已执行过的 `V1` / `V2`
- [ ] 若改了表结构，已用 `backend/tools/gen_entities.py` 重新生成实体

## 自测记录

```bash
cd backend && ./gradlew build -x test
cd vue && npm run build
```

- [ ] 上述两条命令本地通过（与 CI 一致）
- [ ] 主链路手动验证过：登录 → 选题 → 上传 → 算分 → 发布成绩（按改动范围取舍）
- [ ] 界面改动附截图（浅色 + 深色；必要时附 420px 窄屏）

## 其他说明

<!-- 破坏性变更、配置项增减、需要 reviewer 特别注意的地方 -->
