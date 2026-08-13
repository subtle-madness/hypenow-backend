# 어드민 전역 크롤링 비용 API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서비스 전체의 크롤링 콜·비용을 세 구간(전체·이번 달·오늘, KST)과 파이프라인별로 분해해 내려주는 어드민 API `GET /v1/admin/crawling-cost/summary`를 만든다.

**Architecture:** 크롤러 파이프라인 몫은 raw DB `crawl_run`을 analytics 뷰로 집계해 analysis DB `crawl_call_daily` 미러 테이블로 옮기고, 모니터링 몫은 was가 monitoring DB의 `brand_call_count`·`target_call_count`를 날짜 축으로 직접 합산한다. was가 두 갈래를 Java에서 더하고 전역 단가를 곱한다. 모듈 간 HTTP는 쓰지 않는다(ARCHITECTURE §2).

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(analytics/was/contract-analysis) · PostgreSQL(뷰 + Flyway) · JdbcClient/JdbcTemplate · JUnit 5 + AssertJ + Mockito · Testcontainers · SQL 하니스(psql)

설계 정본: [docs/superpowers/specs/2026-08-13-admin-global-crawling-cost-design.md](../specs/2026-08-13-admin-global-crawling-cost-design.md)

## Global Constraints

- **주석·로그·커밋 메시지는 한국어.** 커밋 prefix는 `feat(모듈):` / `docs:` / `test(모듈):`.
- **신규 Flyway 마이그레이션은 UTC 타임스탬프 채번** — `V$(date -u +%Y%m%d%H%M%S)__<설명>.sql`. KST 채번 금지(08-12 운영 크래시루프). 기존 `V1`~`V49` 파일은 절대 rename 금지.
- **테스트 실행 전 `DOCKER_HOST` 필수** — `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`. 미설정 시 Testcontainers 통합 테스트가 대량 실패하며, 테스트 결함으로 오진하기 쉽다.
- **테스트는 모듈 단위가 기본** — `./gradlew :was:test`, `./gradlew :analytics:test`. 전체 `./gradlew test`는 PR 직전에만.
- **was는 raw DB에 접근하지 않는다.** 크롤러 데이터는 오직 미러된 `crawl_call_daily`로만 읽는다.
- **`analytics` 뷰에서 raw 테이블을 직접 만지는 것은 `00_base.sql`의 base 뷰뿐이다**(ARCHITECTURE §4-4). 상위 뷰는 base 뷰를 consume한다.
- **미러 계약 3중 정합**: 뷰 컬럼 이름·순서 = record 컴포넌트의 snake_case 변환 = DDL 컬럼 이름·순서. `MirrorJob.toSnakeCase`는 대문자 앞에만 `_`를 넣는다.
- **API는 404·500을 내지 않는다** — 못 읽은 구간은 `sources[].available=false` + 해당 집계 0.
- **비용은 반올림하지 않는다** — `BigDecimal(unitPrice).multiply(BigDecimal.valueOf(calls))` 결과 그대로.
- KST 상수는 `com.celfit.was.v1.common.KstTimestamps.KST`를 쓴다.

## File Structure

| 파일 | 책임 |
|---|---|
| `analytics/views/00_base.sql` (수정) | `v_base_crawl_run` 추가 — raw `crawl_run`을 만지는 유일한 자리 |
| `analytics/views/30_crawl_cost.sql` (신규) | `v_crawl_call_daily` — 잡·KST 달력일별 유료 요청 수 |
| `analytics/test/30_crawl_cost.test.sql` (신규) | 위 뷰의 SQL 하니스 |
| `contract-analysis/.../CrawlCallDaily.java` (신규) | 미러 그릇 record |
| `analytics/.../db/migration/analysis/V<UTC>__crawl_call_daily.sql` (신규) | 미러 테이블 DDL |
| `analytics/.../mirror/MirrorConfig.java` (수정) | 등록부에 1행 |
| `analytics/.../mirror/FlywaySchemaTest.java` (수정) | DDL=record 대조 케이스 |
| `analytics/.../mirror/CrawlCallDailyMirrorTest.java` (신규) | 뷰→테이블 왕복(LocalDate 매핑 실증) |
| `was/.../crawlcost/CrawlCallDailyRepository.java` (신규) | 미러 테이블 조회 |
| `was/.../monitoring/DailyCallSum.java` (신규) | 날짜별 콜 합 1행 |
| `was/.../monitoring/BrandReadRepository.java` (수정) | `sumDailyCallCounts()` |
| `was/.../monitoring/MonitoringReadRepository.java` (수정) | `sumDailyCallCounts()` |
| `was/.../v1/admin/PeriodSums.java` (신규, 기존 서비스에서 추출) | 세 구간 누적기 — 두 서비스 공용 |
| `was/.../v1/admin/AdminCrawlingUsageService.java` (수정) | 중첩 `PeriodSums` 제거하고 공용 클래스 사용 |
| `was/.../v1/admin/AdminCrawlingCostSummary.java` (신규) | 응답 계약 record |
| `was/.../v1/admin/AdminCrawlingCostSummaryService.java` (신규) | 3소스 합산·단가 곱셈·열화 판정 |
| `was/.../v1/admin/AdminCrawlingCostController.java` (수정) | GET summary 1개 추가 |

---

### Task 1: analytics 뷰 — 잡·KST 달력일별 유료 요청 수

**Files:**
- Modify: `analytics/views/00_base.sql` (파일 끝에 추가)
- Create: `analytics/views/30_crawl_cost.sql`
- Test: `analytics/test/30_crawl_cost.test.sql`

**Interfaces:**
- Consumes: raw DB `crawl_run(id, job, trigger_type, actor_id, status, request_count, started_at)`
- Produces: `analytics.v_crawl_call_daily(job text, called_on date, calls bigint)` — Task 2의 미러 소스

**사전 준비:** 실데이터 postgres 컨테이너가 떠 있어야 한다.

```bash
docker start crawler-postgres-1
```

컨테이너 이름은 머신마다 다르다(`docker ps -a`로 확인 후 `PG_CONTAINER=<이름>`으로 오버라이드).

- [ ] **Step 1: 실패하는 하니스 테스트를 쓴다**

`analytics/test/30_crawl_cost.test.sql`:

```sql
-- 크롤러 파이프라인 유료 요청 집계. 검증 축 4개:
--   ① KST 달력일 경계(UTC 15:00 = KST 다음날 00:00) ② request_count NULL·0 제외
--   ③ status 미필터(요청이 나간 실패 실행도 과금이므로 계상) ④ 잡별 그룹핑
-- 공용 dummy.sql의 crawl_run 99990000은 request_count NULL이라 자동으로 제외 모수가 된다.
INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, request_count, started_at) VALUES
 -- 같은 KST 날(06-05)로 접히는 두 실행: 14:59:59Z = 23:59:59 KST.
 (99990010,'COLLECT','SCHEDULED','profile-hiker-mobile','SUCCEEDED', 3, timestamptz '2026-06-05 00:30:00+00'),
 (99990011,'COLLECT','SCHEDULED','profile-hiker-mobile','SUCCEEDED', 2, timestamptz '2026-06-05 14:59:59+00'),
 -- 1초 뒤 = KST 06-06 00:00:01 → 다음 날로 갈라져야 한다.
 (99990012,'COLLECT','SCHEDULED','profile-hiker-mobile','SUCCEEDED', 7, timestamptz '2026-06-05 15:00:01+00'),
 -- 잡이 다르면 다른 행.
 (99990013,'REELS','SCHEDULED','hiker-v2-clips','SUCCEEDED', 1, timestamptz '2026-06-05 00:30:00+00'),
 -- 요청은 나갔으나 404로 끝난 실행 — 과금 실체와 맞게 계상한다(status 미필터).
 (99990014,'REELS','SCHEDULED','hiker-v2-clips','FAILED', 4, timestamptz '2026-06-05 00:40:00+00'),
 -- Apify 실행(결과 건당 과금) — request_count NULL이라 제외.
 (99990015,'DISCOVER','MANUAL','apify/instagram-hashtag-scraper','SUCCEEDED', NULL, timestamptz '2026-06-05 00:30:00+00'),
 -- 무료 소스(self) — 0건이라 제외(0짜리 행을 만들지 않는다).
 (99990016,'QUALIFY','SCHEDULED','profile-self','SUCCEEDED', 0, timestamptz '2026-06-05 00:30:00+00');

DO $$
BEGIN
  -- ① COLLECT는 KST 06-05에 3+2=5, 06-06에 7로 갈린다.
  ASSERT (SELECT calls FROM analytics.v_crawl_call_daily
          WHERE job = 'COLLECT' AND called_on = date '2026-06-05') = 5,
         'COLLECT 06-05 != 5 (KST 경계 접힘)';
  ASSERT (SELECT calls FROM analytics.v_crawl_call_daily
          WHERE job = 'COLLECT' AND called_on = date '2026-06-06') = 7,
         'COLLECT 06-06 != 7 (KST 자정 넘김)';
  -- ③ 실패 실행 포함: REELS 06-05 = 1 + 4.
  ASSERT (SELECT calls FROM analytics.v_crawl_call_daily
          WHERE job = 'REELS' AND called_on = date '2026-06-05') = 5,
         'REELS 06-05 != 5 (실패 실행 미계상)';
  -- ② NULL·0은 행 자체가 없어야 한다.
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_crawl_call_daily WHERE job = 'DISCOVER'),
         'DISCOVER 행 존재 (request_count NULL이 제외되지 않음)';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_crawl_call_daily WHERE job = 'QUALIFY'),
         'QUALIFY 행 존재 (request_count 0이 제외되지 않음)';
  -- ④ 시드 전체에서 나오는 행은 위 3개뿐.
  ASSERT (SELECT count(*) FROM analytics.v_crawl_call_daily) = 3,
         'v_crawl_call_daily 행 수 != 3';
END $$;
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
analytics/test/run.sh test/30_crawl_cost.test.sql
```

