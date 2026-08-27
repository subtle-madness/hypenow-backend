# 6.21 발굴 목록 사전집계 + count 통합 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/influencers` 캐시 미스 4~11초를 수백 ms 이하로 — 파생 집계 3종을 matview로 사전계산하고 count 쿼리를 본 쿼리에 통합한다.

**Architecture:** analytics 소유 analysis DB에 matview 3종(`account_beauty_ratio` 전환 + `account_category_share`·`account_sponsored_counts` 신설, unique index + REFRESH CONCURRENTLY). analytics 잡 훅이 입력 변경 잡(MIRROR·ANALYZE·LATE_BACKFILL_ANALYZE·BATCH_COLLECT) 후 갱신. was는 상관 서브쿼리·풀집계 대신 matview 조인 + `count(*) OVER ()`.

**Tech Stack:** Spring Boot 4.1 / Java 21 / Flyway / JdbcClient / Testcontainers(PostgreSQL)

**스펙:** [2026-08-27-discovery-precompute-design.md](../specs/2026-08-27-discovery-precompute-design.md)

## Global Constraints

- 신규 Flyway는 UTC 타임스탬프 채번: 이 계획은 `V20260827045100__discovery_precompute_matviews.sql` 고정 사용.
- `DROP VIEW`는 `-- allow-destructive: <사유>` 주석 필수(migration-guard).
- 테스트는 모듈 단위: `./gradlew :analytics:test`, `./gradlew :was:test`. **이 머신은 Docker Desktop이 정본 — `DOCKER_HOST`를 export 하지 않는다.**
- 커밋 메시지 한국어, prefix `feat(analytics):`/`feat(was):`/`docs:`.
- 게이트 임계 20, MIN_ANALYZED 8, MIN_BEAUTY_RATIO 20.0 — 기존 상수 그대로.

---

### Task 1: analytics 마이그레이션 — matview 3종 + 정의 검증 테스트

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V20260827045100__discovery_precompute_matviews.sql`
- Test: `analytics/src/test/java/com/celfit/analytics/mirror/DiscoveryPrecomputeMatviewsTest.java`

**Interfaces:**
- Produces: matview `account_beauty_ratio(account_handle, analyzed_count, beauty_count)`, `account_category_share(account_handle, main_category, pct int)`, `account_sponsored_counts(account_handle, cnt)` — Task 2·3이 이름으로 참조.

- [ ] **Step 1: 실패하는 테스트 작성** — `AccountCategoryStatsViewTest` 패턴(프레시 컨테이너 + Flyway 전체 적용) 그대로:

```java
package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 발굴 사전집계 matview 3종(V20260827045100) — 정의·round 산식·refresh 반영을 검증한다.
 * 마이그레이션은 WITH DATA로 만들지만 시드가 그 뒤라, 갱신은 DerivedViewRefresher로 수행
 * (CONCURRENTLY 경로 자체를 태운다 — unique index 누락 시 여기서 즉시 실패).
 */
