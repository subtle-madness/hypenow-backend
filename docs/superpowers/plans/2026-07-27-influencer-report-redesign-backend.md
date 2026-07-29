# 인플루언서 리포트 개편 — 백엔드(analytics·was) 구현 계획

> 상태: 🟢 활성 · ✅ 구현됨(머지 대기)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인플루언서 AI 리포트 개편 — LLM 카피 7종→5종(요약 3분할), 퍼센타일·성장세·유효 팔로워·협업 제품·게시물 미리보기·브랜드/유사 인플루언서 조회를 백엔드에 추가한다.

**Architecture:** 크로스 계정 산출(퍼센타일·중앙값 ER)만 analysis DB 파생 뷰(V35 `account_category_stats` 패턴)로 만들고, 계정 단위 산출(성장세·광고 간격·유효 팔로워·헤드라인 템플릿)은 was Assembler 라이브 계산으로 둔다(기존 `comparison()` 선례). 광고 판정 정본은 `content_analyses.ad_type='sponsored'`(캡션 분류) — raw 뷰(`10_account_detail.sql`)는 건드리지 않는다(크로스 DB 제약). `account_analyses`는 INSERT-only 이력이므로 컬럼은 끝에 추가만 하고 구 컬럼은 보존한다.

**Tech Stack:** Java 21 / Spring Boot 4.1 / JdbcClient(was)·JdbcTemplate(analytics) / Flyway(analysis DB) / Testcontainers PostgreSQL / Gemini(Vertex) LLM.

**확정 결정 (2026-07-27):**
1. 협업 적합도(등급·순위·근거문) — **스코프 제외** (관련 산출 없음)
2. ad_headline — **LLM 제거, was 템플릿 조립** (`lastAdNote()` 선례)
3. 유효 팔로워 — `followers × min(1, 계정 ER / 피어 중앙값 ER)` 휴리스틱, 정확도 요구 낮음
4. 유사 인플루언서 — **임베딩 없이** traits 교집합 + 동일 주 카테고리 + 팔로워 근접 휴리스틱

**프론트 목업 정본:** 세션 스크래치 `influencer-report-final.html` (사용자 `~/Downloads/인플루언서-리포트-개편-목업.html` 사본). 프론트(celfit-front) 작업은 이 계획 범위 밖.

---

## 작업 환경

메인 폴더는 세션 공유(브랜치 수시 변경)라 **worktree에서 작업한다**.

```bash
cd /Users/woomin/Project/hypenow-backend
git worktree add .worktrees/report-redesign -b feat/influencer-report-redesign origin/develop
cd .worktrees/report-redesign
```

PR은 develop 대상 1건. 커밋은 태스크 단위(한국어, `feat(모듈):` prefix).

⚠️ **마이그레이션 번호는 머지 직전 재확인** — 이 계획은 V39·V40을 가정하지만 develop에 다른 analysis 마이그레이션이 먼저 들어오면 재번호(V18 경합 전례).

## 파일 맵

| 파일 | 작업 |
|---|---|
| `analytics/src/main/resources/db/migration/analysis/V39__account_peer_stats_view.sql` | 생성 — 피어 퍼센타일 뷰 |
| `analytics/src/main/resources/db/migration/analysis/V40__account_analyses_section_summaries.sql` | 생성 — 요약 3분할 컬럼 |
| `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountAnalysis.java` | 수정 — 신규 3필드 append |
| `analytics/.../llm/AccountCopy.java` | 수정 — 7종→5종 |
| `analytics/.../llm/GeminiAccountSynthesizer.java` | 수정 — INSTRUCTIONS·SCHEMA 재작성 |
| `analytics/.../analyze/AccountAnalysisJob.java` | 수정 — INSERT·가드·백필 대상 조건 |
| `analytics/.../analyze/ClaudeBurstRunner.java` | 수정 — INSERT 컬럼 정렬 |
| `analytics/src/test/.../mirror/AccountPeerStatsViewTest.java` | 생성 |
| `analytics/src/test/.../llm/GeminiAccountSynthesizerTest.java` | 수정 |
| `analytics/src/test/.../analyze/AccountAnalysisJobTest.java` | 수정 |
| `analytics/src/test/.../analyze/ClaudeBurstRunnerTest.java` | 수정 |
| `was/.../v1/influencer/InfluencerAiReport.java` | 재작성 — DTO v2 |
| `was/.../v1/influencer/V1InfluencerReportRepository.java` | 수정 — 쿼리 확장 + 신규 3쿼리 |
| `was/.../v1/influencer/V1InfluencerReportAssembler.java` | 재작성 — 성장세·유효팔로워·헤드라인 |
| `was/.../v1/influencer/V1InfluencerReportController.java` | 수정 — 배선 + similar 엔드포인트 |
| `was/.../v1/brand/V1BrandController.java`, `V1BrandRepository.java`, `BrandInfluencer.java` | 생성 |
| `was/src/test/.../v1/influencer/*Test.java` | 수정 |
| `was/src/test/.../v1/brand/V1BrandControllerTest.java` | 생성 |
| `ARCHITECTURE.md` §5·§7 | 수정 — 트랙·결정 기록 |

---

### Task 1: V39 — `account_peer_stats` 파생 뷰

피어 그룹 = 주 카테고리(계정별 최빈 `main_group`) × 팔로워 버킷. 전체 4지표 + 광고 4지표의 "상위 %"(percent_rank)와 유효 팔로워용 중앙값 ER을 낸다. analysis DB에는 `app_setting`이 없으므로 버킷 경계는 하드코딩(변경은 후속 마이그레이션 — beauty_taxonomy 어휘와 같은 규약).

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V39__account_peer_stats_view.sql`
- Test: `analytics/src/test/java/com/celfit/analytics/mirror/AccountPeerStatsViewTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`AccountCategoryStatsViewTest`와 같은 구성(Testcontainers + Flyway `classpath:db/migration/analysis`)으로 작성한다.

