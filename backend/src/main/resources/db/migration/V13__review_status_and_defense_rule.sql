-- 报告页卡住 + 题数分档 + 三原则判别 + 加分题
--
-- 设计取舍：
-- 1) 不改既有状态机。defense_session 只加「旁路字段」（快照 / 判定 / 覆盖留痕），
--    READY → IN_PROGRESS → COMPLETED → EVALUATED 的推进代码一行不动。
-- 2) 加分题与计分题「同表不同标」：defense_question.is_bonus=1 的题不参与总分，
--    defense_session.bonus_* 单独存结果，成绩计算链路（GradeService）不读这两列，
--    所以不可能被误计入总分。
-- 3) 历史答辩不追溯：所有新列可空或带默认值，老会话 dimension 为 NULL 时
--    前端按「未分维度」展示，判定结论保持 NULL（未判定），不会被新规则改写。
-- 4) 评审状态要能被「收敛」：code_analysis / report_evaluation 补 error_message 与
--    retry_count，配合 ReviewWatchdog 把卡在 RUNNING 的记录标 FAILED，
--    否则任务线程一挂就永远是「评审中」。

-- ---------------------------------------------------------------- 评审状态
ALTER TABLE code_analysis
    ADD COLUMN error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（面向学生，不含堆栈）',
    ADD COLUMN retry_count   INT           NOT NULL DEFAULT 0 COMMENT '重新触发次数';

ALTER TABLE report_evaluation
    ADD COLUMN error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（面向学生，不含堆栈）',
    ADD COLUMN retry_count   INT           NOT NULL DEFAULT 0 COMMENT '重新触发次数';

-- 看门狗按 (status, updated_at) 扫超时记录，没有索引会全表扫
CREATE INDEX idx_ca_status_updated ON code_analysis (status, updated_at);
CREATE INDEX idx_re_status_updated ON report_evaluation (status, updated_at);

-- ---------------------------------------------------------------- 题数规则快照
ALTER TABLE defense_session
    ADD COLUMN code_score_snapshot     DECIMAL(5, 1) DEFAULT NULL COMMENT '发起时源码分快照（决定题档）',
    ADD COLUMN question_rule_tier      VARCHAR(16)   DEFAULT NULL COMMENT '题数档位：T0 / T1 / T2 / T3 / T4',
    ADD COLUMN difficulty_mix          VARCHAR(64)   DEFAULT NULL COMMENT '难度配比快照，如 EASY:3,MEDIUM:2,HARD:1',
    ADD COLUMN question_override_reason VARCHAR(255) DEFAULT NULL COMMENT '教师手动指定题数时的理由（非空即视为覆盖）',
    ADD COLUMN bonus_question_count    TINYINT       NOT NULL DEFAULT 0 COMMENT '加分题数量（不计入 question_count）';

-- ---------------------------------------------------------------- 三原则判定
ALTER TABLE defense_session
    ADD COLUMN ai_dependent            TINYINT       DEFAULT NULL COMMENT '1=完全依赖 AI / 0=未判定为依赖 / NULL=未判定（历史数据）',
    ADD COLUMN principle_result        VARCHAR(500)  DEFAULT NULL COMMENT 'P1/P2/P3 逐项结论 JSON 快照',
    ADD COLUMN ai_dependent_overridden TINYINT       NOT NULL DEFAULT 0 COMMENT '教师是否覆盖过判定',
    ADD COLUMN override_reason         VARCHAR(255)  DEFAULT NULL COMMENT '覆盖理由（仅留痕，不做二次审批）',
    ADD COLUMN override_by             BIGINT        DEFAULT NULL COMMENT '覆盖人 ID',
    ADD COLUMN override_at             DATETIME(3)   DEFAULT NULL COMMENT '覆盖时间',
    ADD COLUMN bonus_result            VARCHAR(16)   DEFAULT NULL COMMENT '加分题结果：CORRECT / WRONG / UNANSWERED',
    ADD COLUMN bonus_score             DECIMAL(5, 1) DEFAULT NULL COMMENT '加分题得分（仅供展示，不进总分）',
    ADD COLUMN bonus_comment           VARCHAR(1000) DEFAULT NULL COMMENT '加分题 AI 简评（不进总分）';

-- 题目维度标签：P1_RETELL / P2_REASON / P3_MODIFY；加分题 IS_BONUS
ALTER TABLE defense_question
    ADD COLUMN dimension VARCHAR(16) DEFAULT NULL COMMENT '判别维度：P1_RETELL / P2_REASON / P3_MODIFY / IS_BONUS / NULL(历史)',
    ADD COLUMN is_bonus  TINYINT     NOT NULL DEFAULT 0 COMMENT '是否加分题（不参与总分）';

-- 单题评分与判定证据
ALTER TABLE defense_answer
    ADD COLUMN score   DECIMAL(5, 1) DEFAULT NULL COMMENT '单题得分 0~100',
    ADD COLUMN passed  TINYINT       DEFAULT NULL COMMENT '该维度是否通过（阈值判定）',
    ADD COLUMN evidence VARCHAR(1000) DEFAULT NULL COMMENT '判定证据：转写片段或要点，供复核与申诉',
    ADD COLUMN comment VARCHAR(1000) DEFAULT NULL COMMENT 'AI 简评',
    ADD COLUMN skipped TINYINT       NOT NULL DEFAULT 0 COMMENT '学生主动跳过（加分题可跳过）';

-- ---------------------------------------------------------------- 批量发起答辩
CREATE TABLE IF NOT EXISTS defense_batch_task (
    id            BIGINT       NOT NULL COMMENT '主键',
    offering_id   BIGINT       NOT NULL COMMENT '开课 ID',
    operator_id   BIGINT       NOT NULL COMMENT '发起教师 ID',
    status        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / DONE / PARTIAL / FAILED',
    total         INT          NOT NULL DEFAULT 0 COMMENT '请求条数',
    succeeded     INT          NOT NULL DEFAULT 0 COMMENT '成功条数',
    skipped       INT          NOT NULL DEFAULT 0 COMMENT '跳过条数',
    failed        INT          NOT NULL DEFAULT 0 COMMENT '失败条数',
    config        JSON         DEFAULT NULL COMMENT '本次批量参数（并发数 / 是否允许覆盖等）',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at   DATETIME(3)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_dbt_offering (offering_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '批量发起答辩任务';

CREATE TABLE IF NOT EXISTS defense_batch_item (
    id          BIGINT       NOT NULL COMMENT '主键',
    batch_id    BIGINT       NOT NULL COMMENT '批量任务 ID',
    selection_id BIGINT      NOT NULL COMMENT '选题 ID',
    student_id  BIGINT       DEFAULT NULL COMMENT '学生 ID',
    status      VARCHAR(16)  NOT NULL COMMENT 'SUCCEEDED / SKIPPED / FAILED',
    session_id  BIGINT       DEFAULT NULL COMMENT '成功时创建的会话 ID',
    question_count INT       DEFAULT NULL COMMENT '本次生成题数（不含加分题）',
    reason      VARCHAR(255) DEFAULT NULL COMMENT '跳过 / 失败原因',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_dbi_batch (batch_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '批量发起答辩逐条结果';
