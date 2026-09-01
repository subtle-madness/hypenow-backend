# 유사 인플루언서 F&B 개방 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** F&B 인플루언서 리포트의 유사 추천이 F&B 계정끼리 동작하게 — 카테고리 스탯·피어 뷰를 축 인지로 재구성하고 was 유사 쿼리에 축 파라미터를 단다.

**Architecture:** analytics 마이그레이션 1개가 `account_category_stats`(axis 컬럼 추가)·`account_peer_axis_stats`(신설, 계정×축)·`account_peer_stats`(구 이름 = 뷰티 투영)를 재정의한다. was는 대상 계정 축을 `findFnbAxis`로 파생해 `findSimilarHandles`·`findShares`에 전달한다. 뷰티 축 행은 기존과 동일 집합(스펙 §5 불변 증명).

**Tech Stack:** PostgreSQL 뷰(Flyway analysis 마이그레이션), Spring JdbcClient, Testcontainers.

**스펙:** [docs/superpowers/specs/2026-09-01-similar-influencer-fnb-axis-design.md](../specs/2026-09-01-similar-influencer-fnb-axis-design.md)

## Global Constraints

- 마이그레이션 채번은 **UTC 타임스탬프**: `date -u +%Y%m%d%H%M%S` (CLAUDE.md — KST 채번 금지)
- 구 뷰 DROP+재생성에는 `-- allow-destructive: <사유>` 주석 필수 (migration-guard)
- 브랜치 `feat/similar-influencer-fnb-axis`(이미 생성됨, origin/develop 기반) — **핫픽스 경로 금지**(마이그레이션 동반), develop 대상 PR
- 테스트는 모듈 단위: `./gradlew :analytics:test --tests "..."`, `./gradlew :was:test --tests "..."`
- 로컬 도커는 Docker Desktop, `DOCKER_HOST` 미설정이 정답
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(모듈):` 식, 커밋 말미 Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
- 기존 축 파생 관용구: 뷰티 `COALESCE(a.beauty, true)` / F&B `COALESCE(a.fnb, false)` — 방향 뒤집지 말 것

---

### Task 1: analytics — 축 인지 뷰 마이그레이션

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V<UTC>__similar_axis_views.sql` (`<UTC>` = `date -u +%Y%m%d%H%M%S` 실행값)
- Test: `analytics/src/test/java/com/celfit/analytics/mirror/AccountPeerStatsViewTest.java` (수정)

**Interfaces:**
- Produces: 뷰 `account_category_stats(account_handle, main_group, content_count, axis)` /
  `account_peer_axis_stats(handle, axis, peer_category, follower_bucket, peer_size, top_pct_views, top_pct_er, top_pct_likes, top_pct_comments, top_pct_ad_views, top_pct_ad_er, top_pct_ad_likes, top_pct_ad_comments, peer_median_er_pct, global_median_er_pct)` /
  `account_peer_stats`(구 14컬럼 그대로 — axis='beauty' 투영). Task 2의 was 쿼리가 앞 둘을 읽는다.

- [ ] **Step 1: 실패하는 테스트 추가**

`AccountPeerStatsViewTest`의 `migrate()` 끝(주석 `// h·i는 ...` 뒤)에 F&B 시드 추가. 마이그레이션이 F&B 어휘를 이미 시드하므로(V20260831032411) 실제 slug/라벨을 쓴다. `avg_er_pct`는 NULL로 둬야 기존 `중앙값_ER` 테스트(전체 9곳 전제)가 안 깨진다:

```java
// F&B 단독 계정 k — 축 뷰 검증용. avg_er_pct NULL: 기존 중앙값 테스트의 전체 모수(9곳)를
// 흔들지 않기 위해(percentile_cont는 NULL 제외). snack 2건 > convenience 1건 → fnb 축 최빈 간식류.
db.update("INSERT INTO account_summaries (handle, followers) VALUES ('k', 20000)");
db.update("""
		INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
		  views, likes, comments, sponsored) VALUES
		  ('k1', 'k', now() - interval '3 days', 'reels', 8000, 400, 30, false),
		  ('k2', 'k', now() - interval '2 days', 'reels', 7000, 350, 25, false),
		  ('k3', 'k', now() - interval '1 days', 'reels', 6000, 300, 20, false)""");
db.update("""
		INSERT INTO content_analyses (short_code, analyzed_at, model, is_beauty, main_category, ad_type)
		VALUES
		  ('k1', now(), 'm', false, 'snack', 'organic'),
		  ('k2', now(), 'm', false, 'snack', 'organic'),
		  ('k3', now(), 'm', false, 'convenience', 'organic')""");
```

테스트 3개 추가 (클래스 말미):