기대: `ERROR: relation "analytics.v_crawl_call_daily" does not exist`

- [ ] **Step 3: base 뷰를 추가한다**

`analytics/views/00_base.sql` **맨 끝에** 추가:

```sql
-- crawl_run 노출 — 크롤러 파이프라인의 유료 요청 수(비용 집계 재료). payload 파싱 없음.
-- request_count는 비Apify 소스가 실제로 구매한 요청 수다(V7): Apify 실행은 NULL(결과 건당 과금이라
-- 콜 기반 산출 불가), 무료 소스(profile-self)는 0. 상위 뷰(30)가 이 둘을 제외한다.
CREATE OR REPLACE VIEW analytics.v_base_crawl_run AS
SELECT
  id AS crawl_run_id,
  job,
  actor_id,
  status,
  request_count,
  started_at
FROM crawl_run;
```

- [ ] **Step 4: 집계 뷰를 만든다**

`analytics/views/30_crawl_cost.sql`:

```sql
-- 크롤러 파이프라인 유료 요청 일별 집계 (설계 2026-08-13 §3-2·3-3) — analysis DB
-- crawl_call_daily로 미러돼 was 어드민 전역 크롤링 비용 API가 읽는다.
--
-- 모수: request_count > 0인 실행만. Apify 실행(NULL — 결과 건당 과금)과 무료 소스(0)가
-- 이 한 조건으로 자연히 빠진다. 액터 라벨 화이트리스트를 두지 않는 이유가 이것 — 새 무료
-- 경로가 생겨도 규칙을 고칠 필요가 없다.
--
-- status는 거르지 않는다: request_count는 finishOk 경로에서만 기록되고, 요청이 나간 뒤
-- 404로 끝난 실행(ReelsJob·SimilarJob의 '콘텐츠 없음')도 요청은 이미 샀으므로 값을 유지한다.
--
-- 날짜는 KST 달력일 — monitoring의 brand_call_count.called_on·target_call_count.called_on과
-- 같은 시간대라 was가 세 소스를 한 경계로 합산한다. 자정을 넘긴 실행은 시작일 몫이다(실행
-- 단위라 요청을 시각별로 쪼갤 수 없고, 크롤 잡은 01:00~03:55 KST의 짧은 실행들이라 무의미).
--
-- 컬럼 계약: CrawlCallDaily record(job, calledOn, calls) ↔ crawl_call_daily DDL과 이름·순서
-- 일치 필수(§4-3, MirrorJob이 실행 시 대조). sum()은 numeric이라 ::bigint 캐스트 필수.
CREATE OR REPLACE VIEW analytics.v_crawl_call_daily AS
SELECT
  job,
  (started_at AT TIME ZONE 'Asia/Seoul')::date AS called_on,
  sum(request_count)::bigint AS calls
FROM analytics.v_base_crawl_run
WHERE request_count > 0
GROUP BY job, (started_at AT TIME ZONE 'Asia/Seoul')::date;
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

```bash
analytics/test/run.sh test/30_crawl_cost.test.sql
```

기대: `PASS: test/30_crawl_cost.test.sql` → `ALL GREEN`

- [ ] **Step 6: 기존 하니스 전체가 깨지지 않았는지 확인한다**

```bash
analytics/test/run.sh
```

기대: `ALL GREEN` (00_base.sql 수정이 기존 뷰에 영향 없음을 확인)

- [ ] **Step 7: 커밋**

```bash
git add analytics/views/00_base.sql analytics/views/30_crawl_cost.sql analytics/test/30_crawl_cost.test.sql
git commit -m "feat(analytics): 크롤러 파이프라인 유료 요청 일별 집계 뷰

request_count > 0 조건 하나로 Apify(결과 건당 과금·NULL)와 무료 소스(0)를 제외한다.
날짜는 KST 달력일 — monitoring의 call_count 테이블과 같은 경계로 맞춰 was가 합산한다.
status는 거르지 않는다: 요청이 나간 뒤 404로 끝난 실행도 과금은 발생했다."
```

---

### Task 2: 미러 배선 — contract record + 미러 테이블 DDL + 등록

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/CrawlCallDaily.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V<UTC>__crawl_call_daily.sql`
- Modify: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java`
- Test: `analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java` (수정)
- Test: `analytics/src/test/java/com/celfit/analytics/mirror/CrawlCallDailyMirrorTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 `analytics.v_crawl_call_daily(job, called_on, calls)`
- Produces: analysis DB 테이블 `crawl_call_daily(job text, called_on date, calls bigint)` — Task 3이 읽는다.
  record `CrawlCallDaily(String job, LocalDate calledOn, long calls)`

**주의:** 이 태스크의 `CrawlCallDailyMirrorTest`가 중요한 이유는 **기존 미러 record 중 `LocalDate` 컴포넌트를 쓰는 것이 하나도 없어서**다. `MirrorJob`이 `rs.getObject(n, LocalDate.class)`로 읽는 경로가 `date` 컬럼에서 실제로 도는지 실증하지 않으면, 운영 미러가 처음 도는 순간 터진다.

- [ ] **Step 1: 실패하는 테스트 2개를 쓴다**

`FlywaySchemaTest.java`에 import와 테스트 메서드를 추가한다. import 블록(알파벳 순서 유지 — `ContentMetricSnapshot` 다음, `LandingStats` 앞):

```java
import com.celfit.contract.analysis.CrawlCallDaily;
```

기존 마지막 테스트 메서드 뒤에 추가:

```java
	@Test
	void crawl_call_daily_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("crawl_call_daily", CrawlCallDaily.class);
	}
```

`CrawlCallDailyMirrorTest.java` 신규 생성:

```java
package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.contract.analysis.CrawlCallDaily;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * crawl_call_daily 미러의 뷰→테이블 왕복 검증. 이 미러는 기존 미러 record 중 유일하게
 * {@code LocalDate} 컴포넌트를 갖는다 — MirrorJob이 date 컬럼을 rs.getObject(n, LocalDate.class)로
 * 읽고 다시 쓰는 경로가 실제로 도는지 여기서 실증한다(안 하면 운영 미러 첫 실행에서 터진다).
 * 뷰 정의 자체의 집계 규칙은 SQL 하니스(analytics/test/30_crawl_cost.test.sql)가 검증한다.
 */
@Testcontainers
class CrawlCallDailyMirrorTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	MirrorJob job;

	@BeforeEach
	void setUp() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		job = new MirrorJob(db, ds);
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		db.update("DROP TABLE IF EXISTS crawl_call_daily");
		db.update("DROP TABLE IF EXISTS crawl_call_src");
		db.update("CREATE SCHEMA analytics");
		// 소스는 최소 픽스처다 — 실제 30_crawl_cost.sql을 여기 복사하지 않는다. 그 집계 규칙은
		// SQL 하니스가 실 스키마로 검증하고, 여기서 볼 것은 date 컬럼의 JDBC 왕복뿐이다.
		// 뷰 정의를 복제하면 원본이 바뀌어도 이 테스트는 스테일한 사본으로 계속 통과한다.
		db.update("CREATE TABLE crawl_call_src (job text, called_on date, calls bigint)");
		db.update("""
				CREATE VIEW analytics.v_crawl_call_daily AS
				SELECT job, called_on, calls FROM crawl_call_src
				""");
		db.update("""
				CREATE TABLE crawl_call_daily (job text NOT NULL, called_on date NOT NULL,
				    calls bigint NOT NULL, PRIMARY KEY (job, called_on))
				""");
	}

	@Test
	void date_컬럼이_LocalDate로_왕복한다() {
		db.update("""
				INSERT INTO crawl_call_src VALUES
				 ('COLLECT', date '2026-06-05', 3), ('COLLECT', date '2026-06-06', 7)
				""");

		int moved = job.mirror(new MirrorSpec<>("v_crawl_call_daily", "crawl_call_daily", CrawlCallDaily.class));

		assertEquals(2, moved);
		// 타입을 명시해 읽는다 — queryForList(sql)는 date를 java.sql.Date로 돌려주므로
		// LocalDate와 직접 비교하면 왕복이 정상이어도 실패한다.
		assertEquals(List.of(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 6)),
				db.queryForList("SELECT called_on FROM crawl_call_daily ORDER BY called_on", LocalDate.class));
		assertEquals(List.of(3L, 7L),
				db.queryForList("SELECT calls FROM crawl_call_daily ORDER BY called_on", Long.class));
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :analytics:test --tests "com.celfit.analytics.mirror.CrawlCallDailyMirrorTest" --tests "com.celfit.analytics.mirror.FlywaySchemaTest"
```

