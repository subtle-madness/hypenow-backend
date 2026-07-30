> 상태: ✅ 반영됨 (부분) — **A1·B1은 같은 날 다른 브랜치(feat/analytics-recent-window-pin, develop 머지)가 독립 구현**해 이 PR에서는 제외.
> 이 PR(A2만)이 실제로 담는 것: **콘텐츠 리포트 `comparison.views` 차트 라이브 분리 + `recentReels` contentId·isCurrent**(§ Task A2).
> A1(usable-핀 공유 뷰)·B1(ads ad_type 통일)은 develop에 이미 있으며, B1의 경우 develop 버전이 ER 비교·lastAdNote까지 통일해 더 완전하다(아래 §Phase B·요청 4 잔여는 develop에서 이미 해소). C1/D1 계약·미결 문서는 #63이 반영.

# AI 리포트 데이터 정합성 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 콘텐츠·인플루언서 AI 리포트가 목록/프로필 엔드포인트와 모순되는 문제(릴스 조회수 NULL, 광고 집계 0)를 뷰·서빙 계층에서 바로잡아, 같은 백엔드의 두 응답이 같은 데이터를 말하게 한다.

**Architecture:** 근본 원인은 셋이다 — ① 릴스 지표 핀의 `usable` 우선순위가 `v_contents`에만 있고 최근-윈도우 경로(`v_recent_content`→베이스라인·계정 시계열)엔 없어 빈 타임라인 스냅샷이 조회수를 NULL로 덮음(PR #58이 못 고친 쌍둥이 버그), ② 인플루언서 리포트 `ads`가 릴스 전용 `is_paid_partnership`를 쓰는데 카드 `adType`은 LLM `ad_type`을 써서 소스가 다름, ③ `comparison.views`의 baseline·rank·count가 분석 시점 `content_analyses`에 얼어붙어 라이브 `value`와 드리프트. 해결: ①은 핀 로직을 공유 뷰 `v_content_pinned_metric`으로 추출해 최근-윈도우 경로가 재사용(라이브 뷰가 자동 정정), ②는 `ads.strip`·`sponsoredCount`를 was에서 LLM `ad_type` 소스로 통일, ③은 사용자 결정("서술은 고정·차트는 라이브 분리")대로 차트 수치를 was에서 라이브 재계산하고 `content_analyses` 프리즈 컬럼은 AI 서술 전용으로 남긴다.

**Tech Stack:** PostgreSQL 분석 뷰(`analytics/views/*.sql`, SQL 하니스 `analytics/test/*.test.sql`), was Spring Boot(JdbcClient, record DTO, MockMvc/단위 테스트). 미러 테이블 스키마 무변경(뷰 컬럼 형태 유지).

---

## 배경 — 추적으로 확정한 사실 (구현 전 필독)

- `GET /v1/contents/{id}/ai-report`의 `comparison.views`는 [V1ContentReportAssembler.java:48](../../../was/src/main/java/com/celfit/was/v1/content/V1ContentReportAssembler.java)에서 조립: `value`=`contents.views`(라이브 핀), `baseline`·`rankInRecent`·`recentCount`=`content_analyses`(분석 시점 고정), `recentReels[]`=`account_content_series`(라이브·핀 미적용).
- 릴스 조회수 NULL의 원인: `account_content_series`→`v_account_recent`→`v_recent_content`→`v_base_detail`([00_base.sql:174](../../../analytics/views/00_base.sql))가 **`usable` 우선순위 없는 최신 스냅샷 승**. 재열거 때 SELF_GQL 타임라인(`video_view_count:0`→NULL)이 최신이라 clips의 실값을 덮는다. PR #58은 이 버그를 `v_contents`(02_serving)에서만 고쳤다.
- `value`(30,405)와 같은 릴스의 `recentReels` 슬롯(null)이 어긋난 것은 두 값이 **다른 핀 경로**를 타기 때문. A1이 두 경로를 하나로 합쳐 해소한다.
- `ads.sponsoredCount`·`strip`는 `account_summaries.sponsored_count`·`account_content_series.sponsored`(둘 다 `ad_marked`=`is_paid_partnership`, [01_recent_window.sql:17](../../../analytics/views/01_recent_window.sql))에서 오고, 카드 `adType`과 `ads.brands`는 `content_analyses.ad_type`(LLM)에서 온다. **소스 불일치**가 "8/9 sponsored인데 count 0, 브랜드 칩만 정상"의 정체.
- 제약: `analytics/views/*`는 raw DB에서 돌고 `content_analyses`(LLM `ad_type`)는 analysis DB에 있어, 최근-윈도우 뷰는 `ad_type`을 볼 수 없다. 그래서 ②의 통일은 **was 계층**에서 한다(was는 두 미러를 함께 읽을 수 있음 — `V1InfluencerReportRepository`가 이미 `account_content_series`⨝`content_analyses`를 함 [findBrands](../../../was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java)).

## File Structure

**분석 뷰 (raw DB, `analytics` 스키마)**
- Modify `analytics/views/00_base.sql` — 신규 뷰 `v_content_pinned_metric`(콘텐츠별 usable-핀 지표) 추가. `v_base_detail`(메타=최신) 바로 뒤, 형제 셀렉터로.
- Modify `analytics/views/02_serving.sql` — `v_contents`의 인라인 `snap`/`pinned` CTE를 `v_content_pinned_metric` 조인으로 대체(동작 동일).
- Modify `analytics/views/01_recent_window.sql` — `v_recent_content`의 지표(views/likes/comments) 소스를 `v_base_detail`→`v_content_pinned_metric`으로 교체(메타·`paid_partnership`는 `v_base_detail` 유지).

**SQL 하니스**
- Modify `analytics/test/01_recent_window.test.sql` — 최근-윈도우 경로 usable-핀 회귀 케이스 추가(빈 타임라인 스냅샷이 조회수를 덮지 않음).
- Modify `analytics/test/02_serving.test.sql`, `03_analysis_baseline.test.sql`, `10_account_detail.test.sql` — 핀 전환으로 바뀌는 기대값 정정(있으면).

**was — 콘텐츠 리포트 (comparison.views 라이브 분리 + contentId)**
- Modify `was/.../v1/content/V1ContentReportRepository.java` — `findRecentReels`가 `short_code`도 반환, `ReelPointRow`에 `shortCode` 추가.
- Modify `was/.../v1/content/ContentAiReport.java` — `ReelPoint`에 `contentId`·`isCurrent` 추가.
- Modify `was/.../v1/content/V1ContentReportAssembler.java` — `comparison.views`의 baseline·multiple·rankInRecent·recentCount를 fetched reels에서 라이브 계산, recentReels에 식별자 채움.
- Modify `was/.../v1/content/V1ContentReportAssemblerTest.java` — 라이브 계산·식별자 테스트.

**was — 인플루언서 리포트 (ads 소스 통일)**
- Modify `was/.../v1/influencer/V1InfluencerReportRepository.java` — `findSeries`가 `content_analyses.ad_type` LEFT JOIN으로 `sponsored`를 LLM 기준으로.
- Modify `was/.../v1/influencer/V1InfluencerReportAssembler.java` — `ads.sponsoredCount`를 series의 sponsored 합으로(=strip과 동일 소스).
- Modify `was/.../v1/influencer/V1InfluencerReportAssemblerTest.java` — 통일 소스 테스트.

**문서**
- Modify `ARCHITECTURE.md` — §7 결정 기록 1줄, §8 미결(ER organic/ad 소스 잔여) 반영.

---

## Phase A — 콘텐츠 AI 리포트 comparison.views 정합성

### Task A1: 공유 핀 뷰 추출 + 최근-윈도우 경로 지표 소스 통일

**Files:**
- Modify: `analytics/views/00_base.sql` (v_base_detail 정의 뒤, 186행 근처)
- Modify: `analytics/views/02_serving.sql:69-114` (v_contents)
- Modify: `analytics/views/01_recent_window.sql:9-33` (v_recent_content)
- Test: `analytics/test/01_recent_window.test.sql`

> **핀 우선순위 불변식(PR #58과 동일):** ① 성숙∧완비 중 가장 이른 것 → ② 완비 중 최신 → ③ 성숙 중 가장 이른 것 → ④ 최신. 이 순서를 `v_content_pinned_metric` 한 곳에만 두고 `v_contents`·`v_recent_content`가 공유한다.

- [ ] **Step 1: 실패 테스트 작성 — 최근-윈도우 경로가 빈 타임라인 스냅샷에 오염되지 않아야 한다**

`analytics/test/01_recent_window.test.sql` 파일 끝에 아래 블록을 추가한다. (fixture는 02b와 같은 구조 — 성숙 타임라인 스냅샷 views NULL이 성숙 clips play_count 9000보다 이르게 수집된 릴스.)

```sql
-- [A1 회귀] 최근-윈도우 경로도 usable-핀을 써야 한다(v_contents 밖의 쌍둥이 버그).
-- 빈 타임라인 스냅샷(06-05, views NULL)이 clips 스냅샷(06-07, 9000)보다 이르게 수집된 릴스.
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990301,'dummy_rw','REELS','dummy_a',99990001, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990001,99990000,'SELF_GQL','dummy_a',5500,
  '{"status":"ok","data":{"user":{"username":"dummy_a","edge_owner_to_timeline_media":{"count":1,"edges":[
     {"node":{"shortcode":"dummy_rw","product_type":"clips","video_view_count":0,
              "edge_media_preview_like":{"count":400},"edge_media_to_comment":{"count":40},
              "edge_media_to_caption":{"edges":[{"node":{"text":"cap rw tl"}}]},
              "display_url":"https://thumb/rw_tl.jpg"}}
   ]}}}}'::jsonb,
  timestamptz '2026-06-05 12:00:00+09');
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_rw","product_type":"clips","taken_at":1780272000,"like_count":410,"comment_count":41,"play_count":9000,"video_duration":22.0,"is_paid_partnership":false,"caption":{"text":"cap rw"},"image_versions2":{"candidates":[{"url":"https://thumb/rw_v1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09');

DO $$
BEGIN
  ASSERT (SELECT views FROM analytics.v_recent_content WHERE short_code = 'dummy_rw') = 9000,
    'v_recent_content rw views != 9000 (완비 clips 스냅샷을 핀해야 함 — 빈 타임라인 오염 금지)';
  ASSERT (SELECT likes FROM analytics.v_recent_content WHERE short_code = 'dummy_rw') = 410,
    'v_recent_content rw likes != 410';
END $$;
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `PG_CONTAINER=crawler-postgres-1 ./analytics/test/run.sh analytics/test/01_recent_window.test.sql`
Expected: FAIL — `v_recent_content rw views != 9000` (현재는 최신 타임라인 스냅샷 NULL이 핀됨). 컨테이너명은 머신에 따라 `hypenow-crawler-postgres-1` 등 — `PG_CONTAINER`로 오버라이드(CLAUDE.md 함정).

- [ ] **Step 3: `v_content_pinned_metric` 뷰 추가 (`analytics/views/00_base.sql`)**

`v_base_detail` 정의(186행 `ORDER BY content_id, captured_at DESC, id DESC;`) 바로 뒤에 아래를 추가한다.

```sql
-- 콘텐츠별 지표 핀(usable 우선). v_base_detail(메타=최신)의 형제 — 이쪽은 지표 전용.
-- 우선순위: ① 성숙∧완비 최이른 → ② 완비 최신 → ③ 성숙 최이른 → ④ 최신 (PR #58과 동일).
-- 여러 서빙 뷰(v_contents·v_recent_content)가 공유해 "지표 소스"를 한 곳으로 고정한다.
-- matured = 업로드 +N일(app_setting 'analytics.metric-pin-days', 기본 3) 이후 수집.
-- usable = hype/평균에 필요한 지표 완비(릴스는 views·likes·comments, 피드는 likes·comments).
CREATE OR REPLACE VIEW analytics.v_content_pinned_metric AS
WITH snap AS (
  SELECT h.id, h.content_id, h.views, h.likes, h.comments_count, h.captured_at,
         h.captured_at >= c.uploaded_at + make_interval(days => COALESCE(
           (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)) AS matured,
         (h.likes IS NOT NULL AND h.comments_count IS NOT NULL
          AND (c.content_type <> 'REELS' OR h.views IS NOT NULL)) AS usable
  FROM analytics.v_base_content_snapshot h
  JOIN analytics.v_base_content c USING (content_id)
)
SELECT DISTINCT ON (content_id) content_id, views, likes, comments_count, captured_at
FROM snap
ORDER BY content_id,
         (matured AND usable) DESC,
         CASE WHEN matured AND usable THEN captured_at END ASC,
         CASE WHEN matured AND usable THEN id END ASC,
         usable DESC,
         CASE WHEN usable THEN captured_at END DESC,
         CASE WHEN usable THEN id END DESC,
         matured DESC,
         CASE WHEN matured THEN captured_at END ASC,
         CASE WHEN matured THEN id END ASC,
         captured_at DESC, id DESC;
```

- [ ] **Step 4: `v_contents`를 공유 뷰 조인으로 리팩터 (`analytics/views/02_serving.sql:69-114`)**

`v_contents`의 `WITH snap AS (...) , pinned AS (...)` CTE 전체(70-95행)를 제거하고, 본문의 `JOIN pinned p USING (content_id)`(113행)를 `JOIN analytics.v_content_pinned_metric p USING (content_id)`로 바꾼다. 66-68행의 usable 설명 주석은 `v_content_pinned_metric`으로 옮겨졌으니 요약만 남긴다. 결과(96-114행 형태):

```sql
CREATE OR REPLACE VIEW analytics.v_contents AS
SELECT
  e.short_code,
  e.owner_username AS account_handle,
  d.thumbnail_url,
  d.caption,
  e.uploaded_at AS posted_at,
  lower(e.content_type) AS content_type,
  d.video_duration,
  'https://www.instagram.com/p/' || e.short_code || '/' AS original_url,
  p.views,
  p.likes,
  p.comments_count AS comments,
  analytics.hype_score(lower(e.content_type), p.views, p.likes, p.comments_count, pr.followers,
                       extract(epoch FROM (now() - e.uploaded_at)) / 86400.0) AS hype_score,
  p.captured_at AS metric_captured_at
FROM analytics.v_serving_content e
JOIN analytics.v_base_detail d USING (content_id)
JOIN analytics.v_content_pinned_metric p USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username;
```

- [ ] **Step 5: `v_recent_content` 지표 소스 교체 (`analytics/views/01_recent_window.sql:9-33`)**

`ranked` CTE에서 지표를 `v_content_pinned_metric`에서 가져오고, `paid_partnership`·`video_duration`은 `v_base_detail` 유지. 8행 주석의 "ad_marked ... is_paid_partnership" 서술은 그대로 둔다(광고 소스는 이 태스크에서 안 바꿈).

```sql
CREATE OR REPLACE VIEW analytics.v_recent_content AS
WITH ranked AS (
  SELECT
    c.content_id,
    c.short_code,
    c.owner_username,
    c.uploaded_at,
    c.content_type,
    d.paid_partnership AS ad_marked,
    pm.likes,
    pm.comments_count,
    pm.views,
    d.video_duration,
    row_number() OVER (PARTITION BY c.owner_username
                       ORDER BY c.uploaded_at DESC, c.content_id DESC) AS recency_rank
  FROM analytics.v_base_content c
  JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
  JOIN analytics.v_base_detail d USING (content_id)
  JOIN analytics.v_content_pinned_metric pm USING (content_id)
  WHERE c.origin = 'ENUMERATION'
    AND i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company
)
SELECT *
FROM ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);
```

- [ ] **Step 6: 새 테스트 통과 확인**

Run: `PG_CONTAINER=crawler-postgres-1 ./analytics/test/run.sh analytics/test/01_recent_window.test.sql`
Expected: PASS (`ALL GREEN`).

- [ ] **Step 7: 전체 하니스 실행 — 핀 전환 파급 확인**

Run: `PG_CONTAINER=crawler-postgres-1 ./analytics/test/run.sh`
Expected: 02b_reels_pin는 여전히 PASS(v_contents 동작 불변). 만약 01/03/10 테스트가 FAIL하면, 그 fixture가 콘텐츠당 스냅샷 여러 개를 최신-승 전제로 기대값을 박아둔 것 — `analytics/test/seed/dummy.sql`과 해당 `.test.sql`을 읽고, 최신-승이 아니라 usable-핀 결과로 기대값을 정정한다(값이 실제로 바뀌는 게 맞는 케이스만; NULL이 실값으로 복구되는 방향이어야 한다). 정정 후 다시 실행해 `ALL GREEN` 확인.

- [ ] **Step 8: 커밋**

```bash
git add analytics/views/00_base.sql analytics/views/02_serving.sql analytics/views/01_recent_window.sql analytics/test/
git commit -m "fix(analytics): 최근-윈도우 지표 경로에 usable-핀 공유 뷰 적용 — 릴스 조회수 NULL 오염 차단

v_contents에만 있던 usable 우선순위 핀(PR #58)을 v_content_pinned_metric으로 추출해
v_recent_content(베이스라인·계정 시계열의 상류)도 공유. 빈 SELF_GQL 타임라인 스냅샷이
clips 실값을 덮어 recentReels·baseline이 NULL이 되던 쌍둥이 버그 해소. 카드 조회수와
recentReels 조회수가 같은 핀 소스를 써 엔드포인트 간 값도 일치."
```

### Task A2: comparison.views 차트 라이브 분리 + recentReels 식별자

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/content/ContentAiReport.java:16-20` (ReelPoint)
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentReportRepository.java:46-52,88` (findRecentReels, ReelPointRow)
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentReportAssembler.java:48-62`
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentReportAssemblerTest.java`

> **정책(사용자 결정):** AI 서술이 인용한 수치는 `content_analyses`에 고정 유지, **차트(comparison.views)의 baseline·multiple·rankInRecent·recentCount는 라이브 재계산**. 라이브 재계산은 이미 fetch하는 recentReels 리스트 하나에서 파생해 블록 내부 정합을 보장한다(§4-2의 "표현 조립"). `content_analyses.recent_reels_*` 컬럼은 삭제하지 않는다(서술 생성 입력으로 잔존).

- [ ] **Step 1: 실패 테스트 작성 — 라이브 baseline·rank·count·식별자**

`V1ContentReportAssemblerTest.java`에 아래 테스트를 추가한다. 현재 콘텐츠 `sc1`(릴스, views 30405)과 계정 릴스 3개(sc0=5040, sc1=30405, sc2=null) 시나리오. baseline=avg(비NULL)=round((5040+30405)/2)=17723, count=2, rank(sc1)=1, multiple=30405/17723=1.7, isCurrent는 sc1만 true. (프리즈 컬럼 `recentReelsAvgViews` 등은 일부러 다른 값 999로 두어 무시됨을 검증.)

```java
@Test
void comparisonViews_라이브_재계산_프리즈컬럼_무시_식별자_채움() {
    var row = reportRowWithViews("sc1", 30405L, /*frozenBaseline*/ 999L, /*frozenRank*/ 9, /*frozenCount*/ 9);
    var reels = List.of(
            new V1ContentReportRepository.ReelPointRow("sc0", 5040L, odt("2026-07-08")),
            new V1ContentReportRepository.ReelPointRow("sc1", 30405L, odt("2026-07-14")),
            new V1ContentReportRepository.ReelPointRow("sc2", null, odt("2026-07-17")));

    var report = assembler.toReport(row, reels, Map.of(), List.of());
    var v = report.comparison().views();

    assertThat(v.value()).isEqualTo(30405L);
    assertThat(v.baseline()).isEqualTo(17723L);          // 프리즈 999 아님 — 라이브
    assertThat(v.recentCount()).isEqualTo(2);            // 비NULL 릴스만
    assertThat(v.rankInRecent()).isEqualTo(1);          // 30405가 최고
    assertThat(v.multiple()).isEqualByComparingTo("1.7");
    assertThat(v.recentReels()).extracting("contentId").containsExactly("sc0", "sc1", "sc2");
    assertThat(v.recentReels()).extracting("isCurrent").containsExactly(false, true, false);
    assertThat(v.recentReels().get(2).views()).isNull();
}
```

`reportRowWithViews`·`odt` 헬퍼가 테스트에 없으면 기존 테스트의 `ReportRow` 생성 관용구를 재사용해 추가한다(모든 필드 채우되 위 4개만 유의미, `contentType`="reels", `shortCode`=인자). 시그니처는 `ReelPointRow(String shortCode, Long views, OffsetDateTime postedAt)`로 Step 3에서 확정.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests '*V1ContentReportAssemblerTest*'`
Expected: 컴파일 실패 — `ReelPointRow`에 `shortCode` 없음, `ReelPoint`에 `contentId`/`isCurrent` 없음.

- [ ] **Step 3: DTO·리포지토리에 식별자 추가**

`ContentAiReport.java:18` — `ReelPoint`에 필드 추가:

```java
public record ReelPoint(String contentId, Long views, String postedAt, boolean isCurrent) {
}
```

`V1ContentReportRepository.java` — `findRecentReels`가 `short_code`도 SELECT하고 `ReelPointRow`에 `shortCode` 추가:

```java
public List<ReelPointRow> findRecentReels(String handle) {
    return jdbcClient.sql("""
            SELECT short_code, views, posted_at FROM account_content_series
            WHERE account_handle = :h AND content_type = 'reels'
            ORDER BY posted_at
            """).param("h", handle).query(ReelPointRow.class).list();
}
```

```java
public record ReelPointRow(String shortCode, Long views, OffsetDateTime postedAt) {
}
```

- [ ] **Step 4: 어셈블러 라이브 재계산 (`V1ContentReportAssembler.java:48-62`)**

`comparison` 메서드를 아래로 교체한다. baseline/count/rank는 reels에서 파생, `value`는 `row.views()`.

```java
private ContentAiReport.Comparison comparison(ReportRow row, List<ReelPointRow> reels) {
    var recentReels = reels.stream()
            .map(r -> new ContentAiReport.Comparison.Views.ReelPoint(
                    r.shortCode(), r.views(), kstDate(r.postedAt()),
                    r.shortCode() != null && r.shortCode().equals(row.shortCode())))
            .toList();

    // 라이브 파생: 비NULL 릴스 조회수만 (v_analysis_baseline reels 규칙과 동일).
    List<Long> reelViews = reels.stream().map(ReelPointRow::views).filter(java.util.Objects::nonNull).toList();
    Long baseline = reelViews.isEmpty() ? null
            : Math.round(reelViews.stream().mapToLong(Long::longValue).average().orElse(0));
    Integer recentCount = reelViews.isEmpty() ? null : reelViews.size();
    Integer rankInRecent = rankInRecent(reels, row.shortCode());

    var views = new ContentAiReport.Comparison.Views(row.views(), baseline,
            multiple(row.views(), baseline), rankInRecent, recentCount, recentReels);
    var engagementRate = new ContentAiReport.Comparison.EngagementRate(
            engagementRateValue(row.views(), row.likes(), row.comments()),
            row.recent12AvgEngagementRate());
    var quality = new ContentAiReport.Comparison.EngagementQuality(
            new ContentAiReport.Comparison.EngagementQuality.Counts(row.likes(), row.recent12AvgLikeCount()),
            new ContentAiReport.Comparison.EngagementQuality.Counts(row.comments(), row.recent12AvgCommentCount()));
    return new ContentAiReport.Comparison(views, engagementRate, quality, row.contentsPattern());
}

/** 이 콘텐츠의 조회수 랭크(비NULL 릴스 중 내림차순, 경쟁 랭크). 본인 views NULL이면 null. */
private Integer rankInRecent(List<ReelPointRow> reels, String shortCode) {
    Long self = reels.stream().filter(r -> shortCode.equals(r.shortCode()))
            .map(ReelPointRow::views).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    if (self == null) {
        return null;
    }
    long higher = reels.stream().map(ReelPointRow::views).filter(java.util.Objects::nonNull)
            .filter(v -> v > self).count();
    return (int) (higher + 1);
}
```

> baseline/rate 등 `recent12*`·`recentContentsCount`는 그대로 `content_analyses`에서 읽는다(engagementRate.baseline, quality.baselineCount, scope.analyzedCount) — 이번 분리는 `views` 서브블록의 4개 파생값에 한정. `row.recentReelsAvgViews()`/`rankInRecentReels()`/`recentReelsCount()`는 이제 응답에 안 쓰이지만 record·쿼리에서 제거하지 않는다(향후 서술 검증·디버깅용, 그리고 SELECT 축소는 별도 정리 태스크).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :was:test --tests '*V1ContentReportAssemblerTest*'`
Expected: PASS.

- [ ] **Step 6: 컨트롤러 테스트 회귀 확인 (응답 형태 변경 반영)**

Run: `./gradlew :was:test --tests '*V1ContentReportControllerTest*'`
Expected: `recentReels` JSON에 `contentId`·`isCurrent` 필드가 늘어 기존 정확 일치 단정이 있으면 FAIL. 해당 테스트의 기대 JSON에 두 필드를 추가해 정정 후 PASS.

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/content/ was/src/test/java/com/celfit/was/v1/content/
git commit -m "fix(was): 콘텐츠 리포트 comparison.views 차트 라이브 분리 + recentReels 식별자

baseline·multiple·rankInRecent·recentCount를 라이브 recentReels에서 재계산해
분석 시점 고정값과의 드리프트 제거(AI 서술 인용 수치는 content_analyses 유지).
recentReels[]에 contentId·isCurrent 추가 — 프론트가 위치 가정 없이 현재 콘텐츠 강조."
```

## Phase B — 인플루언서 AI 리포트 ads 소스 통일

### Task B1: ads.strip·sponsoredCount를 LLM ad_type 소스로 통일

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java:44-52,90-92` (findSeries, SeriesRow)
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportAssembler.java:78-87` (toAds)
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportAssemblerTest.java`

> `sponsored`의 정본을 `content_analyses.ad_type='sponsored'`(카드·brands와 동일 소스)로 옮긴다. `sponsored`는 chart.bars와 ads.strip이 공유하므로 둘 다 자동으로 같은 소스가 된다. 미분석 콘텐츠(content_analyses 없음)는 sponsored=false. **주의:** ER 비교(`organic_avg`/`ad_avg`/`ad_drop_pct`)는 여전히 `account_summaries`(is_paid_partnership 기반)에서 온다 — 이번 범위 밖(§ 잔여 결정 참조).

- [ ] **Step 1: 실패 테스트 작성 — sponsored가 ad_type 기준, count=strip 합**

`V1InfluencerReportAssemblerTest.java`에 추가. series 3건 중 2건 sponsored(ad_type 기준)일 때 `ads.strip`=[false,true,true], `ads.sponsoredCount`=2 — `summary.sponsoredCount`(0으로 세팅)와 **무관**해야 한다.

```java
@Test
void ads_sponsored는_series_ad_type_기준_summary_카운트_무시() {
    var summary = summaryWith(/*sponsoredCount(무시돼야)*/ 0L, /*organicAvg*/ null, /*adAvg*/ null);
    var series = List.of(
            seriesRow("2026-07-10", false),
            seriesRow("2026-07-12", true),
            seriesRow("2026-07-14", true));

    var report = assembler.toReport(summary, null, series, List.of(), List.of());

    assertThat(report.ads().strip()).containsExactly(false, true, true);
    assertThat(report.ads().sponsoredCount()).isEqualTo(3L - 1L); // 2
}
```

`seriesRow(date, sponsored)`·`summaryWith(...)` 헬퍼가 없으면 기존 테스트의 `SeriesRow`/`SummaryRow` 생성 관용구를 재사용해 추가(`SeriesRow`의 sponsored 인자에 boolean 그대로).

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests '*V1InfluencerReportAssemblerTest*'`
Expected: FAIL — 현재 `sponsoredCount`는 `summary.sponsoredCount()`(0)를 반환.

- [ ] **Step 3: findSeries가 ad_type 기준 sponsored 반환 (`V1InfluencerReportRepository.java:44-52`)**

```java
/** 윈도우 내 게시물 시계열 — 올린 순. sponsored는 LLM ad_type 기준(카드·brands와 동일 소스). */
public List<SeriesRow> findSeries(String handle) {
    return jdbcClient.sql("""
            SELECT s.posted_at, s.content_type, s.views, s.likes, s.comments,
                   COALESCE(an.ad_type = 'sponsored', false) AS sponsored
            FROM account_content_series s
            LEFT JOIN content_analyses an ON an.short_code = s.short_code
            WHERE s.account_handle = :h
            ORDER BY s.posted_at, s.short_code
            """).param("h", handle).query(SeriesRow.class).list();
}
```

`SeriesRow` record는 형태 그대로(90-92행, `Boolean sponsored`) 둔다.

- [ ] **Step 4: toAds가 series에서 sponsoredCount 계산 (`V1InfluencerReportAssembler.java:78-87`)**

`summary.sponsoredCount()`를 series의 sponsored 합으로 대체:

```java
private InfluencerAiReport.Ads toAds(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
        List<BrandRow> brands, OffsetDateTime now) {
    long sponsoredCount = series.stream().filter(p -> Boolean.TRUE.equals(p.sponsored())).count();
    return new InfluencerAiReport.Ads(
            sponsoredCount,
            series.stream().map(p -> Boolean.TRUE.equals(p.sponsored())).toList(),
            lastAdNote(summary.lastAdPostedAt(), now),
            toComparison(summary),
            copy == null ? null : copy.adHeadline(),
            brands.stream().map(b -> new InfluencerAiReport.Ads.Brand(b.name(), b.cnt())).toList());
}
```

> `InfluencerAiReport.Ads`의 sponsoredCount 타입이 `Long`이면 `sponsoredCount`(long)가 오토박싱되어 그대로 맞는다. `Integer`면 `(int)`로 캐스팅. DTO를 열어 타입 확인 후 맞춘다. `lastAdNote`는 여전히 `summary.lastAdPostedAt()`(paid_partnership 기반)을 쓰지만, 이는 "마지막 광고 N주 전" 문구용 부가 정보라 이번 범위에서 유지(잔여 결정에 함께 기록).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :was:test --tests '*V1InfluencerReportAssemblerTest*'`
Expected: PASS.

- [ ] **Step 6: 컨트롤러 테스트 회귀**

Run: `./gradlew :was:test --tests '*V1InfluencerReportControllerTest*'`
Expected: strip/sponsoredCount 기대값이 paid_partnership 전제였다면 정정 필요 — ad_type 픽스처를 반영해 PASS.

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "fix(was): 인플루언서 리포트 ads.strip·sponsoredCount를 LLM ad_type 소스로 통일

카드 adType·ads.brands와 같은 content_analyses.ad_type을 정본으로 — 릴스 전용
is_paid_partnership만 세던 '8/9 sponsored인데 count 0' 모순 해소. chart.bars·ads.strip이
같은 sponsored 소스를 공유. ER organic/ad 비교는 별도 결정으로 잔존."
```

## Phase C — 부수 계약 정리

### Task C1: "최근 12개" 정의 명확화 (문서 계약)

**Files:**
- Modify: `ARCHITECTURE.md` §7 결정 기록

> 코드 변경 없음. `analyzedCount`(=`account_summaries.analyzed_count`, 최근-윈도우 내 스냅샷 보유 콘텐츠 수)와 `recentContents`(LIMIT 12 ⨝ `content_analyses`, **AI 분석 완료분만**)는 정의가 다르며, 12 미만일 때 was는 이미 실개수를 반환한다(9개면 9개). 계약을 문서로 못박아 프론트가 `recentContents.length`를 실개수로 신뢰하게 한다.

- [ ] **Step 1: 결정 기록에 계약 명시** (Task D와 함께 커밋 — 아래 Phase D)

## Phase D — 문서 갱신

### Task D1: ARCHITECTURE.md 결정 기록·미결 갱신

**Files:**
- Modify: `ARCHITECTURE.md` §7(맨 위 새 행), §8(미결)

- [ ] **Step 1: §7 결정 기록 맨 위에 추가**

```markdown
| 2026-07-19 | **AI 리포트 데이터 정합성 3건 수정** — ① 릴스 지표 usable-핀(PR #58)을 `v_content_pinned_metric` 공유 뷰로 추출해 최근-윈도우 경로(`v_recent_content`→베이스라인·계정 시계열)도 재사용: 빈 SELF_GQL 타임라인 스냅샷이 clips 실값을 덮어 recentReels·baseline·계정 평균이 NULL이 되던 쌍둥이 버그 해소, 카드 조회수와 recentReels 조회수가 같은 핀 소스로 일치. ② 콘텐츠 리포트 `comparison.views`의 baseline·multiple·rankInRecent·recentCount를 라이브 recentReels에서 재계산(AI 서술 인용 수치는 `content_analyses` 고정 유지 — "서술 고정·차트 라이브" 분리), recentReels[]에 `contentId`·`isCurrent` 추가. ③ 인플루언서 리포트 `ads.strip`·`sponsoredCount`를 LLM `ad_type`(카드·brands와 동일 소스)로 통일 — 릴스 전용 `is_paid_partnership`만 세던 "8/9 sponsored인데 0" 모순 해소. **계약:** `recentContents`는 분석 완료분만(LIMIT 12 ⨝ content_analyses)이라 `analyzedCount`(윈도우 스냅샷 수)보다 적을 수 있고, was는 실개수를 반환한다 | 본 계획서 |
```

- [ ] **Step 2: §8 미결에 ER 소스 잔여 추가**

```markdown
| ads ER 비교·lastAdNote 소스 | `ads.strip`·`sponsoredCount`는 LLM `ad_type`으로 통일(07-19)했으나 `ads.comparison`(organic/ad 평균·`ad_drop_pct`)과 `lastAdNote`는 여전히 `account_summaries`의 `is_paid_partnership` 기반 — 라이브 was 재계산(윈도우 metric×ad_type) 또는 뷰의 cross-DB 제약상 유지 중 결정 필요 |
```

- [ ] **Step 3: 커밋**

```bash
git add ARCHITECTURE.md
git commit -m "docs: AI 리포트 정합성 수정 결정 기록 + ads ER 소스 잔여 미결"
```

---

## Self-Review 체크

- **Spec coverage:** 요청서 1(갱신 정책)=A1+A2(라이브 분리로 대체), 2(파생값 재계산)=A1(뷰 자동 정정)+A2(라이브), 3(식별자)=A2 Step 3, 4(광고 소스 통일)=B1, 5(최근 12 정의)=C1/D1. ✅ 전부 매핑.
- **핀 우선순위 일치:** `v_content_pinned_metric`의 ORDER BY = `v_contents`.pinned 기존 순서 verbatim → 02b_reels_pin 회귀 유지. ✅
- **타입 일치:** `ReelPointRow(String shortCode, Long views, OffsetDateTime postedAt)`·`ReelPoint(String contentId, Long views, String postedAt, boolean isCurrent)`·`SeriesRow(... Boolean sponsored)` — A2/B1에서 일관 사용. ✅
- **경계:** was는 analysis DB만 조인(§4-4 준수 — raw·app 크로스 없음), 뷰는 raw만. ✅
- **잔여 리스크(실행자 필독):** A1은 계정 집계(avg_views·avg_er·trend·ads ER)를 최신-승→usable-핀으로 바꾸므로 **다음 미러에서 운영 수치가 이동**한다(대부분 NULL→실값 복구 방향). Step 7에서 하니스로 파급을 확인하고, 운영 반영은 미러 1회 후 `/v1` 스팟체크로 검증할 것.