```java
package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * 피어 퍼센타일 뷰(V39) 계약:
 * ① 피어 그룹 = 최빈 main_group × 팔로워 버킷 ② percent_rank는 값 큰 쪽이 0(상위)
 * ③ NULL 지표(피드 전용 avg_views 등)는 순위에서 제외돼 NULL
 * ④ 광고 지표는 ad_type='sponsored' 정본 ⑤ 중앙값 ER(피어·전체) 노출.
 */
@Testcontainers
class AccountPeerStatsViewTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis").load().migrate();
		db = new JdbcTemplate(ds);
		// 같은 버킷(1만-5만)·같은 카테고리(스킨케어) 3계정 — avg_views 50k/30k/10k 순.
		// c는 피드 전용(avg_views NULL) 별도 검증용이 아니라 순위 3위. d는 avg_views NULL.
		db.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				  ('a', 'A', NULL, 20000), ('b', 'B', NULL, 30000), ('c', 'C', NULL, 40000),
				  ('d', 'D', NULL, 25000)""");
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  avg_views, avg_er_pct, avg_likes, avg_comments) VALUES
				  ('a', 20000, 12, 6, 'views', 50000, 4.0, 3000, 150),
				  ('b', 30000, 12, 6, 'views', 30000, 3.0, 2000, 100),
				  ('c', 40000, 12, 6, 'views', 10000, 2.0, 1000, 50),
				  ('d', 25000, 12, 0, 'likes', NULL,  1.0,  500, 20)""");
		// 주 카테고리: 전원 스킨케어(최빈). content_analyses 시드로 카테고리·광고를 같이 만든다.
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('a1', 'a', now() - interval '3 days', 'reels', 60000, 3500, 160, false),
				  ('a2', 'a', now() - interval '2 days', 'reels', 40000, 2500, 140, false),
				  ('b1', 'b', now() - interval '3 days', 'reels', 30000, 2000, 100, false),
				  ('c1', 'c', now() - interval '3 days', 'reels', 10000, 1000, 50, false),
				  ('d1', 'd', now() - interval '3 days', 'feed',  NULL,   500, 20, false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, analyzed_at, model, is_beauty, main_category, ad_type)
				VALUES
				  ('a1', now(), 'm', true, 'skincare', 'sponsored'),
				  ('a2', now(), 'm', true, 'skincare', 'organic'),
				  ('b1', now(), 'm', true, 'skincare', 'organic'),
				  ('c1', now(), 'm', true, 'skincare', 'organic'),
				  ('d1', now(), 'm', true, 'skincare', 'organic')""");
	}

	@Test
	void 피어_그룹과_퍼센타일() {
		Map<String, Object> a = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'a'");
		assertEquals("스킨케어", a.get("peer_category")); // beauty_taxonomy main_label
		assertEquals("1만-5만", a.get("follower_bucket"));
		assertEquals(4L, a.get("peer_size"));
		assertEquals(0, a.get("top_pct_views"));   // avg_views 1위 → percent_rank 0
		Map<String, Object> c = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'c'");
		assertEquals(100, c.get("top_pct_views")); // 3계정 중 꼴찌 → 100
	}

	@Test
	void NULL_지표는_순위에서_제외() {
		Map<String, Object> d = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'd'");
		assertNull(d.get("top_pct_views"));
		// er 순위(4.0/3.0/2.0/1.0 중 1.0)는 있어야 한다
		assertEquals(100, d.get("top_pct_er"));
	}

	@Test
	void 광고_지표는_ad_type_정본으로_계산() {
		Map<String, Object> a = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'a'");
		assertEquals(0, a.get("top_pct_ad_views")); // 광고 게시물 보유 계정이 a뿐 → 단독 1위
		Map<String, Object> b = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'b'");
		assertNull(b.get("top_pct_ad_views"));      // 광고 없음 → NULL
	}

	@Test
	void 중앙값_ER() {
		Map<String, Object> a = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'a'");
		// 4.0, 3.0, 2.0, 1.0의 중앙값 = 2.5
		assertEquals(new java.math.BigDecimal("2.5"), a.get("peer_median_er_pct"));
		assertEquals(new java.math.BigDecimal("2.5"), a.get("global_median_er_pct"));
	}
}
```

주의: `content_analyses` INSERT의 NOT NULL 컬럼은 V3 DDL을 열어 확인하고 필요한 최소 컬럼을 채운다(위 코드가 컴파일 후 INSERT 제약으로 깨지면 V3의 required 컬럼을 추가).

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.mirror.AccountPeerStatsViewTest"
```
Expected: FAIL — `relation "account_peer_stats" does not exist`

- [ ] **Step 3: V39 마이그레이션 작성**

```sql
-- 리포트 개편(07-27): 피어 그룹(주 카테고리 × 팔로워 버킷) 내 지표 퍼센타일 + 중앙값 ER.
-- analysis DB 파생 뷰(V35 account_category_stats 패턴) — 미러 아님, was가 직접 읽는다.
-- 광고 지표는 캡션 분류 정본(content_analyses.ad_type='sponsored') 기준 (AccountAdCanon과 동일).
-- 버킷 경계는 하드코딩 — analysis DB에는 app_setting이 없고, 경계 변경은 후속 마이그레이션으로
-- (beauty_taxonomy 어휘 수정과 같은 규약).
-- percent_rank 규약: 값 큰 쪽이 0(그룹 1위) → 화면 "상위 X%"는 round(rank*100).
-- NULL 지표는 (지표 IS NULL) 파티션 분리로 순위 모수에서 제외하고 결과도 NULL.
CREATE VIEW account_peer_stats AS
WITH cat AS (
  SELECT DISTINCT ON (account_handle) account_handle, main_group
  FROM account_category_stats
  ORDER BY account_handle, content_count DESC, main_group
),
ad AS (
  SELECT s.account_handle,
         round(avg(s.views) FILTER (WHERE s.views > 0))::bigint                            AS ad_avg_views,
         round(avg((s.likes + s.comments)::numeric / NULLIF(ac.followers, 0)) * 100, 1)    AS ad_avg_er_pct,
         round(avg(s.likes))::bigint                                                        AS ad_avg_likes,
         round(avg(s.comments))::bigint                                                     AS ad_avg_comments
  FROM account_content_series s
  JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
  JOIN accounts ac ON ac.handle = s.account_handle
  GROUP BY s.account_handle
),
base AS (
  SELECT su.handle,
         COALESCE(c.main_group, '미분류') AS peer_category,
         CASE WHEN su.followers >= 500000 THEN '50만+'
              WHEN su.followers >= 100000 THEN '10만-50만'
              WHEN su.followers >=  50000 THEN '5만-10만'
              WHEN su.followers >=  10000 THEN '1만-5만'
              ELSE '1만 미만' END          AS follower_bucket,
         su.avg_views, su.avg_er_pct, su.avg_likes, su.avg_comments,
         ad.ad_avg_views, ad.ad_avg_er_pct, ad.ad_avg_likes, ad.ad_avg_comments
  FROM account_summaries su
  LEFT JOIN cat c ON c.account_handle = su.handle
  LEFT JOIN ad   ON ad.account_handle = su.handle
),
med AS (
  SELECT peer_category, follower_bucket,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS peer_median_er_pct
  FROM base
  GROUP BY 1, 2
),
gmed AS (
  SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS global_median_er_pct FROM base
)
SELECT b.handle, b.peer_category, b.follower_bucket,
       count(*) OVER peer AS peer_size,
       CASE WHEN b.avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_views IS NULL)
          ORDER BY b.avg_views DESC) * 100)::numeric)::int END       AS top_pct_views,
       CASE WHEN b.avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_er_pct IS NULL)
          ORDER BY b.avg_er_pct DESC) * 100)::numeric)::int END      AS top_pct_er,
       CASE WHEN b.avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_likes IS NULL)
          ORDER BY b.avg_likes DESC) * 100)::numeric)::int END       AS top_pct_likes,
       CASE WHEN b.avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_comments IS NULL)
          ORDER BY b.avg_comments DESC) * 100)::numeric)::int END    AS top_pct_comments,
       CASE WHEN b.ad_avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_views IS NULL)
          ORDER BY b.ad_avg_views DESC) * 100)::numeric)::int END    AS top_pct_ad_views,
       CASE WHEN b.ad_avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_er_pct IS NULL)
          ORDER BY b.ad_avg_er_pct DESC) * 100)::numeric)::int END   AS top_pct_ad_er,
       CASE WHEN b.ad_avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_likes IS NULL)
          ORDER BY b.ad_avg_likes DESC) * 100)::numeric)::int END    AS top_pct_ad_likes,
       CASE WHEN b.ad_avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_comments IS NULL)
          ORDER BY b.ad_avg_comments DESC) * 100)::numeric)::int END AS top_pct_ad_comments,
       round(m.peer_median_er_pct::numeric, 1)   AS peer_median_er_pct,
       round(g.global_median_er_pct::numeric, 1) AS global_median_er_pct
FROM base b
JOIN med m USING (peer_category, follower_bucket)
CROSS JOIN gmed g
WINDOW peer AS (PARTITION BY b.peer_category, b.follower_bucket);

COMMENT ON VIEW account_peer_stats IS
  '피어(주 카테고리×팔로워 버킷) 퍼센타일 + 중앙값 ER — 리포트 개편(07-27, V39). 미러 아님.';
