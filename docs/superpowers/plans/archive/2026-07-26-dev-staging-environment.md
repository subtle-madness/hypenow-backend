# dev 스테이징 환경(태스크 K) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료 (2026-07-28)
> 스펙: [specs/2026-07-26-dev-staging-environment-design.md](../../specs/2026-07-26-dev-staging-environment-design.md)

**Goal:** develop 푸시마다 운영 인스턴스 위에 dev 스택(dev-was·dev-analytics·dev-postgres)이 자동 배포되고, 운영 postgres-raw를 읽기 전용 공유하되 뷰는 `analytics_dev` 스키마에 격리 설치되는 스테이징을 만든다.

**Architecture:** Java는 SQL의 `analytics.` 스키마 접두어를 제거하고 raw DataSource의 `search_path`를 프로퍼티(`analytics.raw-schema`, 기본 `analytics`)로 주입한다 — 운영 동작 불변, dev만 env로 `analytics_dev`. 뷰 SQL은 배포 시 치환 스크립트(따옴표 내부 제외)로 `analytics_dev`에 설치하고, dev DB 계정은 raw 읽기 전용 + `analytics_dev`만 소유(fail-closed). dev CD는 CI 성공 후 workflow_run으로 발화한다.

**Tech Stack:** Spring Boot 4.1 / Java 21 / HikariCP `connection-init-sql` / Testcontainers 2.x (`org.testcontainers.postgresql.PostgreSQLContainer`) / docker compose profiles / Caddy / GitHub Actions

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix: `feat(analytics):` / `feat(deploy):` / `ci:` / `docs:`
- 작업 브랜치에서 진행, develop 대상 PR로 합침. develop·main 직접 push 금지
- **서버 상태 변경(.env 추가·DNS 레코드)은 코드·CI로 하지 않는다** — deploy/README.md 개통 체크리스트로 문서화하고 실행은 사용자 확인 후
- 고정 이름(모든 태스크 공통): DB role `analytics_dev` · 스키마 `analytics_dev` · 프로퍼티 `analytics.raw-schema`(기본값 `analytics`, env `ANALYTICS_RAW_SCHEMA`) · dev 이미지 태그 `:develop` · compose 프로파일 `dev` · 도메인 `dev-api.hypenow.io`
- compose의 dev 전용 env 변수는 전부 `${VAR:-}` 기본값을 둔다 — 운영 CD의 `docker compose config` 검증("is not set" 시 실패)을 깨지 않기 위함
- Testcontainers 이미지는 기존 컨벤션대로 `postgres:16-alpine`
- 전체 게이트: `./gradlew :analytics:test` GREEN (다른 모듈은 무접촉이라 `test` 전체는 태스크 2에서 1회만)

---

### Task 1: raw DataSource search_path 구성 + 회귀 테스트

**Files:**
- Modify: `analytics/src/main/resources/application.yml` (app.datasource.raw 블록)
- Create: `analytics/src/test/java/com/celfit/analytics/config/RawSchemaSearchPathTest.java`

**Interfaces:**
- Produces: 프로퍼티 `analytics.raw-schema`(기본 `analytics`) → raw 커넥션의 `search_path = <schema>, public`. Task 2의 무접두어 SQL과 Task 4의 `ANALYTICS_RAW_SCHEMA: analytics_dev` env가 이 위에 선다.
- 주의: 이 태스크 완료 시점의 프로덕션 SQL은 아직 `analytics.` 정규화 상태 — 정규화된 이름은 search_path를 무시하므로 기존 동작과 충돌하지 않는다(태스크 독립성).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.analytics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * raw DataSource search_path 회귀 가드 (태스크 K) — 뷰 스키마 선택은 이 한 줄(connection-init-sql)에
 * 걸려 있다. 운영은 기본값 analytics, dev 스테이징은 analytics.raw-schema=analytics_dev 오버라이드.
 * application.yml을 실제 로드(ConfigDataApplicationContextInitializer)해 yml의 init-sql 배선까지 잠근다.
 */
@Testcontainers
class RawSchemaSearchPathTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(DataSourceConfig.class)
				.withPropertyValues(
						"app.datasource.raw.jdbc-url=" + pg.getJdbcUrl(),
						"app.datasource.raw.username=" + pg.getUsername(),
						"app.datasource.raw.password=" + pg.getPassword(),
						"app.datasource.analysis.jdbc-url=" + pg.getJdbcUrl(),
						"app.datasource.analysis.username=" + pg.getUsername(),
						"app.datasource.analysis.password=" + pg.getPassword());
	}

	@Test
	void 기본값은_analytics_스키마를_먼저_본다() {
		runner().run(ctx -> {
			JdbcTemplate raw = ctx.getBean("rawJdbcTemplate", JdbcTemplate.class);
			assertEquals("analytics, public",
					raw.queryForObject("SELECT current_setting('search_path')", String.class));
		});
	}

	@Test
	void raw_schema_프로퍼티가_dev_스키마로_오버라이드한다() {
		runner().withPropertyValues("analytics.raw-schema=analytics_dev").run(ctx -> {
			JdbcTemplate raw = ctx.getBean("rawJdbcTemplate", JdbcTemplate.class);
			assertEquals("analytics_dev, public",
					raw.queryForObject("SELECT current_setting('search_path')", String.class));
		});
	}
}
```

⚠️ Spring Boot 4 주의(CLAUDE.md 함정 항목과 동류): `ConfigDataApplicationContextInitializer`의 패키지가 3.x(`org.springframework.boot.test.context`)에서 이동했을 수 있다 — 컴파일 에러가 나면 IDE 자동 완성으로 실제 위치를 찾아 import만 고친다(테스트 본문 불변).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.config.RawSchemaSearchPathTest"`
Expected: FAIL — `current_setting('search_path')`가 `"$user", public`(PG 기본값)이라 assertEquals 불일치

