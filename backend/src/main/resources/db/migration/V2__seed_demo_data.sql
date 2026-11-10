-- =============================================================================
-- V2 · 演示种子数据（仅用于本地开发 / 课程答辩演示）
--
-- ⚠️ 生产环境务必删除本文件，或通过 spring.flyway.locations + profile 隔离，
--    否则会往真实库里写入一批 123456 弱口令账号。
--
-- 账号与前端 vue/src/views/LoginView.vue 的 demoAccounts 完全一致：
--   学生  2023010101 / 张三     教师  T2019008 / 李文博     管理员 admin
--   统一初始密码 123456，哈希为 BCrypt(strength=10)，可被
--   BCryptPasswordEncoder.matches() 直接校验。
--
-- 时间窗用相对 NOW() 的偏移量，保证任何时间初始化数据库都处于「可选题」状态，
-- 不会出现「跑起来发现选题窗口已过期」的尴尬。
--
-- 不预置任何 selection：留出空名额，方便直接演示/压测选题并发控制。
-- =============================================================================

SET NAMES utf8mb4;

-- 1. 用户 ---------------------------------------------------------------------
-- 教师/管理员的 student_no 为 NULL；MySQL 唯一索引允许多个 NULL。
INSERT INTO sys_user
    (id, username, password_hash, name, role, student_no, class_name, email, status, must_change_password)
VALUES
    (9001, 'admin',      '$2a$10$CyIFMFUrC2NhvPfO7wd2mOmia5Ns5vT14oxL78JZKqPN2PmVU2Eaa',
     '系统管理员', 'ADMIN',   NULL,          NULL,               'admin@sss.local',    'ACTIVE', 0),
    (9002, 'T2019008',   '$2a$10$gfqDqmD2eS5yTsnrbgr5duHnSvf27ZEmg9Wpl5SCKT35XsHT4jGmm',
     '李文博',     'TEACHER', NULL,          NULL,               'liwenbo@sss.local',  'ACTIVE', 0),
    (9003, '2023010101', '$2a$10$U9WnVjBlau1byV5mK.Vb3uhEQuzZZoDiJC2G1a34bwrltRw5vm21.',
     '张三',       'STUDENT', '2023010101', '计科 2023-01 班', 'zhangsan@sss.local', 'ACTIVE', 0),
    (9004, '2023010102', '$2a$10$0hCHIcz1I5H5jOmJ0NKwveHx3bck6lRRiciRC.XSAk5g2NAsvCNYO',
     '李四',       'STUDENT', '2023010102', '计科 2023-01 班', 'lisi@sss.local',     'ACTIVE', 0),
    (9005, '2023010103', '$2a$10$c/7aYoLxqbCly61N0dLNwux6IWI47KMuBVnCb57hlvbzrg9.dlc5m',
     '王五',       'STUDENT', '2023010103', '计科 2023-01 班', 'wangwu@sss.local',   'ACTIVE', 0),
    (9006, 'T2019009',   '$2a$10$T0sSzDPL9xmR/rz3ZzlQDuQOlEdRvg3HV7mM002fhP3pJirXKhJwe',
     '周敏',       'TEACHER', NULL,          NULL,               'zhoumin@sss.local',  'ACTIVE', 0);

-- 2. 课程模板 -----------------------------------------------------------------
INSERT INTO course (id, code, name, credit, description, created_by)
VALUES (9101, 'CD-2026', '面向对象程序设计课程设计', 2.0,
        '以 Java / Spring Boot 完成一个完整的小型业务系统，并提交源码、报告与语音答辩。', 9002);

-- 3. 开课（已发布、选题窗口开放中） -------------------------------------------
INSERT INTO offering
    (id, course_id, teacher_id, semester, class_name, description, status,
     topic_select_limit, weight_defense, weight_code, weight_report,
     defense_mode, question_count, max_answer_seconds, allow_retry, enable_video, language,
     select_start, select_end, submit_deadline, defense_start, defense_end, published_at, version)
VALUES
    (9201, 9101, 9002, '2026-2027-1', '计科 2023-01 班',
     '演示用教学班：选题窗口常开，权重 40/35/25。', 'PUBLISHED',
     3, 40, 35, 25,
     'PER_STUDENT', 6, 180, 0, 0, 'zh-CN',
     DATE_SUB(NOW(3), INTERVAL 30 DAY),
     DATE_ADD(NOW(3), INTERVAL 365 DAY),
     DATE_ADD(NOW(3), INTERVAL 400 DAY),
     DATE_ADD(NOW(3), INTERVAL 410 DAY),
     DATE_ADD(NOW(3), INTERVAL 425 DAY),
     NOW(3), 0);

-- 4. 开课名单 -----------------------------------------------------------------
INSERT INTO offering_student (id, offering_id, student_id, status) VALUES
    (9301, 9201, 9003, 'ACTIVE'),
    (9302, 9201, 9004, 'ACTIVE'),
    (9303, 9201, 9005, 'ACTIVE');

-- 5. 已发布题目（selected_count 全部为 0，留出名额供选题并发测试） --------------
INSERT INTO topic
    (id, offering_id, title, description, requirements, difficulty,
     max_select_count, selected_count, tags, expected_tech, status, published_at, created_by, version)
VALUES
    (9401, 9201, '基于 Spring Boot 的校园二手交易平台',
     '实现一个支持发布商品、下单、留言与站内信的校园 C2C 平台。',
     '要求分层清晰、接口符合 REST 规范、使用 JWT 鉴权，并给出单元测试。',
     'MEDIUM', 2, 0, '["SpringBoot","MySQL","JWT"]', '["Java 17","Spring Boot 3","MyBatis-Plus"]',
     'PUBLISHED', NOW(3), 9002, 0),
    (9402, 9201, '轻量级 RPC 框架设计与实现',
     '从零实现一个支持服务注册、序列化与负载均衡的最小 RPC 框架。',
     '要求说明超时、重试与失败隔离的设计取舍，附压测数据。',
     'HARD', 1, 0, '["RPC","Netty","原理"]', '["Java","Netty","动态代理"]',
     'PUBLISHED', NOW(3), 9002, 0),
    (9403, 9201, '智能问答知识库助手',
     '构建可导入文档、检索增强生成并支持追问的问答助手。',
     '要求给出检索召回评测结果与失败降级方案。',
     'HARD', 2, 0, '["RAG","LLM","检索"]', '["Python","LangChain","向量库"]',
     'PUBLISHED', NOW(3), 9002, 0),
    (9404, 9201, '分布式文件存储小系统',
     '实现分片上传、秒传、断点续传与权限控制的文件服务。',
     '要求说明哈希去重与并发一致性方案，禁止路径穿越。',
     'EASY', 1, 0, '["存储","秒传","并发"]', '["Java","MinIO","Redis"]',
     'PUBLISHED', NOW(3), 9002, 0);
