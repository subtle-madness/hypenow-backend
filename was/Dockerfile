# 빌드 컨텍스트 = was/ (jar는 호스트에서 ./gradlew :was:bootJar 로 먼저 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