```

주의: `peer_category`는 `account_category_stats.main_group`이 이미 한글 라벨(`main_label` 폴백 slug)이다 — 테스트 기대값 '스킨케어'가 그 근거.

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.mirror.AccountPeerStatsViewTest"
```
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis/V39__account_peer_stats_view.sql \
        analytics/src/test/java/com/celfit/analytics/mirror/AccountPeerStatsViewTest.java
git commit -m "feat(analytics): 피어 퍼센타일 뷰 account_peer_stats 추가 (V39)"
```

---

### Task 2: V40 — `account_analyses` 요약 3분할 컬럼 + contract record

INSERT-only 이력이므로 구 컬럼(summary·trend_note·chart_note·ad_headline·pace_note)은 **DROP 하지 않고** 신규 3컬럼을 끝에 추가한다. `FlywaySchemaTest`의 "컬럼 이름·순서 = record" 규약 때문에 record도 같은 순서로 끝에 append.

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V40__account_analyses_section_summaries.sql`
- Modify: `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountAnalysis.java`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 리포트 개편(07-27): 단일 summary → 섹션별 3분할(성과/콘텐츠/광고).
-- 이력 테이블(INSERT-only)이라 구 컬럼은 보존 — 과거 행 판독용. 신규 행은 구 카피 컬럼에 NULL을 쓴다.
ALTER TABLE account_analyses ADD COLUMN perf_summary    text;
ALTER TABLE account_analyses ADD COLUMN content_summary text;
ALTER TABLE account_analyses ADD COLUMN ad_summary      text;

COMMENT ON COLUMN account_analyses.summary     IS '구 단일 요약 — 07-27 개편 후 미기록(과거 이력만)';
COMMENT ON COLUMN account_analyses.trend_note  IS '미기록(07-27) — 프론트가 추이 그래프로 대체';
COMMENT ON COLUMN account_analyses.chart_note  IS '미기록(07-27) — 프론트가 게시물별 차트로 대체';
COMMENT ON COLUMN account_analyses.ad_headline IS '미기록(07-27) — was 템플릿 조립으로 대체';
COMMENT ON COLUMN account_analyses.pace_note   IS '미기록(07-27) — 표시 제거';
```

- [ ] **Step 2: contract record 확장**

`AccountAnalysis.java` — 기존 12컴포넌트 끝에 3개 append (DDL 순서와 일치):

```java
public record AccountAnalysis(String handle, OffsetDateTime analyzedAt, String model,
		OffsetDateTime inputLastPostedAt, Long inputAnalyzedCount, String tagline, String summary,
		String trendNote, String chartNote, List<String> traits, String adHeadline, String paceNote,
		String perfSummary, String contentSummary, String adSummary) {
}
```

javadoc의 adHeadline 설명 문단을 "07-27 개편 이후 미기록(was 템플릿 대체), perfSummary·contentSummary·adSummary가 섹션별 요약"으로 갱신.

- [ ] **Step 3: FlywaySchemaTest로 DDL=record 정합 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.mirror.FlywaySchemaTest"
```
Expected: PASS (`account_analyses` 대조 포함). 이 시점에 `AccountAnalysisJob`·`ClaudeBurstRunner`가 `new AccountAnalysis(...)` 12인자 호출로 **컴파일 실패**한다 — 임시로 세 인자에 `null, null, null`을 덧붙여 컴파일만 살린다(Task 4·5에서 실값 배선).

- [ ] **Step 4: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis/V40__account_analyses_section_summaries.sql \
        contract-analysis/src/main/java/com/celfit/contract/analysis/AccountAnalysis.java \
        analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java \
        analytics/src/main/java/com/celfit/analytics/analyze/ClaudeBurstRunner.java
git commit -m "feat(analytics): account_analyses 요약 3분할 컬럼 추가 (V40) + 계약 record 확장"
```

---

### Task 3: LLM 카피 5종 — `AccountCopy`·`GeminiAccountSynthesizer`

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AccountCopy.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/GeminiAccountSynthesizerTest.java`

- [ ] **Step 1: 테스트를 신 계약으로 수정 (실패 상태로)**

`GeminiAccountSynthesizerTest`에서:
- `RESPONSE` 픽스처 교체:

```java
static final String RESPONSE = """
		{"tagline":"저자극 스킨케어 중심 · 성분표를 짚어가며 사용 전후 비교로 설득하는 정보형 리뷰 톤",
		 "traits":["정보형","스킨케어"],
		 "perfSummary":"평균 조회수가 팔로워의 0.4배 수준입니다.",
		 "contentSummary":"성분 설명과 사용 전후 비교가 반복됩니다.",
		 "adSummary":""}""";
```

- `카피_7종을_레코드로_돌려준다` → `카피_5종을_레코드로_돌려준다`로 개명하고 `copy.perfSummary()`·`copy.contentSummary()` 단언 추가, `summary()`·`trendNote()` 단언 제거.
- `프롬프트에_절제규칙과_광고_상황이_실린다`의 `schema().contains("adHeadline")` → `schema().contains("adSummary")`, 그리고 `assertTrue(!calls.get(0).schema().contains("adHeadline"))` 추가.
- `지시문이_광고_헤드라인의_평가를_금지한다` → 지시문에 여전히 "평가나 권유는 쓰지 마라"와 `AdSituation` 4케이스 라벨이 있는지는 유지(adSummary 규칙으로 이동), 메서드명을 `지시문이_광고_요약의_평가를_금지한다`로.
- tagline 상세화 검증 추가:

```java
@Test
void 지시문이_태그라인_구체화를_요구한다() {
	String system = GeminiAccountSynthesizer.instructions();
	assertTrue(system.contains("70자 이내"), system);
	assertTrue(system.contains("전개 방식"), system);
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.GeminiAccountSynthesizerTest"
```
Expected: COMPILE FAIL (AccountCopy 필드 불일치)

- [ ] **Step 3: 구현**

`AccountCopy.java`:

```java
/** LLM 계정 카피 산출 — 리포트 개편(07-27) 5종: 태그라인·성향 태그·섹션 요약 3종. */
public record AccountCopy(String tagline, List<String> traits,
		String perfSummary, String contentSummary, String adSummary) {
}
```

`GeminiAccountSynthesizer.java` — SCHEMA·INSTRUCTIONS 교체(클래스 나머지는 그대로):

```java
	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "tagline":{"type":"string"},"traits":{"type":"array","items":{"type":"string"}},
			  "perfSummary":{"type":"string"},"contentSummary":{"type":"string"},
			  "adSummary":{"type":"string"}},
			 "required":["tagline","traits","perfSummary","contentSummary","adSummary"]}""";

	static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: 프로필 헤더 한 줄 소개. 무엇을 다루는 계정인지에 더해 말투·전개 방식·설득
			  방식(예: 성분 근거 제시, 사용 전후 비교, 브이로그형)까지 구체적으로. "·"로 구획, 70자 이내
			  (예: "저자극 스킨케어 중심 · 성분표를 짚어가며 사용 전후 비교로 설득하는 정보형 리뷰 톤")
			- traits: 콘텐츠 성향 태그 3~5개, 각 2~6자 명사구
			- perfSummary: 성과 요약 2~3문장 — 평균 지표의 수준(팔로워 대비), 최근 흐름, 포맷(릴스/피드)별
			  반응 차이 중심 (계정 지표의 recent·trend 수치 근거)
			- contentSummary: 콘텐츠 성격 요약 2~3문장 — 무엇을 어떤 방식으로 다루는지, 반복되는 형식·톤
			  (카테고리 믹스·캡션 근거)
			- adSummary: 광고 활동 요약 2~3문장. 입력의 "광고 활동" 값에 따라 아래처럼 쓴다.
			  어느 경우든 **좋다·나쁘다·유리하다·적합하다 같은 평가나 권유는 쓰지 마라** — 수치와 사실만
			  객관적으로 진술하고 판단은 읽는 사람에게 맡긴다.
			  · "비교 가능": organic 평균과 협찬 평균의 차이, 광고에서의 톤 변화 여부를 수치·캡션 근거로
			  · "협찬 없음": 협찬 표기 게시물이 없다는 사실과 해당 기간의 지표를 그대로 진술
			  · "전량 협찬": 협찬 건수·비중과 그 성과 수치를 진술하고, 비교할 organic이 없다는 점을 밝힌다
			  · "판단 불가": 빈 문자열

			%s""".formatted(LlmGuard.RULES);
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.GeminiAccountSynthesizerTest"
```
Expected: PASS. (이 시점에 Job·BurstRunner가 `copy.summary()` 등으로 컴파일 실패하면 Task 4·5 선반영 대상 — 컴파일만 살리는 최소 수정 허용)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/AccountCopy.java \
        analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java \
        analytics/src/test/java/com/celfit/analytics/llm/GeminiAccountSynthesizerTest.java