```java
@Test
void FnB_계정은_축_뷰에서_FnB_피어를_갖고_구_뷰에서는_미분류다() {
	// 축 뷰: k의 fnb 축 최빈은 간식류(snack 2건 > convenience 1건), beauty 축은 분류 0건 → 미분류.
	Map<String, Object> fnb = db.queryForMap(
			"SELECT * FROM account_peer_axis_stats WHERE handle = 'k' AND axis = 'fnb'");
	assertEquals("간식류", fnb.get("peer_category"));
	Map<String, Object> beauty = db.queryForMap(
			"SELECT * FROM account_peer_axis_stats WHERE handle = 'k' AND axis = 'beauty'");
	assertEquals("미분류", beauty.get("peer_category"));
	// 구 이름 뷰 = 뷰티 투영 — F&B 분류가 새어 들어오면 안 된다(기존 화면 불변).
	Map<String, Object> legacy = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'k'");
	assertEquals("미분류", legacy.get("peer_category"));
}

@Test
void 카테고리_스탯_뷰티_축은_구_V35_정의와_동치다() {
	// 신 뷰 axis='beauty' 투영 ≡ 구 정의(is_beauty 게이트) — 양방향 EXCEPT 합 0건(스펙 §5-1).
	Long diff = db.queryForObject("""
			WITH old AS (
			  SELECT s.account_handle, COALESCE(t.main_label, a.main_category) AS main_group,
			         count(*) AS content_count
			  FROM account_content_series s
			  JOIN content_analyses a ON a.short_code = s.short_code
			  LEFT JOIN (SELECT DISTINCT main_value, main_label FROM beauty_taxonomy) t
			         ON t.main_value = a.main_category
			  WHERE a.is_beauty IS TRUE AND a.main_category IS NOT NULL
			  GROUP BY 1, 2),
			new AS (
			  SELECT account_handle, main_group, content_count
			  FROM account_category_stats WHERE axis = 'beauty')
			SELECT (SELECT count(*) FROM (SELECT * FROM old EXCEPT SELECT * FROM new) d1)
			     + (SELECT count(*) FROM (SELECT * FROM new EXCEPT SELECT * FROM old) d2)
			""", Long.class);
	assertEquals(0L, diff);
}

@Test
void FnB_분류는_카테고리_스탯에_fnb_축으로_실린다() {
	Map<String, Object> row = db.queryForMap("""
			SELECT main_group, content_count FROM account_category_stats
			WHERE account_handle = 'k' AND axis = 'fnb' AND main_group = '간식류'""");
	assertEquals(2L, row.get("content_count"));
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.mirror.AccountPeerStatsViewTest"`
Expected: FAIL — `account_peer_axis_stats` 뷰 부재(relation does not exist) 및 `account_category_stats`에 `axis` 컬럼 부재. 기존 테스트 7개는 PASS 유지 확인.

- [ ] **Step 3: 마이그레이션 작성**

파일명 채번: `date -u +%Y%m%d%H%M%S` → `V<출력값>__similar_axis_views.sql`. 내용 전문:

