-- =============================================================================
-- 路线 B（本机独立安装的 MySQL 8）专用：建库 + 建应用账号
--
-- 用法：mysql -u root -p < scripts/init-local-db.sql
--       然后输入你本机 MySQL 的 root 密码。
--
-- 只做库和用户，不建表：22 张表与演示数据由后端启动时 Flyway 自动执行
-- V1__init_schema.sql / V2__seed_demo_data.sql 完成。
--
-- 这里的口令必须与以下三处保持一致：
--   .env.example                                      (DB_NAME / DB_USER / DB_PASSWORD)
--   docker-compose.yml                                (同名环境变量的默认值)
--   spring boot_service/src/main/resources/application.yaml (datasource 默认值)
-- =============================================================================

CREATE DATABASE IF NOT EXISTS sss
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- MySQL 8 的 GRANT 不再隐式建用户，必须显式 CREATE USER。
-- 同时建 localhost 与 127.0.0.1 两个 host，避免 JDBC 走 TCP 时被判成别的账号。
CREATE USER IF NOT EXISTS 'sss'@'localhost'  IDENTIFIED BY 'sss123456';
CREATE USER IF NOT EXISTS 'sss'@'127.0.0.1' IDENTIFIED BY 'sss123456';

-- IF NOT EXISTS 不会更新已有账号的口令。若之前用别的密码建过 sss 用户，
-- 这里强制重置，避免 Flyway 一直报 Access denied for user 'sss'。
ALTER USER 'sss'@'localhost'  IDENTIFIED BY 'sss123456';
ALTER USER 'sss'@'127.0.0.1' IDENTIFIED BY 'sss123456';

GRANT ALL PRIVILEGES ON sss.* TO 'sss'@'localhost';
GRANT ALL PRIVILEGES ON sss.* TO 'sss'@'127.0.0.1';
FLUSH PRIVILEGES;

-- 验证（应看到 sss 库）：SHOW DATABASES LIKE 'sss';
