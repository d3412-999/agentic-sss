# 服务器部署记录（agentic-sss）

> 目标服务器：`101.200.131.222`（CentOS 8 用户态 / kernel 3.10，Docker 26.0.2 + Compose v2.26.1）
> 部署日期：2026-11-17

## 一、访问地址

| 入口 | 地址 |
|---|---|
| **前端（推荐）** | <http://sss.101.200.131.222.nip.io/> |
| 前端（备用域名） | <http://sss.101.200.131.222.sslip.io/> |
| 后端健康检查（经同域 Nginx） | <http://sss.101.200.131.222.nip.io/api/v1/health> |

演示账号（V2 种子数据，密码统一 `123456`）：`admin`（管理员）、`T2019008`（教师）、`2023010101`（学生）。

**为什么不用裸 IP / 18080 端口**：这台服务器 80 端口已被其它站点（django-vue-lyadmin）占用，
安全组只对外开放了 80/443/8000/8081/8088 等端口，`18080/18081/18000` 从公网不可达。
因此本项目前端只绑定 `127.0.0.1:18080`，由宿主机 Nginx 用 `nip.io` 泛解析域名 + Host 精确匹配
转发；裸 IP 仍访问原站点，互不影响。

## 二、部署拓扑

```
公网 80 (宿主机 nginx, server_name sss.101.200.131.222.nip.io)
          │  proxy_pass 127.0.0.1:18080
          ▼
   sss-full-web (nginx)  ──/api──▶  sss-full-backend :8081 ──▶ sss-full-mysql / redis
                                          │
                                          └──▶ sss-full-ai :8000（DeepSeek + sherpa-onnx + OpenCV）
```

- 容器：`sss-full-{mysql,redis,ai,backend,web}`，均为 `restart: unless-stopped`，Docker 已 `systemctl enable`。
- 数据卷：`sss-full_mysql-data`、`sss-full_redis-data`、`sss-full_object-data`、`sss-full_ai-data`。
- 宿主机端口：仅 `127.0.0.1:18080/18081/18000`，公网不可直接访问。

## 三、从零部署步骤

```bash
# 1. 源码（gh-proxy 中转，服务器直连 github 可能失败）
git clone --depth 1 https://gh-proxy.com/https://github.com/d3412-999/agentic-sss.git /opt/agentic-sss
cd /opt/agentic-sss

# 2. 环境变量：从本地 .env 复制一份，至少包含
#    DB_*、REDIS_PASSWORD、JWT_SECRET、LLM_API_KEY、AI_INTERNAL_TOKEN
#    （数据库/Redis 只在内网使用；JWT/AI token 建议重新生成）
chmod 600 .env

# 3. 基础镜像（Docker Hub 直连被墙，用 daocloud 镜像并重新打 tag）
for img in mysql:8.0 redis:7.2 eclipse-temurin:17-jre python:3.12-slim node:20-alpine nginx:1.27-alpine; do
  docker pull docker.m.daocloud.io/library/$img && docker tag docker.m.daocloud.io/library/$img $img
done

# 4. 后端 jar：本机构建后上传，避免服务器重新下载 Maven 依赖
#    backend/build/libs/sss-backend-0.1.0.jar -> /opt/agentic-sss/backend/build/libs/

# 5. ASR 模型（约 232MB）
mkdir -p langchain_service/models/sherpa-onnx-paraformer-zh
cd langchain_service/models/sherpa-onnx-paraformer-zh
curl -L -O https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/model.int8.onnx
curl -L -O https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/tokens.txt
cd /opt/agentic-sss

# 6. 构建并启动（服务器覆盖文件只把端口绑到 127.0.0.1）
docker compose -f docker-compose.full.yml -f deploy/docker-compose.server.yml build
docker compose -f docker-compose.full.yml -f deploy/docker-compose.server.yml up -d

# 7. 宿主机 Nginx 反代（80 端口按 Host 匹配）
cp deploy/nginx-agentic-sss.conf /usr/local/nginx/conf/conf.d/agentic-sss.conf
# 确认 nginx.conf 的 http {} 内有 include /usr/local/nginx/conf/conf.d/*.conf;
nginx -t && nginx -s reload
```

## 四、更新部署

```bash
cd /opt/agentic-sss
git pull --ff-only                      # 或继续用 gh-proxy 远端
#   后端 jar 更新后上传到 backend/build/libs/，再重建：
docker compose -f docker-compose.full.yml -f deploy/docker-compose.server.yml build
docker compose -f docker-compose.full.yml -f deploy/docker-compose.server.yml up -d
```

## 五、验收记录（2026-11-17）

- `GET /api/v1/health` → `UP`；Flyway V1–V14 自动迁移成功；
- 前端页面 → 200，标题「课程设计智能答辩与评分系统」；
- 学生 / 教师 / 管理员三类演示账号登录成功；
- 真实 DeepSeek 代码评审：`deepseek-chat`、`verdict=BLOCK`、7 条 findings、规则分上限 45；
- sherpa-onnx 中文 ASR：10.03s 中文 WAV 转写正确；
- OpenCV 人脸在场检测：空白图 `present=false`。

## 六、注意事项

1. **磁盘**：服务器根分区约 99G、已用 ~90G（剩余 ~5G），镜像/构建缓存增长后需清理
   （`docker image prune`、`docker builder prune`，不影响运行中的容器）。
2. **HTTPS**：当前只提供 HTTP 入口；正式使用建议绑定域名并配置证书，避免登录口令明文传输。
3. **安全**：服务器 root 口令已在对话中出现，部署后建议立即修改并改用 SSH key；
   `.env` 里的 `LLM_API_KEY` 是真实密钥，权限 600，且未提交仓库。
4. 服务器原有站点（80 端口按 IP/其它 Host）保持不动；本项目只占新增 Host 与 127.0.0.1 端口。