git commit -m "feat(analytics): 계정 카피 7종→5종 — 요약 3분할·tagline 상세화, 헤드라인 LLM 제거"
```

---

### Task 4: `AccountAnalysisJob` — INSERT·가드·백필 대상 조건

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java`

- [ ] **Step 1: 테스트 수정 (실패 상태로)**

`AccountAnalysisJobTest`에서:
- `fakePort()` 응답 교체:

```java
	AccountSynthesisPort fakePort() {
		return account -> {
			calls.add(account);
			return new AccountCopy("태그라인: " + account.handle(),
					List.of("저자극", "성분리뷰", "정보형"),
					"성과 요약", "콘텐츠 요약", "광고 요약");
		};
	}
```

- 저장 단언을 신규 컬럼으로 교체(기존 `ad_headline`/`pace_note` 단언 위치):
  - `perf_summary`·`content_summary` 저장 확인, `summary`·`trend_note`·`chart_note`·`ad_headline`·`pace_note`는 **NULL** 저장 확인
  - adSummary 조건부: `AdSituation.INSUFFICIENT` 계정은 `ad_summary` NULL, 그 외 저장
- 빈 카피 가드 테스트의 실패 유발 픽스처를 `perfSummary` 공백으로 교체
- **백필 재대상 테스트 추가**:

```java
	@Test
	void 구_스키마_행은_perf_summary가_비어_재대상이_된다() {
		// 신 스키마 이전에 쌓인 행: 입력 동일(stale 아님)이지만 perf_summary가 없다
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, summary, traits)
				VALUES ('acct_ad', now() - interval '10 days', 'm',
				  timestamptz '2026-07-01 09:00:00+09', 6, '옛 태그라인', '옛 요약', '["a"]'::jsonb)""");
		rewireJob(fakePort());

		job.run();

		// 재분석돼 이력 2행, 최신 행에는 perf_summary가 있다
		assertEquals(2, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
		assertEquals("성과 요약", db.queryForObject("""
				SELECT perf_summary FROM account_analyses WHERE handle = 'acct_ad'
				ORDER BY analyzed_at DESC LIMIT 1""", String.class));
	}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountAnalysisJobTest"
```
Expected: FAIL

- [ ] **Step 3: 구현**

대상 쿼리(백필 조건 추가 — batch limit이 유일한 스로틀, 쿨다운은 stale 재분석에만):

```java
		List<String> targets = analysis.queryForList("""
				SELECT s.handle
				FROM account_summaries s
				LEFT JOIN LATERAL (
				  SELECT a.input_last_posted_at, a.analyzed_at, a.perf_summary
				  FROM account_analyses a WHERE a.handle = s.handle
				  ORDER BY a.analyzed_at DESC LIMIT 1
				) latest ON true
				WHERE latest.analyzed_at IS NULL
				   OR latest.perf_summary IS NULL  -- 07-27 개편 백필: 구 스키마 행 자연 재대상
				   OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
				       AND latest.analyzed_at < now() - make_interval(days => ?))
				ORDER BY s.handle
				LIMIT ?""", String.class,
				settings.accountAnalyzeCooldownDays(), settings.accountAnalyzeBatchLimit());
```

`analyzeOne` 저장부 교체:

```java
		// 이력 INSERT 전 가드 — 빈 카피가 "최신 행"으로 서빙되는 것을 차단
		if (isBlank(copy.tagline()) || isBlank(copy.perfSummary()) || isBlank(copy.contentSummary())) {
			throw new IllegalStateException("계정 카피가 비어 있음: " + handle);
		}
		if (copy.traits() == null || copy.traits().isEmpty()) {
			throw new IllegalStateException("traits가 비어 있음: " + handle);
		}
		List<String> traits = List.copyOf(copy.traits().size() > MAX_TRAITS
				? copy.traits().subList(0, MAX_TRAITS) : copy.traits());

		// 구 카피 5컬럼(summary·trend/chart_note·ad_headline·pace_note)은 07-27 개편 후 미기록(NULL).
		AccountAnalysis row = new AccountAnalysis(handle, OffsetDateTime.now(), model,
				lastPostedAt, analyzedCount, copy.tagline(), null, null, null, traits, null, null,
				copy.perfSummary(), copy.contentSummary(),
				adSituation.writesHeadline() ? blankToNull(copy.adSummary()) : null);
		analysis.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, traits, perf_summary, content_summary, ad_summary)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
				row.handle(), row.analyzedAt(), row.model(), row.inputLastPostedAt(),
				row.inputAnalyzedCount(), row.tagline(), json.writeValueAsString(row.traits()),
				row.perfSummary(), row.contentSummary(), row.adSummary());
```

(`AdSituation.writesHeadline()`은 의미가 "광고 진술 근거가 있는가"라 adSummary에 그대로 재사용. 이름 변경은 하지 않는다 — 호출처 최소 변경.)

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountAnalysisJobTest"
```
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java \
        analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java
git commit -m "feat(analytics): 계정 카피 잡 신 스키마 전환 + perf_summary NULL 자연 백필"
```

---

### Task 5: `ClaudeBurstRunner` 정렬

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ClaudeBurstRunner.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ClaudeBurstRunnerTest.java`

- [ ] **Step 1: 테스트의 카피 JSON 픽스처를 5종으로 교체 후 실패 확인**

픽스처의 `{"tagline":...,"summary":...}` 류 JSON을 Task 3의 RESPONSE와 같은 5키 형태로 교체.

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ClaudeBurstRunnerTest"
```
Expected: FAIL

- [ ] **Step 2: `insertAccountCopy`의 INSERT를 Task 4와 동일 컬럼으로 교체**

`INSERT INTO account_analyses (... tagline, summary, trend_note, chart_note, traits, ad_headline, pace_note)` → `(... tagline, traits, perf_summary, content_summary, ad_summary)`. 가드·traits 절단도 Task 4와 동일 규칙 유지(기존 코드 구조를 따르되 컬럼만 정렬).

- [ ] **Step 3: 통과 확인 후 커밋**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ClaudeBurstRunnerTest"
git add analytics/src/main/java/com/celfit/analytics/analyze/ClaudeBurstRunner.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ClaudeBurstRunnerTest.java
git commit -m "feat(analytics): 버스트 러너 계정 카피 5종 정렬"
```

---

### Task 6: was DTO v2 — `InfluencerAiReport`

스펙 6.5 개편(호환 미유지 — 프론트도 동시 개편). 전체 파일 교체:

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/InfluencerAiReport.java`