기대: 컴파일 실패 — `cannot find symbol: class CrawlCallDaily`

- [ ] **Step 3: contract record를 만든다**

`contract-analysis/src/main/java/com/celfit/contract/analysis/CrawlCallDaily.java`:

```java
package com.celfit.contract.analysis;

import java.time.LocalDate;

/**
 * 크롤러 파이프라인 유료 요청 일별 집계 1행 (미러: analytics.v_crawl_call_daily → crawl_call_daily).
 * was 어드민 전역 크롤링 비용 API의 크롤러 몫 재료(설계 2026-08-13).
 *
 * <p>job은 crawler JobName의 이름 그대로(DISCOVER·QUALIFY·COLLECT·SIMILAR·REELS 등) — 라벨 매핑은
 * 소비자(was) 표현 계층 몫이고, 매핑에 없는 잡도 코드명으로 노출해 비용이 조용히 삼켜지지 않게 한다.
 *
 * <p>calledOn은 <b>KST 달력일</b> — monitoring의 brand_call_count.called_on·target_call_count.called_on과
 * 같은 시간대다(was가 세 소스를 한 경계로 합산하는 전제).
 *
 * <p>calls는 실제로 구매한 요청 수(crawl_run.request_count 합)이지 수집 건수가 아니다.
 * Apify 실행(결과 건당 과금)과 무료 소스는 뷰 단계에서 제외돼 여기 오지 않는다.
 */
public record CrawlCallDaily(String job, LocalDate calledOn, long calls) {
}
```

- [ ] **Step 4: 미러 테이블 DDL을 만든다**

파일명을 UTC로 채번한다(KST 금지 — 08-12 사고):

```bash
echo "analytics/src/main/resources/db/migration/analysis/V$(date -u +%Y%m%d%H%M%S)__crawl_call_daily.sql"
```

출력된 경로로 파일을 만들고 내용:

```sql
-- 크롤러 파이프라인 유료 요청 일별 집계 미러 테이블 (설계 2026-08-13) — expand 단계 신규 테이블.
-- 소스는 raw DB의 analytics.v_crawl_call_daily, 적재는 analytics 미러(TRUNCATE+INSERT 한 트랜잭션).
-- was 어드민 전역 크롤링 비용 API가 읽는 유일한 크롤러 표면이다(was는 raw DB에 접근하지 않는다).
--
-- 컬럼 이름·순서는 CrawlCallDaily record와 일치해야 한다(§4-3) — FlywaySchemaTest가 대조한다.
-- called_on은 KST 달력일(뷰가 AT TIME ZONE 'Asia/Seoul'로 변환).
CREATE TABLE crawl_call_daily (
    job       text   NOT NULL,   -- crawler JobName 이름 그대로
    called_on date   NOT NULL,   -- KST 달력일
    calls     bigint NOT NULL,   -- 구매한 요청 수(수집 건수 아님)
    PRIMARY KEY (job, called_on)
);
```

- [ ] **Step 5: 미러 등록부에 1행 추가한다**

`analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java` — import 추가(알파벳 순서):

```java
import com.celfit.contract.analysis.CrawlCallDaily;
```

`mirrorRegistry()`의 `List.of(...)` 마지막 원소 뒤에 추가:

```java
				new MirrorSpec<>("v_crawl_call_daily", "crawl_call_daily", CrawlCallDaily.class)));
```

(직전 원소 `new MirrorSpec<>("v_landing_stats", "landing_stats", LandingStats.class)` 끝의 `));`를 `),`로 바꾸고 위 줄을 붙인다.)

Javadoc 첫 줄도 갱신한다 — `서빙 뷰 3종(B1) + 인플루언서 상세 2종(C1) + 랜딩 통계 1종(P3)` →
`서빙 뷰 3종(B1) + 인플루언서 상세 2종(C1) + 랜딩 통계 1종(P3) + 크롤링 비용 1종(2026-08-13)`

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :analytics:test --tests "com.celfit.analytics.mirror.*"
```

기대: PASS (FlywaySchemaTest의 새 케이스 + CrawlCallDailyMirrorTest + 기존 MirrorJobTest)

- [ ] **Step 7: 커밋**

```bash
git add contract-analysis/src/main/java/com/celfit/contract/analysis/CrawlCallDaily.java analytics/src/main/resources/db/migration/analysis analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java analytics/src/test/java/com/celfit/analytics/mirror
git commit -m "feat(analytics): 크롤링 비용 일별 집계 미러 배선

contract record + analysis DDL + 등록부 1행. record가 LocalDate 컴포넌트를 갖는 첫 미러라
MirrorJob의 date 왕복 경로를 CrawlCallDailyMirrorTest로 실증한다 — 안 하면 운영 미러
첫 실행에서 드러난다."
```

---

### Task 3: was — 미러 테이블 조회 리포지토리

**Files:**
- Create: `was/src/main/java/com/celfit/was/crawlcost/CrawlCallDailyRepository.java`
- Test: `was/src/test/java/com/celfit/was/crawlcost/CrawlCallDailyRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2의 analysis DB 테이블 `crawl_call_daily`
- Produces:
  - `CrawlCallDailyRepository.findAll(): List<CrawlCallDailyRepository.JobCallDaily>`
  - `record JobCallDaily(String job, LocalDate calledOn, long calls)` (리포지토리 중첩 record)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`was/src/test/java/com/celfit/was/crawlcost/CrawlCallDailyRepositoryTest.java`:

```java
package com.celfit.was.crawlcost;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.crawlcost.CrawlCallDailyRepository.JobCallDaily;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 미러 테이블 조회 검증 — was는 crawl_call_daily를 analysis DB(기본 데이터소스)에서
 * 스키마 접두어 없이 읽는다(contents·account_summaries와 같은 자리). 테이블 자체는 analytics
 * 모듈의 Flyway 소관이라 was 테스트 DB에는 없다 — 여기서 직접 만든다(기존 어드민 조회
 * 테스트가 contents·accounts를 만드는 것과 같은 관용구).
 */
class CrawlCallDailyRepositoryTest extends IntegrationTest {

	@Autowired
	CrawlCallDailyRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	@BeforeEach
	void setUp() {
		jdbcClient.sql("DROP TABLE IF EXISTS crawl_call_daily").update();
		jdbcClient.sql("""
				CREATE TABLE crawl_call_daily (job text NOT NULL, called_on date NOT NULL,
				    calls bigint NOT NULL, PRIMARY KEY (job, called_on))
				""").update();
	}

	@Test
	void 미러_테이블이_비어_있으면_빈_목록이다() {
		assertThat(repository.findAll()).isEmpty();
	}

	@Test
	void 잡_날짜별_행을_그대로_읽는다() {
		jdbcClient.sql("""
				INSERT INTO crawl_call_daily VALUES
				 ('COLLECT', date '2026-08-13', 120),
				 ('COLLECT', date '2026-08-12', 98),
				 ('REELS',   date '2026-08-13', 7)
				""").update();

		List<JobCallDaily> rows = repository.findAll();

		assertThat(rows).containsExactlyInAnyOrder(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 120),
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 12), 98),
				new JobCallDaily("REELS", LocalDate.of(2026, 8, 13), 7));
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.crawlcost.CrawlCallDailyRepositoryTest"
```

기대: 컴파일 실패 — `package com.celfit.was.crawlcost does not exist`

- [ ] **Step 3: 리포지토리를 만든다**

`was/src/main/java/com/celfit/was/crawlcost/CrawlCallDailyRepository.java`:

```java
package com.celfit.was.crawlcost;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 크롤러 파이프라인 유료 요청 일별 집계 조회(설계 2026-08-13) — analytics 미러가 채우는
 * analysis DB의 crawl_call_daily. was는 이 테이블로만 크롤러 비용을 본다(raw DB 접근 금지).
 *
 * <p>기본 데이터소스(analysis DB)라 스키마 접두어가 없다 — contents·account_summaries와 같은 자리.
 *
 * <p>전량 조회인 이유: 행 수가 (잡 × 날짜)로 접혀 있어 파이프라인 5종 × 운영 일수 규모다.
 * 세 구간(전체·이번 달·오늘) 중 "전체"가 결국 전 기간을 요구하므로 기간 필터가 무의미하다.
 */
@Repository
public class CrawlCallDailyRepository {

	private final JdbcClient jdbcClient;

	public CrawlCallDailyRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<JobCallDaily> findAll() {
		return jdbcClient.sql("SELECT job, called_on, calls FROM crawl_call_daily")
				.query(JobCallDaily.class)
				.list();
	}

	/** crawl_call_daily 1행 — calledOn은 KST 달력일(미러 뷰가 변환), calls는 구매한 요청 수. */
	public record JobCallDaily(String job, LocalDate calledOn, long calls) {
	}
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.crawlcost.CrawlCallDailyRepositoryTest"
```

기대: PASS (2건)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/crawlcost was/src/test/java/com/celfit/was/crawlcost
git commit -m "feat(was): 크롤링 비용 미러 테이블 조회 리포지토리