@Testcontainers
class DiscoveryPrecomputeMatviewsTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;

	@BeforeAll
	static void seed() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis").load().migrate();
		db = new JdbcTemplate(ds);
		// acc_a 뷰티·분류 5건: makeup 4 + skincare 1 (80%/20% — 게이트 경계), 협찬 2건.
		// a6는 is_beauty=false(비율 분모에는 잡히고 share 모수에서 제외), x1은 미분석.
		db.update("""
				INSERT INTO account_content_series
				  (short_code, account_handle, posted_at, content_type, views, likes, comments, sponsored)
				VALUES ('a1','acc_a',now(),'reels',100,10,1,false),
				       ('a2','acc_a',now(),'reels',200,20,2,false),
				       ('a3','acc_a',now(),'reels',300,30,3,false),
				       ('a4','acc_a',now(),'feed',NULL,40,4,false),
				       ('a5','acc_a',now(),'reels',500,50,5,false),
				       ('a6','acc_a',now(),'reels',600,60,6,false),
				       ('x1','acc_x',now(),'reels',700,70,7,false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, model, main_category, is_beauty, ad_type)
				VALUES ('a1','t','makeup',true,'sponsored'),
				       ('a2','t','makeup',true,NULL),
				       ('a3','t','makeup',true,NULL),
				       ('a4','t','makeup',true,'sponsored'),
				       ('a5','t','skincare',true,NULL),
				       ('a6','t',NULL,false,NULL)""");
		new DerivedViewRefresher(ds).refresh();
	}

	@Test
	void 뷰티_비율은_분석건수와_뷰티건수를_계정별로_집계한다() {
		Map<String, Object> row = db.queryForMap(
				"SELECT analyzed_count, beauty_count FROM account_beauty_ratio WHERE account_handle = 'acc_a'");
		assertEquals(6L, row.get("analyzed_count"));
		assertEquals(5L, row.get("beauty_count"));
	}

	@Test
	void 카테고리_비중은_게이트와_같은_round_산식이다() {
		List<Map<String, Object>> rows = db.queryForList("""
				SELECT main_category, pct FROM account_category_share
				WHERE account_handle = 'acc_a' ORDER BY main_category""");
		// makeup 4/5 → 80, skincare 1/5 → 20 (경계값: 게이트 ≥20 통과)
		assertEquals(List.of(Map.of("main_category", "makeup", "pct", 80),
				Map.of("main_category", "skincare", "pct", 20)), rows);
	}

	@Test
	void 협찬_수는_ad_type_sponsored만_센다() {
		assertEquals(Integer.valueOf(2), db.queryForObject(
				"SELECT cnt FROM account_sponsored_counts WHERE account_handle = 'acc_a'", Integer.class));
		assertEquals(Integer.valueOf(0), db.queryForObject(
				"SELECT count(*) FROM account_sponsored_counts WHERE account_handle = 'acc_x'", Integer.class));
	}
}
```

`DerivedViewRefresher`는 Task 2에서 만들지만 이 테스트가 먼저 참조한다 — Task 1에서는 테스트 컴파일을 위해 Step 3에서 마이그레이션과 함께 최소 구현(클래스 뼈대 + refresh())을 만든다. (Task 1·2는 같은 세션에서 연달아 실행할 것.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.mirror.DiscoveryPrecomputeMatviewsTest"`
Expected: 컴파일 실패(DerivedViewRefresher 없음) → Step 3 후 재실행 시 마이그레이션 미존재면 FAIL.

- [ ] **Step 3: 마이그레이션 + refresher 최소 구현 작성**

`V20260827045100__discovery_precompute_matviews.sql`:

