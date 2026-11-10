-- =============================================================================
-- 课程设计智能答辩与评分系统 · 数据库初始化脚本（MySQL 8.0+）
--
-- 约定：
--   1. 主键统一为 BIGINT（应用侧雪花算法生成，MyBatis-Plus ASSIGN_ID），不使用自增；
--   2. 所有业务表带 created_at / updated_at，可逻辑删除的表带 deleted；
--   3. 需要并发控制的行带 version（乐观锁）；
--   4. 字符集 utf8mb4 / utf8mb4_0900_ai_ci；
--   5. 不建物理外键，关系与完整性由应用层保证（便于分库与数据迁移）。
-- =============================================================================

SET NAMES utf8mb4;

-- 1. 用户（教师 / 学生）
CREATE TABLE IF NOT EXISTS sys_user (
    id                   BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    username             VARCHAR(64)  NOT NULL COMMENT '登录账号：学号 / 工号',
    password_hash        VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    name                 VARCHAR(50)  NOT NULL COMMENT '姓名',
    role                 VARCHAR(16)  NOT NULL COMMENT '角色：STUDENT / TEACHER / ADMIN',
    student_no           VARCHAR(32)  DEFAULT NULL COMMENT '学号（学生）',
    class_name           VARCHAR(64)  DEFAULT NULL COMMENT '班级',
    email                VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    avatar_file_id       BIGINT       DEFAULT NULL COMMENT '头像文件 ID',
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / DISABLED',
    must_change_password TINYINT      NOT NULL DEFAULT 0 COMMENT '是否必须修改密码：0 否 / 1 是',
    last_login_at        DATETIME(3)  DEFAULT NULL COMMENT '最近登录时间',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_student_no (student_no),
    KEY idx_user_role_status (role, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户（教师 / 学生）';

-- 2. 课程模板
CREATE TABLE IF NOT EXISTS course (
    id          BIGINT       NOT NULL COMMENT '主键',
    code        VARCHAR(32)  NOT NULL COMMENT '课程代码',
    name        VARCHAR(128) NOT NULL COMMENT '课程名称',
    credit      DECIMAL(4, 1) NOT NULL DEFAULT 0.0 COMMENT '学分',
    description TEXT         DEFAULT NULL COMMENT '课程简介（Markdown）',
    created_by  BIGINT       DEFAULT NULL COMMENT '创建人（教师）',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_code (code),
    KEY idx_course_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程模板';

-- 3. 开课（某学期某教学班的一门课程设计）
CREATE TABLE IF NOT EXISTS offering (
    id                 BIGINT       NOT NULL COMMENT '主键',
    course_id          BIGINT       NOT NULL COMMENT '课程 ID',
    teacher_id         BIGINT       NOT NULL COMMENT '任课教师 ID',
    semester           VARCHAR(16)  NOT NULL COMMENT '学期，如 2026-2027-1',
    class_name         VARCHAR(64)  NOT NULL COMMENT '教学班',
    description        TEXT         DEFAULT NULL COMMENT '开课说明',
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT / PUBLISHED / CLOSED / ARCHIVED',
    topic_select_limit INT          NOT NULL DEFAULT 3 COMMENT '每人最多可选题目数',
    weight_defense     TINYINT      NOT NULL DEFAULT 40 COMMENT '答辩成绩权重（%）',
    weight_code        TINYINT      NOT NULL DEFAULT 35 COMMENT '源码成绩权重（%）',
    weight_report      TINYINT      NOT NULL DEFAULT 25 COMMENT '报告成绩权重（%）',
    defense_mode       VARCHAR(16)  NOT NULL DEFAULT 'PER_STUDENT' COMMENT '答辩组织方式：PER_STUDENT / PER_TOPIC',
    question_count     TINYINT      NOT NULL DEFAULT 6 COMMENT '答辩题目数量',
    max_answer_seconds SMALLINT     NOT NULL DEFAULT 180 COMMENT '单题作答时限（秒）',
    allow_retry        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许跳过/重答：0 否 / 1 是',
    enable_video       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否录制视频：0 否 / 1 是',
    language           VARCHAR(16)  NOT NULL DEFAULT 'zh-CN' COMMENT '答辩语言',
    select_start       DATETIME(3)  DEFAULT NULL COMMENT '选题开始时间',
    select_end         DATETIME(3)  DEFAULT NULL COMMENT '选题结束时间',
    submit_deadline    DATETIME(3)  DEFAULT NULL COMMENT '材料提交截止时间',
    defense_start      DATETIME(3)  DEFAULT NULL COMMENT '答辩开始时间',
    defense_end        DATETIME(3)  DEFAULT NULL COMMENT '答辩结束时间',
    published_at       DATETIME(3)  DEFAULT NULL COMMENT '发布时间',
    version            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offering_class (teacher_id, semester, class_name),
    KEY idx_offering_teacher (teacher_id, status),
    KEY idx_offering_semester (semester),
    KEY idx_offering_course (course_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '开课';

-- 4. 开课学生名单
CREATE TABLE IF NOT EXISTS offering_student (
    id          BIGINT      NOT NULL COMMENT '主键',
    offering_id BIGINT      NOT NULL COMMENT '开课 ID',
    student_id  BIGINT      NOT NULL COMMENT '学生 ID',
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / DISABLED',
    joined_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '加入时间',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_os_offering_student (offering_id, student_id),
    KEY idx_os_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '开课学生名单';

-- 5. 课程设计题目
CREATE TABLE IF NOT EXISTS topic (
    id               BIGINT       NOT NULL COMMENT '主键',
    offering_id      BIGINT       NOT NULL COMMENT '开课 ID',
    title            VARCHAR(100) NOT NULL COMMENT '题目名称',
    description      MEDIUMTEXT   DEFAULT NULL COMMENT '题目描述（Markdown）',
    requirements     MEDIUMTEXT   DEFAULT NULL COMMENT '具体要求（Markdown）',
    difficulty       VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '难度：EASY / MEDIUM / HARD',
    max_select_count INT          NOT NULL DEFAULT 1 COMMENT '人数上限',
    selected_count   INT          NOT NULL DEFAULT 0 COMMENT '已选人数（容量预占）',
    tags             JSON         DEFAULT NULL COMMENT '标签数组',
    expected_tech    JSON         DEFAULT NULL COMMENT '期望技术栈数组',
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT / PUBLISHED / ARCHIVED',
    published_at     DATETIME(3)  DEFAULT NULL COMMENT '发布时间',
    created_by       BIGINT       DEFAULT NULL COMMENT '创建人',
    version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_title (offering_id, title),
    KEY idx_topic_offering_status (offering_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程设计题目';

-- 6. 题目附件
CREATE TABLE IF NOT EXISTS topic_attachment (
    id         BIGINT      NOT NULL COMMENT '主键',
    topic_id   BIGINT      NOT NULL COMMENT '题目 ID',
    file_id    BIGINT      NOT NULL COMMENT '文件 ID',
    sort_no    INT         NOT NULL DEFAULT 0 COMMENT '排序号',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ta_topic_file (topic_id, file_id),
    KEY idx_ta_file (file_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题目附件';

-- 7. 选题
CREATE TABLE IF NOT EXISTS selection (
    id          BIGINT      NOT NULL COMMENT '主键',
    offering_id BIGINT      NOT NULL COMMENT '开课 ID（冗余，便于按开课查询）',
    topic_id    BIGINT      NOT NULL COMMENT '题目 ID',
    student_id  BIGINT      NOT NULL COMMENT '学生 ID',
    status      VARCHAR(16) NOT NULL DEFAULT 'SELECTED' COMMENT '状态：SELECTED / SUBMITTED / DEFENDING / DEFENDED / GRADED / CANCELED',
    version     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    canceled_at DATETIME(3) DEFAULT NULL COMMENT '退选时间',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_selection_student_topic (student_id, topic_id),
    KEY idx_selection_offering (offering_id, status),
    KEY idx_selection_topic (topic_id),
    KEY idx_selection_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生选题';

-- 8. 提交物（每道选题的源码 / 报告各一条，指向最新版本）
CREATE TABLE IF NOT EXISTS submission (
    id                BIGINT      NOT NULL COMMENT '主键',
    selection_id      BIGINT      NOT NULL COMMENT '选题 ID',
    type              VARCHAR(16) NOT NULL COMMENT '类型：SOURCE / REPORT',
    latest_version    INT         NOT NULL DEFAULT 0 COMMENT '最新版本号',
    latest_version_id BIGINT      DEFAULT NULL COMMENT '最新版本记录 ID',
    status            VARCHAR(16) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态：UPLOADED / PARSING / VALID / INVALID / ANALYZING / ANALYZED',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted           TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_selection_type (selection_id, type),
    KEY idx_submission_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '提交物（源码 / 报告）';

-- 9. 提交物版本（每次上传生成一条，历史版本保留）
CREATE TABLE IF NOT EXISTS submission_version (
    id             BIGINT       NOT NULL COMMENT '主键',
    submission_id  BIGINT       NOT NULL COMMENT '提交物 ID',
    version        INT          NOT NULL COMMENT '版本号，从 1 开始',
    file_id        BIGINT       NOT NULL COMMENT '文件 ID',
    status         VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED' COMMENT '状态：UPLOADED / PARSING / VALID / INVALID / DELETED',
    parse_result   JSON         DEFAULT NULL COMMENT '解析结果（文件数、行数、构建方式等）',
    page_count     INT          DEFAULT NULL COMMENT '报告页数',
    java_file_count INT         DEFAULT NULL COMMENT 'Java 文件数',
    code_lines     INT          DEFAULT NULL COMMENT '有效代码行数',
    build_tool     VARCHAR(16)  DEFAULT NULL COMMENT '构建方式：MAVEN / GRADLE / PLAIN',
    compile_passed TINYINT      DEFAULT NULL COMMENT '编译是否通过：0 否 / 1 是 / NULL 未检测',
    remark         VARCHAR(255) DEFAULT NULL COMMENT '版本说明',
    created_by     BIGINT       DEFAULT NULL COMMENT '上传人',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sv_submission_version (submission_id, version),
    KEY idx_sv_file (file_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '提交物版本';

-- 10. 文件对象
CREATE TABLE IF NOT EXISTS file_object (
    id           BIGINT       NOT NULL COMMENT '主键',
    storage      VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT '存储类型：LOCAL / MINIO / OSS',
    bucket       VARCHAR(64)  DEFAULT NULL COMMENT '存储桶',
    object_key   VARCHAR(512) NOT NULL COMMENT '对象键',
    filename     VARCHAR(255) NOT NULL COMMENT '原始文件名',
    content_type VARCHAR(128) DEFAULT NULL COMMENT 'MIME 类型',
    size_bytes   BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    sha256       CHAR(64)     DEFAULT NULL COMMENT '文件 SHA-256，用于秒传与去重',
    created_by   BIGINT       DEFAULT NULL COMMENT '上传人',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_file_sha256 (sha256),
    KEY idx_file_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件对象';

-- 11. 源码分析结果（每个源码提交一条）
CREATE TABLE IF NOT EXISTS code_analysis (
    id             BIGINT       NOT NULL COMMENT '主键',
    submission_id  BIGINT       NOT NULL COMMENT '源码提交物 ID',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / RUNNING / SUCCEEDED / FAILED',
    score          DECIMAL(5, 1) DEFAULT NULL COMMENT '总分（百分制）',
    grade          VARCHAR(16)  DEFAULT NULL COMMENT '等级',
    compile_passed TINYINT      DEFAULT NULL COMMENT '编译是否通过',
    summary        TEXT         DEFAULT NULL COMMENT '总体评价',
    metrics        JSON         DEFAULT NULL COMMENT '度量指标（复杂度、重复率、注释率等）',
    tool_errors    JSON         DEFAULT NULL COMMENT '工具执行异常',
    model          VARCHAR(64)  DEFAULT NULL COMMENT 'AI 评审模型',
    finished_at    DATETIME(3)  DEFAULT NULL COMMENT '完成时间',
    version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ca_submission (submission_id),
    KEY idx_ca_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '源码分析结果';

-- 12. 源码问题明细
CREATE TABLE IF NOT EXISTS code_issue (
    id            BIGINT        NOT NULL COMMENT '主键',
    analysis_id   BIGINT        NOT NULL COMMENT '分析结果 ID',
    tool          VARCHAR(16)   NOT NULL COMMENT '工具：CHECKSTYLE / PMD / SPOTBUGS / AI',
    rule_name     VARCHAR(128)  DEFAULT NULL COMMENT '规则名',
    severity      VARCHAR(16)   NOT NULL DEFAULT 'LOW' COMMENT '严重度：HIGH / MEDIUM / LOW / ERROR / WARNING / INFO',
    message       VARCHAR(1000) NOT NULL COMMENT '问题描述',
    file_path     VARCHAR(512)  DEFAULT NULL COMMENT '文件路径',
    line_no       INT           DEFAULT NULL COMMENT '行号',
    column_no     INT           DEFAULT NULL COMMENT '列号',
    snippet       TEXT          DEFAULT NULL COMMENT '代码片段',
    ignored       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否被教师忽略',
    ignore_reason VARCHAR(255)  DEFAULT NULL COMMENT '忽略原因',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ci_analysis (analysis_id, severity),
    KEY idx_ci_rule (tool, rule_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '源码问题明细';

-- 13. 报告评审结果（每个报告提交一条）
CREATE TABLE IF NOT EXISTS report_evaluation (
    id            BIGINT        NOT NULL COMMENT '主键',
    submission_id BIGINT        NOT NULL COMMENT '报告提交物 ID',
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / RUNNING / SUCCEEDED / FAILED',
    score         DECIMAL(5, 1) DEFAULT NULL COMMENT '总分',
    grade         VARCHAR(16)   DEFAULT NULL COMMENT '等级',
    summary       TEXT          DEFAULT NULL COMMENT '总体评价',
    highlights    JSON          DEFAULT NULL COMMENT '亮点',
    problems      JSON          DEFAULT NULL COMMENT '问题与建议',
    consistency   JSON          DEFAULT NULL COMMENT '报告-源码一致性检查',
    plagiarism    JSON          DEFAULT NULL COMMENT '查重结果',
    page_count    INT           DEFAULT NULL COMMENT '页数',
    model         VARCHAR(64)   DEFAULT NULL COMMENT '评审模型',
    finished_at   DATETIME(3)   DEFAULT NULL COMMENT '完成时间',
    version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_re_submission (submission_id),
    KEY idx_re_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '报告评审结果';

-- 14. 答辩会话
CREATE TABLE IF NOT EXISTS defense_session (
    id             BIGINT        NOT NULL COMMENT '主键',
    offering_id    BIGINT        NOT NULL COMMENT '开课 ID',
    student_id     BIGINT        NOT NULL COMMENT '学生 ID',
    mode           VARCHAR(16)   NOT NULL DEFAULT 'PER_STUDENT' COMMENT '组织方式：PER_STUDENT / PER_TOPIC',
    status         VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED / GENERATING / READY / IN_PROGRESS / COMPLETED / EVALUATED / EXPIRED',
    selection_ids  JSON          DEFAULT NULL COMMENT '覆盖的选题 ID 数组',
    question_count TINYINT       NOT NULL DEFAULT 0 COMMENT '题目数量',
    score          DECIMAL(5, 1) DEFAULT NULL COMMENT '答辩总分',
    grade          VARCHAR(16)   DEFAULT NULL COMMENT '等级',
    summary        TEXT          DEFAULT NULL COMMENT '答辩总评',
    started_at     DATETIME(3)   DEFAULT NULL COMMENT '开始时间',
    finished_at    DATETIME(3)   DEFAULT NULL COMMENT '结束时间',
    evaluated_at   DATETIME(3)   DEFAULT NULL COMMENT '评分完成时间',
    duration_ms    BIGINT        DEFAULT NULL COMMENT '答辩时长（毫秒）',
    reset_count    INT           NOT NULL DEFAULT 0 COMMENT '教师重置次数',
    version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ds_offering_status (offering_id, status),
    KEY idx_ds_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '答辩会话';

-- 15. 答辩题目
CREATE TABLE IF NOT EXISTS defense_question (
    id                 BIGINT       NOT NULL COMMENT '主键',
    defense_id         BIGINT       NOT NULL COMMENT '答辩会话 ID',
    order_no           INT          NOT NULL COMMENT '题目序号，从 1 开始',
    type               VARCHAR(16)  NOT NULL COMMENT '类型：DESIGN / CODE / REPORT / CONCEPT / EXTENSION',
    difficulty         VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '难度',
    question           TEXT         NOT NULL COMMENT '题干',
    expected_points    JSON         DEFAULT NULL COMMENT '评分要点（教师端可见）',
    reference_answer   TEXT         DEFAULT NULL COMMENT '参考答案（教师端可见）',
    source_refs        JSON         DEFAULT NULL COMMENT '题目来源引用（文件行号 / 报告章节 / 问题 ID）',
    max_answer_seconds SMALLINT     NOT NULL DEFAULT 180 COMMENT '作答时限（秒）',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dq_defense_order (defense_id, order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '答辩题目';

-- 16. 答辩作答
CREATE TABLE IF NOT EXISTS defense_answer (
    id             BIGINT        NOT NULL COMMENT '主键',
    defense_id     BIGINT        NOT NULL COMMENT '答辩会话 ID',
    question_id    BIGINT        NOT NULL COMMENT '答辩题目 ID',
    audio_file_id  BIGINT        DEFAULT NULL COMMENT '音频文件 ID',
    duration_ms    BIGINT        DEFAULT NULL COMMENT '作答时长（毫秒）',
    transcript     MEDIUMTEXT    DEFAULT NULL COMMENT '转写文本',
    asr_confidence DECIMAL(4, 3) DEFAULT NULL COMMENT 'ASR 平均置信度',
    status         VARCHAR(16)   NOT NULL DEFAULT 'UPLOADED' COMMENT '状态：UPLOADED / TRANSCRIBING / TRANSCRIBED / FAILED',
    answered_at    DATETIME(3)   DEFAULT NULL COMMENT '作答时间',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_da_question (question_id),
    KEY idx_da_defense (defense_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '答辩作答';

-- 17. 评分明细（维度分 / 单题分，按业务类型区分）
CREATE TABLE IF NOT EXISTS score (
    id         BIGINT        NOT NULL COMMENT '主键',
    biz_type   VARCHAR(32)   NOT NULL COMMENT '业务类型：DEFENSE / DEFENSE_QUESTION / REPORT / CODE',
    biz_id     BIGINT        NOT NULL COMMENT '业务 ID',
    item_key   VARCHAR(64)   DEFAULT NULL COMMENT '维度或题目键',
    item_name  VARCHAR(128)  DEFAULT NULL COMMENT '维度或题目名称',
    weight     DECIMAL(5, 1) DEFAULT NULL COMMENT '权重（百分制维度用）',
    score      DECIMAL(5, 1) DEFAULT NULL COMMENT '得分',
    comment    VARCHAR(500)  DEFAULT NULL COMMENT '评语',
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_score_biz (biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评分明细';

-- 18. 学生成绩（开课维度汇总）
CREATE TABLE IF NOT EXISTS grade (
    id              BIGINT        NOT NULL COMMENT '主键',
    offering_id     BIGINT        NOT NULL COMMENT '开课 ID',
    student_id      BIGINT        NOT NULL COMMENT '学生 ID',
    defense_score   DECIMAL(5, 1) DEFAULT NULL COMMENT '答辩成绩',
    code_score      DECIMAL(5, 1) DEFAULT NULL COMMENT '源码成绩',
    report_score    DECIMAL(5, 1) DEFAULT NULL COMMENT '报告成绩',
    final_score     DECIMAL(5, 1) DEFAULT NULL COMMENT '最终成绩',
    level           VARCHAR(16)   DEFAULT NULL COMMENT '等级：优秀 / 良好 / 中等 / 及格 / 不及格',
    status          VARCHAR(16)   NOT NULL DEFAULT 'AUTO' COMMENT '状态：AUTO / ADJUSTED / LOCKED',
    adjusted        TINYINT       NOT NULL DEFAULT 0 COMMENT '是否被教师调整',
    teacher_comment VARCHAR(1000) DEFAULT NULL COMMENT '教师评语',
    published_at    DATETIME(3)   DEFAULT NULL COMMENT '成绩发布时间',
    version         INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grade_offering_student (offering_id, student_id),
    KEY idx_grade_status (offering_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生成绩';

-- 19. 成绩明细（按选题拆分）
CREATE TABLE IF NOT EXISTS grade_item (
    id            BIGINT        NOT NULL COMMENT '主键',
    grade_id      BIGINT        NOT NULL COMMENT '成绩 ID',
    selection_id  BIGINT        NOT NULL COMMENT '选题 ID',
    topic_title   VARCHAR(100)  DEFAULT NULL COMMENT '题目名称快照',
    defense_score DECIMAL(5, 1) DEFAULT NULL COMMENT '答辩分',
    code_score    DECIMAL(5, 1) DEFAULT NULL COMMENT '源码分',
    report_score  DECIMAL(5, 1) DEFAULT NULL COMMENT '报告分',
    final_score   DECIMAL(5, 1) DEFAULT NULL COMMENT '小计',
    weights       JSON          DEFAULT NULL COMMENT '计算时的权重快照',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gi_grade_selection (grade_id, selection_id),
    KEY idx_gi_selection (selection_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '成绩明细';

-- 20. 异步任务（解析、分析、评审、生成题目、导出等）
CREATE TABLE IF NOT EXISTS async_task (
    id            BIGINT        NOT NULL COMMENT '主键',
    type          VARCHAR(32)   NOT NULL COMMENT '任务类型：PARSE_SOURCE / PARSE_REPORT / CODE_ANALYSIS / REPORT_EVALUATION / DEFENSE_QUESTION_GEN / DEFENSE_EVALUATION / GRADE_EXPORT / GRADE_RECALC',
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED',
    progress      TINYINT       NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    biz_type      VARCHAR(32)   DEFAULT NULL COMMENT '业务类型',
    biz_id        BIGINT        DEFAULT NULL COMMENT '业务 ID',
    operator_id   BIGINT        DEFAULT NULL COMMENT '发起人',
    payload       JSON          DEFAULT NULL COMMENT '任务入参',
    result        JSON          DEFAULT NULL COMMENT '任务结果',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    started_at    DATETIME(3)   DEFAULT NULL COMMENT '开始时间',
    finished_at   DATETIME(3)   DEFAULT NULL COMMENT '结束时间',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_task_status (status, created_at),
    KEY idx_task_biz (biz_type, biz_id),
    KEY idx_task_operator (operator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '异步任务';

-- 21. 站内通知
CREATE TABLE IF NOT EXISTS notification (
    id         BIGINT       NOT NULL COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '接收人',
    type       VARCHAR(32)  NOT NULL COMMENT '类型：SELECTION_OPEN / SUBMIT_DEADLINE / DEFENSE_READY / ANALYSIS_DONE / GRADE_PUBLISHED / TEACHER_MESSAGE',
    title      VARCHAR(200) NOT NULL COMMENT '标题',
    content    VARCHAR(1000) DEFAULT NULL COMMENT '正文',
    link       VARCHAR(512) DEFAULT NULL COMMENT '前端跳转路径',
    read_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读',
    read_at    DATETIME(3)  DEFAULT NULL COMMENT '阅读时间',
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_notification_user (user_id, read_flag),
    KEY idx_notification_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站内通知';

-- 22. 审计日志（追加写，不更新、不删除）
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT       NOT NULL COMMENT '主键',
    actor_id    BIGINT       DEFAULT NULL COMMENT '操作人',
    actor_role  VARCHAR(16)  DEFAULT NULL COMMENT '操作人角色',
    action      VARCHAR(64)  NOT NULL COMMENT '动作：GRADE_ADJUST / DEFENSE_RESET / GRADE_PUBLISH / STUDENT_IMPORT ...',
    target_type VARCHAR(32)  DEFAULT NULL COMMENT '对象类型',
    target_id   BIGINT       DEFAULT NULL COMMENT '对象 ID',
    before_json JSON         DEFAULT NULL COMMENT '变更前快照',
    after_json  JSON         DEFAULT NULL COMMENT '变更后快照',
    reason      VARCHAR(500) DEFAULT NULL COMMENT '原因（改分等必须填写）',
    ip          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
    user_agent  VARCHAR(255) DEFAULT NULL COMMENT 'User-Agent',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_target (target_type, target_id),
    KEY idx_audit_actor (actor_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '审计日志';