- [ ] **Step 3: application.yml에 connection-init-sql 추가**

`analytics/src/main/resources/application.yml`의 `app.datasource.raw` 블록에 한 줄 추가:

```yaml
app:
  datasource:
    raw:
      jdbc-url: jdbc:postgresql://localhost:5433/crawler
      username: crawler
      password: crawler
      driver-class-name: org.postgresql.Driver
      # 뷰·캐시 스키마 선택(태스크 K) — 운영 기본 analytics, dev 스테이징은 ANALYTICS_RAW_SCHEMA=analytics_dev.
      # SQL은 뷰 이름을 무접두어로 쓰고(태스크 2) 이 search_path가 스키마를 결정한다.
      connection-init-sql: SET search_path TO ${analytics.raw-schema:analytics}, public
```

(analysis 블록은 무변경 — search_path 개념은 raw 전용)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.config.RawSchemaSearchPathTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/resources/application.yml analytics/src/test/java/com/celfit/analytics/config/RawSchemaSearchPathTest.java
git commit -m "feat(analytics): raw DataSource search_path를 analytics.raw-schema 프로퍼티로 구성 (태스크 K)"
```

---

### Task 2: SQL의 `analytics.` 스키마 접두어 제거 (프로덕션 + 테스트)

**Files:**
- Modify (프로덕션 — `analytics.` 접두어만 삭제, 그 외 무변경):
  - `analytics/src/main/java/com/celfit/analytics/analyze/BaselineLoader.java` (2곳)
  - `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (2곳)
  - `analytics/src/main/java/com/celfit/analytics/analyze/ClaudeBurstRunner.java` (3곳)
  - `analytics/src/main/java/com/celfit/analytics/analyze/GeminiBackfillRunner.java` (3곳)
  - `analytics/src/main/java/com/celfit/analytics/classify/CommentClassificationJob.java` (2곳)
  - `analytics/src/main/java/com/celfit/analytics/archive/ImageArchiveJob.java` (2곳)
  - `analytics/src/main/java/com/celfit/analytics/admin/PipelineStatsService.java` (4곳 — ⚠️ 아래 가드)
  - `analytics/src/main/java/com/celfit/analytics/spike/VlmSpikeRunner.java` (2곳)
  - `analytics/src/main/java/com/celfit/analytics/coverage/CoverageRepository.java` (2곳)
  - `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java` (MirrorSpec 7곳)
- Modify (테스트): `analytics/src/test/java/com/celfit/analytics/testsupport/TestDb.java` + raw DataSource를 직접 만드는 테스트들(Step 3 목록)

**Interfaces:**
- Consumes: Task 1의 search_path (프로덕션은 Hikari init-sql, 테스트는 JDBC URL `currentSchema`)
- Produces: 프로덕션 SQL은 뷰를 무접두어로 참조(`FROM v_analysis_baseline`), `MirrorSpec` 뷰 이름도 무접두어(`"v_accounts"`). `TestDb.rawDataSource(pg)` 헬퍼.

- [ ] **Step 1: 프로덕션 SQL 접두어 제거**

각 파일에서 `analytics.v_이름` → `v_이름`으로 치환. 형태는 전부 동일하다. 예시 (BaselineLoader.java):

```java
// 변경 전
				FROM analytics.v_analysis_account_baseline""",
// 변경 후
				FROM v_analysis_account_baseline""",
```

MirrorConfig.java의 등록부는 다음과 같이 된다:

```java
		return new MirrorRegistry(List.of(
				new MirrorSpec<>("v_accounts", "accounts", Account.class),
				new MirrorSpec<>("v_contents", "contents", Content.class),
				new MirrorSpec<>("v_content_comments", "content_comments", ContentComment.class),
				new MirrorSpec<>("v_content_metric_snapshots", "content_metric_snapshots",
						ContentMetricSnapshot.class),
				new MirrorSpec<>("v_account_summaries", "account_summaries", AccountSummary.class),
				new MirrorSpec<>("v_account_content_series", "account_content_series", AccountContentPoint.class),
				new MirrorSpec<>("v_landing_stats", "landing_stats", LandingStats.class)));
```