```sql
-- 6.21 발굴 목록 사전집계 (2026-08-27, 스펙 2026-08-27-discovery-precompute-design.md).
-- 발굴 쿼리가 요청마다 하던 집계 3종(뷰티 비율·카테고리 비중 게이트·협찬 수)을 matview로
-- 내려 캐시 미스 4~11초를 수백 ms로 줄인다. 입력(account_content_series·content_analyses)은
-- 나이트리 잡 체인에서만 변하므로 잡 훅 refresh(DerivedViewRefresher)로 신선도 손실 없음.
-- unique index는 REFRESH CONCURRENTLY 필수 조건.

-- allow-destructive: 같은 이름·컬럼의 materialized view로 즉시 재생성 (사전집계 전환, V45 대체)
DROP VIEW account_beauty_ratio;
CREATE MATERIALIZED VIEW account_beauty_ratio AS
SELECT s.account_handle,
       count(*) FILTER (WHERE an.is_beauty IS NOT NULL) AS analyzed_count,
       count(*) FILTER (WHERE an.is_beauty IS TRUE)     AS beauty_count
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
GROUP BY s.account_handle
WITH DATA;
CREATE UNIQUE INDEX ux_account_beauty_ratio ON account_beauty_ratio (account_handle);
COMMENT ON MATERIALIZED VIEW account_beauty_ratio IS
  '계정별 뷰티 게시물 비율 원시 카운트(V45 뷰의 matview 전환) — 임계값 정책은 was가 적용.
   갱신: analytics DerivedViewRefresher(입력 변경 잡 후크).';

-- 계정×대분류 비중 — 발굴 게이트(V1InfluencerDiscoveryRepository mainCategory ≥20)와
-- 동일 분모(is_beauty IS TRUE AND main_category IS NOT NULL 창 내 게시물)·동일 round 산식.
-- 게이트 원식 round(100.0*count FILTER(main=:mc)/count(*))에서 :mc 0건이면 0(≥20 false)
-- ↔ 여기선 행 부재(EXISTS false) — 결과 동치.
CREATE MATERIALIZED VIEW account_category_share AS
SELECT s.account_handle, an.main_category,
       round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY s.account_handle))::int AS pct
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
WHERE an.is_beauty IS TRUE AND an.main_category IS NOT NULL
GROUP BY s.account_handle, an.main_category
WITH DATA;
CREATE UNIQUE INDEX ux_account_category_share
    ON account_category_share (account_handle, main_category);
COMMENT ON MATERIALIZED VIEW account_category_share IS
  '계정×대분류(slug) 비중 — 발굴 mainCategory 게이트 전용 사전집계. 카드 표시용 믹스는
   account_category_stats(라벨 기준)가 따로 있다. 갱신: DerivedViewRefresher.';

CREATE MATERIALIZED VIEW account_sponsored_counts AS
SELECT s.account_handle, count(*) AS cnt
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
GROUP BY s.account_handle
WITH DATA;
CREATE UNIQUE INDEX ux_account_sponsored_counts ON account_sponsored_counts (account_handle);
COMMENT ON MATERIALIZED VIEW account_sponsored_counts IS
  '계정별 협찬(ad_type=sponsored) 게시물 수 — 발굴 목록 sp 조인 사전집계. 갱신: DerivedViewRefresher.';
```

`analytics/src/main/java/com/celfit/analytics/mirror/DerivedViewRefresher.java`:

```java
package com.celfit.analytics.mirror;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 발굴 사전집계 matview 갱신(V20260827045100 3종) — 입력(account_content_series·
 * content_analyses)을 쓰는 잡(MIRROR·ANALYZE·LATE_BACKFILL_ANALYZE·BATCH_COLLECT) 완료 후
 * AnalyticsJobService가 호출한다. CONCURRENTLY라 갱신 중에도 was 조회가 막히지 않는다
 * (unique index 필수 — 마이그레이션이 보장).
 */
public class DerivedViewRefresher {

	private static final Logger log = LoggerFactory.getLogger(DerivedViewRefresher.class);

	private static final List<String> MATVIEWS = List.of(
			"account_beauty_ratio", "account_category_share", "account_sponsored_counts");

	private final JdbcTemplate analysis;

	public DerivedViewRefresher(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public void refresh() {
		for (String view : MATVIEWS) {
			long start = System.nanoTime();
			analysis.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
			log.info("파생 matview 갱신 {} ({}ms)", view, (System.nanoTime() - start) / 1_000_000);
		}
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.mirror.DiscoveryPrecomputeMatviewsTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 기존 analytics 마이그레이션 스위트 회귀 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.mirror.*"`
Expected: PASS — 특히 `FlywaySchemaTest`·`AccountCategoryStatsViewTest`가 새 마이그레이션 포함 전체 적용을 재검증.

- [ ] **Step 6: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis/V20260827045100__discovery_precompute_matviews.sql \
        analytics/src/main/java/com/celfit/analytics/mirror/DerivedViewRefresher.java \
        analytics/src/test/java/com/celfit/analytics/mirror/DiscoveryPrecomputeMatviewsTest.java