- [ ] **Step 1: DTO 작성 (컴파일은 Task 8 완료 시점에 함께 확인)**

```java
package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 6.5 인플루언서 AI 리포트 응답 v2 (07-27 개편) — 카피 없음(account_analyses 미생성/구 스키마)
 * 이어도 블록 구조는 유지하고 카피 필드만 null.
 * 유효 팔로워·성장세·헤드라인은 알고리즘 산출(LLM 아님) — 산식은 Assembler 참조.
 */
public record InfluencerAiReport(String tagline, Long analyzedCount, Long totalPosts,
		Long effectiveFollowers, Integer effectiveFollowersPct,
		Stats stats, Chart chart, ContentMix contentMix, Ads ads, Activity activity) {

	/** 전체/광고 2행 × 4지표. ad 행은 광고 게시물이 없으면 null. */
	public record Stats(String metric, BigDecimal viewsPerFollower, String perfSummary,
			StatRow overall, StatRow ad) {
		public record StatRow(Stat views, Stat er, Stat likes, Stat comments) {
		}
		/** value: views·likes·comments는 정수, er은 퍼센트(소수 1). growthPct·topPct 산출 불가 시 null. */
		public record Stat(BigDecimal value, Integer growthPct, Integer topPct) {
		}
	}

	/** bars가 추이 그래프·게시물 차트·광고 스트립·브랜드별 게시물 패널의 단일 소스. */
	public record Chart(String metric, List<Bar> bars) {
		public record Bar(Long views, Long likes, Long comments, String postedAt,
				Boolean sponsored, String contentType, String caption, String thumbnailUrl,
				String brand) {
		}
	}

	public record ContentMix(String contentSummary, List<Category> categories, List<String> traits) {
		public record Category(String label, Long count) {
		}
	}

	public record Ads(String adSummary, Long sponsoredCount, List<Boolean> strip, String lastAdNote,
			BigDecimal adIntervalDays, Long lastAdDaysAgo, String headline,
			List<Brand> brands, List<Product> products) {
		public record Brand(String name, Long count) {
		}
		public record Product(String name, Long count) {
		}
	}

	public record Activity(Long lastUploadDaysAgo, Boolean isActive, BigDecimal avgIntervalDays) {
	}
}
```

제거된 것: `summary`, `Trend`(방향은 growthPct가 대체), `chart.note`, `Ads.Comparison`(ad 행으로 흡수), `paceNote`.

- [ ] **Step 2: 커밋은 Task 8에서 Repository·Assembler와 함께** (중간 상태는 컴파일 불가라 단독 커밋 불가)

---

### Task 7: Repository 확장

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java`

- [ ] **Step 1: 쿼리·record 수정**

`findSummary` — `followers` 추가(유효 팔로워·광고 ER 재료):

```java
	public Optional<SummaryRow> findSummary(String handle) {
		return jdbcClient.sql("""
				SELECT followers, analyzed_count, posts_count, metric, avg_views, views_per_follower,
				       avg_er_pct, avg_likes, avg_comments, last_posted_at, avg_interval_days
				FROM account_summaries
				WHERE handle = :h
				""").param("h", handle).query(SummaryRow.class).optional();
	}
```

(트렌드·광고 구컬럼은 더 이상 읽지 않는다 — 성장세·광고는 전부 series에서 계산.)

`findLatestCopy` — 신 컬럼:

```java
	public Optional<CopyRow> findLatestCopy(String handle) {
		return jdbcClient.sql("""
				SELECT tagline, traits::text AS traits_json, perf_summary, content_summary, ad_summary
				FROM account_analyses
				WHERE handle = :h
				ORDER BY analyzed_at DESC
				LIMIT 1
				""").param("h", handle).query(CopyRow.class).optional();
	}
```

`findSeries` — 미리보기 툴팁 재료(caption·thumbnail·대표 브랜드). 썸네일은 랭킹 카드와 같은 관용구(`ContentCardRow`의 `COALESCE('/img/' || object_path, thumbnail_url)`):

```java
	public List<SeriesRow> findSeries(String handle) {
		return jdbcClient.sql("""
				SELECT s.posted_at, s.content_type, s.views, s.likes, s.comments,
				       COALESCE(an.ad_type = 'sponsored', false) AS sponsored,
				       c.caption,
				       COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
				       (SELECT b->>'name' FROM jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				        LIMIT 1) AS brand
				FROM account_content_series s
				LEFT JOIN content_analyses an ON an.short_code = s.short_code
				LEFT JOIN contents c ON c.short_code = s.short_code
				LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = s.short_code
				WHERE s.account_handle = :h
				ORDER BY s.posted_at, s.short_code
				""").param("h", handle).query(SeriesRow.class).list();
	}
```

`findProducts` 신규(`findBrands` 복제, `detected_products`):

```java
	/** ads.products — 광고 콘텐츠(ad_type='sponsored')의 detected_products name 집계 (V30). */
	public List<ProductRow> findProducts(String handle) {
		return jdbcClient.sql("""
				SELECT p->>'name' AS name, count(*) AS cnt
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_products, '[]'::jsonb)) p
				WHERE s.account_handle = :h AND an.ad_type = 'sponsored'
				GROUP BY 1 ORDER BY cnt DESC, name
				""").param("h", handle).query(ProductRow.class).list();
	}
```

`findPeerStats` 신규(V39 뷰):

```java
	/** 피어 퍼센타일·중앙값 ER (account_peer_stats, V39) — 미러 아닌 파생 뷰 직접 읽기. */
	public Optional<PeerStatsRow> findPeerStats(String handle) {
		return jdbcClient.sql("""
				SELECT peer_size, top_pct_views, top_pct_er, top_pct_likes, top_pct_comments,
				       top_pct_ad_views, top_pct_ad_er, top_pct_ad_likes, top_pct_ad_comments,
				       peer_median_er_pct, global_median_er_pct
				FROM account_peer_stats
				WHERE handle = :h
				""").param("h", handle).query(PeerStatsRow.class).optional();
	}
```

record 정의(교체·추가):

```java
	public record SummaryRow(Long followers, Long analyzedCount, Long postsCount, String metric,
			Long avgViews, BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes,
			Long avgComments, OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
	}

	public record CopyRow(String tagline, String traitsJson, String perfSummary,
			String contentSummary, String adSummary) {
	}

	public record SeriesRow(OffsetDateTime postedAt, String contentType, Long views, Long likes,
			Long comments, Boolean sponsored, String caption, String thumbnailUrl, String brand) {
	}

	public record ProductRow(String name, Long cnt) {
	}

	public record PeerStatsRow(Long peerSize, Integer topPctViews, Integer topPctEr,
			Integer topPctLikes, Integer topPctComments, Integer topPctAdViews, Integer topPctAdEr,
			Integer topPctAdLikes, Integer topPctAdComments, BigDecimal peerMedianErPct,
			BigDecimal globalMedianErPct) {
	}
```

- [ ] **Step 2: 커밋은 Task 8에서 함께**

---

### Task 8: Assembler 재작성 + 단위 테스트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportAssemblerTest.java`

- [ ] **Step 1: 테스트 재작성 (실패 상태로)**

기존 픽스처를 신 record로 교체하고, 아래 계약을 단언한다(기존 `lastAdNote`·strip·KST 날짜 테스트는 시그니처만 맞춰 유지):