```sql
-- 유사 추천 F&B 개방 — 카테고리 스탯·피어 뷰 축 인지화 (스펙 2026-09-01-similar-influencer-fnb-axis).
--
-- account_category_stats: 뷰티 게이트(is_beauty IS TRUE) 제거 + axis 컬럼(맨 끝, 어휘 파생 —
-- 어휘 밖 main_category는 is_beauty 폴백, 운영 실측 0건이라 이론 방어). 같은 이름 유지 근거:
-- 구 소비자(계정 카피 잡 2곳·구 was 믹스 CTE)에 행 추가는 무해·개선(스펙 §3-1) — main_group은
-- 축을 가로질러 중복되지 않는다(대분류가 축을 결정).
--
-- account_peer_axis_stats: V39 body를 계정×축으로 확장(신설). 피어 그룹·퍼센타일·중앙값 전부에
-- axis 파티션 추가. gmed(전역 중앙값 ER)는 base가 전 계정을 양축에 싣기 때문에 축별로 갈라도
-- 값이 동일하다 — 아래 뷰티 투영 동치의 근거.
--
-- account_peer_stats(구 이름): axis='beauty' 투영으로 재정의. 행이 계정당 1→2가 되는 축 뷰를
-- 같은 이름으로 두면 롤링 중 구 findSimilarHandles의 peers CTE에 핸들이 중복돼 유사 목록이
-- 깨진다 — 투영이 expand, 구 이름 제거는 다음 릴리스의 contract 판단.
-- allow-destructive: 뷰 재정의 — DROP 직후 같은 마이그레이션 트랜잭션 안에서 재생성해 참조
--   공백이 없고, 신 소비자(was 유사 추천)는 같은 릴리스로 나간다
DROP VIEW account_peer_stats;
DROP VIEW account_category_stats;

CREATE VIEW account_category_stats AS
SELECT s.account_handle,
       COALESCE(t.main_label, a.main_category) AS main_group,
       count(*)                                AS content_count,
       COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END) AS axis
FROM account_content_series s
JOIN content_analyses a ON a.short_code = s.short_code
LEFT JOIN (SELECT DISTINCT main_value, main_label, axis FROM beauty_taxonomy) t
       ON t.main_value = a.main_category
WHERE a.main_category IS NOT NULL
GROUP BY s.account_handle, COALESCE(t.main_label, a.main_category),
         COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END);

COMMENT ON VIEW account_category_stats IS
  '계정 카테고리 믹스 — 최근 N개 윈도우 × 캡션 대분류 × 축(2026-09-01). 미러 아님(analysis DB 파생 뷰).';

CREATE VIEW account_peer_axis_stats AS
WITH cat AS (
  SELECT DISTINCT ON (account_handle, axis) account_handle, axis, main_group
  FROM account_category_stats
  ORDER BY account_handle, axis, content_count DESC, main_group
),
ad AS (
  SELECT s.account_handle,
         round(avg(s.views) FILTER (WHERE s.views > 0))::bigint                            AS ad_avg_views,
         round(avg((s.likes + s.comments)::numeric / NULLIF(su.followers, 0)) * 100, 1)    AS ad_avg_er_pct,
         round(avg(s.likes))::bigint                                                        AS ad_avg_likes,
         round(avg(s.comments))::bigint                                                     AS ad_avg_comments
  FROM account_content_series s
  JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
  JOIN account_summaries su ON su.handle = s.account_handle
  GROUP BY s.account_handle
),
base AS (
  SELECT su.handle, ax.axis,
         COALESCE(c.main_group, '미분류') AS peer_category,
         CASE WHEN su.followers IS NULL   THEN '미상'
              WHEN su.followers >= 500000 THEN '50만+'
              WHEN su.followers >= 100000 THEN '10만-50만'
              WHEN su.followers >=  50000 THEN '5만-10만'
              WHEN su.followers >=  10000 THEN '1만-5만'
              ELSE '1만 미만' END          AS follower_bucket,
         su.avg_views, su.avg_er_pct, su.avg_likes, su.avg_comments,
         ad.ad_avg_views, ad.ad_avg_er_pct, ad.ad_avg_likes, ad.ad_avg_comments
  FROM account_summaries su
  CROSS JOIN (VALUES ('beauty'), ('fnb')) AS ax(axis)
  LEFT JOIN cat c ON c.account_handle = su.handle AND c.axis = ax.axis
  LEFT JOIN ad   ON ad.account_handle = su.handle
),
med AS (
  SELECT axis, peer_category, follower_bucket,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS peer_median_er_pct
  FROM base
  GROUP BY axis, peer_category, follower_bucket
),
gmed AS (
  SELECT axis, percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS global_median_er_pct
  FROM base
  GROUP BY axis
)
SELECT b.handle, b.axis, b.peer_category, b.follower_bucket,
       count(*) OVER peer AS peer_size,
       CASE WHEN b.avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_views IS NULL)
          ORDER BY b.avg_views DESC) * 100)::numeric)::int END       AS top_pct_views,
       CASE WHEN b.avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_er_pct IS NULL)
          ORDER BY b.avg_er_pct DESC) * 100)::numeric)::int END      AS top_pct_er,
       CASE WHEN b.avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_likes IS NULL)
          ORDER BY b.avg_likes DESC) * 100)::numeric)::int END       AS top_pct_likes,
       CASE WHEN b.avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_comments IS NULL)
          ORDER BY b.avg_comments DESC) * 100)::numeric)::int END    AS top_pct_comments,
       CASE WHEN b.ad_avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_views IS NULL)
          ORDER BY b.ad_avg_views DESC) * 100)::numeric)::int END    AS top_pct_ad_views,
       CASE WHEN b.ad_avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_er_pct IS NULL)
          ORDER BY b.ad_avg_er_pct DESC) * 100)::numeric)::int END   AS top_pct_ad_er,
       CASE WHEN b.ad_avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_likes IS NULL)
          ORDER BY b.ad_avg_likes DESC) * 100)::numeric)::int END    AS top_pct_ad_likes,
       CASE WHEN b.ad_avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_comments IS NULL)
          ORDER BY b.ad_avg_comments DESC) * 100)::numeric)::int END AS top_pct_ad_comments,
       round(m.peer_median_er_pct::numeric, 1)   AS peer_median_er_pct,
       round(g.global_median_er_pct::numeric, 1) AS global_median_er_pct
FROM base b
JOIN med m ON m.axis = b.axis AND m.peer_category = b.peer_category
          AND m.follower_bucket = b.follower_bucket
JOIN gmed g ON g.axis = b.axis
WINDOW peer AS (PARTITION BY b.axis, b.peer_category, b.follower_bucket);

COMMENT ON VIEW account_peer_axis_stats IS
  '피어(축×주 카테고리×팔로워 버킷) 퍼센타일 + 중앙값 ER (2026-09-01). 미러 아님.';

CREATE VIEW account_peer_stats AS
SELECT handle, peer_category, follower_bucket, peer_size,
       top_pct_views, top_pct_er, top_pct_likes, top_pct_comments,
       top_pct_ad_views, top_pct_ad_er, top_pct_ad_likes, top_pct_ad_comments,
       peer_median_er_pct, global_median_er_pct
FROM account_peer_axis_stats
WHERE axis = 'beauty';

COMMENT ON VIEW account_peer_stats IS
  '구 이름 호환 — account_peer_axis_stats의 뷰티 투영(2026-09-01). 신규 소비자는 축 뷰를 쓸 것.';
```

- [ ] **Step 4: 테스트 통과 확인 (기존 7개 포함 전부)**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.mirror.AccountPeerStatsViewTest"`
Expected: PASS (신규 3개 + 기존 7개 — 기존 테스트는 구 이름 뷰=투영 위에서 그대로 성립해야 한다. 하나라도 깨지면 투영 동치가 깨진 것이니 마이그레이션을 고칠 것, 테스트를 고치지 말 것)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis/V*__similar_axis_views.sql \
        analytics/src/test/java/com/celfit/analytics/mirror/AccountPeerStatsViewTest.java
git commit -m "feat(analytics): 카테고리 스탯·피어 뷰 축 인지화 — 유사 추천 F&B 개방 재료

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: was — findFnbAxis 신설 + findSimilarHandles 축 파라미터

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportRepository.java`
- Test: `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1의 `account_peer_axis_stats(handle, axis, peer_category, ...)`·`account_category_stats(..., axis)`
- Produces: `public boolean findFnbAxis(String handle)` / `public List<String> findSimilarHandles(String handle, boolean fnbAxis)` — Task 3 컨트롤러가 호출

- [ ] **Step 1: 테스트 픽스처를 신 뷰 형상으로 갱신**

`setUpTables()`에서:

① `accounts`에 축 컬럼 추가(59행 근처):

```java
jdbcTemplate.execute("""
		CREATE TABLE accounts (
		    handle    text PRIMARY KEY,
		    followers bigint,
		    beauty    boolean,
		    fnb       boolean
		)""");
```

② `beauty_taxonomy`에 axis 컬럼 추가(107행 근처, V1InfluencerDiscoveryRepositoryTest와 동일 형상):

```java
jdbcTemplate.execute("""
		CREATE TABLE beauty_taxonomy (
		    main_value text NOT NULL,
		    main_label text NOT NULL,
		    mid_label  text NOT NULL,
		    sub_label  text NOT NULL,
		    main_order int  NOT NULL,
		    mid_order  int  NOT NULL,
		    sub_order  int  NOT NULL,
		    axis       text NOT NULL DEFAULT 'beauty',
		    PRIMARY KEY (main_value, mid_label, sub_label)
		)""");
```

③ 두 뷰 DDL 사본 교체(121~185행). DROP 목록(47~48행)과 tearDown(311~312행)에 `account_peer_axis_stats`를 추가하고, 사본은 아래로:

```java
// 아래 두 뷰 DDL은 analytics V35 계열(account_category_stats)·V<UTC>(account_peer_axis_stats,
// 2026-09-01 축 인지화) 마이그레이션의 사본이다 — 원본이 바뀌면 같이 갱신할 것. 실뷰의
// 퍼센타일·중앙값 컬럼은 was 미소비라 생략(기존 사본 관례).
jdbcTemplate.execute("""
		CREATE VIEW account_category_stats AS
		SELECT s.account_handle,
		       COALESCE(t.main_label, a.main_category) AS main_group,
		       count(*)                                AS content_count,
		       COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END) AS axis
		FROM account_content_series s
		JOIN content_analyses a ON a.short_code = s.short_code
		LEFT JOIN (SELECT DISTINCT main_value, main_label, axis FROM beauty_taxonomy) t
		       ON t.main_value = a.main_category
		WHERE a.main_category IS NOT NULL
		GROUP BY s.account_handle, COALESCE(t.main_label, a.main_category),
		         COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END)
		""");
jdbcTemplate.execute("""
		CREATE VIEW account_peer_axis_stats AS
		WITH cat AS (
		  SELECT DISTINCT ON (account_handle, axis) account_handle, axis, main_group
		  FROM account_category_stats
		  ORDER BY account_handle, axis, content_count DESC, main_group
		),
		base AS (
		  SELECT su.handle, ax.axis,
		         COALESCE(c.main_group, '미분류') AS peer_category,
		         CASE WHEN su.followers IS NULL   THEN '미상'
		              WHEN su.followers >= 500000 THEN '50만+'
		              WHEN su.followers >= 100000 THEN '10만-50만'
		              WHEN su.followers >=  50000 THEN '5만-10만'
		              WHEN su.followers >=  10000 THEN '1만-5만'
		              ELSE '1만 미만' END          AS follower_bucket
		  FROM account_summaries su
		  CROSS JOIN (VALUES ('beauty'), ('fnb')) AS ax(axis)
		  LEFT JOIN cat c ON c.account_handle = su.handle AND c.axis = ax.axis
		)
		SELECT handle, axis, peer_category, follower_bucket FROM base
		""");
```

(구 이름 `account_peer_stats` 사본은 삭제 — 신 was 코드는 축 뷰만 읽는다. DROP 문은 잔존 뷰 정리를 위해 setUp·tearDown 양쪽에 유지.)

④ 기존 `findSimilarHandles("...")` 호출 전부를 `findSimilarHandles("...", false)`로 치환(테스트 16곳 — `유사_핸들은_...`부터 `뷰티_비율_게이트_...`까지). 컴파일 에러가 치환 누락을 잡아준다.

- [ ] **Step 2: 신규 실패 테스트 추가**

클래스 말미에 추가:

```java
/** F&B 유사 시드 — is_beauty=false + F&B 대분류(snack 등), accounts.fnb=true·beauty=false. */
private void seedFnbSimAccount(String handle, long followers, String traitsJson, String... mixCounts) {
	jdbcTemplate.update("INSERT INTO accounts (handle, followers, beauty, fnb) VALUES (?, ?, false, true)",
			handle, followers);
	jdbcTemplate.update("INSERT INTO account_summaries (handle, followers, last_posted_at) VALUES (?, ?, now())",
			handle, followers);
	jdbcTemplate.update(
			"INSERT INTO account_analyses (handle, analyzed_at, traits) VALUES (?, now(), ?::jsonb)",
			handle, traitsJson);
	int post = 0;
	for (int i = 0; i < mixCounts.length; i += 2) {
		String category = mixCounts[i];
		int count = Integer.parseInt(mixCounts[i + 1]);
		for (int j = 0; j < count; j++) {
			String shortCode = handle + "_f" + (post++);
			jdbcTemplate.update("""
					INSERT INTO account_content_series (short_code, account_handle, posted_at,
					  content_type, views, likes, comments, sponsored) VALUES
					  (?, ?, now(), 'reels', 1000, 10, 1, false)""", shortCode, handle);
			jdbcTemplate.update("""
					INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
					  detected_brands) VALUES (?, false, ?, 'organic', NULL)""", shortCode, category);
		}
	}
}

@Test
void FnB_축_유사는_FnB_계정끼리_뷰티_비율_게이트_없이_동작한다() {
	// beauty_taxonomy에 fnb 어휘 시드(운영 V20260831032411의 축약)
	jdbcTemplate.update("""
			INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
			  main_order, mid_order, sub_order, axis) VALUES
			  ('snack', '간식류', '간식류', '과자', 11, 1, 1, 'fnb')""");
	seedFnbSimAccount("fme", 10_000, "[\"정보형 리뷰\"]", "snack", "10");
	// fcand: traits 완전 일치 + 같은 fnb 피어(간식류). 뷰티 비율 0%지만 F&B 축은 그 게이트를
	// 안 문다 — 걸리면 전멸(스펙 §4). 분석은 10건이라 표본 부족 보류도 아니다.
	seedFnbSimAccount("fcand", 12_000, "[\"정보형 리뷰\"]", "snack", "10");
	// bcand: 뷰티 계정(간식류 아님) — traits가 같아도 축이 달라 후보 자체가 아니어야 한다.
	seedSimAccount("bcand", 12_000, "[\"정보형 리뷰\"]", "skincare", "10");

	assertThat(repository.findSimilarHandles("fme", true)).containsExactly("fcand");
}

@Test
void 뷰티_축_유사에_FnB_계정은_섞이지_않는다() {
	jdbcTemplate.update("""
			INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
			  main_order, mid_order, sub_order, axis) VALUES
			  ('snack', '간식류', '간식류', '과자', 11, 1, 1, 'fnb')""");
	seedSimAccount("bme", 10_000, "[\"정보형 리뷰\"]", "skincare", "10");
	seedSimAccount("bcand", 12_000, "[\"정보형 리뷰\"]", "skincare", "10");
	// F&B 계정 — 뷰티 축 피어는 '미분류'라 skincare 풀과 안 겹치고, 뷰티 비율 게이트로도 걸러진다.
	seedFnbSimAccount("fnoise", 12_000, "[\"정보형 리뷰\"]", "snack", "10");

	assertThat(repository.findSimilarHandles("bme", false)).containsExactly("bcand");
}

@Test
void findFnbAxis는_FnB_단독만_true다() {
	jdbcTemplate.update("""
			INSERT INTO accounts (handle, followers, beauty, fnb) VALUES
			  ('fnb_only', 1000, false, true),
			  ('mixed', 1000, true, true),
			  ('beauty_only', 1000, true, false),
			  ('legacy', 1000, NULL, NULL)""");
	assertThat(repository.findFnbAxis("fnb_only")).isTrue();
	assertThat(repository.findFnbAxis("mixed")).isFalse();      // 혼합은 beauty(기존 화면 불변)
	assertThat(repository.findFnbAxis("beauty_only")).isFalse();
	assertThat(repository.findFnbAxis("legacy")).isFalse();     // 레거시 null은 뷰티 모수 출신
	assertThat(repository.findFnbAxis("ghost")).isFalse();      // 행 부재 → 기본 beauty
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportRepositoryTest"`
Expected: 컴파일 실패 — `findSimilarHandles(String, boolean)`·`findFnbAxis` 미정의.

- [ ] **Step 4: 리포지토리 구현**

`V2InfluencerReportRepository.java` — `findSimilarHandles`를 아래로 교체(Javadoc의 07-31 최적화 문단은 유지하고 첫 문장들만 축 설명 추가), `findFnbAxis` 신설:

```java
/**
 * 대상 계정의 유사 추천 축 — F&B 단독 계정만 fnb(true). 혼합(뷰티∧F&B)·레거시(null)는
 * beauty: 기존 뷰티 화면 불변 우선(스펙 2026-09-01 §4). COALESCE 방향은 발굴 무필터
 * (beauty→true, fnb→false)와 동일 논리 — 미러 갱신 전 구 행은 전부 뷰티 모수 출신.
 */
public boolean findFnbAxis(String handle) {
	return jdbcClient.sql("""
			SELECT COALESCE(fnb, false) AND NOT COALESCE(beauty, true)
			FROM accounts WHERE handle = :h
			""").param("h", handle).query(Boolean.class).optional().orElse(false);
}
```

`findSimilarHandles(String handle, boolean fnbAxis)`: SQL 변경 3곳 + 게이트 분기. peers·cats CTE를 축 필터로:

```java
public List<String> findSimilarHandles(String handle, boolean fnbAxis) {
	// 후보 게이트 분기(발굴 build()와 동일 패턴, 스펙 2026-09-01 §4): 뷰티 축은 기존 뷰티 비율
	// 게이트(결과 불변), F&B 축은 accounts.fnb 게이트 — F&B 계정은 뷰티 비율 0이라 걸면 전멸.
	String candidateGate = fnbAxis
			? "  AND COALESCE(ac.fnb, false)"
			: """
			    AND (COALESCE(br.analyzed_count, 0) < :minAnalyzed
			         OR 100.0 * br.beauty_count / NULLIF(br.analyzed_count, 0) >= :minBeautyRatio)""";
	var spec = jdbcClient.sql("""
			WITH peers AS MATERIALIZED (
			  SELECT handle, peer_category FROM account_peer_axis_stats WHERE axis = :axis
			),
			cats AS MATERIALIZED (
			  SELECT account_handle, main_group, content_count FROM account_category_stats
			  WHERE axis = :axis
			),
			""" + /* me ~ scored의 WHERE c.handle <> :h 까지 기존 본문 그대로 */ """
			  WHERE c.handle <> :h
			""" + candidateGate + """
			)
			SELECT handle
			FROM scored
			WHERE score >= 0.30
			ORDER BY score DESC, abs(followers - my_followers) ASC, handle ASC
			LIMIT 10
			""")
			.param("h", handle)
			.param("axis", fnbAxis ? "fnb" : "beauty");
	if (!fnbAxis) {
		spec = spec.param("minAnalyzed", MIN_ANALYZED).param("minBeautyRatio", MIN_BEAUTY_RATIO_PERCENT);
	}
	return spec.query(String.class).list();
}
```

