-- =============================================================================
-- V8：移除教学班创建上的数量限制与废弃配置
--
-- 语义约定（前后端、AI 服务共同遵守）：NULL = 不限。
--   * topic_select_limit = NULL  每人可选题目数不限
--   * question_count      = NULL  答辩题数不限（发起答辩时按需指定，未指定用默认值）
--   * course_id           = NULL  未关联课程模板（前端已移除该选择）
--   * defense_mode               已废弃，固定走 AI 异步答辩；列保留以兼容历史数据
--
-- 本迁移只放宽约束 + 归一存量值，不删除任何列，历史教学班仍可正常读取与编辑。
-- 可重复执行：MODIFY 与幂等 UPDATE 均不会因重复运行而失败。
--
-- 回滚（Flyway 不自动回滚，需手工执行；执行前先确认没有真正需要「不限」的教学班）：
--   UPDATE offering SET topic_select_limit = 3 WHERE topic_select_limit IS NULL;
--   UPDATE offering SET question_count = 6      WHERE question_count IS NULL;
--   UPDATE offering SET course_id = (SELECT MIN(id) FROM course) WHERE course_id IS NULL;
--   ALTER TABLE offering
--       MODIFY COLUMN topic_select_limit INT NOT NULL DEFAULT 3,
--       MODIFY COLUMN question_count TINYINT NOT NULL DEFAULT 6,
--       MODIFY COLUMN course_id BIGINT NOT NULL COMMENT '课程 ID';
-- =============================================================================

ALTER TABLE offering
    MODIFY COLUMN topic_select_limit INT     NULL DEFAULT NULL COMMENT '每人最多可选题目数；NULL = 不限',
    MODIFY COLUMN question_count     TINYINT NULL DEFAULT NULL COMMENT '答辩题目数量；NULL = 不限',
    MODIFY COLUMN course_id          BIGINT  NULL              COMMENT '课程 ID；NULL = 未关联课程模板',
    MODIFY COLUMN defense_mode       VARCHAR(16) NOT NULL DEFAULT 'PER_STUDENT'
        COMMENT '已废弃：固定 AI 异步答辩，仅保留历史数据';

-- 存量归一：历史教学班的 3 / 5 等硬限制统一改为「不限」。
UPDATE offering SET topic_select_limit = NULL WHERE topic_select_limit IS NOT NULL;
UPDATE offering SET question_count = NULL WHERE question_count IS NOT NULL;