```java
	private SummaryRow fullSummary() {
		return new SummaryRow(10000L, 12L, 187L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L,
				OffsetDateTime.parse("2026-07-10T00:00:00Z"), new BigDecimal("2.5"));
	}

	private PeerStatsRow peer() {
		return new PeerStatsRow(20L, 18, 26, 32, 45, 39, 42, 48, 53,
				new BigDecimal("2.0"), new BigDecimal("2.4"));
	}

	private SeriesRow row(String at, Long views, long likes, long comments, boolean sp) {
		return new SeriesRow(OffsetDateTime.parse(at), "reels", views, likes, comments, sp,
				"캡션", "/img/t.jpg", sp ? "브랜드A" : null);
	}

	@Test
	void 성장세는_앞절반_뒤절반_평균_증감률() {
		// 앞절반(2건) views 평균 10000, 뒤절반(2건) 평균 15000 → +50%
		var series = List.of(
				row("2026-07-01T00:00:00Z", 8000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 12000L, 100, 10, false),
				row("2026-07-03T00:00:00Z", 14000L, 100, 10, false),
				row("2026-07-04T00:00:00Z", 16000L, 100, 10, false));
		var report = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());
		assertThat(report.stats().overall().views().growthPct()).isEqualTo(50);
	}

	@Test
	void 광고행은_sponsored만으로_계산하고_광고_없으면_null() {
		var noAds = List.of(row("2026-07-01T00:00:00Z", 8000L, 100, 10, false));
		assertThat(assembler.toReport(fullSummary(), null, noAds, List.of(), List.of(),
				List.of(), peer()).stats().ad()).isNull();

		var withAds = List.of(
				row("2026-07-01T00:00:00Z", 10000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 6000L, 300, 30, true),
				row("2026-07-03T00:00:00Z", 4000L, 400, 40, true));
		var ad = assembler.toReport(fullSummary(), null, withAds, List.of(), List.of(),
				List.of(), peer()).stats().ad();
		assertThat(ad.views().value()).isEqualByComparingTo("5000"); // (6000+4000)/2
		assertThat(ad.views().topPct()).isEqualTo(39);               // peer의 top_pct_ad_views
	}

	@Test
	void 유효_팔로워는_피어_중앙값_ER_대비_보정_상한_1() {
		// 계정 ER 3.10, 피어 중앙값 2.0 → 비율 1 초과 → 상한 1 → followers 그대로
		var r = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), peer());
		assertThat(r.effectiveFollowers()).isEqualTo(10000L);
		assertThat(r.effectiveFollowersPct()).isEqualTo(100);

		// 피어 중앙값 6.2 → 3.10/6.2 = 0.5
		var half = new PeerStatsRow(20L, null, null, null, null, null, null, null, null,
				new BigDecimal("6.2"), new BigDecimal("2.4"));
		var r2 = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), half);
		assertThat(r2.effectiveFollowers()).isEqualTo(5000L);
		assertThat(r2.effectiveFollowersPct()).isEqualTo(50);
	}

	@Test
	void 피어가_없으면_전체_중앙값_폴백_그것도_없으면_null() {
		var r = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), null);
		assertThat(r.effectiveFollowers()).isNull();
		assertThat(r.stats().overall().views().topPct()).isNull();
	}

	@Test
	void 헤드라인은_사실값_템플릿() {
		// 마지막 광고 2026-07-05(10일 전), 광고 2건 간격 4일, 최다 브랜드 "브랜드A"
		var series = List.of(
				row("2026-07-01T00:00:00Z", 10000L, 100, 10, true),
				row("2026-07-05T00:00:00Z", 8000L, 100, 10, true));
		var ads = assembler.toReport(fullSummary(), null, series, List.of(),
				List.of(new BrandRow("브랜드A", 2L)), List.of(), peer()).ads();
		assertThat(ads.headline()).isEqualTo("최근 10일 전 브랜드A 협업 · 평균 4일 간격으로 광고 진행");
		assertThat(ads.adIntervalDays()).isEqualByComparingTo("4.0");
		assertThat(ads.lastAdDaysAgo()).isEqualTo(10L);
	}

	@Test
	void 피어_3계정_미만이면_topPct_숨김() {
		var tiny = new PeerStatsRow(2L, 18, 26, 32, 45, 39, 42, 48, 53,
				new BigDecimal("2.0"), new BigDecimal("2.4"));
		var r = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), tiny);
		assertThat(r.stats().overall().views().topPct()).isNull();
	}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerReportAssemblerTest"
```
Expected: COMPILE FAIL → 구현 후 재실행

- [ ] **Step 3: Assembler 구현**

시그니처: `toReport(SummaryRow, CopyRow, List<SeriesRow>, List<CategoryRow>, List<BrandRow>, List<ProductRow>, PeerStatsRow /*nullable*/)`. 핵심 산식:

```java
	/** 성장세: 올린 순 앞절반(floor(n/2)) vs 뒤절반, 각 절반에서 값>0만 평균 —
	 *  10_account_detail trend CTE와 같은 경계·필터. 근거 부족(절반 비었거나 older 0)이면 null. */
	static Integer growthPct(List<Double> valuesInOrder) {
		int n = valuesInOrder.size();
		if (n < 2) {
			return null;
		}
		double olderSum = 0, newerSum = 0;
		int olderN = 0, newerN = 0;
		for (int i = 0; i < n; i++) {
			double v = valuesInOrder.get(i) == null ? 0 : valuesInOrder.get(i);
			if (v <= 0) {
				continue;
			}
			if (i < n / 2) { olderSum += v; olderN++; } else { newerSum += v; newerN++; }
		}
		if (olderN == 0 || newerN == 0 || olderSum == 0) {
			return null;
		}
		return (int) Math.round(((newerSum / newerN) / (olderSum / olderN) - 1) * 100);
	}
```

- 지표별 값 추출: views=`views`, er 대용=`likes+comments`(팔로워 상수라 증감률 동일), likes, comments. 전체 행은 series 전체, 광고 행은 `sponsored==true`만으로 `growthPct` 각각 계산.
- **overall StatRow**: 값은 summary(avg_views·avg_er_pct·avg_likes·avg_comments), topPct는 peer(top_pct_*).
- **ad StatRow**: sponsored 게시물이 0건이면 `null`. 값은 series 재계산 — views는 `views>0` 평균 반올림, er은 `avg(likes+comments)*100/followers` 소수 1자리(HALF_UP), likes·comments 평균 반올림. topPct는 peer(top_pct_ad_*).
- **topPct 규칙**: `peer == null || peer.peerSize() < 3`이면 전부 null(피어가 너무 작으면 무의미).
- **유효 팔로워**:

```java
	/** 유효 팔로워 = followers × min(1, 계정 ER / 기준 ER). 기준 = 피어 중앙값 ER(폴백 전체 중앙값).
	 *  휴리스틱(07-27 확정) — 정밀도보다 방향성. 근거 없으면 null(화면은 칸 숨김). */
	static Long effectiveFollowers(Long followers, BigDecimal accountErPct, PeerStatsRow peer) {
		if (followers == null || accountErPct == null || peer == null) {
			return null;
		}
		BigDecimal ref = peer.peerMedianErPct() != null ? peer.peerMedianErPct()
				: peer.globalMedianErPct();
		if (ref == null || ref.signum() <= 0) {
			return null;
		}
		double ratio = Math.min(1.0, accountErPct.doubleValue() / ref.doubleValue());
		return Math.round(followers * ratio);
	}
```

`effectiveFollowersPct` = `Math.toIntExact(Math.round(ratio * 100))` (같은 ratio, followers null 처리 동일).
- **광고 간격·최근일**: sponsored postedAt 목록에서 `lastAdDaysAgo = daysSince(max)`, `adIntervalDays = 간격 스팬/(건수-1)` 소수 1자리(HALF_UP), 광고 2건 미만이면 null — `avg_interval_days`와 같은 정의.
- **헤드라인 템플릿** (LLM 대체, 조사 회피 위해 "협업" 명사형):

