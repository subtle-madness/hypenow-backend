# CLAUDE.md

## 세션 시작

- **[ARCHITECTURE.md](ARCHITECTURE.md)를 먼저 읽는다** — 시스템 구조·작업 트랙 상태·결정 기록의 기준(살아있는 문서).
- 구조나 태스크 상태가 바뀌는 작업을 했으면 ARCHITECTURE.md의 §5(작업 트랙 표)와 §7(결정 기록)을 같이 갱신한다.
- 문서 체계: `ARCHITECTURE.md`(항상 최신) / `docs/superpowers/specs/`(설계 기록 — 영구 보존·내용 불변) /
  `docs/superpowers/plans/`(구현 계획 — 실행 완료 시 `plans/archive/`로 이동).
  dated 문서는 첫머리 상태 헤더(`> 상태: 🟢 활성 · ✅ 구현/실행/반영됨 · 🗄 대체됨 · ⏸ 보류`)를 유지한다.

## 시스템 경계 (위반 금지)

- **crawler** → raw DB(`crawler`) 쓰기. 크롤링 담당 영역 — 분석 작업에서 raw 스키마를 바꾸지 않는다.
- **analytics** → raw 읽기, 분석 결과(analysis DB) 쓰기. LLM 분석도 이 층 소속.
- **was** → 분석 결과는 **읽기만**, 쓰기는 **서비스 데이터(`app` 스키마)에만**. raw DB 접근 금지.
  분석 결과와 서비스 데이터를 SQL 조인하지 않는다(조합은 was 코드에서).
- 모듈 간 Java 공유는 계약 모듈 `contract-analysis`(분석 결과 record·enum, 순수 JDK)만 —
  crawler는 계약 모듈과 무관. 미러는 타입 기반(뷰 SQL/Flyway DDL/공유 record — ARCHITECTURE §4-3).

## 빌드·검증

- 전체 테스트: `./gradlew test` (Java 21, Spring Boot 4.1, Gradle 멀티모듈: crawler/analytics/was)
- 분석 뷰 검증: SQL 하니스(더미 시드 + BEGIN/ROLLBACK 격리) 컨벤션 — 기존 run.sh는 07-12 초기화로 삭제, 태스크 A에서 재구축
- 실행: `./gradlew :was:bootRun`(8081) / `:crawler:bootRun`(8080, 어드민 `/ui`) / `:analytics:bootRun`(8082, 어드민 `/ui` — 잡 트리거·로그. one-shot 미러는 `--analytics.mirror-on-startup=true --spring.main.web-application-type=none`)
- DB: docker `crawler-postgres-1` (포트 5433, crawler/crawler, DB `crawler`·`analysis`) —
  컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다를 수 있음(예: `hypenow-crawler-postgres-1`).
  스크립트는 `PG_CONTAINER` 환경변수로 오버라이드.

## 컨벤션

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix는 `feat(모듈):`/`docs:` 식.
- **브랜치·PR**: 기본 브랜치는 `develop`. 작업은 `feat/*`(또는 `docs/*`, `chore/*`) 브랜치에서 하고
  **develop 대상 PR**로 합친다. develop·main 직접 push 금지. `main`은 릴리스용.
- **배포는 develop→main 머지(CD)로만 한다.** `deploy/scripts/deploy.sh` 수동 실행 금지 —
  긴급 롤백·CD 불능 시에만, 반드시 사용자 확인 후 `--force`로. (07-20: develop 체크아웃 상태의
  수동 배포가 CD의 main 배포를 덮어 was/analytics 버전 불일치 → 랭킹 API 전면 500.
  `:latest`는 마지막 push가 이긴다.)
- crawler는 DDD/헥사고날(`<context>/{domain, application/{service,port}, adapter/{in,out}}`), was·analytics는 평탄 패키지.
- DTO는 record(+정적 `from()`). was 조회는 JdbcClient. Jackson 3(`tools.jackson.*`).
- 분석 뷰는 `analytics/views/NN_*.sql` 번호순 적용, 테스트는 같은 번호 `analytics/test/NN_*.test.sql`.
- 런타임 설정은 `app_setting(key,value)` — 뷰가 직접 읽는 키도 있다(예: `analytics.recent-window`).
  **기준값은 crawler Flyway 마이그레이션으로 시드**(`ON CONFLICT DO NOTHING`, V16 참조 —
  07-20 수동 등록분 유실 사고 후 확립). 기준값 추가·변경은 후속 마이그레이션으로,
  수동 UPDATE는 런타임 토글(프로바이더 전환·임시 상향)만.
- 배열 저장은 `text[]` 대신 `jsonb` (기존 `RawComment.payload` 매핑 관용구 재사용).

## 함정

- `docker compose up`은 디렉토리명 기반 프로젝트로 **빈 컨테이너를 새로 만든다** — 실데이터는
  기존 컨테이너(`crawler-postgres-1`, 머신에 따라 이름 상이)에 있음. `docker start`로 기동할 것.
- `.env`는 JVM에 자동 로드되지 않는다 — `APIFY_TOKEN` 등은 셸 `export` 필요.
- Spring Boot 4 주의: `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지,
  Testcontainers 2.x는 `org.testcontainers.postgresql.PostgreSQLContainer`.
- **피드 게시물은 조회수(views)가 항상 NULL** — 조회수 집계·비율 계산에는 항상 NULL 규칙이 따라붙는다.
- **"분석 잔여 몇 건 / 왜 분석 안 됐나" 카운트는 `analytics/check/pending.sh` 정본으로.** 미분석
  콘텐츠의 timely 여부는 분석 완료 전엔 어디에도 영속화되지 않는다(`v_analysis_candidates.timely`가
  유일한 판정 지점) — `content_analyses` 단독 카운트나 즉석 쿼리는 "제때창(3일) 놓쳐서 분석 안
  함(영구 제외·상세 트랙)"과 "분석 대상인데 대기"를 뭉갠다(07-21·07-28 오답 전력).
