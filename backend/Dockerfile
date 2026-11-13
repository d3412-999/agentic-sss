# 后端镜像（快路径）：直接复用宿主机构建好的 jar，镜像里不再跑 Gradle
#
# 为什么不在这里跑 Gradle：
#   容器内 ./gradlew 会去 services.gradle.org 下载 Gradle 发行包（~130MB），
#   国内网络经常卡住/EOF 导致构建失败；宿主机 Gradle 缓存已有依赖，15 秒就能打包。
#
# 构建前必须先执行（或直接用 scripts/docker-up.ps1）：
#   cd backend
#   .\gradlew.bat bootJar -x test      # 产物：build/libs/sss-backend-<version>.jar
#
# 想要"容器内全自动构建"的版本见 Dockerfile.gradle（CI / 海外网络用）。

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx1024m"

# .dockerignore 已排除 *-plain.jar，这里只会匹配到可执行 jar
COPY build/libs/*.jar /app/app.jar

RUN mkdir -p /data/objects
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