analysis DB의 crawl_call_daily를 전량 읽는다 — 세 구간 중 '전체'가 전 기간을 요구해
기간 필터가 무의미하고, 행이 (잡 × 날짜)로 접혀 있어 전량이 싸다."
```

---

### Task 4: was — monitoring 콜의 전역 합계 조회

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/DailyCallSum.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (`findDailyCallCounts` 바로 뒤에 추가)
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringReadRepository.java` (`findDailyCallCounts` 바로 뒤에 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/GlobalCallSumRepositoryTest.java`

**Interfaces:**
- Consumes: monitoring DB `brand_call_count(brand_id, called_on, calls)` · `target_call_count(user_id, called_on, calls)`
- Produces:
  - `record DailyCallSum(LocalDate calledOn, long calls)` (`com.celfit.was.monitoring`)
  - `BrandReadRepository.sumDailyCallCounts(): List<DailyCallSum>`
  - `MonitoringReadRepository.sumDailyCallCounts(): List<DailyCallSum>`

**주의:** `sum(calls)`는 numeric을 돌려준다 — record 컴포넌트가 `long`이라 `::bigint` 캐스트가 없으면 런타임에 터진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`was/src/test/java/com/celfit/was/monitoring/GlobalCallSumRepositoryTest.java`:

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 모니터링 콜의 전역(전 브랜드·전 유저) 날짜별 합계 검증(설계 2026-08-13 §3-4).
 * 유저별 카드(AdminCrawlingUsageService)와 달리 연결 기간으로 자르지 않는다 — 공유 브랜드가
 * 유저마다 계상되는 이중 계상을 피하려면 브랜드 축에서 직접 합산해야 하기 때문이다.
 */
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.cron=-",
		"monitoring.digest.catchup-cron=-", "monitoring.recover.cron=-"})
class GlobalCallSumRepositoryTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	BrandReadRepository brandReads;
	@Autowired
	MonitoringReadRepository monitoringReads;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	DataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbcClient.sql("TRUNCATE brand_call_count").update();
		jdbcClient.sql("TRUNCATE target_call_count").update();
	}

	@Test
	void 브랜드_콜은_전_브랜드가_날짜별로_합산된다() {
		jdbcClient.sql("""
				INSERT INTO brand_call_count VALUES
				 (1, date '2026-08-13', 10), (2, date '2026-08-13', 5), (3, date '2026-08-12', 7)
				""").update();

		assertThat(brandReads.sumDailyCallCounts()).containsExactlyInAnyOrder(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 15),
				new DailyCallSum(LocalDate.of(2026, 8, 12), 7));
	}

	@Test
	void 캠페인_콜은_전_유저가_날짜별로_합산된다() {
		jdbcClient.sql("""
				INSERT INTO target_call_count VALUES
				 (100, date '2026-08-13', 3), (200, date '2026-08-13', 4), (100, date '2026-08-11', 9)
				""").update();

		assertThat(monitoringReads.sumDailyCallCounts()).containsExactlyInAnyOrder(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 7),
				new DailyCallSum(LocalDate.of(2026, 8, 11), 9));
	}

	@Test
	void 행이_없으면_빈_목록이다() {
		assertThat(brandReads.sumDailyCallCounts()).isEmpty();
		assertThat(monitoringReads.sumDailyCallCounts()).isEmpty();
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.monitoring.GlobalCallSumRepositoryTest"
```

기대: 컴파일 실패 — `cannot find symbol: class DailyCallSum`

- [ ] **Step 3: 공용 행 record를 만든다**

`was/src/main/java/com/celfit/was/monitoring/DailyCallSum.java`:

```java
package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * 날짜별 콜 합 1행 — 브랜드·캠페인 두 파이프라인의 전역 집계가 같은 모양이라 공용이다.
 * calledOn은 KST 달력일(쓰는 쪽인 monitoring이 KST로 적재 — brand_call_count DDL 참조).
 *
 * <p>유저별 조회({@link MonitoringReadRepository.UserCallDailyRow})와 형태는 같지만 의미가
 * 다르다 — 이쪽은 유저·브랜드 축을 이미 접은 전역 합이라 유저 귀속 정보가 없다.
 */
public record DailyCallSum(LocalDate calledOn, long calls) {
}
```

- [ ] **Step 4: 브랜드 전역 합계 메서드를 추가한다**

`BrandReadRepository.java`의 `findDailyCallCounts(Collection<Long> brandIds)` 바로 뒤에 추가:

```java
	/**
	 * 전 브랜드 날짜별 콜 합(설계 2026-08-13 §3-4) — 어드민 전역 크롤링 비용 API의 브랜드 몫.
	 * 유저별 카드({@link #findDailyCallCounts})와 달리 연결 기간으로 자르지 않는다: 공유 브랜드는
	 * 유저마다 계상되므로 유저별 값을 더하면 실제로 나간 돈보다 커진다. 전사 합계는 브랜드 축에서
	 * 직접 합산해야 정확하다.
	 *
	 * <p>sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수다(record 컴포넌트가 long).
	 */
	public List<DailyCallSum> sumDailyCallCounts() {
		return jdbc.sql("""
				SELECT called_on, sum(calls)::bigint AS calls
				FROM brand_call_count
				GROUP BY called_on
				""")
				.query(DailyCallSum.class)
				.list();
	}
```

- [ ] **Step 5: 캠페인 전역 합계 메서드를 추가한다**

`MonitoringReadRepository.java`의 `findDailyCallCounts(long userId)` 바로 뒤에 추가:

```java
	/**
	 * 전 유저 날짜별 콜 합(설계 2026-08-13 §3-4) — 어드민 전역 크롤링 비용 API의 캠페인·콘텐츠 몫.
	 * 한 콜이 여러 유저의 캠페인을 서빙하면 target_call_count에 유저마다 +1로 기록돼 있어, 이
	 * 합계 역시 그만큼 상한 쪽으로 치우친다(브랜드 공유와 같은 관점 — 계약 문서에 명시).
	 *
	 * <p>sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수다(record 컴포넌트가 long).
	 */
	public List<DailyCallSum> sumDailyCallCounts() {
		return jdbc.sql("""
				SELECT called_on, sum(calls)::bigint AS calls
				FROM target_call_count
				GROUP BY called_on
				""")
				.query(DailyCallSum.class)
				.list();
	}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.monitoring.GlobalCallSumRepositoryTest"
```

기대: PASS (3건)

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring
git add was/src/test/java/com/celfit/was/monitoring/GlobalCallSumRepositoryTest.java
git commit -m "feat(was): 모니터링 콜 전역 합계 조회 2종

브랜드·캠페인 콜을 날짜 축으로만 접는다 — 유저별 값을 더하면 공유 브랜드가 유저마다
계상돼 실제 지출보다 커지므로, 전사 합계는 반드시 원본 축에서 직접 합산한다."
```

---

### Task 5: was — 합산 서비스와 응답 계약

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/admin/PeriodSums.java`
- Modify: `was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingUsageService.java` (중첩 `PeriodSums` 제거)
- Create: `was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingCostSummary.java`
- Create: `was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingCostSummaryService.java`
- Test: `was/src/test/java/com/celfit/was/v1/admin/AdminCrawlingCostSummaryServiceTest.java`

**Interfaces:**
- Consumes: Task 3 `CrawlCallDailyRepository.findAll()` · Task 4 `BrandReadRepository.sumDailyCallCounts()` / `MonitoringReadRepository.sumDailyCallCounts()` · 기존 `AppSettingRepository.findValue/upsert`
- Produces:
  - `AdminCrawlingCostSummaryService.summary(): AdminCrawlingCostSummary`
  - `AdminCrawlingCostSummary(Totals totals, List<Segment> breakdown, BigDecimal unitPriceUsd, List<SourceStatus> sources)`
  - 중첩: `Totals(long totalCalls, long monthCalls, long todayCalls, BigDecimal totalCostUsd, BigDecimal monthCostUsd, BigDecimal todayCostUsd)` / `Segment(String key, String label, long totalCalls, long monthCalls, long todayCalls, BigDecimal totalCostUsd, BigDecimal monthCostUsd, BigDecimal todayCostUsd)` / `SourceStatus(String key, boolean available, LocalDate latestCallOn)`
  - `PeriodSums(LocalDate today, LocalDate monthStart)` with `add(LocalDate, long)` · `total()` · `month()` · `day()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`was/src/test/java/com/celfit/was/v1/admin/AdminCrawlingCostSummaryServiceTest.java`:

```java
package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.crawlcost.CrawlCallDailyRepository;
import com.celfit.was.crawlcost.CrawlCallDailyRepository.JobCallDaily;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.DailyCallSum;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.Segment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 전역 크롤링 비용 합산의 순수 로직 검증(설계 2026-08-13) — KST 경계(자정·월초), 세 소스 합산,
 * 잡 라벨 매핑, 열화(모니터링 비활성·조회 예외) 규칙을 고정 Clock으로 못 박는다.
 * 실 DB 왕복·인가는 AdminCrawlingCostSummaryIntegrationTest가 커버.
 */
class AdminCrawlingCostSummaryServiceTest {

	private final BrandReadRepository brandReads = mock(BrandReadRepository.class);
	private final MonitoringReadRepository monitoringReads = mock(MonitoringReadRepository.class);
	private final CrawlCallDailyRepository crawlReads = mock(CrawlCallDailyRepository.class);
	private final AppSettingRepository settings = mock(AppSettingRepository.class);

	@BeforeEach
	void setUp() {
		given(settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY))
				.willReturn(Optional.of("0.0006"));
		given(brandReads.sumDailyCallCounts()).willReturn(List.of());
		given(monitoringReads.sumDailyCallCounts()).willReturn(List.of());
		given(crawlReads.findAll()).willReturn(List.of());
	}

	/** 서비스가 KST로 재조정(clock.withZone)하므로 픽스처 시간대는 UTC로 준다. */
	private AdminCrawlingCostSummaryService serviceAt(String utcInstant) {
		return new AdminCrawlingCostSummaryService(Optional.of(brandReads), Optional.of(monitoringReads),
				crawlReads, settings, Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
	}

	private static Segment segment(List<Segment> breakdown, String key) {
		return breakdown.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
	}

	@Test
	void 데이터가_없어도_고정_7행과_0을_돌려준다() {
		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		assertThat(summary.breakdown()).extracting(Segment::key).containsExactly(
				"BRAND_MONITORING", "CAMPAIGN_MONITORING", "CRAWLER_DISCOVER", "CRAWLER_QUALIFY",
				"CRAWLER_COLLECT", "CRAWLER_SIMILAR", "CRAWLER_REELS");
		assertThat(summary.totals().totalCalls()).isZero();
		assertThat(summary.totals().totalCostUsd()).isEqualByComparingTo("0");
		assertThat(summary.unitPriceUsd()).isEqualByComparingTo("0.0006");
	}

	@Test
	void 세_소스를_KST_구간별로_합산하고_단가를_곱한다() {
		// 고정 시각 2026-08-13 10:00 KST → 오늘=08-13, 이번 달 시작=08-01.
		given(brandReads.sumDailyCallCounts()).willReturn(List.of(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 10),    // 오늘·이달·전체
				new DailyCallSum(LocalDate.of(2026, 8, 1), 90),     // 이달·전체
				new DailyCallSum(LocalDate.of(2026, 7, 31), 400))); // 전체만
		given(monitoringReads.sumDailyCallCounts()).willReturn(List.of(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 5)));
		given(crawlReads.findAll()).willReturn(List.of(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 100),
				new JobCallDaily("REELS", LocalDate.of(2026, 7, 20), 1000)));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		Segment brand = segment(summary.breakdown(), "BRAND_MONITORING");
		assertThat(brand.totalCalls()).isEqualTo(500);
		assertThat(brand.monthCalls()).isEqualTo(100);
		assertThat(brand.todayCalls()).isEqualTo(10);
		assertThat(segment(summary.breakdown(), "CRAWLER_COLLECT").todayCalls()).isEqualTo(100);
		assertThat(segment(summary.breakdown(), "CRAWLER_REELS").monthCalls()).isZero();
		assertThat(segment(summary.breakdown(), "CRAWLER_REELS").totalCalls()).isEqualTo(1000);

		// totals = breakdown 합: 전체 500+5+100+1000 = 1605, 이달 100+5+100 = 205, 오늘 10+5+100 = 115.
		assertThat(summary.totals().totalCalls()).isEqualTo(1605);
		assertThat(summary.totals().monthCalls()).isEqualTo(205);
		assertThat(summary.totals().todayCalls()).isEqualTo(115);
		// 비용은 반올림 없이 곱셈 결과 그대로 — 1605 × 0.0006 = 0.9630.
		assertThat(summary.totals().totalCostUsd()).isEqualByComparingTo("0.9630");
	}

	@Test
	void KST_자정_경계가_오늘과_어제를_가른다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 7)));

		// 2026-08-13 23:59:59 KST — 아직 08-13.
		assertThat(segment(serviceAt("2026-08-13T14:59:59Z").summary().breakdown(), "CRAWLER_COLLECT")
				.todayCalls()).isEqualTo(7);
		// 2초 뒤 = 08-14 00:00:01 KST — 어제 몫이 되어 today에서 빠진다.
		assertThat(segment(serviceAt("2026-08-13T15:00:01Z").summary().breakdown(), "CRAWLER_COLLECT")
				.todayCalls()).isZero();
	}

	@Test
	void KST_월초_경계가_이번_달과_지난달을_가른다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 1), 7)));

		// 2026-08-31 23:59:59 KST — 8월분이라 month에 든다.
		assertThat(segment(serviceAt("2026-08-31T14:59:59Z").summary().breakdown(), "CRAWLER_COLLECT")
				.monthCalls()).isEqualTo(7);
		// 2초 뒤 = 09-01 00:00:01 KST — 지난달이 되어 빠진다.
		assertThat(segment(serviceAt("2026-08-31T15:00:01Z").summary().breakdown(), "CRAWLER_COLLECT")
				.monthCalls()).isZero();
	}

	@Test
	void 매핑에_없는_잡도_코드명으로_노출된다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new JobCallDaily("NEWJOB", LocalDate.of(2026, 8, 13), 42)));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		Segment unknown = segment(summary.breakdown(), "CRAWLER_NEWJOB");
		assertThat(unknown.label()).isEqualTo("NEWJOB");
		assertThat(unknown.totalCalls()).isEqualTo(42);
		assertThat(summary.totals().totalCalls()).isEqualTo(42);   // 합계에서 삼켜지지 않는다
	}

	@Test
	void 모니터링_비활성이면_열화_표시하고_크롤러_몫은_그대로_낸다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 3)));
		AdminCrawlingCostSummaryService service = new AdminCrawlingCostSummaryService(
				Optional.empty(), Optional.empty(), crawlReads, settings,
				Clock.fixed(Instant.parse("2026-08-13T01:00:00Z"), ZoneOffset.UTC));

		AdminCrawlingCostSummary summary = service.summary();

		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("MONITORING");
			assertThat(s.available()).isFalse();
			assertThat(s.latestCallOn()).isNull();
		});
		assertThat(segment(summary.breakdown(), "BRAND_MONITORING").totalCalls()).isZero();
		assertThat(segment(summary.breakdown(), "CRAWLER_COLLECT").totalCalls()).isEqualTo(3);
	}

	@Test
	void 모니터링_조회가_터져도_500이_아니라_열화로_접는다() {
		given(brandReads.sumDailyCallCounts())
				.willThrow(new DataAccessResourceFailureException("monitoring DB 불통"));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("MONITORING");
			assertThat(s.available()).isFalse();
		});
		assertThat(summary.totals().totalCalls()).isZero();
	}

	@Test
	void 소스별_최신_날짜를_신선도로_노출한다() {
		given(brandReads.sumDailyCallCounts()).willReturn(List.of(
				new DailyCallSum(LocalDate.of(2026, 8, 11), 1),
				new DailyCallSum(LocalDate.of(2026, 8, 13), 1)));
		given(crawlReads.findAll()).willReturn(List.of(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 10), 1)));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("MONITORING");
			assertThat(s.latestCallOn()).isEqualTo(LocalDate.of(2026, 8, 13));
		});
		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("CRAWLER");
			assertThat(s.available()).isTrue();
			assertThat(s.latestCallOn()).isEqualTo(LocalDate.of(2026, 8, 10));
		});
	}

	@Test
	void 단가가_숫자가_아니면_기본값으로_폴백한다() {
		given(settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY))
				.willReturn(Optional.of("abc"));

		assertThat(serviceAt("2026-08-13T01:00:00Z").summary().unitPriceUsd())
				.isEqualByComparingTo(AdminCrawlingUsageService.DEFAULT_UNIT_PRICE);
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.v1.admin.AdminCrawlingCostSummaryServiceTest"
```

기대: 컴파일 실패 — `cannot find symbol: class AdminCrawlingCostSummaryService`

- [ ] **Step 3: 세 구간 누적기를 공용 클래스로 추출한다**

`was/src/main/java/com/celfit/was/v1/admin/PeriodSums.java` 신규:

```java
package com.celfit.was.v1.admin;

