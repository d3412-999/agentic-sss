-- =============================================================================
-- 管理员 - 教师账号管理：补充教师基础档案字段
--
-- 教师登录账号沿用 sys_user.username（工号），本次只增加管理端表单需要的最小信息：
--   手机号、所属院系 / 部门。
-- 不改动既有数据：两列都允许 NULL，历史教师账号仍可正常登录与管理。
-- =============================================================================

ALTER TABLE sys_user
    ADD COLUMN phone      VARCHAR(32) DEFAULT NULL COMMENT '手机号（教师档案）' AFTER email,
    ADD COLUMN department VARCHAR(64) DEFAULT NULL COMMENT '所属院系 / 部门（教师档案）' AFTER phone;
