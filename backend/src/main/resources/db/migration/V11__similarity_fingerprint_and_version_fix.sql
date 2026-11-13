-- ============================================================================
-- V11：相似度指纹增强 + 历史版本号回填
--
-- 两个独立的数据修正，都只做「补列 / 补值」，不删数据、不改语义：
--
--   1) submission_version_file.content_sig
--      存归一化内容的 MinHash 签名（128 维，逗号分隔）。
--      用于识别「改了变量名、注释、缩进，甚至重排了文件，但代码主体高度重合」的抄袭。
--      现有的 sha256（完全相同）与 structure_key（目录结构相同）是两个极端，
--      中间这档「内容大部分一致」此前是检测盲区。
--
--   2) submission.latest_version / latest_version_id 回填
--      历史上有 latest_version=0 但实际已有 version 1..4 的记录（早期只写 version 表、
--      没同步提交物的 latest_*）。读取层已加兜底，但把值修正过来能让统计与排序也正确。
--      仅更新「latest_version=0 或 latest_version_id 为空」的行，正常行不动。
--
-- 部署前请备份：
--   mysqldump -h127.0.0.1 -uroot -p sss > backup_before_v11_$(date +%Y%m%d_%H%M%S).sql
--
-- 回滚（人工执行，Flyway 不回滚）：
--   ALTER TABLE submission_version_file DROP COLUMN content_sig;
--   -- latest_version 的原值无法逐行还原（回填前没有留档），
--   -- 若确需还原，请从备份的 backup_before_v11_*.sql 里按 id 取回。
--   注意：删列会丢失已计算出的指纹，但不会影响任何业务数据；
--   指纹是纯派生数据，重新提交或下次检测时会重算。
-- ============================================================================


-- 1. 内容指纹列
--    128 维 MinHash，每维 0~4294967295，十进制最长 10 位 + 1 个逗号 → 约 1408 字符。
--    留 2048 足够，且不会撑爆行内存储（InnoDB DYNAMIC 行格式对长 VARCHAR 会溢出到页外）。
ALTER TABLE submission_version_file
    ADD COLUMN content_sig VARCHAR(2048) DEFAULT NULL
        COMMENT '归一化内容的 MinHash 签名（128 维），用于内容级相似度比对' AFTER structure_key;

-- 只为源码建索引：报告是长文本，指纹命中更可能是「同一份模板改标题」，
-- 误报率高，暂不参与内容级比对（如需开启，在配置里放开 report 的 similarity）。
CREATE INDEX idx_svf_content_sig ON submission_version_file (content_sig(64));


-- 2. 历史版本号回填
--    只动异常行：latest_version = 0 或 latest_version_id 为空。
--    SET 子查询用「该提交物下最大的 version」与「对应那条 version 的 id」，两者同源，保证配对。
UPDATE submission s
SET s.latest_version = (SELECT MAX(sv.version) FROM submission_version sv WHERE sv.submission_id = s.id),
    s.latest_version_id = (SELECT sv.id
                           FROM submission_version sv
                           WHERE sv.submission_id = s.id
                           ORDER BY sv.version DESC, sv.id DESC
                           LIMIT 1)
WHERE (s.latest_version = 0 OR s.latest_version_id IS NULL)
  AND EXISTS (SELECT 1 FROM submission_version sv WHERE sv.submission_id = s.id);


-- 3. 回填校验（执行后人工核对，三条都应为 0）
--    3.1 仍存在 version=0 但已有版本的提交物
--        SELECT COUNT(*) FROM submission s
--         WHERE s.latest_version = 0
--           AND EXISTS (SELECT 1 FROM submission_version sv WHERE sv.submission_id = s.id);
--    3.2 latest_version 与 latest_version_id 指向的版本号不一致
--        SELECT COUNT(*) FROM submission s
--          JOIN submission_version sv ON sv.id = s.latest_version_id
--         WHERE sv.version <> s.latest_version;
--    3.3 latest_version_id 不属于该提交物
--        SELECT COUNT(*) FROM submission s
--          JOIN submission_version sv ON sv.id = s.latest_version_id
--         WHERE sv.submission_id <> s.id;