import java.time.LocalDate;

/**
 * 세 구간(전체·이번 달·오늘) 누적기 — 유저별 사용량(2026-08-12)과 전역 비용(2026-08-13)이 공유한다.
 * 경계는 전부 KST 달력일이고, 미래 날짜 행(이론상)은 month·day에서 제외된다.
 *
 * <p>원래 AdminCrawlingUsageService의 중첩 클래스였으나 두 번째 소비자가 생겨 끌어올렸다 —
 * 구간 판정이 두 벌이 되면 한쪽만 고쳐지는 순간 같은 화면의 두 숫자가 다른 경계를 쓰게 된다.
 */
class PeriodSums {

	private final LocalDate today;
	private final LocalDate monthStart;
	private long total;
	private long month;
	private long day;

	PeriodSums(LocalDate today, LocalDate monthStart) {
		this.today = today;
		this.monthStart = monthStart;
	}

	void add(LocalDate calledOn, long calls) {
		total += calls;
		if (!calledOn.isBefore(monthStart) && !calledOn.isAfter(today)) {
			month += calls;
		}
		if (calledOn.equals(today)) {
			day += calls;
		}
	}

	/**
	 * 이미 구간별로 접힌 값을 그대로 더한다 — 총계용(전역 비용 API). 날짜를 다시 판정하지
	 * 않으므로 breakdown 합과 totals가 구조적으로 어긋날 수 없다.
	 */
	void addPreAggregated(long total, long month, long day) {
		this.total += total;
		this.month += month;
		this.day += day;
	}

