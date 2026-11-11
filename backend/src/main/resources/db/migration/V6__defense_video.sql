-- 答辩整场录像（教师回放用）
--
-- 录像文件本体走现有 file_object + 本地对象存储（purpose=DEFENSE_VIDEO），
-- 这里只保存引用与元数据；逐题音频仍存 defense_answer.audio_file_id（ASR 用）。
ALTER TABLE defense_session
    ADD COLUMN video_file_id BIGINT DEFAULT NULL COMMENT '答辩录像文件 ID（file_object.id）',
    ADD COLUMN video_duration_ms BIGINT DEFAULT NULL COMMENT '录像时长（毫秒）',
    ADD COLUMN video_size_bytes BIGINT DEFAULT NULL COMMENT '录像文件大小（字节）',
    ADD COLUMN video_uploaded_at DATETIME(3) DEFAULT NULL COMMENT '录像上传时间';
