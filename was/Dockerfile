# 빌드 컨텍스트 = was/ (jar는 호스트에서 ./gradlew :was:bootJar 로 먼저 빌드하고 java -Djarmode=tools
# extract --layers --launcher 로 build/extracted/에 레이어 추출까지 마친 뒤 이 Dockerfile을 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
# non-root 실행 — 컨테이너 탈출 시 피해 최소화 (앱은 디스크 쓰기 없음, 8081은 비특권 포트)
RUN useradd --system --no-create-home --shell /usr/sbin/nologin was
USER was
WORKDIR /app
# layered jar — 변경 빈도가 낮은 레이어부터 COPY해야 Docker 레이어 캐시가 dependencies에서 히트한다
# (실측: dependencies 47M·loader 604K는 불변, 코드만 바뀐 재배포의 전송은 application 1.5M뿐).
# snapshot-dependencies/는 항상 빈 디렉토리로 생성됨(정상).
COPY build/extracted/dependencies/ ./
COPY build/extracted/spring-boot-loader/ ./
COPY build/extracted/snapshot-dependencies/ ./
COPY build/extracted/application/ ./
ENV JAVA_OPTS=""
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
