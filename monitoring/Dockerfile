# 빌드 컨텍스트 = monitoring/ (jar는 호스트에서 ./gradlew :monitoring:bootJar 로 먼저 빌드하고 java -Djarmode=tools
# extract --layers --launcher 로 build/extracted/에 레이어 추출까지 마친 뒤 이 Dockerfile을 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
# non-root 실행 — was와 동일 규율. 모니터링은 디스크 쓰기 없음(적재는 사설 monitoring DB로만).
RUN useradd --system --no-create-home --shell /usr/sbin/nologin monitoring
USER monitoring
WORKDIR /app
# layered jar — 변경 빈도가 낮은 레이어부터 COPY해야 Docker 레이어 캐시가 dependencies에서 히트한다
# (배포당 이미지 전송량 35M→1.2M). snapshot-dependencies/는 항상 빈 디렉토리로 생성됨(정상).
COPY build/extracted/dependencies/ ./
COPY build/extracted/spring-boot-loader/ ./
COPY build/extracted/snapshot-dependencies/ ./
COPY build/extracted/application/ ./
ENV JAVA_OPTS=""
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