구현 시 주의: "기존 본문 그대로" 부분은 현재 파일의 `me`~`scored` CTE(130~181행)를 문자 그대로 옮긴다 — `LEFT JOIN account_beauty_ratio br` 조인은 양축 공통 유지(F&B 축에서 미사용이지만 무해), 기존 뷰티 비율 게이트 2줄(180~181행)만 `candidateGate`로 대체된다. 클래스 상단 주석(14~17행)의 게이트 설명에 "F&B 축은 accounts.fnb 게이트(스펙 2026-09-01)" 한 줄 추가.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportRepositoryTest"`
Expected: PASS — 기존 유사 테스트 16개(축 false, 결과 불변 = 스펙 §5-3)·신규 3개 전부.

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportRepository.java \
        was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportRepositoryTest.java
git commit -m "feat(was): 유사 추천 쿼리 축 인지화 — findFnbAxis 파생 + F&B 축 게이트

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: was — 컨트롤러 배선 (축 파생 → 유사·카드 비중 전달)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportController.java:104-122`
- Test: `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportControllerTest.java`

**Interfaces:**
- Consumes: Task 2의 `findFnbAxis(handle)`·`findSimilarHandles(handle, fnbAxis)`, 기존 `V1InfluencerDiscoveryRepository.findShares(handles, fnbAxis)`

- [ ] **Step 1: 컨트롤러 테스트 갱신 + 신규 실패 테스트**

기존 similar 테스트의 목 스텁을 신 시그니처로: `given(repository.findSimilarHandles("<대상>")).willReturn(...)` → `given(repository.findFnbAxis("<대상>")).willReturn(false)` 추가 + `findSimilarHandles(eq("<대상>"), eq(false))`로 치환. `discoveryRepository.findShares(List.of("b", "a"), false)` 스텁은 유지.

신규 테스트(같은 파일, 기존 similar 테스트 옆) — F&B 대상이면 축이 끝까지 전파되는 배선 가드:

```java
@Test
void FnB_대상의_유사는_fnb_축으로_유사_핸들과_카드_비중을_친다() throws Exception {
	given(repository.findSummary("fnbstar")).willReturn(Optional.of(summaryRow()));
	given(repository.findFnbAxis("fnbstar")).willReturn(true);
	given(repository.findSimilarHandles("fnbstar", true)).willReturn(List.of("fpeer"));
	given(discoveryRepository.findCardsByHandles(List.of("fpeer"))).willReturn(List.of());
	given(discoveryRepository.findShares(List.of("fpeer"), true)).willReturn(List.of());
	given(discoveryRepository.findBrands(List.of("fpeer"))).willReturn(List.of());
	given(discoveryRepository.findThumbs(List.of("fpeer"))).willReturn(List.of());
	given(discoveryRepository.findEngagements(List.of("fpeer"))).willReturn(List.of());

	mockMvc.perform(get("/v2/influencers/fnbstar/similar"))
			.andExpect(status().isOk());

	verify(discoveryRepository).findShares(List.of("fpeer"), true);
}
```

(`summaryRow()`·mockMvc 준비는 파일의 기존 similar 테스트 관용구를 그대로 따른다 — 기존 테스트에서 findSummary 스텁에 쓰는 헬퍼/픽스처를 재사용하고, 없으면 그 테스트의 인라인 생성 방식을 복제한다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportControllerTest"`
Expected: FAIL — 컨트롤러가 아직 `findFnbAxis`를 안 부르고 `findShares(handles, false)` 고정.

- [ ] **Step 3: 컨트롤러 수정**

`similar()`(104~122행)에서:

```java
List<String> handles = repository.findSimilarHandles(influencerId);
```
→
```java
// 축은 대상 계정에서 파생(F&B 단독 → fnb) — 후보 풀·믹스·카드 비중이 같은 축을 따라간다.
boolean fnbAxis = repository.findFnbAxis(influencerId);
List<String> handles = repository.findSimilarHandles(influencerId, fnbAxis);
```