	long total() {
		return total;
	}

	long month() {
		return month;
	}

	long day() {
		return day;
	}
}
```

`AdminCrawlingUsageService.java`에서 파일 하단의 `private static final class PeriodSums { ... }` 블록 전체를 **삭제**하고, `usageFor`의 반환문을 접근자 호출로 바꾼다:

```java
		return new AdminCrawlingUsage(sums.total(), sums.month(), sums.day(), unitPrice);
```

(`sums.total` → `sums.total()` 등 필드 직접 접근이 접근자 호출로 바뀌는 것 외에 로직 변화는 없다.)

- [ ] **Step 4: 응답 계약 record를 만든다**

`was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingCostSummary.java`:

```java
package com.celfit.was.v1.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /v1/admin/crawling-cost/summary 응답(설계 2026-08-13 §2) — envelope data.
 *
 * <p>집계 단위는 <b>유료 요청 1회</b>다(수집 건수 아님). 모니터링 몫은 Hiker HTTP 교환 1번,
 * 크롤러 몫은 crawl_run이 구매한 요청 수 — 집계 지점이 다를 뿐 단위는 같다.
 *
 * <p><b>이 값은 유저별 카드(GET /v1/admin/users/{id}/crawling-usage)의 합과 일치하지 않는다</b> —
 * 공유 브랜드는 유저마다 계상되므로 유저별 합이 더 크다. 실제로 나간 돈은 이쪽이다.
 */
public record AdminCrawlingCostSummary(Totals totals, List<Segment> breakdown,
		BigDecimal unitPriceUsd, List<SourceStatus> sources) {

	/** 세 구간 총계 — 항상 breakdown 전 행의 합과 일치한다(같은 누적기 산출). */
	public record Totals(long totalCalls, long monthCalls, long todayCalls,
			BigDecimal totalCostUsd, BigDecimal monthCostUsd, BigDecimal todayCostUsd) {
	}

	/**
	 * 파이프라인 1구간. 콜이 0이어도 행을 유지한다 — 행이 사라지면 프론트가 "파이프라인이
	 * 없어졌다"와 "안 썼다"를 구분할 수 없다.
	 */
	public record Segment(String key, String label, long totalCalls, long monthCalls, long todayCalls,
			BigDecimal totalCostUsd, BigDecimal monthCostUsd, BigDecimal todayCostUsd) {
	}

	/**
	 * 소스별 가용성·신선도. available=false는 "0"이 아니라 "모름"이라는 신호다.
	 * latestCallOn은 그 소스가 가진 최신 KST 달력일 — 크롤러 몫은 미러(하루 1회)를 타므로
	 * 이 값이 어디까지 반영됐는지를 드러낸다.
	 */
	public record SourceStatus(String key, boolean available, LocalDate latestCallOn) {
	}
}
```

- [ ] **Step 5: 합산 서비스를 만든다**

`was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingCostSummaryService.java`:

```java
package com.celfit.was.v1.admin;

import com.celfit.was.crawlcost.CrawlCallDailyRepository;
import com.celfit.was.crawlcost.CrawlCallDailyRepository.JobCallDaily;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.DailyCallSum;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.Segment;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.SourceStatus;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.Totals;
import com.celfit.was.v1.common.KstTimestamps;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 어드민 전역 크롤링 비용 집계(설계 2026-08-13) — 서비스 전체가 크롤링에 쓴 돈을 세 구간
 * (전체·이번 달·오늘, KST)과 파이프라인별로 분해한다.
 *
 * <p><b>모니터링 몫</b>은 brand_call_count·target_call_count를 <b>원본 축에서 직접</b> 합산한다.
 * 유저별 카드({@link AdminCrawlingUsageService})처럼 연결 기간으로 자른 값을 더하면, 공유
 * 브랜드가 유저마다 계상돼 실제 지출보다 큰 수가 나온다. 이 차이는 계약 문서에 명시돼 있다.
 *
 * <p><b>크롤러 몫</b>은 analytics 미러가 채우는 crawl_call_daily를 읽는다 — was는 raw DB에
 * 접근할 수 없고(시스템 경계), 모듈 간 HTTP도 쓰지 않는다(ARCHITECTURE §2). 대가는 신선도로,
 * 미러 주기(04:30 KST) 이후의 콜은 다음 미러까지 보이지 않는다 —
 * {@code sources[].latestCallOn}이 그 지연을 드러낸다.
 *
 * <p><b>404·500을 내지 않는다</b>: 못 읽은 구간은 available=false로 표시하고 집계를 0으로 둔다.
 * 비용 관측이 어드민 화면을 통째로 죽이면 안 된다.
 */
@Service
public class AdminCrawlingCostSummaryService {

	private static final Logger log = LoggerFactory.getLogger(AdminCrawlingCostSummaryService.class);

	private static final String BRAND_KEY = "BRAND_MONITORING";
	private static final String CAMPAIGN_KEY = "CAMPAIGN_MONITORING";
	private static final String CRAWLER_PREFIX = "CRAWLER_";

	/** 표시 순서 고정 + 데이터가 없어도 행을 유지하기 위한 골격(설계 §2). */
	private static final List<String> BASE_KEYS = List.of(BRAND_KEY, CAMPAIGN_KEY,
			"CRAWLER_DISCOVER", "CRAWLER_QUALIFY", "CRAWLER_COLLECT", "CRAWLER_SIMILAR", "CRAWLER_REELS");

	private static final Map<String, String> LABELS = Map.of(
			BRAND_KEY, "브랜드 태그 모니터링",
			CAMPAIGN_KEY, "캠페인·콘텐츠 모니터링",
			"CRAWLER_DISCOVER", "해시태그 발굴",
			"CRAWLER_QUALIFY", "프로필 판정",
			"CRAWLER_COLLECT", "게시물 수집",
			"CRAWLER_SIMILAR", "유사 계정 발굴",
			"CRAWLER_REELS", "릴스 수집");

	private final Optional<BrandReadRepository> brandReads;
	private final Optional<MonitoringReadRepository> monitoringReads;
	private final CrawlCallDailyRepository crawlReads;
	private final AppSettingRepository settings;
	private final Clock clock;

	public AdminCrawlingCostSummaryService(Optional<BrandReadRepository> brandReads,
			Optional<MonitoringReadRepository> monitoringReads, CrawlCallDailyRepository crawlReads,
			AppSettingRepository settings, Clock clock) {
		this.brandReads = brandReads;   // monitoring.enabled=false면 비어 있다 — 모니터링 구간은 열화
		this.monitoringReads = monitoringReads;
		this.crawlReads = crawlReads;
		this.settings = settings;
		this.clock = clock;
	}

	public AdminCrawlingCostSummary summary() {
		BigDecimal unitPrice = currentUnitPrice();
		LocalDate today = LocalDate.now(clock.withZone(KstTimestamps.KST));
		LocalDate monthStart = today.withDayOfMonth(1);

		Map<String, PeriodSums> sums = new LinkedHashMap<>();
		for (String key : BASE_KEYS) {
			sums.put(key, new PeriodSums(today, monthStart));
		}

		MonitoringResult monitoring = readMonitoring(sums, today, monthStart);
		LocalDate crawlerLatest = readCrawler(sums, today, monthStart);

		List<Segment> breakdown = new ArrayList<>();
		PeriodSums totals = new PeriodSums(today, monthStart);
		for (Map.Entry<String, PeriodSums> entry : sums.entrySet()) {
			PeriodSums s = entry.getValue();
			breakdown.add(new Segment(entry.getKey(), LABELS.getOrDefault(entry.getKey(), fallbackLabel(entry.getKey())),
					s.total(), s.month(), s.day(),
					cost(unitPrice, s.total()), cost(unitPrice, s.month()), cost(unitPrice, s.day())));
			// 총계는 구간 값을 그대로 더한다 — 날짜 재판정 없이 breakdown과 항상 일치시키기 위해.
			totals.addPreAggregated(s.total(), s.month(), s.day());
		}

		return new AdminCrawlingCostSummary(
				new Totals(totals.total(), totals.month(), totals.day(),
						cost(unitPrice, totals.total()), cost(unitPrice, totals.month()),
						cost(unitPrice, totals.day())),
				breakdown, unitPrice,
				List.of(new SourceStatus("MONITORING", monitoring.available(), monitoring.latestCallOn()),
						new SourceStatus("CRAWLER", true, crawlerLatest)));
	}