⚠️ **가드**: `PipelineStatsService.java:103-104`의 `WHERE key = 'analytics.metric-pin-days'`, `'analytics.analyze-timely-slack-days'`는 **app_setting 키 문자열이므로 절대 바꾸지 않는다.** 뷰 이름 앞의 접두어만 제거 대상이다.

- [ ] **Step 2: 제거 완료 검증**

Run: `grep -rn "analytics\.v_" analytics/src/main/java --include="*.java"`
Expected: 출력 없음 (또는 Javadoc 주석 `CoverageSource.java:4`뿐 — 주석은 그대로 둬도 됨)

Run: `grep -rn "'analytics\." analytics/src/main/java --include="*.java"`
Expected: `PipelineStatsService.java`의 설정 키 2줄이 그대로 남아 있음

- [ ] **Step 3: 테스트 DataSource 헬퍼 추가 + 테스트 전환**

`TestDb.java`에 헬퍼 추가 (import는 파일 상단에 정리):

```java
	/**
	 * raw 쪽 테스트 DataSource — 운영의 connection-init-sql과 같은 효과를 JDBC URL
	 * currentSchema로 낸다(태스크 K). public을 앞에 둬 픽스처 DDL·Flyway는 기존처럼
	 * public에 만들어지고, 무접두어 뷰 조회만 analytics로 폴백된다.
	 */
	public static DriverManagerDataSource rawDataSource(PostgreSQLContainer<?> pg) {
		String sep = pg.getJdbcUrl().contains("?") ? "&" : "?";
		return new DriverManagerDataSource(
				pg.getJdbcUrl() + sep + "currentSchema=public,analytics",
				pg.getUsername(), pg.getPassword());
	}
```

(import 추가: `org.springframework.jdbc.datasource.DriverManagerDataSource`, `org.testcontainers.postgresql.PostgreSQLContainer`)

다음 테스트 파일에서 **raw 접근용** `new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())`를 `TestDb.rawDataSource(pg)`로 교체:

- `mirror/MirrorJobTest.java` — 추가로 `MirrorSpec` 2곳의 `"analytics.v_fixture"` → `"v_fixture"` (픽스처 DDL `CREATE VIEW analytics.v_fixture ...`는 **그대로** — 오브젝트가 사는 곳은 여전히 analytics 스키마)
- `classify/CommentClassificationJobTest.java`
- `archive/ImageArchiveJobTest.java`
- `analyze/ContentSynthesisRefreshJobTest.java`
- `analyze/ContentAnalysisJobTest.java`
- `analyze/ClaudeBurstRunnerTest.java`
- `analyze/GeminiBackfillRunnerTest.java`
- `analyze/AccountAnalysisJobTest.java`
- `coverage/CoverageRepositoryTest.java`
- `admin/AdminUiControllerTest.java` — raw DataSource를 만들면 교체, 프로덕션 SQL 문자열을 assert하면 무접두어로 정렬

규칙: **픽스처 DDL(`CREATE SCHEMA analytics`, `CREATE VIEW analytics.v_x ...`)은 정규화 유지**, **프로덕션 코드로 전달되는 이름·프로덕션에서 복사한 SQL만 무접두어화**. analysis DB 용도로 만든 DataSource(Flyway 대상)는 손대지 않는다(`FlywaySchemaTest`, `AccountCategoryStatsViewTest`, `BeautyTaxonomySeedTest`는 raw 뷰 미사용 시 무변경).

- [ ] **Step 4: 전체 테스트**

Run: `./gradlew test`
Expected: 전 모듈 GREEN (analytics 226+2개 포함). 실패 시 실패 테스트의 datasource가 헬퍼 미적용인지 먼저 확인.

- [ ] **Step 5: 커밋**

```bash
git add -A analytics/src
git commit -m "feat(analytics): SQL 뷰 참조를 무접두어로 전환 — 스키마는 search_path가 결정 (태스크 K)"
```

---

### Task 3: 뷰 스키마 치환 스크립트 + 셀프테스트

**Files:**
- Create: `deploy/scripts/rewrite-views-dev-schema.sh`
- Create: `deploy/scripts/rewrite-views-dev-schema.test.sh`

**Interfaces:**
- Produces: stdin(뷰 SQL) → stdout(`analytics_dev` 치환본) 필터. Task 5의 cd-dev.yml이 `cat analytics/views/*.sql | rewrite-views-dev-schema.sh | psql`로 사용. 테스트 스크립트는 인자 없이 실행, 실패 시 exit 1.

- [ ] **Step 1: 실패하는 테스트 작성**

`deploy/scripts/rewrite-views-dev-schema.test.sh`:

```bash
#!/usr/bin/env bash
# rewrite-views-dev-schema.sh 픽스처 테스트 — 실제 뷰 SQL의 4가지 패턴을 커버:
# ①스키마 생성(무점) ②뷰 정의·참조 ③따옴표 설정 키(치환 금지) ④달러 인용 함수 본문·DROP
set -euo pipefail
cd "$(dirname "$0")"

actual=$(./rewrite-views-dev-schema.sh <<'IN'
CREATE SCHEMA IF NOT EXISTS analytics;
CREATE OR REPLACE VIEW analytics.v_base_influencer AS
SELECT id FROM influencer;
analytics.v_line_start_case AS x
SELECT COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12),
       analytics.hype_score(t, a, b) FROM analytics.v_contents
CREATE OR REPLACE FUNCTION analytics.refresh_snapshot_cache() RETURNS bigint LANGUAGE plpgsql AS $$
BEGIN
  TRUNCATE analytics.content_snapshot_cache;
END $$;
DROP FUNCTION IF EXISTS analytics.hype_score(text, bigint);
IN
)

expected=$(cat <<'OUT'
CREATE SCHEMA IF NOT EXISTS analytics_dev;
CREATE OR REPLACE VIEW analytics_dev.v_base_influencer AS
SELECT id FROM influencer;
analytics_dev.v_line_start_case AS x
SELECT COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12),
       analytics_dev.hype_score(t, a, b) FROM analytics_dev.v_contents
CREATE OR REPLACE FUNCTION analytics_dev.refresh_snapshot_cache() RETURNS bigint LANGUAGE plpgsql AS $$
BEGIN
  TRUNCATE analytics_dev.content_snapshot_cache;
END $$;
DROP FUNCTION IF EXISTS analytics_dev.hype_score(text, bigint);
OUT
)

if [ "$actual" != "$expected" ]; then
  echo "치환 결과 불일치:"
  diff <(echo "$expected") <(echo "$actual") || true
  exit 1
fi
echo "OK"
```

- [ ] **Step 2: 실패 확인**

Run: `chmod +x deploy/scripts/rewrite-views-dev-schema.test.sh && deploy/scripts/rewrite-views-dev-schema.test.sh`
Expected: FAIL — `rewrite-views-dev-schema.sh: No such file or directory`

- [ ] **Step 3: 스크립트 구현**

`deploy/scripts/rewrite-views-dev-schema.sh`:

```bash
#!/usr/bin/env bash
# 뷰 SQL의 analytics 스키마 참조를 analytics_dev로 치환하는 필터 (태스크 K — dev 스테이징 전용).
# 규칙: 앞 문자가 작은따옴표·식별자 문자가 아닐 때만 치환 — app_setting 키('analytics.recent-window' 등)
#       문자열 리터럴을 보존한다. 달러 인용($$) 함수 본문은 치환 대상(내부의 따옴표 키는 역시 보존).
# 치환 누락은 dev 계정 권한(analytics 스키마 쓰기 불가)과 cd-dev의 잔존 참조 검사가 이중으로 잡는다.
set -euo pipefail
sed -E \
  -e "s/(^|[^'[:alnum:]_])analytics\./\1analytics_dev./g" \
  -e "s/CREATE SCHEMA IF NOT EXISTS analytics;/CREATE SCHEMA IF NOT EXISTS analytics_dev;/"
```

Run: `chmod +x deploy/scripts/rewrite-views-dev-schema.sh`

- [ ] **Step 4: 테스트 통과 + 실뷰 스모크**

Run: `deploy/scripts/rewrite-views-dev-schema.test.sh`
Expected: `OK`

Run: `cat analytics/views/*.sql | deploy/scripts/rewrite-views-dev-schema.sh | grep -cE "[^'[:alnum:]_]analytics\." || echo CLEAN`
Expected: `CLEAN` (치환 후 비-따옴표 `analytics.` 참조 잔존 0)

Run: `cat analytics/views/*.sql | deploy/scripts/rewrite-views-dev-schema.sh | grep -c "'analytics\."`
Expected: 원본과 같은 수 — 확인: `cat analytics/views/*.sql | grep -c "'analytics\."` 와 동일 값 (설정 키 보존)

- [ ] **Step 5: 커밋**

```bash
git add deploy/scripts/rewrite-views-dev-schema.sh deploy/scripts/rewrite-views-dev-schema.test.sh
git commit -m "feat(deploy): dev 뷰 스키마 치환 스크립트 + 픽스처 테스트 (태스크 K)"
```

---

### Task 4: compose dev 프로파일 + Caddyfile dev 사이트

**Files:**
- Modify: `deploy/compose.yaml` (dev 서비스 3종 + 볼륨)
- Modify: `deploy/Caddyfile` (dev-api 사이트 블록)

**Interfaces:**
- Consumes: `:develop` 이미지 태그(Task 5가 push), env `ANALYTICS_RAW_SCHEMA`(Task 1), role `analytics_dev`(Task 5 준비 스크립트)
- Produces: compose 프로파일 `dev`(서비스명 `dev-postgres`·`dev-analytics`·`dev-was`), 서버 `.env` 신규 변수 `DEV_DB_USER`·`DEV_DB_PASSWORD`·`DEV_RAW_DB_PASSWORD`·`DEV_CODES_API_KEY`(전부 `:-` 기본값)

- [ ] **Step 1: compose.yaml에 dev 서비스 추가**

`volumes:` 블록 위, `caddy:` 서비스 뒤에 추가:

