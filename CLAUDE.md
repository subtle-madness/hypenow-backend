# CLAUDE.md

## 세션 시작

- **[ARCHITECTURE.md](ARCHITECTURE.md)를 먼저 읽는다** — 시스템 구조·작업 트랙 상태·결정 기록의 기준(살아있는 문서).
- 구조나 태스크 상태가 바뀌는 작업을 했으면 ARCHITECTURE.md의 §5(작업 트랙 표)와 §7(결정 기록)을 같이 갱신한다.
- 문서 체계: `ARCHITECTURE.md`(항상 최신) / `docs/superpowers/specs/`(시점별 설계 기록) /
  `docs/superpowers/plans/`(태스크 착수 시 작성하는 상세 구현 계획).

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
- 실행: `./gradlew :was:bootRun`(8081) / `:crawler:bootRun`(8080, 어드민 `/ui`) / `:analytics:bootRun`(타입 미러 — 재구축 예정)
- DB: docker `crawler-postgres-1` (포트 5433, crawler/crawler, DB `crawler`·`analysis`)

## 컨벤션

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix는 `feat(모듈):`/`docs:` 식.
- crawler는 DDD/헥사고날(`<context>/{domain, application/{service,port}, adapter/{in,out}}`), was·analytics는 평탄 패키지.
- DTO는 record(+정적 `from()`). was 조회는 JdbcClient. Jackson 3(`tools.jackson.*`).
- 분석 뷰는 `analytics/views/NN_*.sql` 번호순 적용, 테스트는 같은 번호 `analytics/test/NN_*.test.sql`.
- 런타임 설정은 `app_setting(key,value)` — 뷰가 직접 읽는 키도 있다(예: `analytics.recent-window`).
- 배열 저장은 `text[]` 대신 `jsonb` (기존 `RawComment.payload` 매핑 관용구 재사용).

## 함정

- `docker compose up`은 디렉토리명 기반 프로젝트로 **빈 컨테이너를 새로 만든다** — 실데이터는
  `crawler-postgres-1`에 있음. `docker start crawler-postgres-1`로 기동할 것.
- `.env`는 JVM에 자동 로드되지 않는다 — `APIFY_TOKEN` 등은 셸 `export` 필요.
- Spring Boot 4 주의: `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지,
  Testcontainers 2.x는 `org.testcontainers.postgresql.PostgreSQLContainer`.
- **피드 게시물은 조회수(views)가 항상 NULL** — 조회수 집계·비율 계산에는 항상 NULL 규칙이 따라붙는다.