git commit -m "feat(analytics): 발굴 사전집계 matview 3종 + DerivedViewRefresher"
```

---

### Task 2: AnalyticsJobService 잡 훅 — 입력 변경 잡 후 refresh

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java` (analyticsJobService 빈)
- Modify: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java` (refresher 빈)
- Test: `analytics/src/test/java/com/celfit/analytics/admin/AnalyticsJobServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `DerivedViewRefresher.refresh()`.
- Produces: `AnalyticsJobService` 생성자 마지막 인자에 `DerivedViewRefresher` 추가 — 시그니처 변경.

- [ ] **Step 1: 실패하는 테스트 작성** — `AnalyticsJobServiceTest`에 필드·케이스 추가 (기존 `service()` 헬퍼에 mock refresher 주입):

```java
private final com.celfit.analytics.mirror.DerivedViewRefresher derivedViewRefresher =
		mock(com.celfit.analytics.mirror.DerivedViewRefresher.class);
// service() 마지막 인자에 derivedViewRefresher 추가

@Test
void 입력_변경_잡_성공_후_파생_matview를_갱신한다() {
	when(analyzeJob.run()).thenReturn(new JobResult(1, 0, false));
	service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
	org.mockito.Mockito.verify(derivedViewRefresher).refresh();
}

@Test
void 입력_무관_잡은_파생_matview를_갱신하지_않는다() {
	when(archiveJob.run()).thenReturn(new JobResult(1, 0, false));
	service().trigger(JobName.ARCHIVE, TriggerType.MANUAL);
	org.mockito.Mockito.verify(derivedViewRefresher, org.mockito.Mockito.never()).refresh();
}

@Test
void 갱신_실패는_잡_결과를_오염시키지_않는다() {
	when(analyzeJob.run()).thenReturn(new JobResult(1, 0, false));
	org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(derivedViewRefresher).refresh();
	service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
	assertThat(history.recent().getFirst().outcome()).isEqualTo(RunHistory.Outcome.SUCCESS);
}
```

(`history.recent()` 접근자 이름은 RunHistory 실물에 맞출 것 — 기존 테스트에서 이력 검증하는 관용구를 재사용.)

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AnalyticsJobServiceTest"`
Expected: 컴파일 실패(생성자 인자 수 불일치).

- [ ] **Step 3: 구현**

`AnalyticsJobService`: 필드·생성자 인자 `DerivedViewRefresher derivedViewRefresher` 추가, `trigger()`의 `result = run(job);` 직후에 호출 추가:

```java
result = run(job);
refreshDerivedViews(job);
```

```java
/** 파생 matview 입력을 쓰는 잡 — 성공(부분 실패 포함) 후 사전집계를 갱신한다. */
private static final java.util.Set<JobName> DERIVED_INPUT_JOBS = java.util.EnumSet.of(
		JobName.MIRROR, JobName.ANALYZE, JobName.LATE_BACKFILL_ANALYZE, JobName.BATCH_COLLECT);

private void refreshDerivedViews(JobName job) {
	if (!DERIVED_INPUT_JOBS.contains(job)) return;
	try {
		derivedViewRefresher.refresh();
	} catch (Exception e) {
		// 잡 자체는 성공 — 다음 입력 잡 후크가 재시도 기회라 이력은 오염시키지 않는다.
		log.error("파생 matview 갱신 실패", e);
	}
}
```

`MirrorConfig`에 빈 추가:

```java
@Bean
public DerivedViewRefresher derivedViewRefresher(
		@Qualifier("analysisDataSource") DataSource analysisDataSource) {
	return new DerivedViewRefresher(analysisDataSource);
}
```

`AdminConfig.analyticsJobService(...)`: 파라미터에 `DerivedViewRefresher derivedViewRefresher` 추가, 생성자 호출 마지막 인자로 전달.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AnalyticsJobServiceTest"`
Expected: PASS (기존 + 신규 3건)