```yaml
  # ── dev 스테이징 (태스크 K) — develop 브랜치 검증용. --profile dev 로만 기동 ──
  # raw는 운영 postgres-raw를 analytics_dev 계정(읽기 전용 + analytics_dev 스키마 소유)으로 공유.
  # 스케줄 전부 off — 잡은 어드민(터널 8083) 수동 트리거만. LLM 자격증명은 운영 공유(소량 수동 전제).
  dev-postgres:
    image: postgres:17-alpine
    profiles: ["dev"]
    restart: unless-stopped
    logging: *logging
    mem_limit: 512m
    cpus: 1.0
    environment:
      POSTGRES_DB: analysis
      POSTGRES_USER: ${DEV_DB_USER:-}
      POSTGRES_PASSWORD: ${DEV_DB_PASSWORD:-}
    ports:
      - "127.0.0.1:5434:5432"   # 루프백 전용 — 외부 접속은 SSH 터널로만
    volumes:
      - dev-pg-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DEV_DB_USER:-} -d analysis"]
      interval: 10s
      timeout: 5s
      retries: 5

  dev-analytics:
    image: ghcr.io/subtle-madness/hypenow-analytics:develop
    profiles: ["dev"]
    restart: unless-stopped
    logging: *logging
    mem_limit: 1536m
    cpus: 1.0
    environment:
      APP_DATASOURCE_RAW_JDBC_URL: jdbc:postgresql://postgres-raw:5432/crawler
      APP_DATASOURCE_RAW_USERNAME: analytics_dev
      APP_DATASOURCE_RAW_PASSWORD: ${DEV_RAW_DB_PASSWORD:-}
      # dev 뷰·캐시는 analytics_dev 스키마 — 치환 적용(cd-dev)과 짝
      ANALYTICS_RAW_SCHEMA: analytics_dev
      APP_DATASOURCE_ANALYSIS_JDBC_URL: jdbc:postgresql://dev-postgres:5432/analysis
      APP_DATASOURCE_ANALYSIS_USERNAME: ${DEV_DB_USER:-}
      APP_DATASOURCE_ANALYSIS_PASSWORD: ${DEV_DB_PASSWORD:-}
      GEMINI_API_KEY: ${GEMINI_API_KEY}
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/vertex-sa.json
      ANALYTICS_SCHEDULE_ENABLED: "false"
      # 이미지 아카이브는 dev 대상 아님(운영 버킷 오염 방지) — 빈 값이면 잡이 fail-fast
      ANALYTICS_IMAGE_PAR_URL: ""
      JAVA_OPTS: "-Xms256m -Xmx768m"
    volumes:
      - ./secrets/vertex-sa.json:/secrets/vertex-sa.json:ro
    ports:
      - "127.0.0.1:8083:8082"   # 루프백 전용 — 어드민은 SSH 터널(ssh -L 8083:localhost:8083)
    healthcheck:
      test: ["CMD-SHELL", "bash -c '</dev/tcp/127.0.0.1/8082' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 18
      start_period: 90s
    depends_on:
      dev-postgres:
        condition: service_healthy
      postgres-raw:
        condition: service_healthy

  dev-was:
    image: ghcr.io/subtle-madness/hypenow-was:develop
    profiles: ["dev"]
    restart: unless-stopped
    logging: *logging
    mem_limit: 1024m
    cpus: 1.0
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: jdbc:postgresql://dev-postgres:5432/analysis
      DB_USER: ${DEV_DB_USER:-}
      DB_PASSWORD: ${DEV_DB_PASSWORD:-}
      # RESEND 미설정 = 로깅 폴백 — dev 이메일 인증 코드는 docker logs deploy-dev-was-1 에서 확인
      CODES_API_KEY: ${DEV_CODES_API_KEY:-}
      JAVA_OPTS: "-Xms512m -Xmx512m"
    # 기동 순서는 운영과 동일 규율 — dev-analytics healthy(=dev analysis DB 마이그레이션 완료) 후
    depends_on:
      dev-postgres:
        condition: service_healthy
      dev-analytics:
        condition: service_healthy
```

`volumes:` 블록에 추가:

```yaml
volumes:
  pg-data:
  pg-raw-data:
  dev-pg-data:
  caddy-data:
  caddy-config:
```

- [ ] **Step 2: Caddyfile에 dev 사이트 추가**

`deploy/Caddyfile` 끝에 추가:

```
# dev 스테이징 (태스크 K) — 보호는 was 로그인 월(별도 dev 가입 코드) 그대로.
# dev 프로파일 미기동 시 502 — caddy는 요청 시점에 업스트림을 찾으므로 설정 로드는 무해.
{$DEV_API_DOMAIN:dev-api.hypenow.io} {
	handle {
		reverse_proxy dev-was:8081
	}
}
```

- [ ] **Step 3: compose 렌더링 검증**

Run: `cd deploy && docker compose --profile dev config --services 2>/dev/null | sort`
Expected: 기존 7개 + `dev-analytics` `dev-postgres` `dev-was` 포함 10개. (env "is not set" 경고 없음 — dev 변수는 전부 `:-` 기본값)

