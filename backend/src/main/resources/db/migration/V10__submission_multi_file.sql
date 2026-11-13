-- ============================================================================
-- V10：提交物支持多文件（源码 1~9 份、报告 1~9 份，两类独立计数）
--
-- 为什么不是"把 submission 旧文件列搬过来"：
--   本项目从一开始就是三层模型——submission（提交物）→ submission_version（一次上传）
--   → file_object（文件实体）。submission 表里从来没有文件列，file_id 挂在 version 上，
--   所以历史数据回填只是"把每个 version 的单文件展开成一条文件记录"，不涉及列搬迁。
--
-- 为什么不新建 submission_file 而是 submission_version_file：
--   "版本历史 + 每类仅显示最新版本"的语义已经在 submission_version 上，
--   文件挂在 version 上，历史版本的多文件组合才能被完整还原。
--   挂在 submission 上会让"旧版本有几个文件"永久丢失。
--
-- 部署前请备份：
--   mysqldump -h127.0.0.1 -uroot -p sss > backup_before_v10_$(date +%Y%m%d_%H%M%S).sql
--
-- 回滚（人工执行，Flyway 不回滚）：
--   DROP TABLE IF EXISTS submission_version_file;
--   注意：回滚会丢失多文件提交的文件清单；file_object 与磁盘文件仍在，
--   但"哪个文件属于哪个版本"的信息需要靠 submission_version.file_id 恢复（仅单文件版本可用）。
-- ============================================================================

-- 1. 提交版本内的文件清单
CREATE TABLE IF NOT EXISTS submission_version_file (
    id            BIGINT       NOT NULL COMMENT '主键',
    version_id    BIGINT       NOT NULL COMMENT '提交版本 ID（submission_version.id）',
    submission_id BIGINT       NOT NULL COMMENT '冗余：提交物 ID，按提交物聚合时免联表',
    selection_id  BIGINT       NOT NULL COMMENT '冗余：选题 ID，跨版本/跨学生相似度比对用',
    file_type     VARCHAR(16)  NOT NULL COMMENT '类别：SOURCE / REPORT',
    file_id       BIGINT       NOT NULL COMMENT '文件对象 ID（file_object.id）',
    original_name VARCHAR(255) NOT NULL COMMENT '原始文件名，含扩展名',
    size_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    sha256        CHAR(64)     DEFAULT NULL COMMENT '内容 SHA-256，用于去重与相似度比对',
    structure_key CHAR(64)     DEFAULT NULL COMMENT '结构指纹：源码包内条目名排序后的 SHA-256，用于近似比对',
    sort_index    INT          NOT NULL DEFAULT 0 COMMENT '同版本内的展示/评审顺序，从 0 开始',
    created_by    BIGINT       DEFAULT NULL COMMENT '上传人',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一个版本里不允许把同一个物理文件挂两次（前端重复选择同一个文件会命中这里）
    UNIQUE KEY uk_svf_version_file (version_id, file_id),
    KEY idx_svf_version_sort (version_id, sort_index),
    KEY idx_svf_sha256 (sha256),
    KEY idx_svf_structure (structure_key),
    KEY idx_svf_selection_type (selection_id, file_type),
    KEY idx_svf_submission (submission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '提交版本内的文件清单（一个版本 = 1~9 个文件）';


-- 2. 历史回填：每个已有 version 展开成一条文件记录
--    id 直接复用 version.id：version 与 version_file 是两张表，ID 空间互不影响；
--    这样回填是幂等的——重复执行时 LEFT JOIN 命中 x.id 而跳过，不会插重。
INSERT INTO submission_version_file
    (id, version_id, submission_id, selection_id, file_type, file_id,
     original_name, size_bytes, sha256, structure_key, sort_index,
     created_by, created_at, updated_at, deleted)
SELECT sv.id,
       sv.id,
       sv.submission_id,
       s.selection_id,
       s.type,
       sv.file_id,
       COALESCE(f.filename, CONCAT('version-', sv.version)),
       COALESCE(f.size_bytes, 0),
       f.sha256,
       NULL,
       0,
       sv.created_by,
       sv.created_at,
       sv.updated_at,
       sv.deleted
FROM submission_version sv
         JOIN submission s ON s.id = sv.submission_id
         LEFT JOIN file_object f ON f.id = sv.file_id
         LEFT JOIN submission_version_file x ON x.version_id = sv.id
WHERE x.id IS NULL;


-- 3. 相似度命中记录
--    只记「达到阈值」的命中，不存原始比对过程：全量两两比对的结果量太大，
--    而且老师真正关心的只有「这两份高度雷同」这一个事实。
CREATE TABLE IF NOT EXISTS submission_similarity (
    id                    BIGINT       NOT NULL COMMENT '主键',
    topic_id              BIGINT       NOT NULL COMMENT '题目 ID，同一题目下才可比',
    file_type             VARCHAR(16)  NOT NULL COMMENT 'SOURCE / REPORT',
    selection_id          BIGINT       NOT NULL COMMENT '命中方选题 ID',
    other_selection_id    BIGINT       NOT NULL COMMENT '被比对选题 ID',
    submission_id         BIGINT       NOT NULL COMMENT '命中方提交物 ID',
    other_submission_id   BIGINT       NOT NULL COMMENT '被比对提交物 ID',
    version_file_id       BIGINT       NOT NULL COMMENT '命中方文件 ID',
    other_version_file_id BIGINT       NOT NULL COMMENT '被比对文件 ID',
    original_name         VARCHAR(255) DEFAULT NULL COMMENT '命中方文件名',
    other_original_name   VARCHAR(255) DEFAULT NULL COMMENT '被比对文件名',
    score                 DECIMAL(4, 3) NOT NULL COMMENT '相似度 0.000~1.000',
    kind                  VARCHAR(24)  NOT NULL COMMENT 'IDENTICAL（内容相同）/ SAME_STRUCTURE（结构相同）',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一对文件只留一条，重复检测（比如重新评审）不会刷屏
    UNIQUE KEY uk_ss_pair (version_file_id, other_version_file_id),
    KEY idx_ss_selection (selection_id, file_type),
    KEY idx_ss_topic (topic_id, file_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '提交文件相似度命中记录';


-- 4. 回填校验（执行后人工核对，两条都应为 0）
--    4.1 有版本却没有文件记录（除 file_id 为空的异常数据）
--        SELECT COUNT(*) FROM submission_version sv
--         LEFT JOIN submission_version_file f ON f.version_id = sv.id
--         WHERE f.id IS NULL AND sv.file_id IS NOT NULL;
--    4.2 文件记录总数与「有 file_id 的版本数」不一致
--        SELECT (SELECT COUNT(*) FROM submission_version WHERE file_id IS NOT NULL) AS versions,
--               (SELECT COUNT(*) FROM submission_version_file) AS files;
