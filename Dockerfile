# 构建：mvn -DskipTests package
# 运行：docker run -p 8080:8080 -e AI_API_KEY=sk-xxx prompt-coach
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="PromptCoach" \
      org.opencontainers.image.description="AI 提效教练：把模糊需求变成能出结果的提示词"

WORKDIR /app

COPY target/prompt-coach-*.jar app.jar

EXPOSE 8080

# 云平台会注入 PORT，这里让两种环境都能跑
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]