Run: `cd deploy && docker compose config --services 2>/dev/null | grep -c dev- || echo NONE`
Expected: `NONE` — 프로파일 미지정 시 dev 서비스가 안 뜬다(운영 CD의 `up -d` 무영향)

- [ ] **Step 4: 커밋**

```bash
git add deploy/compose.yaml deploy/Caddyfile
git commit -m "feat(deploy): dev 스테이징 compose 프로파일 + dev-api Caddy 사이트 (태스크 K)"
```

---

### Task 5: dev CD 워크플로 + dev 계정 준비 스크립트

**Files:**
- Create: `.github/workflows/cd-dev.yml`
- Create: `deploy/scripts/prepare-dev-raw-role.sh`

**Interfaces:**
- Consumes: Task 3 치환 스크립트, Task 4 compose 프로파일, CI 워크플로 이름 `CI`, 시크릿 `DEPLOY_SSH_KEY`·`DEPLOY_HOST`(운영 CD와 공유)
- Produces: develop CI 성공 → `:develop` 이미지(was·analytics, arm64) → dev 스택 배포. 서버 role `analytics_dev` + 스키마 `analytics_dev`(멱등).

- [ ] **Step 1: dev 계정 준비 스크립트 작성**

`deploy/scripts/prepare-dev-raw-role.sh` (서버에서 실행됨 — cd-dev가 scp 후 호출):

```bash
#!/usr/bin/env bash
# dev raw 계정·스키마 준비(멱등) — 태스크 K. cd-dev가 서버에서 실행한다.
# analytics_dev: crawler 테이블(public) 읽기 전용 + analytics_dev 스키마 소유.
# analytics 스키마엔 USAGE도 주지 않는다 — 치환 누락 시 권한 오류로 즉사(fail-closed).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
: "${DEV_RAW_DB_PASSWORD:?서버 .env에 DEV_RAW_DB_PASSWORD 없음 — deploy/README.md dev 개통 체크리스트 선행}"
PG="${PG_CONTAINER:-deploy-postgres-raw-1}"

docker exec -i "$PG" psql -U crawler -d crawler -v ON_ERROR_STOP=1 \
  -v devpw="$DEV_RAW_DB_PASSWORD" <<'SQL'
DO $do$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'analytics_dev') THEN
    CREATE ROLE analytics_dev LOGIN;
  END IF;
END
$do$;
ALTER ROLE analytics_dev PASSWORD :'devpw';
GRANT CONNECT ON DATABASE crawler TO analytics_dev;
GRANT USAGE ON SCHEMA public TO analytics_dev;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO analytics_dev;
ALTER DEFAULT PRIVILEGES FOR ROLE crawler IN SCHEMA public GRANT SELECT ON TABLES TO analytics_dev;
CREATE SCHEMA IF NOT EXISTS analytics_dev AUTHORIZATION analytics_dev;
SQL
echo "analytics_dev 준비 완료"
```

Run: `chmod +x deploy/scripts/prepare-dev-raw-role.sh && bash -n deploy/scripts/prepare-dev-raw-role.sh`
Expected: 문법 오류 없음(출력 없음)

- [ ] **Step 2: cd-dev.yml 작성**

`.github/workflows/cd-dev.yml`:

