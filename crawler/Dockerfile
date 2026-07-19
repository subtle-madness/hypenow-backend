# 빌드 컨텍스트 = crawler/ (jar는 호스트에서 ./gradlew :crawler:bootJar 로 먼저 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
# non-root 실행 — was와 동일 규율. 크롤러는 디스크 쓰기 없음(적재는 DB로만).
RUN useradd --system --no-create-home --shell /usr/sbin/nologin crawler
USER crawler
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