- [ ] **Step 5: analytics 모듈 전체 테스트**

Run: `./gradlew :analytics:test`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java \
        analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java \
        analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java \
        analytics/src/test/java/com/celfit/analytics/admin/AnalyticsJobServiceTest.java
git commit -m "feat(analytics): 입력 변경 잡 완료 후 파생 matview 자동 갱신 훅"
```

---

### Task 3: was 리포지토리 전환 — matview 조인 + count(\*) OVER () 통합

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepository.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepositoryTest.java`
- Modify(생성자 인자 추가만): `V1InfluencerDiscoveryControllerTest.java`, `V1InfluencerDiscoveryAssemblerTest.java`, `V1InfluencerDiscoveryPageServiceTest.java`, `was/src/test/java/com/celfit/was/cache/CacheIntegrationTest.java`, `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportControllerTest.java`

**Interfaces:**
- Consumes: Task 1 matview 3종의 이름·컬럼.
- Produces: `CardRow`에 마지막 컴포넌트 `Long totalCount` 추가. `findCards(q)` 각 행의 totalCount = 필터 전체 건수(LIMIT 전). `findCardsByHandles`는 totalCount `null`. `countCards(q)`는 유지(0행 폴백 전용 — Task 4).

- [ ] **Step 1: 실패하는 테스트 작성** — `V1InfluencerDiscoveryRepositoryTest`:

(1) `setUpTables()`의 `DROP VIEW IF EXISTS account_beauty_ratio` 앞에 두 줄 추가:

```java
jdbcTemplate.execute("DROP VIEW IF EXISTS account_category_share");
jdbcTemplate.execute("DROP VIEW IF EXISTS account_sponsored_counts");
```

(2) V45 사본 뷰 생성 직후에 신규 두 뷰의 사본 추가(리포지토리 SQL은 뷰/matview를 구분하지 않으므로 테스트는 뷰로 충분 — 정의는 V20260827045100과 동일하게 유지할 것):

```java
// analytics V20260827045100 정의 그대로 — mainCategory 게이트·sp 조인이 참조한다.
jdbcTemplate.execute("""
		CREATE VIEW account_category_share AS
		SELECT s.account_handle, an.main_category,
		       round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY s.account_handle))::int AS pct
		FROM account_content_series s
		JOIN content_analyses an ON an.short_code = s.short_code
		WHERE an.is_beauty IS TRUE AND an.main_category IS NOT NULL
		GROUP BY s.account_handle, an.main_category
		""");
jdbcTemplate.execute("""
		CREATE VIEW account_sponsored_counts AS
		SELECT s.account_handle, count(*) AS cnt
		FROM account_content_series s
		JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
		GROUP BY s.account_handle
		""");
```

(3) 테스트 케이스 추가:

```java
@Test
void findCards의_totalCount는_countCards와_일치한다() {
	for (V1InfluencerDiscoveryQuery q : List.of(
			all(),
			query(null, "skincare", null, null, null, null, null, null, null, null, null),
			query(null, null, null, null, "10k-30k", null, null, null, null, null, null),
			query(null, null, null, null, null, null, "1-2", null, null, null, null))) {
		var rows = repository.findCards(q);
		if (!rows.isEmpty()) {
			assertThat(rows.getFirst().totalCount()).isEqualTo(repository.countCards(q));
		}
	}
}

@Test
void totalCount는_LIMIT과_무관하게_필터_전체_건수다() {
	var q = query(null, null, null, null, null, null, null, null, null, 1, 0);
	var rows = repository.findCards(q);
	assertThat(rows).hasSize(1);
	assertThat(rows.getFirst().totalCount()).isEqualTo(repository.countCards(all()));
}

@Test
void findCardsByHandles의_totalCount는_null이다() {
	var rows = repository.findCardsByHandles(List.of("glow"));
	assertThat(rows.getFirst().totalCount()).isNull();
}
```