```yaml
# develop CI 성공마다 dev 스테이징 배포 (태스크 K) — 대상: was·analytics 2종(:develop, arm64 단일).
# 뷰는 analytics_dev 스키마로 치환 적용(analytics_dev 계정 실행 = 권한 fail-closed) 후 잔존 참조 검사.
# 최초 개통은 deploy/README.md dev 체크리스트(.env 변수·DNS·가입 코드) 선행 — 미비 시 이 워크플로가 명시 실패.
name: CD dev

on:
  workflow_run:
    workflows: [CI]
    types: [completed]
    branches: [develop]

concurrency:
  group: cd-develop
  cancel-in-progress: false

permissions:
  contents: read
  packages: write

jobs:
  build-push:
    name: dev 이미지 빌드·push (${{ matrix.service }})
    if: github.event.workflow_run.conclusion == 'success'
    runs-on: ubuntu-latest
    timeout-minutes: 30
    strategy:
      matrix:
        service: [was, analytics]
    steps:
      - uses: actions/checkout@v7
        with:
          ref: ${{ github.event.workflow_run.head_sha }}

      - name: JDK 21 설치
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'

      - name: Gradle 설정
        uses: gradle/actions/setup-gradle@v6

      - name: bootJar 빌드
        run: ./gradlew :${{ matrix.service }}:bootJar

      # 서버(오라클 A1)가 arm64 단일이라 dev는 멀티아치 불필요 — 운영 CD보다 빠르게
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3

      - name: ghcr 로그인
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: arm64 빌드·push (:develop)
        run: |
          SHA_TAG="develop-sha-$(git rev-parse --short HEAD)"
          REPO="ghcr.io/${{ github.repository_owner }}/hypenow-${{ matrix.service }}"
          docker buildx build --platform linux/arm64 \
            -t "$REPO:develop" -t "$REPO:$SHA_TAG" --push ${{ matrix.service }}

  deploy:
    name: dev 서버 배포
    needs: build-push
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v7
        with:
          ref: ${{ github.event.workflow_run.head_sha }}

      - name: SSH 설정
        run: |
          mkdir -p ~/.ssh
          printf '%s\n' "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/id_deploy
          chmod 600 ~/.ssh/id_deploy
          ssh-keyscan -H "${{ secrets.DEPLOY_HOST }}" >> ~/.ssh/known_hosts

      # compose·Caddyfile은 develop 버전으로 동기화 — 운영 컨테이너는 재기동하지 않으므로 무영향,
      # 다음 main 배포가 main 버전으로 재동기화한다(운영 CD와 같은 검증 포함)
      - name: compose·Caddyfile·스크립트 동기화·검증
        run: |
          scp -i ~/.ssh/id_deploy deploy/compose.yaml deploy/Caddyfile "ubuntu@${{ secrets.DEPLOY_HOST }}:~/deploy/"
          scp -i ~/.ssh/id_deploy deploy/scripts/prepare-dev-raw-role.sh "ubuntu@${{ secrets.DEPLOY_HOST }}:~/deploy/scripts/"
          ssh -i ~/.ssh/id_deploy "ubuntu@${{ secrets.DEPLOY_HOST }}" \
            'cd ~/deploy && out=$(docker compose --profile dev config 2>&1 >/dev/null) || { echo "$out"; exit 1; }
             if echo "$out" | grep -q "is not set"; then
               echo "$out"; echo "compose가 참조하는 env 변수가 서버 .env에 없음 — .env 갱신 후 재배포"; exit 1
             fi'

      - name: dev DB 계정·스키마 준비 (멱등)
        run: |
          ssh -i ~/.ssh/id_deploy "ubuntu@${{ secrets.DEPLOY_HOST }}" 'bash ~/deploy/scripts/prepare-dev-raw-role.sh'

      - name: 치환 셀프테스트
        run: deploy/scripts/rewrite-views-dev-schema.test.sh

      # analytics_dev 계정으로 적용 — 치환 누락 시 analytics 스키마 쓰기 권한이 없어 즉시 실패
      - name: dev 뷰 적용 (analytics_dev 치환)
        run: |
          cat analytics/views/*.sql | deploy/scripts/rewrite-views-dev-schema.sh | \
            ssh -i ~/.ssh/id_deploy "ubuntu@${{ secrets.DEPLOY_HOST }}" \
              'docker exec -i deploy-postgres-raw-1 psql -U analytics_dev -d crawler -v ON_ERROR_STOP=1 -q'

      # 뷰 정의·함수 본문에 analytics.(비-따옴표) 참조가 남았으면 치환 누락 — 배포 실패 처리
      - name: 잔존 참조 검사
        run: |
          residue=$(ssh -i ~/.ssh/id_deploy "ubuntu@${{ secrets.DEPLOY_HOST }}" \
            'docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -tA' <<'SQL'
          SELECT 'view ' || viewname FROM pg_views
            WHERE schemaname = 'analytics_dev' AND definition ~ $q$[^']analytics\.$q$
          UNION ALL
          SELECT 'function ' || p.proname FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'analytics_dev' AND p.prosrc ~ $q$[^']analytics\.$q$;
          SQL
          )
          if [ -n "$residue" ]; then
            echo "analytics_dev 안에 운영 analytics 참조 잔존:"; echo "$residue"; exit 1
          fi

      - name: dev compose pull·재기동
        run: |
          ssh -i ~/.ssh/id_deploy "ubuntu@${{ secrets.DEPLOY_HOST }}" \
            'cd ~/deploy && docker compose --profile dev pull dev-analytics dev-was && \
             docker compose --profile dev up -d dev-postgres dev-analytics dev-was && \
             docker compose --profile dev ps'

      - name: 헬스체크
        run: curl -fsS --retry 5 --retry-delay 10 --retry-all-errors https://dev-api.hypenow.io/health
```

- [ ] **Step 3: 워크플로 문법 검증**

Run: `python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/cd-dev.yml')); print('OK')"`
Expected: `OK` (pyyaml이 없으면 `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest` 대체, 그것도 없으면 첫 develop 푸시에서 확인)

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/cd-dev.yml deploy/scripts/prepare-dev-raw-role.sh
git commit -m "ci: develop CI 성공마다 dev 스테이징 배포하는 cd-dev 신설 (태스크 K)"
```

---

### Task 6: 운영 문서 + 개통 체크리스트

**Files:**
- Modify: `deploy/README.md` (dev 스테이징 섹션 신설)
- Modify: `ARCHITECTURE.md` (§5 트랙 K 상태 갱신)
- Modify: `docs/superpowers/specs/2026-07-26-dev-staging-environment-design.md` (상태 헤더)

**Interfaces:**
- Consumes: Task 4 env 변수 이름·포트, Task 5 워크플로 동작
- Produces: 개통 체크리스트(사용자 실행 항목) — 코드 머지 후 이 문서만 보고 개통 가능해야 한다

- [ ] **Step 1: deploy/README.md에 섹션 추가**

문서 끝에 추가 (§ 번호는 기존 마지막 번호+1로 조정):

```markdown
## §N. dev 스테이징 (태스크 K)

