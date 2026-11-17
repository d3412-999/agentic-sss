-- 批量答辩时间限制 + 自动结束/自动评分支持
ALTER TABLE defense_session
    ADD COLUMN batch_id BIGINT NULL COMMENT '批量发起批次 ID（单条发起为 NULL）' AFTER selection_ids,
    ADD COLUMN deadline_at DATETIME(3) NULL COMMENT '答辩截止时间，超时由看门狗强制结束' AFTER batch_id,
    ADD INDEX idx_defense_session_deadline (status, deadline_at);

ALTER TABLE defense_batch_task
    ADD COLUMN time_limit_minutes INT NULL COMMENT '批量发起的答辩时长限制（分钟）' AFTER config,
    ADD COLUMN deadline_at DATETIME(3) NULL COMMENT '整批答辩截止时间' AFTER time_limit_minutes;
