# 前端镜像：Node 构建静态文件 → Nginx 托管并反代后端
# 构建：docker build -t sss-web ./vue

FROM node:20-alpine AS build
WORKDIR /app
# 国内可加：--build-arg NPM_REGISTRY=https://registry.npmmirror.com
ARG NPM_REGISTRY=https://registry.npmjs.org
COPY package.json package-lock.json ./
RUN npm ci --registry="$NPM_REGISTRY"
COPY . .
# 容器里不需要 VITE_API_TARGET（同源 /api 由 nginx 反代），但不能让它读到 .env 里的 127.0.0.1
RUN rm -f .env .env.example && npm run build

FROM nginx:1.27-alpine
ENV TZ=Asia/Shanghai
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
