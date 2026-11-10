-- =============================================================================
-- V3：报告正文抽取落库 + 优秀作品模板库
--
-- 1. file_object 增加 extracted_text / page_count，避免每次评审都重新解析 PDF；
-- 2. excellent_work 记录教师选中的优秀作品，以及 AI 模板库索引状态。
-- =============================================================================

ALTER TABLE file_object
    ADD COLUMN extracted_text MEDIUMTEXT DEFAULT NULL COMMENT '抽取的正文（报告正文 / 源码摘要）' AFTER sha256,
    ADD COLUMN page_count INT DEFAULT NULL COMMENT '报告页数（PDF 可读，DOCX 通常为空）' AFTER extracted_text;

CREATE TABLE IF NOT EXISTS excellent_work (
    id             BIGINT        NOT NULL COMMENT '主键',
    offering_id    BIGINT        NOT NULL COMMENT '开课 ID',
    selection_id   BIGINT        NOT NULL COMMENT '选题 ID',
    submission_id  BIGINT        NOT NULL COMMENT '报告提交物 ID',
    title          VARCHAR(200)  NOT NULL COMMENT '作品标题',
    student_name   VARCHAR(64)   DEFAULT NULL COMMENT '学生姓名（快照）',
    student_no     VARCHAR(64)   DEFAULT NULL COMMENT '学号（快照）',
    topic_title    VARCHAR(200)  DEFAULT NULL COMMENT '题目名称（快照）',
    note           VARCHAR(1000) DEFAULT NULL COMMENT '教师推荐理由',
    tags           VARCHAR(500)  DEFAULT NULL COMMENT '标签，逗号分隔',
    content_text   MEDIUMTEXT    DEFAULT NULL COMMENT '用于模板库索引的正文快照',
    index_status   VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '模板库索引状态：PENDING / INDEXED / FAILED',
    index_method   VARCHAR(32)   DEFAULT NULL COMMENT '索引方式：local / lightrag+local',
    external_doc_id VARCHAR(128) DEFAULT NULL COMMENT '外部模板库文档 ID',
    error_message  VARCHAR(500)  DEFAULT NULL COMMENT '索引失败原因',
    created_by     BIGINT        DEFAULT NULL COMMENT '操作教师 ID',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ew_offering (offering_id, deleted),
    KEY idx_ew_selection (selection_id),
    KEY idx_ew_status (index_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '优秀作品模板库';
