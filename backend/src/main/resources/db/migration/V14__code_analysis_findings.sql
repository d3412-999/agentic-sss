-- 混合代码评审（确定性规则 + LLM）的落库字段
-- 规则/工具/LLM 合并后的行级 findings、确定性扫描汇总与结论
ALTER TABLE code_analysis
    ADD COLUMN findings JSON NULL COMMENT '混合评审行级问题（规则/工具/LLM）' AFTER tool_errors,
    ADD COLUMN rule_summary JSON NULL COMMENT '确定性扫描汇总（计数/score_cap/verdict）' AFTER findings,
    ADD COLUMN verdict VARCHAR(16) NULL COMMENT 'PASS/WARN/BLOCK' AFTER rule_summary;