(기존 mainCategory·sponsored 필터 테스트들은 그대로 새 SQL의 동치 검증 역할을 한다 — 수정 금지.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepositoryTest"`
Expected: 컴파일 실패(`totalCount()` 없음).

- [ ] **Step 3: 리포지토리 구현**

`V1InfluencerDiscoveryRepository` 변경 4곳:

(a) `FROM_JOINS_TEMPLATE`/`FROM_JOINS_BY_HANDLES`/`FROM_JOINS` 3개 상수를 단일 상수로 교체(sp 푸시다운 주석 포함 삭제 — 사전집계로 존재 이유 소멸):

```java
// sp(협찬 수)·br(뷰티 비율)·게이트(account_category_share)는 사전집계 matview
// (analytics V20260827045100, DerivedViewRefresher가 입력 변경 잡 후 갱신) — 요청 시점
// 풀 집계·핸들 푸시다운이 더는 필요 없다(2026-08-27 사전집계 전환 스펙 §5).
// cp(최신 태그라인)·sp·br는 q·sponsored 필터·게이트가 참조하므로 count 쿼리에도 함께 붙는다.
private static final String FROM_JOINS = """

		FROM account_summaries su
		JOIN accounts a ON a.handle = su.handle
		LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
		LEFT JOIN LATERAL (SELECT aa.tagline FROM account_analyses aa
		                   WHERE aa.handle = su.handle
		                   ORDER BY aa.analyzed_at DESC LIMIT 1) cp ON true
		LEFT JOIN account_sponsored_counts sp ON sp.account_handle = su.handle
		LEFT JOIN account_beauty_ratio br ON br.account_handle = su.handle""";
```

(b) `findCards` SELECT 마지막에 `,\n       count(*) OVER () AS total_count` 추가 (avg_hype_score_precise 뒤). `findCardsByHandles` SELECT 마지막에 `, NULL::bigint AS total_count` 추가하고 `FROM_JOINS_BY_HANDLES` 대신 `FROM_JOINS` 사용(Javadoc의 푸시다운 설명 문장 삭제).

(c) `build()`의 mainCategory 블록 교체(상관 서브쿼리 → 사전집계 EXISTS):

```java
if (q.mainCategory() != null) {
	// 비중 임계값 매칭(포함 여부 아님) — 산식은 account_category_share가 사전계산
	// (categoryShares.pct와 동일 분모·round — matview 정의 주석 참조). :mc 0건이면
	// 행 부재 = EXISTS false — 기존 COALESCE(...,0)>=20 false와 동치.
	where.append("""

			  AND EXISTS (SELECT 1 FROM account_category_share cs
			              WHERE cs.account_handle = su.handle
			                AND cs.main_category = :mainCategory AND cs.pct >= 20)""");
	params.put("mainCategory", q.mainCategory());
}
```

(d) `CardRow`에 마지막 컴포넌트 추가:

```java
// findCards의 count(*) OVER () — 필터 전체 건수(LIMIT 전). findCardsByHandles는 null
// (2026-08-27 count 통합 — countCards는 0행 폴백 전용으로 존치).
Long totalCount
```

클래스 상단 Javadoc의 "본 쿼리 1회 + count 1회" 문구를 "본 쿼리 1회(total 윈도우 포함, 0행일 때만 count 폴백)"로 갱신.

- [ ] **Step 4: 컴파일 깨진 CardRow 생성자 5개 테스트 파일 수정** — 각 `new CardRow(...)` 호출 마지막 인자에 `, null` 추가 (`V1InfluencerDiscoveryControllerTest` 1곳, `V1InfluencerDiscoveryAssemblerTest` 2곳, `V1InfluencerDiscoveryPageServiceTest` 1곳, `CacheIntegrationTest` 1곳, `V2InfluencerReportControllerTest` 2곳). 단 `V1InfluencerDiscoveryPageServiceTest`의 픽스처는 Task 4에서 total 검증에 쓰므로 `, 42L`처럼 구체값이 필요하면 그때 조정.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepositoryTest"`
Expected: PASS (기존 전부 + 신규 3건 — 기존 케이스가 새 SQL의 동치를 증명)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepository.java was/src/test
git commit -m "feat(was): 발굴 목록을 사전집계 matview 조인으로 전환, total을 본 쿼리에 통합"
```

---

### Task 4: 페이지 서비스 — 첫 행 total 사용 + 0행 폴백

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryPageService.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryPageServiceTest.java`

**Interfaces:**
- Consumes: Task 3의 `CardRow.totalCount()`, `countCards(q)`.
- Produces: `DiscoveryPage(cards, total)` 의미 불변(외부 계약 무변경).

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 `V1InfluencerDiscoveryPageServiceTest`의 mock 관용구에 맞춰:

```java
@Test
void total은_findCards_첫_행의_totalCount를_쓴다() {
	// 픽스처 CardRow의 totalCount를 42L로 설정
	when(repository.findCards(any())).thenReturn(List.of(cardRow("glow" /* totalCount=42L */)));
	// ... 보강 4쿼리 stub은 기존 관용구 그대로 ...
	assertThat(service.page(query).total()).isEqualTo(42L);
	verify(repository, never()).countCards(any());
}

@Test
void 빈_페이지는_countCards로_폴백한다() {
	when(repository.findCards(any())).thenReturn(List.of());
	when(repository.countCards(any())).thenReturn(7L);
	assertThat(service.page(query).total()).isEqualTo(7L);
}
```

(정확한 stub·픽스처 형태는 파일의 기존 테스트를 따른다 — mock 필드명·query 헬퍼 재사용.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageServiceTest"`
Expected: FAIL (`countCards`가 항상 호출됨).

- [ ] **Step 3: 구현** — `page()`의 return 교체:

```java
// total은 본 쿼리 윈도우(count(*) OVER ())에서 — countCards 재실행은 0행(offset 초과·공집합)
// 폴백뿐이다(2026-08-27 count 통합).
long total = rows.isEmpty() ? repository.countCards(q) : rows.getFirst().totalCount();
return new DiscoveryPage(cards, total);
```

- [ ] **Step 4: 테스트 통과 + was 모듈 전체**

Run: `./gradlew :was:test`
Expected: PASS 전체.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryPageService.java \
        was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryPageServiceTest.java
git commit -m "feat(was): 발굴 페이지 total을 본 쿼리 윈도우로 — countCards는 0행 폴백만"
```

---

### Task 5: 운영 동치 대조 + EXPLAIN 실측 (읽기 전용 — 코드 변경 없음)

**Files:** 산출물은 스크래치패드에만 (커밋 없음).

- [ ] **Step 1: 동치 대조 SQL 작성** — matview 정의를 동일 SQL의 CTE로 인라인한 신 쿼리 vs 구 쿼리. 전 mainCategory 값에 대해 핸들 집합 차이를 센다:

```sql
WITH cs AS (SELECT s.account_handle, an.main_category,
                   round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY s.account_handle))::int AS pct
            FROM account_content_series s
            JOIN content_analyses an ON an.short_code = s.short_code
            WHERE an.is_beauty IS TRUE AND an.main_category IS NOT NULL
            GROUP BY s.account_handle, an.main_category),