	/**
	 * 모니터링 두 파이프라인을 읽어 누적한다. monitoring.enabled=false(로컬 기본)거나 조회가
	 * 터지면 열화로 접는다 — 부가 서브시스템의 불능이 어드민 비용 화면 전체를 죽이면 안 된다.
	 */
	private MonitoringResult readMonitoring(Map<String, PeriodSums> sums, LocalDate today, LocalDate monthStart) {
		if (brandReads.isEmpty() || monitoringReads.isEmpty()) {
			return new MonitoringResult(false, null);
		}
		try {
			LocalDate latest = null;
			for (DailyCallSum row : brandReads.get().sumDailyCallCounts()) {
				sums.get(BRAND_KEY).add(row.calledOn(), row.calls());
				latest = later(latest, row.calledOn());
			}
			for (DailyCallSum row : monitoringReads.get().sumDailyCallCounts()) {
				sums.get(CAMPAIGN_KEY).add(row.calledOn(), row.calls());
				latest = later(latest, row.calledOn());
			}
			return new MonitoringResult(true, latest);
		} catch (DataAccessException e) {
			log.warn("모니터링 콜 집계 조회 실패 — 해당 구간을 열화 표시한다", e);
			// 부분 누적분을 남기면 "0인지 일부인지" 알 수 없는 수가 나간다 — 0으로 되돌린다.
			sums.put(BRAND_KEY, new PeriodSums(today, monthStart));
			sums.put(CAMPAIGN_KEY, new PeriodSums(today, monthStart));
			return new MonitoringResult(false, null);
		}
	}

	/**
	 * 크롤러 미러를 읽어 잡별로 누적한다. 매핑에 없는 잡도 CRAWLER_&lt;JOB&gt; 구간을 새로 만들어
	 * 노출한다 — 매핑 누락이 비용을 조용히 삼키면 안 된다.
	 */
	private LocalDate readCrawler(Map<String, PeriodSums> sums, LocalDate today, LocalDate monthStart) {
		LocalDate latest = null;
		for (JobCallDaily row : crawlReads.findAll()) {
			String key = CRAWLER_PREFIX + row.job();
			sums.computeIfAbsent(key, k -> new PeriodSums(today, monthStart)).add(row.calledOn(), row.calls());
			latest = later(latest, row.calledOn());
		}
		return latest;
	}

	/** 매핑에 없는 잡의 표시명 — 접두어를 뗀 잡 코드명 그대로. */
	private static String fallbackLabel(String key) {
		return key.startsWith(CRAWLER_PREFIX) ? key.substring(CRAWLER_PREFIX.length()) : key;
	}

	private static LocalDate later(LocalDate current, LocalDate candidate) {
		return current == null || candidate.isAfter(current) ? candidate : current;
	}

	/** 반올림하지 않는다 — 서버가 반올림하면 구간 합과 총합이 어긋난다(설계 §2). */
	private static BigDecimal cost(BigDecimal unitPrice, long calls) {
		return unitPrice.multiply(BigDecimal.valueOf(calls));
	}

	/** 단가 정본은 유저별 카드와 같은 키 하나 — 두 화면의 단가가 갈라질 수 없다. */
	private BigDecimal currentUnitPrice() {
		Optional<String> stored = settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY);
		if (stored.isEmpty()) {
			return AdminCrawlingUsageService.DEFAULT_UNIT_PRICE;
		}
		try {
			return new BigDecimal(stored.get());
		} catch (NumberFormatException e) {
			log.warn("crawling.unit-price-usd 값이 숫자가 아님({}) — 기본값 폴백", stored.get());
			return AdminCrawlingUsageService.DEFAULT_UNIT_PRICE;
		}
	}

	/** 모니터링 구간 읽기 결과 — available=false면 집계는 0이고 "모름"이라는 뜻이다. */
	private record MonitoringResult(boolean available, LocalDate latestCallOn) {
	}
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.v1.admin.AdminCrawlingCostSummaryServiceTest" --tests "com.celfit.was.v1.admin.AdminCrawlingUsageServiceTest"
```

기대: 양쪽 PASS — 두 번째는 `PeriodSums` 추출이 기존 동작을 바꾸지 않았음을 확인하는 회귀 게이트다.

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/admin was/src/test/java/com/celfit/was/v1/admin/AdminCrawlingCostSummaryServiceTest.java
git commit -m "feat(was): 전역 크롤링 비용 합산 서비스

모니터링 2종 + 크롤러 미러를 세 구간(KST)으로 접고 전역 단가를 곱한다. totals는 날짜를
다시 판정하지 않고 구간 값을 그대로 더해 breakdown 합과 구조적으로 일치시킨다.
모니터링 불능(비활성·조회 예외)은 500이 아니라 available=false 열화로 접는다.
세 구간 누적기는 유저별 카드와 공용(PeriodSums 추출) — 경계 판정이 두 벌이 되면
한쪽만 고쳐지는 순간 같은 화면의 두 숫자가 다른 경계를 쓰게 된다."
```

---

### Task 6: was — 엔드포인트 노출과 실 스택 검증

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingCostController.java`
- Test: `was/src/test/java/com/celfit/was/AdminCrawlingCostSummaryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 5 `AdminCrawlingCostSummaryService.summary()`
- Produces: `GET /v1/admin/crawling-cost/summary` → `ApiResponse<AdminCrawlingCostSummary>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`was/src/test/java/com/celfit/was/AdminCrawlingCostSummaryIntegrationTest.java`:

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 전역 크롤링 비용 API 실 DB 통합 검증(설계 2026-08-13) — 인가, 세 소스 합산, 0 구간의 행 유지,
 * 단가 PUT 반영을 실 스택으로 고정한다. KST 경계·열화 규칙의 순수 로직은
 * AdminCrawlingCostSummaryServiceTest가 고정 Clock으로 커버.
 *
 * <p>Clock을 2026-08-13 10:00 KST로 고정한다 — 시드 날짜가 달력에 무관하게 결정적이 되게.
 * crawl_call_daily는 analytics 모듈 Flyway 소관이라 was 테스트 DB에 없다 — 여기서 만든다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.cron=-",
		"monitoring.digest.catchup-cron=-", "monitoring.recover.cron=-",
		"was.rate-limit.per-minute=100"})
class AdminCrawlingCostSummaryIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	/** 고정 시각: 2026-08-13 10:00 KST (= 01:00Z). 오늘=08-13, 이번 달 시작=08-01. */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-08-13T01:00:00Z"), ZoneOffset.UTC);
		}
	}

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "admin-cost@test.io";
	private static final String USER_EMAIL = "user-cost@test.io";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	DataSource dataSource;
	@Autowired
	PasswordEncoder passwordEncoder;

	private Cookie adminSession;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbcClient.sql("TRUNCATE brand_call_count").update();
		jdbcClient.sql("TRUNCATE target_call_count").update();
		jdbcClient.sql("DROP TABLE IF EXISTS crawl_call_daily").update();
		jdbcClient.sql("""
				CREATE TABLE crawl_call_daily (job text NOT NULL, called_on date NOT NULL,
				    calls bigint NOT NULL, PRIMARY KEY (job, called_on))
				""").update();
		jdbcClient.sql("DELETE FROM app.users").update();
		jdbcClient.sql("""
				INSERT INTO app.app_setting (key, value) VALUES ('crawling.unit-price-usd', '0.0006')
				ON CONFLICT (key) DO UPDATE SET value = '0.0006'
				""").update();

		insertUser(ADMIN_EMAIL, "ADMIN");
		insertUser(USER_EMAIL, "USER");
		adminSession = login(ADMIN_EMAIL);
	}

	@Test
	void 미인증은_401이고_일반_유저는_403이다() throws Exception {
		mockMvc.perform(get("/v1/admin/crawling-cost/summary"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(login(USER_EMAIL)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void 데이터가_없어도_200과_고정_7행을_돌려준다() throws Exception {
		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.totals.totalCalls").value(0))
				.andExpect(jsonPath("$.data.breakdown.length()").value(7))
				.andExpect(jsonPath("$.data.breakdown[0].key").value("BRAND_MONITORING"))
				.andExpect(jsonPath("$.data.breakdown[0].label").value("브랜드 태그 모니터링"))
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.0006))
				.andExpect(jsonPath("$.data.sources[?(@.key == 'MONITORING')].available").value(true));
	}

	@Test
	void 세_소스를_합산하고_구간별로_쪼갠다() throws Exception {
		// 브랜드: 오늘 10 + 이달 90 + 지난달 400. 서로 다른 브랜드가 같은 날 쌓여도 합쳐진다.
		jdbcClient.sql("""
				INSERT INTO brand_call_count VALUES
				 (1, date '2026-08-13', 6), (2, date '2026-08-13', 4),
				 (1, date '2026-08-01', 90), (1, date '2026-07-31', 400)
				""").update();
		// 캠페인: 오늘 5.
		jdbcClient.sql("INSERT INTO target_call_count VALUES (100, date '2026-08-13', 5)").update();
		// 크롤러: COLLECT 오늘 100, REELS 지난달 1000.
		jdbcClient.sql("""
				INSERT INTO crawl_call_daily VALUES
				 ('COLLECT', date '2026-08-13', 100), ('REELS', date '2026-07-20', 1000)
				""").update();

		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(adminSession))
				.andExpect(status().isOk())
				// 전체 500+5+100+1000, 이달 100+5+100, 오늘 10+5+100
				.andExpect(jsonPath("$.data.totals.totalCalls").value(1605))
				.andExpect(jsonPath("$.data.totals.monthCalls").value(205))
				.andExpect(jsonPath("$.data.totals.todayCalls").value(115))
				// 1605 × 0.0006 = 0.9630
				.andExpect(jsonPath("$.data.totals.totalCostUsd").value(0.9630))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'BRAND_MONITORING')].totalCalls").value(500))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CAMPAIGN_MONITORING')].todayCalls").value(5))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CRAWLER_COLLECT')].todayCalls").value(100))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CRAWLER_REELS')].totalCalls").value(1000))
				// 안 쓴 구간도 행이 남는다.
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CRAWLER_DISCOVER')].totalCalls").value(0))
				.andExpect(jsonPath("$.data.sources[?(@.key == 'CRAWLER')].latestCallOn").value("2026-08-13"));
	}

	@Test
	void 단가_수정은_즉시_이_API에도_반영된다() throws Exception {
		jdbcClient.sql("INSERT INTO crawl_call_daily VALUES ('COLLECT', date '2026-08-13', 1000)").update();

		mockMvc.perform(put("/v1/admin/crawling-cost/unit-price").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"unitPriceUsd\":0.002}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.002))
				.andExpect(jsonPath("$.data.totals.totalCostUsd").value(2.000));
	}

	// --- 헬퍼 (AdminCrawlingUsageIntegrationTest와 동일 구현) ---

	private long insertUser(String email, String role) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, :hash, :role, '테스터', 'brand', true, true, true)
				RETURNING id
				""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.query(Long.class)
				.single();
	}

	/** 세션 쿠키 이름은 hypenow-session이다(SESSION 아님 — 커스텀 쿠키 설정). */
	private Cookie login(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.AdminCrawlingCostSummaryIntegrationTest"
```