```java
	/** 광고 헤드라인 — 사실값 템플릿(07-27 확정, LLM 아님). 광고 이력 없으면 null. */
	static String headline(Long lastAdDaysAgo, BigDecimal adIntervalDays, String topBrand) {
		if (lastAdDaysAgo == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(lastAdDaysAgo == 0 ? "오늘" : "최근 " + lastAdDaysAgo + "일 전");
		sb.append(topBrand != null ? " " + topBrand + " 협업" : " 광고 게시");
		if (adIntervalDays != null) {
			sb.append(" · 평균 ").append(adIntervalDays.setScale(0, RoundingMode.HALF_UP))
					.append("일 간격으로 광고 진행");
		}
		return sb.toString();
	}
```

topBrand = brands 첫 행(name) — findBrands가 이미 `cnt DESC` 정렬.
- **Bar**: 기존 매핑에 `caption`, `thumbnailUrl`, `brand` 통과. `lastAdNote`·KST 날짜·strip·isActive(14일)·traits 파싱은 기존 코드 유지.
- **카피 배치**: `perfSummary`→Stats, `contentSummary`→ContentMix, `adSummary`→Ads. copy null이면 전부 null(구조 유지).

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerReportAssemblerTest"
```
Expected: PASS

- [ ] **Step 5: 커밋 (Task 6·7·8 일괄)**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "feat(was): AI 리포트 v2 — 2행 스탯(성장세·퍼센타일)·유효 팔로워·미리보기 bars·헤드라인 템플릿"
```

---

### Task 9: Controller 배선 + 테스트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportController.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportControllerTest.java`

- [ ] **Step 1: 컨트롤러 수정**

```java
	@GetMapping("/v1/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReport> aiReport(@PathVariable String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		return ApiResponse.ok(assembler.toReport(summary,
				repository.findLatestCopy(influencerId).orElse(null),
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrands(influencerId),
				repository.findProducts(influencerId),
				repository.findPeerStats(influencerId).orElse(null)));
	}
```

- [ ] **Step 2: 컨트롤러 테스트 갱신**

`fullSummary()`·`CopyRow` 픽스처를 신 record로 교체하고 `findProducts`·`findPeerStats` 목 추가. jsonPath 단언을 신 구조로: `$.data.stats.overall.views.value`, `$.data.stats.overall.views.topPct`, `$.data.effectiveFollowers`, `$.data.ads.products[0].name`, `$.data.chart.bars[0].caption`. 404 테스트는 그대로.

- [ ] **Step 3: 실행·통과·커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerReportControllerTest"
git add was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportController.java \
        was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportControllerTest.java
git commit -m "feat(was): AI 리포트 v2 컨트롤러 배선"
```

---

### Task 10: 브랜드 협업 인플루언서 엔드포인트

`GET /v1/brands/{brand}/influencers` — 브랜드 칩 호버 "이 브랜드와 협업한 다른 인플루언서". `/v1/**` 기본 인증 체인에 자동 포함.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brand/BrandInfluencer.java`
- Create: `was/src/main/java/com/celfit/was/v1/brand/V1BrandRepository.java`
- Create: `was/src/main/java/com/celfit/was/v1/brand/V1BrandController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brand/V1BrandControllerTest.java`

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성** (`V1InfluencerReportControllerTest`와 같은 `@WebMvcTest` 구성)

```java
	@Test
	void 브랜드_협업_인플루언서_목록() throws Exception {
		given(repository.findInfluencers("브랜드A")).willReturn(List.of(
				new BrandInfluencer("minji.beauty", "민지", "/img/p.jpg", 85000L, 3L, "2026-07-20")));
		mockMvc.perform(get("/v1/brands/브랜드A/influencers").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].influencerId").value("minji.beauty"))
				.andExpect(jsonPath("$.data[0].collabCount").value(3));
	}
```

- [ ] **Step 2: 구현**

`BrandInfluencer.java`:

```java
package com.celfit.was.v1.brand;

/** 브랜드 협업 인플루언서 1행 — 리포트 브랜드 칩 호버용 (분석 결과끼리 조인, §4-4 허용). */
public record BrandInfluencer(String influencerId, String name, String profileImageUrl,
		Long followers, Long collabCount, String lastCollabAt) {
}
```

`V1BrandRepository.java`:

```java
package com.celfit.was.v1.brand;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 브랜드 → 협업 인플루언서 크로스 계정 조회. detected_brands(캡션 분류) 정본. */
@Repository
public class V1BrandRepository {

	private final JdbcClient jdbcClient;

	public V1BrandRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<BrandInfluencer> findInfluencers(String brand) {
		return jdbcClient.sql("""
				SELECT s.account_handle AS influencer_id, ac.display_name AS name,
				       COALESCE('/img/' || ip.object_path, ac.profile_image_url) AS profile_image_url,
				       ac.followers, count(*) AS collab_count,
				       to_char(max(s.posted_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD') AS last_collab_at
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
				JOIN accounts ac ON ac.handle = s.account_handle
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = s.account_handle
				CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				WHERE b->>'name' = :brand
				GROUP BY 1, 2, ip.object_path, ac.profile_image_url, ac.followers
				ORDER BY collab_count DESC, max(s.posted_at) DESC
				LIMIT 20
				""").param("brand", brand).query(BrandInfluencer.class).list();
	}
}
```

`V1BrandController.java`:

```java
package com.celfit.was.v1.brand;

import com.celfit.was.v1.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 브랜드 협업 조회 — 리포트 브랜드 칩 호버 (스펙 6.5 v2). */
@RestController
public class V1BrandController {

	private final V1BrandRepository repository;