mains AS (SELECT DISTINCT main_category AS mc FROM content_analyses WHERE main_category IS NOT NULL),
old_q AS (SELECT m.mc, su.handle FROM mains m, account_summaries su
          WHERE COALESCE((SELECT round(100.0 * count(*) FILTER (WHERE an.main_category = m.mc)
                                       / NULLIF(count(*), 0))
                          FROM account_content_series s
                          JOIN content_analyses an ON an.short_code = s.short_code
                          WHERE s.account_handle = su.handle
                            AND an.is_beauty IS TRUE AND an.main_category IS NOT NULL), 0) >= 20),
new_q AS (SELECT m.mc, su.handle FROM mains m, account_summaries su
          WHERE EXISTS (SELECT 1 FROM cs WHERE cs.account_handle = su.handle
                          AND cs.main_category = m.mc AND cs.pct >= 20))
SELECT (SELECT count(*) FROM (SELECT * FROM old_q EXCEPT SELECT * FROM new_q) d1) AS only_old,
       (SELECT count(*) FROM (SELECT * FROM new_q EXCEPT SELECT * FROM old_q) d2) AS only_new;
```

- [ ] **Step 2: 운영 실행** — `ssh ubuntu@155.248.187.106 'docker exec -i deploy-postgres-1 psql -U celfit -d analysis' < <파일>`
Expected: `only_old = 0, only_new = 0`. 불일치면 **중단하고 산식 재검토** (구현 수정 전 원인 규명 — systematic-debugging).

- [ ] **Step 3: 신 쿼리 EXPLAIN ANALYZE** — Task 3의 최종 findCards SQL에서 matview 3종을 위 CTE로 대체(MATERIALIZED 힌트)한 버전으로 emj7j4s6 파라미터(cleansing/오일·밤/클렌징오일/hype) 실행. CTE 집계 비용(~1s)은 matview에서는 0이므로, **CTE 제외 본체 실행 시간**이 수백 ms 이하임을 확인해 기록.

- [ ] **Step 4: 결과를 PR 본문용으로 기록** — before(11.4s 실측)/after(예상 실측), 동치 대조 0/0.

---

### Task 6: 문서 갱신 + PR

**Files:**
- Modify: `ARCHITECTURE.md` (§3 "파생 뷰(미러 아님)" 항목), `DECISIONS.md` (맨 위 추가)
- Move: `docs/superpowers/plans/2026-08-27-discovery-precompute.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: ARCHITECTURE.md §3** — "현재 `account_category_stats`(계정 카테고리 믹스 — V35) 하나." 문장을 다음으로 교체:

```
현재 `account_category_stats`(계정 카테고리 믹스 — V35, 뷰) + 발굴 사전집계 matview 3종
(`account_beauty_ratio`·`account_category_share`·`account_sponsored_counts` —
V20260827045100, 입력 변경 잡 후 DerivedViewRefresher가 REFRESH CONCURRENTLY).
```

- [ ] **Step 2: DECISIONS.md 맨 위에 항목 추가**

```
## 2026-08-27 — 6.21 발굴 목록 사전집계 + count 통합

캐시 미스 4~11초(운영 emj7j4s6 실측 11.4s)의 원인이 요청당 상관 서브쿼리 6,210회×2 +
파생 뷰 풀집계×2로 확정되어(스펙 2026-08-27-discovery-precompute-design.md), 파생 집계
3종을 matview로 사전계산(analytics 소유·잡 훅 refresh)하고 total은 count(*) OVER ()로
본 쿼리에 통합했다. countCards는 0행 폴백 전용. 게이트·협찬수 반영 시점이 "조회 즉시"→
"입력 변경 잡 완료 직후"가 됐지만 입력이 그 잡에서만 변해 관찰 가능한 차이 없음.
```

- [ ] **Step 3: plan 문서 아카이브 이동 + 커밋**

```bash
git mv docs/superpowers/plans/2026-08-27-discovery-precompute.md docs/superpowers/plans/archive/
git add ARCHITECTURE.md DECISIONS.md
git commit -m "docs: 발굴 사전집계 결정 기록·ARCHITECTURE 파생 뷰 갱신, plan 아카이브"
```

- [ ] **Step 4: PR 생성** (develop 대상)

```bash
git push -u origin feature/root-cause-analysis-ee98af
gh pr create --base develop --title "feat: 6.21 발굴 목록 사전집계 matview + count 통합 (캐시 미스 11.4s→수백 ms)" --body "<Task 5 실측 포함 본문>"
```