기대: 404 (엔드포인트 없음) 또는 컨텍스트 로딩 실패

- [ ] **Step 3: 컨트롤러에 엔드포인트를 추가한다**

`AdminCrawlingCostController.java` — 생성자와 필드를 확장한다:

```java
	private final AdminCrawlingUsageService service;
	private final AdminCrawlingCostSummaryService summaryService;

	public AdminCrawlingCostController(AdminCrawlingUsageService service,
			AdminCrawlingCostSummaryService summaryService) {
		this.service = service;
		this.summaryService = summaryService;
	}
```

기존 `usage` 메서드 뒤에 추가:

```java
	/**
	 * 전역 크롤링 비용(설계 2026-08-13) — 서비스 전체의 콜·비용을 세 구간과 파이프라인별로.
	 * 유저 스코프가 없어 파라미터가 없다. 이 GET도 404·500을 내지 않는다 —
	 * 못 읽은 구간은 응답의 sources[].available=false로 드러난다.
	 */
	@GetMapping("/v1/admin/crawling-cost/summary")
	public ApiResponse<AdminCrawlingCostSummary> summary() {
		return ApiResponse.ok(summaryService.summary());
	}
```

클래스 Javadoc도 갱신한다 — `어드민 크롤링 비용 카드 API 2종(2026-08-12 프론트 요청서)` →
`어드민 크롤링 비용 API 3종 — 유저별 사용량·전역 단가 수정(2026-08-12) + 전역 비용 요약(2026-08-13)`

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.AdminCrawlingCostSummaryIntegrationTest" --tests "com.celfit.was.AdminCrawlingUsageIntegrationTest"
```

기대: 양쪽 PASS — 두 번째는 컨트롤러 생성자 변경이 기존 2종을 깨지 않았음을 확인한다.

- [ ] **Step 5: was 모듈 전체 회귀를 돌린다**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test
```

기대: BUILD SUCCESSFUL. 대량 실패가 보이면 먼저 `DOCKER_HOST`부터 확인한다(테스트 결함으로 오진하기 쉬운 실패 양상).

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/admin/AdminCrawlingCostController.java was/src/test/java/com/celfit/was/AdminCrawlingCostSummaryIntegrationTest.java
git commit -m "feat(was): GET /v1/admin/crawling-cost/summary 노출

유저 스코프가 없는 전역 조회라 파라미터가 없다. 단가는 유저별 카드와 같은 키를 읽어
PUT 한 번이 두 화면에 동시에 반영된다."
```

---

### Task 7: 문서 갱신과 PR

**Files:**
- Modify: `docs/superpowers/specs/2026-08-13-admin-global-crawling-cost-design.md` (상태 헤더)
- Modify: `DECISIONS.md` (맨 위에 1행)
- Move: `docs/superpowers/plans/2026-08-13-admin-global-crawling-cost.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: 스펙 상태 헤더를 갱신한다**

`docs/superpowers/specs/2026-08-13-admin-global-crawling-cost-design.md` 4번째 줄:

```markdown
> 상태: ✅ 구현됨 (2026-08-13) · 서버 반영은 develop→staging 승격 후
```

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 1행을 추가한다**

`| 날짜 | 결정 | 근거/상세 |` 헤더 바로 다음 줄(기존 2026-08-13 ETag 행 **위**)에 삽입:

```markdown
| 2026-08-13 | **전역 크롤링 비용은 콜 원본 축에서 합산하고, 크롤러 몫은 analytics 미러로 건너온다** — 유저별 카드(08-12)의 전역판. ① 전사 합계를 유저별 값의 합으로 만들면 공유 브랜드가 유저마다 계상돼 실제 지출보다 커진다 → `brand_call_count`를 브랜드 축에서 직접 합산(유저별 카드 합 > 이 합계가 정상). ② 크롤러 파이프라인 몫(`crawl_run.request_count`)은 was가 raw DB를 못 읽으므로 `v_crawl_call_daily` → `crawl_call_daily` 미러로 옮긴다 — crawler 내부 HTTP API는 3-tier "DB로만" 원칙을 깨는 데다 **스테이징에 test-crawler가 없어 dev-api에서 영구 부분 응답**이 된다. 대가인 신선도(04:30 미러)는 `sources[].latestCallOn`으로 드러낸다. ③ 유료 공급자는 Hiker 단일로 접어 공급자 축·신규 단가 PUT 없이 기존 키를 쓴다 — Apify(결과 건당 과금)·무료 소스는 `request_count IS NOT NULL`(>0) 한 조건으로 제외 | [설계](docs/superpowers/specs/2026-08-13-admin-global-crawling-cost-design.md) · 신규 `GET /v1/admin/crawling-cost/summary` · 미러 record가 `LocalDate`를 갖는 첫 사례라 왕복을 `CrawlCallDailyMirrorTest`로 실증 |
```

- [ ] **Step 3: 전체 테스트를 돌린다 (PR 직전 1회)**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew test
```

기대: BUILD SUCCESSFUL. 이 명령은 모듈 4개가 각자 Testcontainers Postgres를 띄우므로 로컬에선 느리다 — PR 직전에만 돌린다.

- [ ] **Step 4: 완료된 계획 문서를 아카이브로 옮긴다**

```bash
mkdir -p docs/superpowers/plans/archive && git mv docs/superpowers/plans/2026-08-13-admin-global-crawling-cost.md docs/superpowers/plans/archive/
```

- [ ] **Step 5: 커밋하고 PR을 연다**

```bash
git add DECISIONS.md docs/superpowers/specs/2026-08-13-admin-global-crawling-cost-design.md docs/superpowers/plans
git commit -m "docs: 전역 크롤링 비용 API 결정 기록 · 스펙 상태 갱신 · 계획 아카이브"
git push -u origin HEAD
```

```bash
gh pr create --base develop --title "feat: 어드민 전역 크롤링 비용 API" --body "$(cat <<'EOF'
## 요약

서비스 전체의 크롤링 콜·비용을 세 구간(전체·이번 달·오늘, KST)과 파이프라인별로 분해하는
`GET /v1/admin/crawling-cost/summary`를 추가한다. 유저별 카드(08-12)의 전역판.

## 설계 판단

- **전사 합계는 콜 원본 축에서 합산한다** — 유저별 값을 더하면 공유 브랜드가 유저마다 계상돼
  실제 지출보다 커진다. 유저별 카드 합 > 이 합계가 정상이며, 계약 문서에 명시했다.
- **크롤러 몫은 analytics 미러 경유** — was는 raw DB를 못 읽는다. crawler 내부 HTTP API는
  3-tier "DB로만" 원칙을 깨고, 스테이징에 test-crawler가 없어 dev-api에서 영구 부분 응답이 된다.
  대가인 신선도(04:30 미러)는 `sources[].latestCallOn`으로 드러낸다.
- **유료 공급자는 Hiker 단일** — Apify(결과 건당 과금)·무료 소스는 `request_count > 0` 한
  조건으로 빠진다. 새 단가 엔드포인트 없이 기존 키를 쓴다.
- **404·500을 내지 않는다** — 못 읽은 구간은 `sources[].available=false`.

## 검증

- SQL 하니스: KST 경계·NULL/0 제외·status 미필터·잡별 그룹핑
- `CrawlCallDailyMirrorTest`: 미러 record가 `LocalDate`를 갖는 첫 사례라 date 왕복을 실증
- was 단위/통합: KST 자정·월초 경계, 세 소스 합산, 열화(모니터링 비활성·조회 예외), 인가, 단가 PUT 반영

설계: `docs/superpowers/specs/2026-08-13-admin-global-crawling-cost-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**배포 주의:** 이 PR은 스키마 마이그레이션(analysis DB `crawl_call_daily`)을 동반하므로 **핫픽스 경로(main 직행) 금지**다. develop → staging → main 정규 승격으로만 나간다. 미러 테이블은 신규 생성(expand)이라 롤링 배포 중 신구 코드 공존에 안전하다 — was 신 코드만 이 테이블을 읽고, 구 코드는 모른다.
