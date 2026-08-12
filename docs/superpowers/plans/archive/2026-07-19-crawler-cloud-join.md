# 크롤러 클라우드 합류 구현 계획 (크롤러 한정 최소 범위)

> 상태: ✅ 구현/실행/반영됨 (2026-07-19 운영 반영 · DECISIONS 07-19 항목)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** crawler를 서버의 6번째 컨테이너로 추가한다 — **기존 서버 상태(컨테이너 5개·볼륨·기존 .env 항목)는 일절 건드리지 않는다.**

**Architecture:** 스펙 [2026-07-19-crawler-cloud-join-design.md](../../specs/2026-07-19-crawler-cloud-join-design.md)의 §1-3(crawler 상주 합류)만 실행. 블록 볼륨·버킷·백업 개통·메모리 제한은 전부 보류(§보류). 다른 세션의 Claude 뷰티 판정 전환(`crawler.beauty.judge=claude-api`)에 대비해 `ANTHROPIC_AUTH_TOKEN` 전달을 포함한다.

**Tech Stack:** docker compose, buildx multi-arch, GHCR, 기존 `deploy.sh <host> crawler`(스크립트 수정 없음)

## Global Constraints

- **서버·클라우드 상태 변경은 사용자 승인 후에만** — Task 2의 각 단계 개별 승인
- **기존 5개 컨테이너 무변경·무재시작** (특히 `postgres`(analysis)·`postgres-raw`) — compose의 기존 서비스 블록은 한 글자도 수정하지 않는다
- **raw 데이터 이전(사용자 직접 진행 중)이 완료된 뒤에만 crawler 기동** — Flyway 검증·잡 쓰기가 그 DB에 이어진다
- 최적화(mem_limit·shared_buffers) 없음 — 사용자 방침 07-19
- crawler 8080은 `127.0.0.1` 바인드만
- 커밋 메시지 한국어, prefix `feat(deploy):`/`docs:`

---

### Task 1: crawler 컨테이너 정의 (레포만 — 서버 무영향)

**Files:**
- Create: `crawler/Dockerfile`
- Modify: `deploy/compose.yaml` (crawler 서비스 블록 추가만)
- Modify: `deploy/.env.example` (compose가 참조하는 키 전체로 보강)

- [ ] **Step 1: crawler/Dockerfile 작성**

```dockerfile
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
```

- [ ] **Step 2: compose에 crawler 서비스 추가** — `analytics` 블록 아래 삽입, 기존 블록 무변경

```yaml
  # 수집 상주 서버(8080 어드민 /ui) — raw(postgres-raw)에 쓰기. 수동 운영: 어드민 UI로 잡 트리거.
  # 외부 미노출(루프백) — 접근은 SSH 터널(ssh -L 8080:localhost:8080). 스케줄은 기본 off(수동 운영).
  crawler:
    image: ghcr.io/subtle-madness/hypenow-crawler:latest
    restart: unless-stopped
    logging: *logging
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-raw:5432/crawler
      SPRING_DATASOURCE_USERNAME: ${RAW_DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${RAW_DB_PASSWORD}
      APIFY_TOKEN: ${APIFY_TOKEN}
      HIKER_API_KEY: ${HIKER_API_KEY:-}
      DATALIKERS_API_KEY: ${DATALIKERS_API_KEY:-}
      GEMINI_API_KEY: ${GEMINI_API_KEY:-}
      # Claude 뷰티 판정(claude-api 전환 대비) — OAuth 토큰만, ANTHROPIC_API_KEY는 서버에 두지 않는다
      ANTHROPIC_AUTH_TOKEN: ${ANTHROPIC_AUTH_TOKEN:-}
      APIFY_PROXY_URL: ${APIFY_PROXY_URL:-}
      DATAIMPULSE_RESIDENTIAL_PROXY_URL: ${DATAIMPULSE_RESIDENTIAL_PROXY_URL:-}
      DATAIMPULSE_MOBILE_PROXY_URL: ${DATAIMPULSE_MOBILE_PROXY_URL:-}
      JAVA_OPTS: "-Xmx1g"
    ports:
      - "127.0.0.1:8080:8080"   # 루프백 전용 — 어드민은 SSH 터널로만
    depends_on:
      postgres-raw:
        condition: service_healthy
```

- [ ] **Step 3: .env.example 보강** — compose가 참조하는 키 전체를 문서화 (기존 3줄 유지 + 추가)