그리고 #681이 남긴 주석·하드코딩:
```java
// 유사 후보는 뷰티 코퍼스 기반(account_peer_stats, V35 뷰티 게이트) — 축은 뷰티 고정.
// F&B 유사 추천 개방 시 대상 계정 축 파생으로 바꾼다.
discoveryRepository.findShares(handles, false),
```
→
```java
discoveryRepository.findShares(handles, fnbAxis),
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportControllerTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportController.java \
        was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportControllerTest.java
git commit -m "feat(was): 유사 추천 축 배선 — 대상 계정 축을 유사 핸들·카드 비중에 전파

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: was — 콘텐츠 리포트 카테고리 비교 축 개방 (2026-09-01 사용자 추가 요청)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentReportRepository.java:56-81` (`findCategoryContext`)
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentReportRepositoryTest.java`

**Interfaces:**
- Consumes: 없음(독립 — 라이브 집계 쿼리 단독 수정)
- Produces: 시그니처 불변 — `findCategoryContext(String mainCategory, Long views)` 그대로, 모수만 축 중립

**배경:** 콘텐츠 상세(6.x)의 "이 콘텐츠 vs 같은 카테고리 콘텐츠" 섹션이 F&B 게시물에서 "현재 준비중입니다"로 비어 있다(운영 실측 moobocke 게시물). `findCategoryContext`의 `AND an.is_beauty = true`가 원인 — F&B 분석분은 `is_beauty=false`라 표본 0건. `main_category = :mc`가 이미 대분류 하나로 모수를 고정하고 대분류가 축을 결정하므로(불변식, 서빙 개방 §2) `is_beauty` 조건은 뷰티 카테고리에선 잉여, F&B 카테고리에선 치명이다 — 제거해도 뷰티 결과는 동치.

- [ ] **Step 1: 테스트 갱신 + 신규 실패 테스트**

`V1ContentReportRepositoryTest` 픽스처(94~104행)의 `mnb` 행은 "makeup + is_beauty=false"로 **불변식 위반 상태를 모델링**하고 있다(운영에 존재 불가 — sanitize가 축 파생으로 보장). F&B 실데이터 형상으로 교체:

① `beauty_taxonomy` 시드(76~80행)에 axis 컬럼 추가가 필요하므로 테이블 DDL(66~75행)에 `axis text NOT NULL DEFAULT 'beauty'` 컬럼을 추가하고, 시드에 fnb 행 1개 추가:

```java
jdbcTemplate.update("""
		INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
		  main_order, mid_order, sub_order, axis) VALUES
		 ('makeup', '메이크업', '립메이크업', '립틴트', 3, 1, 1, 'beauty'),
		 ('makeup', '메이크업', '아이메이크업', '아이라이너', 3, 3, 1, 'beauty'),
		 ('skincare', '스킨케어', '크림', '크림', 1, 3, 1, 'beauty'),
		 ('snack', '간식류', '간식류', '과자', 11, 1, 1, 'fnb')""");
```

② `mnb` 행을 F&B 분석분으로 교체 — contents 시드는 그대로 두고 content_analyses의 `('mnb', 'makeup', false, ...)` → `('mnb', 'snack', false, 'timely', '요약', 12)`. 주석 갱신: `// mnb는 F&B 분석분(is_beauty=false ∧ snack) — makeup 표본에 안 섞이고 snack 표본이 된다`.

③ 기존 테스트 3개의 기대값은 **불변**(makeup 표본은 여전히 m1·m2·m3·mlegacy 4건·평균 1325 — mnb는 이제 다른 대분류라 제외 사유만 "비뷰티"→"다른 대분류"로 주석 수정). `카테고리_표본은_...` 테스트의 주석에서 `mnb(비뷰티)` → `mnb(다른 대분류·F&B)`.

④ 신규 테스트:

```java
@Test
void FnB_대분류도_같은_카테고리_표본을_받는다() {
	// mnb(snack, is_beauty=false, timely, views 7000)가 유일한 snack 표본 —
	// 구 쿼리의 is_beauty=true 게이트가 남아 있으면 0건으로 "현재 준비중" 화면이 된다.
	var ctx = repository.findCategoryContext("snack", 1000L);

	assertThat(ctx.sampleSize()).isEqualTo(1L);
	assertThat(ctx.avgViews()).isEqualTo(7000L);
	assertThat(ctx.higherCount()).isEqualTo(1L); // 나(1000)보다 높은 mnb
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentReportRepositoryTest"`
Expected: 신규 테스트 FAIL(sampleSize 0 — is_beauty 게이트), 기존 3개는 PASS 유지.

- [ ] **Step 3: 쿼리 수정**

`findCategoryContext`에서 `AND an.is_beauty = true` 한 줄 삭제. Javadoc 모수 규칙 첫 불릿을:

```
* <li>같은 대분류(main_category) 분석분만 — 대분류가 축을 결정하므로(불변식: main 있음 ⇒ 축
*     확정, 서빙 개방 §2) 구 is_beauty=true 게이트는 뷰티 대분류에선 잉여, F&B에선 표본 0건을
*     만들어 제거(2026-09-01). 카테고리는 beauty_taxonomy 대분류.
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentReportRepositoryTest"`
Expected: PASS (기존 + 신규 전부).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/content/V1ContentReportRepository.java \
        was/src/test/java/com/celfit/was/v1/content/V1ContentReportRepositoryTest.java
git commit -m "feat(was): 콘텐츠 리포트 카테고리 비교를 축 중립으로 — F&B '현재 준비중' 해소

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 모듈 검증·운영 사전 대조·문서·PR

**Files:**
- Modify: `DECISIONS.md`(맨 위 행 추가), `docs/tracks/LL2-fnb-콘텐츠-분류.md`(후속 갱신)
- Move: `docs/superpowers/plans/2026-09-01-similar-influencer-fnb-axis.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: 모듈 테스트 전체**

Run: `./gradlew :was:test :analytics:test`
Expected: BUILD SUCCESSFUL. 실패 시 해당 태스크로 돌아가 수정.

- [ ] **Step 2: 운영 DB 사전 동치 대조 (읽기 전용)**

배포 전 신 뷰 정의를 인라인 SQL로 운영에 돌려 구 뷰와 대조(스펙 §5-1·2). ssh로 실행:

```bash
ssh ubuntu@155.248.187.106 "docker exec deploy-postgres-1 psql -U \$(docker exec deploy-postgres-1 printenv POSTGRES_USER) -d analysis -Atc \"
WITH new_stats AS (
  SELECT s.account_handle, COALESCE(t.main_label, a.main_category) AS main_group, count(*) AS content_count
  FROM account_content_series s
  JOIN content_analyses a ON a.short_code = s.short_code
  LEFT JOIN (SELECT DISTINCT main_value, main_label, axis FROM beauty_taxonomy) t
         ON t.main_value = a.main_category
  WHERE a.main_category IS NOT NULL
    AND COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END) = 'beauty'
  GROUP BY 1, 2)