develop 브랜치 검증용 스택. develop CI 성공마다 `cd-dev.yml`이 자동 배포한다.
구조·결정 근거: [specs/2026-07-26-dev-staging-environment-design.md](../../specs/2026-07-26-dev-staging-environment-design.md)

- 접속: `https://dev-api.hypenow.io` (was 로그인 월 — dev 전용 가입 코드 필요)
- 어드민: `ssh -L 8083:localhost:8083 ubuntu@<서버>` 후 `http://localhost:8083/ui`
- dev analysis DB: `ssh -L 5434:localhost:5434` (계정은 .env `DEV_DB_*`)
- raw는 운영 postgres-raw 공유 — dev 계정 `analytics_dev`는 crawler 테이블 읽기 전용,
  뷰·캐시는 `analytics_dev` 스키마(치환 설치). 운영 `analytics` 스키마엔 접근 권한 없음.

### 최초 개통 체크리스트 (1회, 사용자 실행)

1. DNS A 레코드: `dev-api.hypenow.io` → 서버 IP (운영과 동일 IP)
2. 서버 `~/deploy/.env`에 추가 (값 생성: `openssl rand -base64 24`):
   `DEV_DB_USER=devapp` · `DEV_DB_PASSWORD=<생성>` · `DEV_RAW_DB_PASSWORD=<생성>` ·
   `DEV_CODES_API_KEY=<생성>` (미설정 시 가입 코드 적재 API 503 fail-closed)
3. develop에 아무 커밋 푸시 → `CD dev` 워크플로 성공 확인
4. dev 가입 코드 시드: `deploy/scripts/generate-signup-codes.sh`를 dev-api 대상으로 실행
   (또는 `POST https://dev-api.hypenow.io/admin/signup-codes`, Bearer `DEV_CODES_API_KEY`)
5. 가입·로그인 → `/v1/contents` 응답 확인. 이메일 인증 코드는 Resend 미설정(로깅 폴백)이라
   `docker logs deploy-dev-was-1 | grep 인증` 에서 확인

### 일상 사용

- 기능 확인 절차: PR→develop 머지 → cd-dev 완료 대기 → 어드민(8083)에서 미러 수동 실행 →
  dev-api로 API 응답 확인 → 이상 없으면 develop→main 머지(운영 배포)
- 스냅샷 캐시 refresh(필요 시):
  `docker exec -i deploy-postgres-raw-1 psql -U analytics_dev -d crawler -c "SELECT analytics_dev.refresh_snapshot_cache();"`
- dev 스케줄은 전부 off — 미러·분석·LLM 잡은 어드민 수동 트리거만. LLM 실행은 운영
  자격증명 공유이므로 소량으로(쿼터·비용 공유 인지)
- dev 스택 정지: `docker compose --profile dev stop dev-was dev-analytics dev-postgres`
  (운영 무영향 — 프로파일 밖 서비스는 건드리지 않는다)
```

- [ ] **Step 2: ARCHITECTURE.md §5 트랙 K 상태 갱신**

K 행의 상태 칸 `📋 설계 확정 (구현 미착수)` → `✅ (구현 완료 — 개통 체크리스트(README §N) 사용자 실행 대기)`

스펙 문서 상태 헤더 `> 상태: 🟢 활성 · 설계 확정 (구현 미착수)` → `> 상태: 🟢 활성 · ✅ 구현됨 (개통 체크리스트 대기)`

- [ ] **Step 3: 커밋**

```bash
git add deploy/README.md ARCHITECTURE.md docs/superpowers/specs/2026-07-26-dev-staging-environment-design.md
git commit -m "docs: dev 스테이징 운영 문서·개통 체크리스트 + 트랙 K 상태 갱신"
```

---

## 검증 요약 (전 태스크 완료 후)

- [ ] `./gradlew test` GREEN
- [ ] `deploy/scripts/rewrite-views-dev-schema.test.sh` → OK
- [ ] `cd deploy && docker compose --profile dev config --services` → dev 3종 렌더링, 프로파일 미지정 시 미포함
- [ ] `grep -rn "analytics\.v_" analytics/src/main/java` → 주석 외 0건
- [ ] PR 생성(develop 대상) — 머지 후 첫 cd-dev 실행은 개통 체크리스트(.env·DNS) 선행 필요를 PR 본문에 명시

## 운영 반영 순서 (머지 후)

1. 사용자: DNS + 서버 .env(체크리스트 1·2) — **서버 상태 변경이므로 사용자 확인 필수**
2. develop 푸시(머지 자체) → cd-dev 자동 실행 → 실패 시 로그의 명시 메시지(".env 없음" 등) 따라 보완
3. 체크리스트 4·5로 가입 코드·응답 확인
