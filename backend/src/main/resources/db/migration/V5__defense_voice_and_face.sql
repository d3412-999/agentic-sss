-- 答辩语音转写 + 摄像头在场检测（只判「有没有人」，不做身份识别）
--
-- defense_session：记录最近一次在场检测结果、检测时间与累计未检测到人的次数；
--                  人脸图片不落库、不写磁盘，只保留结论。
-- defense_answer：记录本次作答使用的 ASR 模型，便于排障与结果追溯。
ALTER TABLE defense_session
    ADD COLUMN face_present TINYINT DEFAULT NULL COMMENT '最近一次在场检测：1 有人 / 0 无人',
    ADD COLUMN face_checked_at DATETIME(3) DEFAULT NULL COMMENT '最近一次在场检测时间',
    ADD COLUMN face_miss_count INT NOT NULL DEFAULT 0 COMMENT '累计未检测到人的次数';

ALTER TABLE defense_answer
    ADD COLUMN asr_model VARCHAR(64) DEFAULT NULL COMMENT '转写模型';
