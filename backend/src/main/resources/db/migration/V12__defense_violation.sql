-- 答辩违规事件（前端辅助检测 → 服务端留痕 → 教师裁决）
--
-- 设计取舍：
-- 1) 不往 defense_session 上加 violation_count 列。face_check 每 3 秒会更新会话，
--    再叠一个高频自增列只会把乐观锁冲突概率放大；计数改为「按会话聚合查询」，
--    阈值只有 2，一次 GROUP BY 的成本可以忽略。
-- 2) 事件只追加、不更新，教师「维持终止 / 恢复答辩」也各写一条记录，
--    这样一位学生整场答辩的处置链路是完整可回放的，而不是被最后一次操作覆盖。
-- 3) severity 在服务端按类型重新判定（MAJOR 计入阈值 / MINOR 仅留证），
--    不采信前端传来的等级，避免前端被篡改后只上报 MINOR 绕过终止。
CREATE TABLE IF NOT EXISTS defense_violation (
    id             BIGINT       NOT NULL COMMENT '主键',
    defense_id     BIGINT       NOT NULL COMMENT '答辩会话 ID',
    student_id     BIGINT       NOT NULL COMMENT '学生 ID',
    violation_type VARCHAR(32)  NOT NULL COMMENT 'TAB_SWITCH / DEVTOOLS / PASTE_PATTERN / COPY_BLOCK / SHORTCUT_BLOCK / RESUME / UPHOLD',
    severity       VARCHAR(8)   NOT NULL DEFAULT 'MAJOR' COMMENT 'MAJOR 计入终止阈值 / MINOR 仅留证',
    detail         JSON         DEFAULT NULL COMMENT '证据（停留时长、粘贴长度、窗口尺寸等，不含作答正文）',
    ip             VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
    user_agent     VARCHAR(512) DEFAULT NULL COMMENT '浏览器信息',
    occurred_at    DATETIME(3)  NOT NULL COMMENT '发生时间（服务端时间，不采信前端时间戳）',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_dv_defense (defense_id, id),
    KEY idx_dv_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '答辩违规事件';

-- defense_session.status 本身是 VARCHAR(16)，新增终态 VIOLATED 不需要改列定义；
-- 仅补一句注释说明取值，避免后来者以为状态机只有 5 个值。
ALTER TABLE defense_session
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CREATED'
        COMMENT '状态：CREATED / GENERATING / READY / IN_PROGRESS / COMPLETED / EVALUATED / EXPIRED / VIOLATED';