	public V1BrandController(V1BrandRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/v1/brands/{brand}/influencers")
	public ApiResponse<List<BrandInfluencer>> influencers(@PathVariable String brand) {
		return ApiResponse.ok(repository.findInfluencers(brand));
	}
}
```

- [ ] **Step 3: 통과·커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.brand.V1BrandControllerTest"
git add was/src/main/java/com/celfit/was/v1/brand/ was/src/test/java/com/celfit/was/v1/brand/
git commit -m "feat(was): 브랜드 협업 인플루언서 조회 GET /v1/brands/{brand}/influencers"
```

---

### Task 11: 유사 인플루언서 엔드포인트

`GET /v1/influencers/{influencerId}/similar` — 동일 주 카테고리 후보를 traits 교집합·팔로워 근접으로 정렬, 상위 6. matchPct = Jaccard(교집합/합집합)×100, 교집합 0이면 null.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java` (쿼리 추가)
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportController.java` (엔드포인트 추가)
- Create: `was/src/main/java/com/celfit/was/v1/influencer/SimilarInfluencer.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportControllerTest.java` (케이스 추가)

- [ ] **Step 1: 실패하는 테스트 추가**

```java
	@Test
	void 유사_인플루언서_목록() throws Exception {
		given(repository.findSimilar("haeun.log")).willReturn(List.of(
				new V1InfluencerReportRepository.SimilarRow("minji.beauty", "민지", "/img/p.jpg",
						"저자극 스킨케어 성분 리뷰", 3L, 5L)));
		mockMvc.perform(get("/v1/influencers/haeun.log/similar").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].influencerId").value("minji.beauty"))
				.andExpect(jsonPath("$.data[0].matchPct").value(60)); // 3/5 Jaccard
	}
```

- [ ] **Step 2: 구현**

`SimilarInfluencer.java`:

```java
package com.celfit.was.v1.influencer;

/** 유사 인플루언서 1행 — traits·카테고리 겹침 휴리스틱(07-27 확정, 임베딩 아님). */
public record SimilarInfluencer(String influencerId, String name, String profileImageUrl,
		String tagline, Integer matchPct) {
}
```

Repository 쿼리 추가:

```java
	/** 유사 인플루언서 — 같은 주 카테고리(account_peer_stats) 후보를 traits 교집합 내림차순,
	 *  팔로워 근접 오름차순으로 상위 6. 카피 없는 계정은 후보 제외(LATERAL INNER). */
	public List<SimilarRow> findSimilar(String handle) {
		return jdbcClient.sql("""
				WITH me AS (
				  SELECT p.peer_category, ac.followers, la.traits
				  FROM account_peer_stats p
				  JOIN accounts ac ON ac.handle = p.handle
				  JOIN LATERAL (SELECT traits FROM account_analyses
				                WHERE handle = p.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  WHERE p.handle = :h
				)
				SELECT c.handle AS influencer_id, ac.display_name AS name,
				       COALESCE('/img/' || ip.object_path, ac.profile_image_url) AS profile_image_url,
				       la.tagline,
				       (SELECT count(*) FROM jsonb_array_elements_text(la.traits) t
				         WHERE t.value IN (SELECT value FROM jsonb_array_elements_text(me.traits))) AS overlap_n,
				       (SELECT count(DISTINCT value) FROM (
				          SELECT value FROM jsonb_array_elements_text(la.traits)
				          UNION ALL SELECT value FROM jsonb_array_elements_text(me.traits)) u) AS union_n
				FROM account_peer_stats c
				JOIN me ON c.peer_category = me.peer_category
				JOIN accounts ac ON ac.handle = c.handle
				JOIN LATERAL (SELECT tagline, traits FROM account_analyses
				              WHERE handle = c.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = c.handle
				WHERE c.handle <> :h
				ORDER BY overlap_n DESC, abs(ac.followers - me.followers) ASC
				LIMIT 6
				""").param("h", handle).query(SimilarRow.class).list();
	}

	public record SimilarRow(String influencerId, String name, String profileImageUrl,
			String tagline, Long overlapN, Long unionN) {
	}
```

컨트롤러 추가(matchPct 계산은 컨트롤러 인라인 — 조립 규칙이 한 줄이라 Assembler 불요):

```java
	@GetMapping("/v1/influencers/{influencerId}/similar")
	public ApiResponse<List<SimilarInfluencer>> similar(@PathVariable String influencerId) {
		return ApiResponse.ok(repository.findSimilar(influencerId).stream()
				.map(r -> new SimilarInfluencer(r.influencerId(), r.name(), r.profileImageUrl(),
						r.tagline(), r.overlapN() != null && r.overlapN() > 0 && r.unionN() > 0
								? Math.toIntExact(Math.round(r.overlapN() * 100.0 / r.unionN()))
								: null))
				.toList());
	}
```

주의: `union_n`은 `UNION ALL` + `count(DISTINCT value)`로 합집합 크기(중복 제거)를 구한다.

- [ ] **Step 3: 통과·커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerReportControllerTest"
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "feat(was): 유사 인플루언서 조회 GET /v1/influencers/{id}/similar (traits 겹침 휴리스틱)"
```

---

### Task 12: 전체 검증·문서·PR

- [ ] **Step 1: 전체 테스트** (Docker Desktop/colima 필요 — Testcontainers)

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL. 실패 시 그 테스트를 고치고 나서 진행(특히 `MirrorJobTest`·`FlywaySchemaTest`·기존 influencer 테스트의 연쇄 영향).

- [ ] **Step 2: 골드셋 프롬프트 스팟 체크 (수동, 선택)**

프롬프트 재작성이므로 하니스(`~/Downloads/goldset-spike-20260718`)로 5계정 안팎 출력 품질을 훑는다. 실행법은 하니스 README 참조. 통과 기준: 5필드 전부 생성, adSummary 4분기 준수, 수치 인용 오류 없음. (자동화 불가 — 결과를 PR 본문에 요약)

- [ ] **Step 3: ARCHITECTURE.md 갱신**

- §5 작업 트랙 표에 행 추가: `인플루언서 리포트 개편(백엔드) | feat/influencer-report-redesign | 이 계획 링크 | 진행중`
- §7 결정 기록에 3줄: ① 퍼센타일·중앙값은 analysis DB 파생 뷰(V39), 광고 정본 ad_type 유지 ② account_analyses 요약 3분할(V40), 구 컬럼 보존·미기록 ③ ad_headline·성장세·유효 팔로워는 was 알고리즘 산출(LLM 제거)

- [ ] **Step 4: 계획 문서 상태 갱신 + PR**

이 문서 상태 헤더를 `✅ 구현됨`으로 바꾸고 `plans/archive/`로 이동은 머지 후.

```bash
git add ARCHITECTURE.md docs/superpowers/plans/2026-07-27-influencer-report-redesign-backend.md
git commit -m "docs: 인플루언서 리포트 개편 트랙·결정 기록"
git push -u origin feat/influencer-report-redesign
gh pr create --base develop --title "feat: 인플루언서 리포트 개편 — 퍼센타일·요약 3분할·유효 팔로워·브랜드/유사 조회" --body "$(cat <<'EOF'
## 요약
- analysis DB: V39 account_peer_stats(피어 퍼센타일·중앙값 ER), V40 account_analyses 요약 3분할
- analytics: 계정 카피 7종→5종(tagline 상세화, ad_headline LLM 제거), perf_summary NULL 자연 백필
- was: 리포트 DTO v2(2행 스탯·성장세·유효 팔로워·미리보기 bars·헤드라인 템플릿), 브랜드/유사 인플루언서 신규 엔드포인트

## 배포 주의
- analytics 먼저(마이그레이션+새 카피 적재 시작) → was 나중. 프론트 v2 전환 전까지 리포트 화면 필드 불일치 발생(프론트 트랙과 배포 타이밍 협의)
- 전 계정 재분석 1회성 비용(~1,000콜, 새벽 배치 분산)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## 배포 런북 (머지 후)

1. **analytics 배포** — analysis DB Flyway V39·V40 자동 적용. 확인: `SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 2`
2. 다음 새벽 배치(또는 어드민 `/ui` 수동 트리거)에서 계정 카피 백필 시작 — `perf_summary IS NULL` 자연 재대상, batch limit이 스로틀. 확인: `SELECT count(*) FROM account_analyses WHERE perf_summary IS NOT NULL`
3. **was 배포** — 프론트 v2 반영과 타이밍 협의(응답 구조 breaking change)
4. 스팟 체크: 운영 계정 1건 `GET /v1/influencers/{h}/ai-report` — stats.overall.topPct 채워짐, effectiveFollowers 산출, bars에 caption/thumbnailUrl 확인

## 리스크·주의

- **마이그레이션 번호 경합**: 머지 직전 V39·V40 재확인 (V18 전례)
- **raw 뷰 무접촉**: `10_account_detail.sql` 변경 없음 → 운영 뷰 수동 적용·미러 절차 불필요
- **구 카피 서빙 공백**: 백필 완료 전 구 스키마 행은 tagline·traits만 서빙되고 요약 3종은 null — 프론트는 null 섹션 숨김 처리 필요
- **성장세 시맨틱**: 앞절반/뒤절반은 기존 trend CTE와 동일 경계(floor(n/2))·동일 필터(값>0) — 값이 다르게 보이면 이 정의부터 대조
- **`AdSituation.writesHeadline()`**: adSummary 기록 조건으로 재사용(이름은 유지) — INSUFFICIENT만 미기록
