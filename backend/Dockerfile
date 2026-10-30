# =====================================================================
# 后端镜像 —— 多阶段构建（Spring Boot 3.3 + Java 17）
#
#   阶段 1  maven:3.9-eclipse-temurin-17     编译打包（跳过测试）
#   阶段 2  eclipse-temurin:17-jre-jammy     只带 JRE + 一个可执行 fat jar
#
# 构建（在 werewolf-game/backend 目录下执行）：
#   docker build -t werewolf-backend:1.0.0 .
# 或直接用 deploy/docker-compose.yml 一键构建。
#
# 运行期 profile：默认 mysql（连接 MySQL 8，连接信息全部走环境变量，
# 见 src/main/resources/application-mysql.yml）。
# =====================================================================

# ----------------------------------------------------------- 构建阶段 --
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先拷 pom 再拷源码：pom 不变时可以复用依赖下载层，改代码不会重新拉一遍依赖。
# 说明：这里不跑 mvn dependency:go-offline，避免个别镜像源不可达时整体构建失败。
COPY pom.xml ./
COPY src ./src

# 测试在本地 / CI 用 `mvn test` 跑（见 backend/src/test/java），镜像构建阶段跳过，
# 保证部署产物可重复、构建时间可控。
RUN mvn -B -DskipTests package

# ----------------------------------------------------------- 运行阶段 --
FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.title="werewolf-server" \
      org.opencontainers.image.description="基于 AI 增强的多人在线狼人杀系统 - 后端" \
      org.opencontainers.image.version="1.0.0"

WORKDIR /app

# 时区对齐 application.yml 的 jackson.time-zone: Asia/Shanghai
# SPRING_PROFILES_ACTIVE 默认走 mysql profile（h2 仅用于本地零依赖演示与集成测试）
# SERVER_PORT 对应 application.yml 的 server.port
ENV TZ=Asia/Shanghai
ENV SERVER_PORT=8080
ENV SPRING_PROFILES_ACTIVE=mysql

# pom.xml 里 <finalName>werewolf-server</finalName>，因此产物固定是这个文件名
COPY --from=build /build/target/werewolf-server.jar /app/werewolf-server.jar

EXPOSE 8080

# 说明：这里刻意不使用 HEALTHCHECK —— temurin 基础镜像不保证带 curl/wget。
# 需要健康检查时在编排层做，例如 docker-compose 里：
#   healthcheck:
#     test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:8080/api/health || exit 1"]
# 手工验证：
#   curl http://localhost:8080/api/health

# 启动命令
#   - 默认使用镜像里的 SPRING_PROFILES_ACTIVE=mysql；
#   - 固定堆内存（方案第十四节：2 vCPU / 4 GB 服务器，后端给 512 MB 足够）；
#   - 需要覆盖时用 JAVA_TOOL_OPTIONS 环境变量注入即可。
ENTRYPOINT ["java","-Xms256m","-Xmx512m","-Dfile.encoding=UTF-8","-jar","/app/werewolf-server.jar"]
