# 빌드 컨텍스트 = analytics/ (jar는 호스트에서 ./gradlew :analytics:bootJar 로 먼저 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
# non-root 실행 — was와 동일 규율. 백필 러너의 backfill-dir 쓰기용 워킹 디렉토리만 소유.
RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin analytics
USER analytics
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8082
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
