# 빌드 컨텍스트 = was/ (jar는 호스트에서 ./gradlew :was:bootJar 로 먼저 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
# non-root 실행 — 컨테이너 탈출 시 피해 최소화 (앱은 디스크 쓰기 없음, 8081은 비특권 포트)
RUN useradd --system --no-create-home --shell /usr/sbin/nologin was
USER was
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
