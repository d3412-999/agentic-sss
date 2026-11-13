-- =============================================================================
-- V9：题目模型瘦身（删除 5 个字段 + 难度索引）
-- =============================================================================
-- 背景：产品上题目只保留「标题 / 描述 / 难度 / 附件」。交付要求、标签、期望技术栈、
--       容量上限、草稿状态全部下线，题目创建即对学生可见，不再有发布/归档状态机。
--
-- 与 V8 的分工：V8 处理 offering 的限选数与答辩模式（已应用），本脚本只动 topic 表。
--       用户要求里写的是「新迁移用 V8」，但 V8 已被占用且已落库（改动它会破坏 Flyway
--       校验和），因此这里使用 V9，语义与要求完全一致：不改历史迁移、只追加。
--
-- 存量数据处理：
--   1) 5 个被删列的内容随列删除而永久丢失。执行前请备份：
--        mysqldump -h127.0.0.1 -uss -p --single-transaction sss topic > topic_before_v9.sql
--   2) published_at 列保留不动（代码已不再读写），历史发布时间仍可查询。
--   3) 历史题目的 difficulty 若存在非法值（例如手工写入的中文），不需要清洗：
--      读取侧统一降级为「未分级」，写入侧会拒绝新的非法值。
--
-- 回滚（Flyway 不支持自动回滚，需手工执行；被删列的数据无法恢复）：
--   ALTER TABLE topic
--     ADD COLUMN requirements  MEDIUMTEXT  DEFAULT NULL COMMENT '具体要求（Markdown）',
--     ADD COLUMN tags          JSON        DEFAULT NULL COMMENT '标签数组',
--     ADD COLUMN expected_tech JSON        DEFAULT NULL COMMENT '期望技术栈数组',
--     ADD COLUMN max_select_count INT      NOT NULL DEFAULT 1 COMMENT '人数上限',
--     ADD COLUMN status        VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态',
--     ADD KEY idx_topic_offering_status (offering_id, status);
--   ALTER TABLE topic DROP KEY idx_topic_offering_difficulty;
--   DELETE FROM flyway_schema_history WHERE version = '9';
-- =============================================================================

-- 1) 先删依赖 status 的索引，否则列删不掉
ALTER TABLE topic DROP INDEX idx_topic_offering_status;

-- 2) 物理删除已下线的 5 列
ALTER TABLE topic
    DROP COLUMN requirements,
    DROP COLUMN tags,
    DROP COLUMN expected_tech,
    DROP COLUMN max_select_count,
    DROP COLUMN status;

-- 3) 难度成为唯一的题目分类维度：按开课 + 难度建复合索引，覆盖列表筛选与分组统计
ALTER TABLE topic
    ADD KEY idx_topic_offering_difficulty (offering_id, difficulty);

-- 4) selected_count 语义从「容量预占」改为「已选人数统计」，注释同步修正
ALTER TABLE topic
    MODIFY COLUMN selected_count INT NOT NULL DEFAULT 0 COMMENT '已选人数（仅统计，无上限）';

ALTER TABLE topic
    MODIFY COLUMN difficulty VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
        COMMENT '难度编码：EASY / MEDIUM / HARD，展示名见 Difficulty 枚举';