```bash
# raw DB (postgres-raw)
RAW_DB_USER=crawler
RAW_DB_PASSWORD=change-me
# was 이메일 발송(선택)
RESEND_API_KEY=
# analytics LLM
GEMINI_API_KEY=
# crawler (수집) — Apify 필수, 나머지는 쓰는 소스만
APIFY_TOKEN=
HIKER_API_KEY=
DATALIKERS_API_KEY=
ANTHROPIC_AUTH_TOKEN=
APIFY_PROXY_URL=
DATAIMPULSE_RESIDENTIAL_PROXY_URL=
DATAIMPULSE_MOBILE_PROXY_URL=
```

- [ ] **Step 4: 검증** — `cd deploy && docker compose --env-file .env.example config --quiet && echo OK` → `OK`
- [ ] **Step 5: 검증** — `./gradlew :crawler:bootJar && docker build -t crawler-smoke crawler/ && docker run --rm crawler-smoke sh -c 'ls /app/app.jar'` → `/app/app.jar`
- [ ] **Step 6: Commit** — `feat(deploy): crawler 상주 컨테이너 정의 — Dockerfile·compose 서비스(루프백 8080)`

---

### Task 2: 서버 적용 (단계별 사용자 승인 — 기존 컨테이너 재시작 0)

전제: **사용자의 raw 데이터 이전 완료 신호** 이후에만.

- [ ] **Step 1 [승인]:** 서버 `~/deploy/.env`에 crawler 키 추가(값은 사용자 제공: APIFY_TOKEN 필수, ANTHROPIC_AUTH_TOKEN 등) — 기존 항목 무변경. 확인: `grep -c ANTHROPIC_API_KEY ~/deploy/.env` → 0 (있으면 API 과금 경로 — 제거 협의)
- [ ] **Step 2 [승인]:** 서버 `~/deploy/compose.yaml`에 crawler 블록 반영 (레포 버전과 서버 버전 diff 확인 후 추가분만)
- [ ] **Step 3 [승인]:** 맥에서 `deploy/scripts/deploy.sh ubuntu@<IP> crawler` — crawler 이미지만 빌드·push, 서버에서 crawler만 pull·기동. 검증: `docker compose ps` — crawler Up, **기존 5개 Created 시각 불변**
- [ ] **Step 4:** 터널(`ssh -L 8080:localhost:8080`) → `localhost:8080/ui` 접속 → 소량 잡(REELS, batch-limit 기본 10) 실행. 검증: postgres-raw에 신규 행, KST 달력일 재방문이 이전 이력에 이어짐(중복 방문 0), Hiker 과금 카운터 연속, api.hypenow.io 정상

---

### Task 3: 마무리 문서·PR

- [ ] **Step 1:** ARCHITECTURE §2 서술("크롤·분석 로컬" → 전 모듈 인스턴스 1대) + §7 결정 행 추가 (crawler 합류·서버 무변경 원칙·보류 항목 명시)
- [ ] **Step 2:** Commit — `docs: 크롤러 클라우드 합류 반영 — §2 구조·§7 결정 기록`
- [ ] **Step 3:** PR 정리 — 이 브랜치(설계+구현) → develop. `feat/skip-duplicate-run-items`(A안)·다른 세션의 Claude judge 브랜치와 머지 순서 조율 (crawler 이미지는 배포 시점 develop 상태로 빌드됨)

---

## 보류 항목 (이 계획에서 하지 않음 — 각각 별도 승인 시)

- 블록 볼륨(100GB) 부착·postgres-raw 데이터 이전 — raw는 당분간 현행 named volume (부트볼륨 여유 ~수개월)
- Object Storage 버킷 생성 (스크립트 포함)
- 오프사이트 백업(rclone) 개통 — 여전히 가장 급한 운영 리스크로 권고 유지
- deploy.sh 기본 서비스 목록에 crawler 추가 (지금은 명시 인자로 충분)
- 메모리 제한·shared_buffers — 최적화 금지 방침
- raw DB 이사 스크립트 — 사용자 직접 진행
- 크론 자동화·CD Actions·이미지 파이프라인 코드·FE rewrite·아카이빙 — 후속

## 완료 기준

- [ ] crawler 컨테이너 Up + 어드민 UI 터널 접속 + 소량 잡 정상
- [ ] 기존 5개 컨테이너 무변경 (Created 시각) — 특히 postgres(analysis)
- [ ] 서버에 ANTHROPIC_API_KEY 부재
- [ ] ARCHITECTURE 갱신·PR 정리