SELECT (SELECT count(*) FROM (SELECT * FROM account_category_stats EXCEPT SELECT * FROM new_stats) d1)
     + (SELECT count(*) FROM (SELECT * FROM new_stats EXCEPT SELECT * FROM account_category_stats) d2);\""
```

Expected: `0`. (피어 뷰는 카테고리 스탯 동치 + 순수 함수 변환이라 이 대조가 근거의 전부다 — 배포 후 `account_peer_stats` EXCEPT 재확인은 Step 6.)

- [ ] **Step 3: 문서 갱신**

DECISIONS.md 표 맨 위에 1행(콘텐츠 리포트 카테고리 비교 축 중립화도 한 문장 포함할 것):

```markdown
| 2026-09-01 | **유사 인플루언서 추천 축 인지화** — F&B 리포트의 유사 추천이 '미분류' 풀 잡탕이던 것(muk_gyumato 실측)을 해소: `account_category_stats`에 axis 컬럼(게이트 제거, 뷰티 투영 동치), `account_peer_axis_stats` 신설(계정×축 피어, 구 이름은 뷰티 투영 유지 — 롤링 안전), was는 `findFnbAxis`(F&B 단독→fnb, 혼합·레거시→beauty)로 축을 파생해 유사 핸들·카드 비중에 전파. F&B 축 후보 게이트는 `a.fnb`(뷰티 비율 게이트는 뷰티 축만). 부수: 계정 카피 LLM 입력에 F&B 카테고리 분포 유입 시작. 피어 퍼센타일 컬럼은 소비자 0건(죽은 컬럼) 확인 — 축별 준비만, 서빙은 FE 명세 후 | [스펙](docs/superpowers/specs/2026-09-01-similar-influencer-fnb-axis-design.md), 트랙 [LL2](docs/tracks/LL2-fnb-콘텐츠-분류.md) |
```

LL2 트랙 "후속" 섹션의 서빙 개방 항목 끝에 이어붙임:

```markdown
  09-01 유사 추천도 축 인지로 개방(카테고리 스탯·피어 뷰 축별 분리, 구 이름 뷰는 뷰티 투영) —
  구 이름 `account_peer_stats` 투영 제거는 다음 릴리스 contract 판단.
```

plan 문서를 `docs/superpowers/plans/archive/`로 이동(세션 위생 규약 — PR에 포함).

- [ ] **Step 4: 커밋·푸시·PR**

```bash
git add -A
git commit -m "docs: 유사 추천 축 인지화 결정 기록·트랙 갱신 + plan 아카이브

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push -u origin feat/similar-influencer-fnb-axis
gh pr create --base develop --title "feat(analytics,was): 유사 인플루언서 추천 F&B 개방 — 카테고리 스탯·피어 뷰 축 인지화" --body "<스펙 요약 + §5 불변 증명 결과(운영 사전 대조 0건 포함) + 테스트 결과. 말미에 🤖 Generated with [Claude Code](https://claude.com/claude-code)>"
```

- [ ] **Step 5: CI 확인 후 사용자에게 승격 여부 확인**

`gh pr checks <PR번호> --watch`. 마이그레이션 동반이므로 develop 머지 후 승격(develop→staging→main)은 사용자 확인 후 진행.

- [ ] **Step 6: (배포 후) 운영 검증**

staging/운영 배포가 이뤄진 뒤:

```bash
ssh ubuntu@155.248.187.106 "docker exec deploy-postgres-1 psql -U \$(docker exec deploy-postgres-1 printenv POSTGRES_USER) -d analysis -Atc \"SELECT axis, peer_category, count(*) FROM account_peer_axis_stats GROUP BY 1,2 ORDER BY 1,3 DESC LIMIT 20;\""
curl -s "https://api.hypenow.io/v2/influencers/muk_gyumato/similar" | python3 -m json.tool | head -40
```

Expected: fnb 축 피어 그룹(간식류·가공/간편식 등) 형성, muk_gyumato 유사 목록이 F&B 계정들로 구성 + 카드 categoryShares 채워짐.

---

## Self-Review 결과

- 스펙 §3-1(카테고리 스탯 재정의)=Task 1, §3-2(피어 축 뷰+투영)=Task 1, §4(findFnbAxis·게이트 분기·findShares 전파)=Task 2·3, §5(불변 증명)=Task 1 테스트+Task 4 Step 2·6, §6(채번·allow-destructive·정규 경로)=Global Constraints+Task 4 Step 5, §7(검증 계획)=각 태스크 테스트+Task 4.
- 시그니처 일관성: `findFnbAxis(String)→boolean`·`findSimilarHandles(String, boolean)`(Task 2 정의 = Task 3 소비), `findShares(List, boolean)`은 기존(#681).
- 주의 지점: Task 2 Step 4의 "기존 본문 그대로" — me~scored CTE는 파일 130~181행을 문자 그대로 보존해야 하며(집합 기반 CTE·MATERIALIZED 최적화 07-31 실측 배경), 변경은 peers·cats의 축 필터와 게이트 2줄뿐이다.
