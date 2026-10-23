# ===========================================================================
# 多阶段构建：builder 装依赖 + 打包，runtime 只带 JRE 和那个 jar。
#
# 为什么不写成单阶段：单阶段会把 ~/.m2 整个仓库（几百 MB）留在镜像里，
# 而运行时一行都不需要它。多阶段让最终镜像只包含 JRE + jar。
# ===========================================================================

# ---- 前端构建阶段 ---------------------------------------------------------
# 为什么要单独一个阶段，而不是在 maven 阶段里装 node：
#   Spring Boot 只会把 src/main/resources/static/ 打进 jar，而这个目录是
#   npm run build 的产物、不进 git（见 .gitignore）。如果镜像构建跳过它，
#   打出来的 jar 里就没有页面 —— 容器能起来、/actuator/health 也绿，
#   但浏览器打开是 404。这类「构建通过、功能缺失」的缺口最容易被漏掉。
#
# 为什么 WORKDIR 是 /build/web 而不是 /web：
#   vite.config.js 里 outDir 写的是 '../src/main/resources/static'，
#   是相对 web/ 的。把工作目录摆成 /build/web，这个相对路径就正好落在
#   /build/src/main/resources/static —— 与下面 maven 阶段的目录结构一致，
#   于是 COPY --from=frontend 拿到的就是它该在的位置，
#   不需要为容器改一份 vite 配置（改了就会出现「本地能 build、容器不能」）。
FROM node:20-alpine AS frontend
WORKDIR /build/web

# 先只拷锁文件再 npm ci：与下面 maven 阶段先拷 pom.xml 是同一个理由 ——
# 改业务代码不会让依赖层失效。
# 用 npm ci 而不是 npm install：ci 严格按 package-lock.json 装，
# 装出来的版本与本地、与 CI 完全一致；install 可能悄悄升级次要版本，
# 而「镜像里的前端和本地跑的不是同一份代码」这种问题极难排查。
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY web ./
RUN npm run build

# ---- 构建阶段 -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom.xml 再预下载依赖。
#
# 这一步是整个 Dockerfile 里唯一为「构建速度」做的东西，但它值得：
# Docker 逐层缓存，源码改动不会让依赖层失效，于是改一行 Java 后重建
# 只需重新编译，而不是重新下载整个 Spring 依赖树。
# 顺序反过来的话（先 COPY . . 再下依赖），每次改代码都要重下几百 MB。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# 把前端阶段构建出来的页面放进 static/，再打包。
# 顺序不能反：mvn package 会把当时 static/ 里的东西打进 jar，
# 所以 COPY --from=frontend 必须在它前面。
# 注意 .dockerignore 里排除了 src/main/resources/static/，
# 所以这里拿到的**只有**镜像内构建出来的那一份，
# 不会混进本地遗留的旧产物。
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static
# 跳过测试：CI 里 unit job 已经单独跑过一遍了，
# 在构建阶段再跑一次只是让镜像变慢，不会多发现任何问题。
RUN mvn -B -q clean package -DskipTests

# ---- 运行阶段 -------------------------------------------------------------
FROM eclipse-temurin:17-jre

# 显式装 curl。
#
# 不依赖「基础镜像里应该自带 curl」：HEALTHCHECK 用的是 curl，
# 而基础镜像有没有它取决于上游的版本与变体，是这里唯一无法在离线环境
# 验证的假设。显式装一次只多几 MB，但让 HEALTHCHECK 的正确性不依赖
# 上游镜像的细节 —— 否则哪天上游把 curl 从基础层去掉，
# 表现是容器一直 unhealthy，而日志里什么都没有。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 显式建一个非 root 用户。默认的 root 容器一旦被拿下就是宿主机的 root，
# 而应用本身不需要任何特权。
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# 用确定的文件名而不是 *.jar：spring-boot-maven-plugin 重打包后 target/ 里
# 历史上会出现 original-*.jar（不含依赖的原始 jar），通配符有匹配到它的风险，
# 而那个 jar 直接 java -jar 是起不来的。写死名字，错了在构建时就会暴露。
COPY --from=builder /build/target/online-exam-1.0.0.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8081

# 健康检查用应用自带的 actuator。
# 注意这里刻意**不**在镜像里塞 JWT 密钥 —— 应用对密钥 fail-closed，
# 没设就拒绝启动。密钥必须在运行时注入（docker run -e / compose / K8s Secret），
# 绝不能烤进镜像层：镜像分层是公开的，密钥进去就等于公开。
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

# 关于 curl：它由上面那条 apt-get 显式安装，不是"指望基础镜像自带"。
# 之前这里写着一句"eclipse-temurin 基于 Ubuntu，自带 curl" ——
# 那是没有验证过的断言（离线环境拉不到镜像），而它恰好是
# HEALTHCHECK 能否工作的唯一前提。改成显式安装，这个假设就不存在了。
