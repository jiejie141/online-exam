# ===========================================================================
# 多阶段构建：builder 装依赖 + 打包，runtime 只带 JRE 和那个 jar。
#
# 为什么不写成单阶段：单阶段会把 ~/.m2 整个仓库（几百 MB）留在镜像里，
# 而运行时一行都不需要它。多阶段让最终镜像只包含 JRE + jar。
# ===========================================================================

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
# 跳过测试：CI 里 unit job 已经单独跑过一遍了，
# 在构建阶段再跑一次只是让镜像变慢，不会多发现任何问题。
RUN mvn -B -q clean package -DskipTests

# ---- 运行阶段 -------------------------------------------------------------
FROM eclipse-temurin:17-jre

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

# 备注：运行阶段没装 curl 的话 HEALTHCHECK 会一直失败。
# eclipse-temurin:17-jre 基于 Ubuntu，自带 curl，所以上面可以直接用。
