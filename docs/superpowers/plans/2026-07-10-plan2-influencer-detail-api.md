# Plan 2: 인플루언서 상세 API (비LLM) Implementation Plan

> 상태: 🟢 활성 — 태스크 C1·E 참고 자료. 단 미러 방식·모듈 구조는 ARCHITECTURE §4가 우선
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인플루언서 상세 v4의 비LLM 데이터 전부(헤더·정체성·성과·일관성·커머셜 기초 + 게시물별 조회수 시계열 + 협업 이력 골격)를 반환하는 `GET /api/influencers/{username}` 엔드포인트를 만든다.

**Architecture:** Plan 1과 동일한 데이터 흐름 — crawler DB에 analytics 뷰 추가(`10_creator_detail.sql`: 계정 1행 요약 뷰 + 시계열/협업이력 1:N 뷰) → `MaterializationService`가 analysis DB로 미러(`creator_detail`/`creator_view_series`/`creator_ad_history` + 미등록 08 기둥 뷰 4종) → was가 조회해 블록 구조 JSON으로 조립. "최근 N개 윈도우"는 **Plan 1이 만든 `analytics.v_recent_content`를 재사용**한다(윈도우 뷰를 다시 만들지 않는다). 변동성/모멘텀 임계값은 뷰가 직접 읽는 `app_setting` 키로 런타임 조정한다. LLM 블록(AI 브리핑·브랜드 적합성·페르소나·광고유형 라벨)은 이 플랜 범위 밖 — 응답에 필드 자체가 없고 Plan 4·5에서 additive하게 추가된다.

**Tech Stack:** PostgreSQL 17 뷰(SQL) + analytics 모듈 SQL 테스트 하니스(`analytics/test/run.sh`), Java 21 / Spring Boot 4.1 (was: JdbcClient, record DTO, Jackson 3 `tools.jackson.*`), Testcontainers(`postgres:17-alpine`), MockMvc(`spring-boot-starter-webmvc-test`).

**사전 조건 (중요):**
1. 로컬 Docker에 `crawler-postgres-1` 컨테이너 기동(`docker compose up -d`, 포트 5433, DB `crawler`/`analysis`).
2. **Plan 1이 병합되어 있어야 한다.** 이 플랜은 Plan 1이 만든 것을 재사용한다:
   - SQL: `analytics/views/09_post_detail.sql`의 `analytics.v_recent_content`(최근 N개 윈도우), `analytics.v_author_summary`(작성자 요약·히트). 파일명순 적용 규칙상 09가 10보다 먼저 적용되므로 09가 존재해야 10의 뷰가 컴파일된다.
   - was: `com.celfit.was.config.ClockConfig`, `com.celfit.was.config.WebConfig`(CORS `/api/**`), `was/src/test/java/com/celfit/was/IntegrationTest.java`(Testcontainers 베이스), `was/build.gradle`의 testcontainers 3종. 이 플랜은 이들을 **재생성하지 않고 재사용**한다.
   - roadmap의 'Plan 1·2 독립 배포' 원칙과 상충하는 지점이지만, 확정 설계 결정(§"반드시 지킬 설계 결정" 1)이 "윈도우 뷰를 다시 만들지 마라"를 명시하므로 재사용을 택한다. Self-Review에 근거를 남긴다.
3. **재사용 뷰의 참조 컬럼 계약(Task 1·2 SQL이 의존).** Task 1·2 뷰는 아래 컬럼명을 그대로 참조하므로 Plan 1의 `09_post_detail.sql`이 이 이름을 노출해야 한다(불일치 시 뷰 생성이 컴파일 실패). 실 crawler DB로 검증 완료:
   - `analytics.v_recent_content` (= `v_content_performance` 최근 N행): `owner_username, content_id, short_code, uploaded_at, views, likes, comments_count, engagement_rate, content_format, main_group, ad_marked, followers`. (더미 원본 표기는 `likes`/`comments`지만 뷰 컬럼은 `likes`/`comments_count`, ER은 `engagement_rate`, 형식은 `content_format`(clips→'reel').)
   - `analytics.v_author_summary`: `owner_username, avg_views, avg_engagement_rate, hit_count, hit_rate, sample_size`.
   - `raw_profile.payload`(jsonb) 키: `followsCount, postsCount, biography`(헤더 프로필용). `account.username = owner_username`, `account.id = raw_profile.account_id`.

**참고 파일 (컨벤션 출처):**
- 뷰 스타일: [analytics/views/08_creator_pillars.sql](../../analytics/views/08_creator_pillars.sql), [analytics/views/03_creators.sql](../../analytics/views/03_creators.sql), [analytics/views/09_post_detail.sql](../../analytics/views/09_post_detail.sql)(Plan 1)
- SQL 테스트: [analytics/test/run.sh](../../analytics/test/run.sh), [analytics/test/08_creator_pillars.test.sql](../../analytics/test/08_creator_pillars.test.sql), 더미 시드 [analytics/seed/dummy.sql](../../analytics/seed/dummy.sql)
- 미러: [analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java](../../analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java)
- was 컨벤션·Plan 1 산출물: `was/src/main/java/com/celfit/was/postdetail/*`, `was/src/main/java/com/celfit/was/config/*`

---

## 더미 데이터 기대값 근거

모든 SQL 테스트는 `BEGIN; seed/dummy.sql; <test>; ROLLBACK;`으로 격리된다. 더미 5건(`analytics/seed/dummy.sql`):

| content | short_code | owner | 팔로워/tier | uploaded_at(KST) | main_group | ad | format | likes | comments | views | ER |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 9101 | dummy_c1 | dummy_micro | 5000/micro | 2026-06-01 09:00 | A | F | reel | 500 | 50 | 10000 | 0.1100 |
| 9102 | dummy_c2 | dummy_mid | 50000/mid | 2026-06-01 14:00 | A | **T** | feed | 2000 | 100 | **NULL** | 0.0420 |
| 9103 | dummy_c3 | dummy_macro | 500000/macro | 2026-06-02 09:00 | B | F | reel | 20000 | 500 | 400000 | 0.0410 |
| 9104 | dummy_c4 | dummy_over | 8000/micro | 2026-06-03 20:00 | B | F | reel | 1600 | 200 | 30000 | 0.2250 |
| 9105 | dummy_c5 | dummy_micro | 5000/micro | 2026-06-01 09:30 | B | F | reel | 300 | 30 | 8000 | 0.0660 |

기본 윈도우 N=12라 각 계정의 게시물이 전부 윈도우에 포함된다. 계정은 4개(micro/mid/macro/over).

### 계정 단위 기대값 (더미만, 픽스처 추가 전)

**dummy_micro** (2건, 둘 다 reel·views 있음):
- sample_size=2, views_sample_size=2
- avg_views=(10000+8000)/2=**9000.0**, median_views(percentile_cont)=**9000.0**, min_views=**8000**
- avg_ER=(0.11+0.066)/2=**0.0880**, median_ER=**0.0880**
- views_cv=stddev_samp{10000,8000}/9000=1414.2135.../9000=**0.1571** (views_sample_size 2<4 → volatility_label **NULL**)
- posts_per_week: span=30분→epoch일 0.0208, GREATEST(0.0208,7)=7 → 2*7/7=**2.0**
- 카테고리 비중: A 1건(avg_views 10000.0), B 1건(avg_views 8000.0) → 각 **50.0%** (count 동률→main_group 오름차순 A,B)
- 형식 비중: reel 2건(avg_views 9000.0) → **100.0%**
- 헤더 프로필: follows_count=**300**, posts_count=**120**, biography=**NULL**(seed에 키 없음), follower_band=**'1만 미만'**, primary_category=**'A'**(A,B 동률→알파벳), first_uploaded_at=**2026-06-01 09:00+09**
- reach_efficiency=avg_views/tier_avg_views(coarse micro=16000.0)=9000/16000=**0.56**, reach_efficiency_pct=round((0.5625-1)*100)=**-44**
- position_percentile(세분 밴드×주력카테고리 `1만 미만·A` 단독 파티션)=**0.0000** (percent_rank 단일행=0)
- momentum: sample 2<4 → **NULL**, momentum_warning=**false**
- hit_count=**0**, hit_rate=**0.0000** (v_author_summary 재사용; 2표본은 산술적으로 2배 히트 불가)
- 커머셜: ad_count=0, ad_ratio=**0.0000**, ad_avg_views=**NULL**, ad_avg_engagement=**NULL**, non_ad_avg_views=**9000.0**, ad_avg_gap_days=**NULL**, last_ad_uploaded_at=**NULL**

**dummy_over** (1건, reel):
- reach_efficiency=30000/16000=**1.88**, reach_efficiency_pct=round(87.5)=**88**, tier=micro
- follower_band=**'1만 미만'**, primary_category=**'B'** → 포지션 파티션 `1만 미만·B`(dummy_micro의 `·A`와 다른 파티션), 단독이라 position_percentile=**0.0000**
- sample_size=1, momentum NULL

**dummy_mid** (1건 = c2, feed·views NULL·**광고**) — NULL/커머셜 함정 검증:
- sample_size=1, views_sample_size=0
- avg_views=**NULL**, median_views=**NULL**, views_cv=**NULL**, min_views=**NULL**, volatility_label=**NULL**, reach_efficiency=**NULL**
- ad_count=1, ad_ratio=**1.0000**, ad_avg_views=**NULL**(유일 광고가 피드), ad_avg_engagement=likes+comments=2000+100=**2100.0**, non_ad_avg_views=**NULL**

`micro` tier 평균 조회수(윈도우 전체 게시물 기준) = (c1 10000 + c5 8000 + c4 30000)/3 = **16000.0**.

### 추가 픽스처(테스트 파일 내 트랜잭션 INSERT — id 9005~9007 계정, 9110~ 콘텐츠)

더미만으로는 모멘텀·CV 라벨·히트(표본 3+)를 검증할 수 없어 3개 계정을 추가한다. 계정 followers는 모두 coarse mid tier(10000~100000)라 micro tier 기대값(위)에 영향을 주지 않는다. 세분 밴드로는 series 20000·erratic 25000 = `1만~3만`, decline 30000 = `3만~5만`이고 셋 다 주력 카테고리 B라, 포지션 파티션 `1만~3만·B`에 series·erratic 2계정이 들어가 percent_rank 판별(erratic ER 0.0022 < series ER 0.083 → erratic 0.0, series 1.0)이 가능하다.

**dummy_series** (9005, followers 20000, 6 reel, 2건 광고):

| short | uploaded_at | views | ad | likes | comments |
|---|---|---|---|---|---|
| dummy_s1 | 2026-05-01 09:00 | 10000 | F | 400 | 40 |
| dummy_s2 | 2026-05-08 09:00 | 12000 | F | 500 | 50 |
| dummy_s3 | 2026-05-15 09:00 | 11000 | **T** | 1000 | 100 |
| dummy_s4 | 2026-05-22 09:00 | 20000 | F | 800 | 80 |
| dummy_s5 | 2026-05-29 09:00 | 22000 | F | 900 | 90 |
| dummy_s6 | 2026-06-05 09:00 | 60000 | **T** | 5000 | 1000 |

- sample_size=6, views_sample_size=6, avg_views=135000/6=**22500.0**, median_views(3·4번째 12000,20000 보간)=**16000.0**, min_views=**10000**
- views_cv≈19034.18/22500=**0.8460** → **BETWEEN 0.84 AND 0.85**, volatility_label=**'mid'**
- posts_per_week: span 05-01~06-05=35일 → 6*7/35=**1.2**
- 모멘텀(오름차순 6건, cnt/2=3): previous={s1,s2,s3}(10000,12000,11000)avg=11000, recent={s4,s5,s6}(20000,22000,60000)avg=34000 → ratio=34000/11000=**3.0909**, pct=round(209.09)=**209**, warning=**false**
- hit: avg_views 22500, 2배=45000, s6(60000)만 → hit_count=**1**, hit_rate=1/6=**0.1667**
- 커머셜(광고 s3,s6): ad_count=**2**, ad_ratio=2/6=**0.3333**, ad_avg_views=(11000+60000)/2=**35500.0**, ad_avg_engagement=((1000+100)+(5000+1000))/2=(1100+6000)/2=**3550.0**, non_ad_avg_views=(10000+12000+20000+22000)/4=**16000.0**, ad_avg_gap_days=(06-05 − 05-15)=21일/(2-1)=**21.0**, last_ad_uploaded_at=**2026-06-05 09:00+09**
- 헤더/포지션: biography=**'글로우 크리에이터'**(픽스처 payload에 삽입), follower_band=**'1만~3만'**, primary_category=**'B'**, position_percentile=**1.0000**(`1만~3만·B`에서 erratic보다 ER 높아 최상), first_uploaded_at=**2026-05-01 09:00+09**

**dummy_decline** (9006, followers 30000, 4 reel, ad 없음) — 모멘텀 하락 경고:

| short | uploaded_at | views |
|---|---|---|
| dummy_d1 | 2026-05-01 09:00 | 50000 |
| dummy_d2 | 2026-05-08 09:00 | 40000 |
| dummy_d3 | 2026-05-15 09:00 | 30000 |
| dummy_d4 | 2026-05-22 09:00 | 20000 |

- sample_size=4, 모멘텀(cnt/2=2): previous={d1,d2}avg=45000, recent={d3,d4}avg=25000 → ratio=25000/45000=**0.5556**, pct=round(-44.44)=**-44**, warning=**true**(-44≤-15)
- views_cv≈12909.94/35000=**0.3689** → **BETWEEN 0.36 AND 0.37**, volatility_label=**'low'**
- posts_per_week: span 21일 → 4*7/21=**1.3**, follower_band=**'3만~5만'**(30000)

**dummy_erratic** (9007, followers 25000, 4 reel, ad 없음) — 고변동성:

| short | uploaded_at | views |
|---|---|---|
| dummy_e1 | 2026-05-01 09:00 | 1000 |
| dummy_e2 | 2026-05-08 09:00 | 2000 |
| dummy_e3 | 2026-05-15 09:00 | 3000 |
| dummy_e4 | 2026-05-22 09:00 | 100000 |

- views_cv≈49006.8/26500=**1.8493** → **BETWEEN 1.84 AND 1.86**, volatility_label=**'high'**
- follower_band=**'1만~3만'**(25000), primary_category=**'B'**, position_percentile=**0.0000**(`1만~3만·B`에서 ER 0.0022로 series보다 낮아 최하)

### 1:N 자식 뷰 기대값 (픽스처 포함)

- `v_creator_view_series` 행 수 = 윈도우 전체 게시물 = 5(더미)+6+4+4=**19**. dummy_series `dummy_s6` is_hit=**true**, `dummy_s1` is_hit=**false**.
- `v_creator_ad_history` 행 수 = 광고 게시물 = c2(dummy_mid)+s3+s6=**3**. dummy_series `dummy_s6` is_hit=true, `dummy_s3` is_hit=false.

---

## app_setting 런타임 키 (뷰가 직접 읽음 — SettingsService 경유 아님)

Plan 1의 `analytics.recent-window`와 같은 방식으로 뷰가 `app_setting`을 직접 `COALESCE`로 읽는다. **SettingsService(int 전용)에 등록하지 않는다** — 소수 경계값이라 int 계약과 맞지 않고, 뷰 직접 조회로 충분(운영은 admin SQL 또는 향후 확장). 근거는 Self-Review에 남긴다.

| 키 | 기본값 | 의미·근거 |
|---|---|---|
| `analytics.recent-window` | 12 | (Plan 1 소유) 최근 N개 윈도우 |
| `analytics.volatility-mid-cv` | 0.5 | CV<0.5 '낮음'. 표준편차가 평균의 절반 미만 = 안정적 |
| `analytics.volatility-high-cv` | 1.0 | CV≥1.0 '높음'. 표준편차가 평균 이상 = 변동 큼(CV 통설 임계) |
| `analytics.detail-min-sample` | 4 | 모멘텀·변동성 라벨 최소 표본. 절반당 최소 2개, CV 안정 최소 4점 미만이면 라벨 대신 NULL |
| `analytics.momentum-drop-threshold` | 15 | 최근-이전 조회수 -15% 이하 하락 시 모멘텀 경고 칩. 노이즈 위 유의미한 하락 신호 |

---

### Task 1: 계정 요약 뷰 `v_creator_detail` (정체성·성과·일관성·커머셜 스칼라)

**Files:**
- Create: `analytics/views/10_creator_detail.sql` (전반부: 단일 행 뷰)
- Create: `analytics/test/10_creator_detail.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/10_creator_detail.test.sql` 생성:

```sql
-- 결정적 실행을 위해 이 플랜이 읽는 app_setting 키를 기본값으로 강제
DELETE FROM app_setting WHERE key IN (
  'analytics.recent-window', 'analytics.volatility-mid-cv', 'analytics.volatility-high-cv',
  'analytics.detail-min-sample', 'analytics.momentum-drop-threshold');

-- v_creator_detail: 더미 계정 4개
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_creator_detail) = 4, 'creator_detail rows != 4';
END $$;

-- dummy_micro: 정체성 + 성과 + 일관성 + 커머셜
DO $$
BEGIN
  ASSERT (SELECT sample_size        FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 2, 'micro sample != 2';
  ASSERT (SELECT views_sample_size  FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 2, 'micro views_sample != 2';
  ASSERT (SELECT avg_views          FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 9000.0, 'micro avg_views != 9000';
  ASSERT (SELECT median_views       FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 9000.0, 'micro median_views != 9000';
  ASSERT (SELECT min_views          FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 8000, 'micro min_views != 8000';
  ASSERT (SELECT avg_engagement_rate    FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.0880, 'micro avg_ER != 0.088';
  ASSERT (SELECT median_engagement_rate FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.0880, 'micro median_ER != 0.088';
  ASSERT (SELECT views_cv           FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.1571, 'micro views_cv != 0.1571';
  ASSERT (SELECT volatility_label   FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') IS NULL, 'micro volatility_label not null (n<4)';
  ASSERT (SELECT posts_per_week     FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 2.0, 'micro posts_per_week != 2.0';
  ASSERT (SELECT tier               FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 'micro', 'micro tier wrong';
  -- 헤더 프로필 기본 (raw_profile 최신 payload; seed엔 biography 없음 → NULL)
  ASSERT (SELECT follows_count      FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 300, 'micro follows_count != 300';
  ASSERT (SELECT posts_count        FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 120, 'micro posts_count != 120';
  ASSERT (SELECT biography          FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') IS NULL, 'micro biography not null (seed has none)';
  ASSERT (SELECT follower_band      FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = '1만 미만', 'micro band wrong';
  ASSERT (SELECT primary_category   FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 'A', 'micro primary_category != A';
  ASSERT (SELECT first_uploaded_at  FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = timestamptz '2026-06-01 09:00:00+09', 'micro first_uploaded_at wrong';
  -- 성과: 도달 효율(coarse tier avg 16000) + 구간 포지션(세분 밴드×주력 카테고리)
  ASSERT (SELECT reach_efficiency     FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.56, 'micro reach != 0.56';
  ASSERT (SELECT reach_efficiency_pct FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = -44, 'micro reach_pct != -44';
  ASSERT (SELECT tier_avg_views       FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 16000.0, 'micro tier_avg != 16000';
  ASSERT (SELECT position_percentile  FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.0000, 'micro percentile != 0 (alone in 1만미만·A)';
  -- 모멘텀 가드 (2<4)
  ASSERT (SELECT momentum_ratio   FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') IS NULL, 'micro momentum not null';
  ASSERT (SELECT momentum_warning FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = false, 'micro momentum_warning != false';
  -- 히트 (v_author_summary 재사용)
  ASSERT (SELECT hit_count FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0, 'micro hit_count != 0';
  ASSERT (SELECT hit_rate  FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.0000, 'micro hit_rate != 0';
  -- 커머셜
  ASSERT (SELECT ad_count         FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0, 'micro ad_count != 0';
  ASSERT (SELECT ad_ratio         FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 0.0000, 'micro ad_ratio != 0';
  ASSERT (SELECT ad_avg_views     FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') IS NULL, 'micro ad_avg_views not null';
  ASSERT (SELECT non_ad_avg_views FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 9000.0, 'micro non_ad_avg != 9000';
END $$;

-- dummy_micro: 카테고리·형식 비중 jsonb (camelCase 키)
DO $$
BEGIN
  ASSERT (SELECT category_breakdown->0->>'mainGroup' FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 'A', 'micro cat[0] != A';
  ASSERT (SELECT (category_breakdown->0->>'sharePct')::numeric FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 50.0, 'micro cat[0] share != 50';
  ASSERT (SELECT (category_breakdown->0->>'contentCount')::int FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 1, 'micro cat[0] count != 1';
  ASSERT (SELECT (category_breakdown->0->>'avgViews')::numeric FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 10000.0, 'micro cat[0] avg != 10000';
  ASSERT (SELECT category_breakdown->1->>'mainGroup' FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 'B', 'micro cat[1] != B';
  ASSERT (SELECT format_breakdown->0->>'contentFormat' FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 'reel', 'micro fmt[0] != reel';
  ASSERT (SELECT (format_breakdown->0->>'sharePct')::numeric FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 100.0, 'micro fmt[0] share != 100';
  ASSERT (SELECT (format_breakdown->0->>'avgViews')::numeric FROM analytics.v_creator_detail WHERE owner_username='dummy_micro') = 9000.0, 'micro fmt[0] avg != 9000';
END $$;

-- dummy_over: tier 내 포지션 최상 + 도달 효율
DO $$
BEGIN
  ASSERT (SELECT reach_efficiency     FROM analytics.v_creator_detail WHERE owner_username='dummy_over') = 1.88, 'over reach != 1.88';
  ASSERT (SELECT reach_efficiency_pct FROM analytics.v_creator_detail WHERE owner_username='dummy_over') = 88, 'over reach_pct != 88';
  ASSERT (SELECT position_percentile  FROM analytics.v_creator_detail WHERE owner_username='dummy_over') = 0.0000, 'over percentile != 0 (alone in 1만미만·B)';
  ASSERT (SELECT follower_band        FROM analytics.v_creator_detail WHERE owner_username='dummy_over') = '1만 미만', 'over band wrong';
  ASSERT (SELECT primary_category     FROM analytics.v_creator_detail WHERE owner_username='dummy_over') = 'B', 'over primary_category != B';
  ASSERT (SELECT tier                 FROM analytics.v_creator_detail WHERE owner_username='dummy_over') = 'micro', 'over tier wrong';
END $$;

-- dummy_mid: views NULL 함정 + 광고 게시물 평균(CPV 함정)
DO $$
BEGIN
  ASSERT (SELECT views_sample_size  FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') = 0, 'mid views_sample != 0';
  ASSERT (SELECT avg_views          FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid avg_views not null';
  ASSERT (SELECT median_views       FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid median_views not null';
  ASSERT (SELECT views_cv           FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid views_cv not null';
  ASSERT (SELECT min_views          FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid min_views not null';
  ASSERT (SELECT reach_efficiency   FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid reach not null';
  ASSERT (SELECT follows_count      FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') = 800, 'mid follows_count != 800';
  ASSERT (SELECT posts_count        FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') = 400, 'mid posts_count != 400';
  ASSERT (SELECT follower_band      FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') = '5만~10만', 'mid band wrong';
  ASSERT (SELECT ad_ratio           FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') = 1.0000, 'mid ad_ratio != 1';
  ASSERT (SELECT ad_avg_views       FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid ad_avg_views not null (feed)';
  ASSERT (SELECT ad_avg_engagement  FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') = 2100.0, 'mid ad_avg_engagement != 2100';
  ASSERT (SELECT non_ad_avg_views   FROM analytics.v_creator_detail WHERE owner_username='dummy_mid') IS NULL, 'mid non_ad_avg not null';
END $$;

-- ===== 표본 3+ 필요한 케이스: 트랜잭션 내 픽스처 INSERT (id 9005~9007, 9110~) =====
INSERT INTO account(id, username) VALUES
 (9005,'dummy_series'), (9006,'dummy_decline'), (9007,'dummy_erratic');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9005,9990,'{"username":"dummy_series","followersCount":20000,"followsCount":300,"postsCount":80,"biography":"글로우 크리에이터","verified":false,"isBusinessAccount":true,"businessCategoryName":"Beauty"}'::jsonb, timestamptz '2026-06-06 00:00:00+09'),
 (9006,9990,'{"username":"dummy_decline","followersCount":30000,"followsCount":300,"postsCount":80,"verified":false,"isBusinessAccount":true,"businessCategoryName":"Beauty"}'::jsonb, timestamptz '2026-06-06 00:00:00+09'),
 (9007,9990,'{"username":"dummy_erratic","followersCount":25000,"followsCount":300,"postsCount":80,"verified":false,"isBusinessAccount":true,"businessCategoryName":"Beauty"}'::jsonb, timestamptz '2026-06-06 00:00:00+09');

INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9110,'dummy_s1','REELS','dummy_series', timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9111,'dummy_s2','REELS','dummy_series', timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9112,'dummy_s3','REELS','dummy_series', timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', true),
 (9113,'dummy_s4','REELS','dummy_series', timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false),
 (9114,'dummy_s5','REELS','dummy_series', timestamptz '2026-05-29 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-29 00:00:00+09','glow_sub','B', false),
 (9115,'dummy_s6','REELS','dummy_series', timestamptz '2026-06-05 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-06-05 00:00:00+09','glow_sub','B', true),
 (9120,'dummy_d1','REELS','dummy_decline',timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9121,'dummy_d2','REELS','dummy_decline',timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9122,'dummy_d3','REELS','dummy_decline',timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', false),
 (9123,'dummy_d4','REELS','dummy_decline',timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false),
 (9130,'dummy_e1','REELS','dummy_erratic',timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9131,'dummy_e2','REELS','dummy_erratic',timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9132,'dummy_e3','REELS','dummy_erratic',timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', false),
 (9133,'dummy_e4','REELS','dummy_erratic',timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false);

INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9110,9990,'{"shortCode":"dummy_s1","type":"Video","likesCount":400,"commentsCount":40,"videoPlayCount":10000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9111,9990,'{"shortCode":"dummy_s2","type":"Video","likesCount":500,"commentsCount":50,"videoPlayCount":12000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9112,9990,'{"shortCode":"dummy_s3","type":"Video","likesCount":1000,"commentsCount":100,"videoPlayCount":11000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-16 09:00:00+09'),
 (9113,9990,'{"shortCode":"dummy_s4","type":"Video","likesCount":800,"commentsCount":80,"videoPlayCount":20000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-23 09:00:00+09'),
 (9114,9990,'{"shortCode":"dummy_s5","type":"Video","likesCount":900,"commentsCount":90,"videoPlayCount":22000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-30 09:00:00+09'),
 (9115,9990,'{"shortCode":"dummy_s6","type":"Video","likesCount":5000,"commentsCount":1000,"videoPlayCount":60000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-06 09:00:00+09'),
 (9120,9990,'{"shortCode":"dummy_d1","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":50000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9121,9990,'{"shortCode":"dummy_d2","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":40000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9122,9990,'{"shortCode":"dummy_d3","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":30000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-16 09:00:00+09'),
 (9123,9990,'{"shortCode":"dummy_d4","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":20000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-23 09:00:00+09'),
 (9130,9990,'{"shortCode":"dummy_e1","type":"Video","likesCount":50,"commentsCount":5,"videoPlayCount":1000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9131,9990,'{"shortCode":"dummy_e2","type":"Video","likesCount":50,"commentsCount":5,"videoPlayCount":2000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9132,9990,'{"shortCode":"dummy_e3","type":"Video","likesCount":50,"commentsCount":5,"videoPlayCount":3000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-16 09:00:00+09'),
 (9133,9990,'{"shortCode":"dummy_e4","type":"Video","likesCount":50,"commentsCount":5,"videoPlayCount":100000,"videoDuration":20,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-05-23 09:00:00+09');

-- dummy_series: 성과·일관성·모멘텀·커머셜
DO $$
BEGIN
  ASSERT (SELECT sample_size       FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 6, 'series sample != 6';
  ASSERT (SELECT avg_views         FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 22500.0, 'series avg_views != 22500';
  ASSERT (SELECT median_views      FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 16000.0, 'series median_views != 16000';
  ASSERT (SELECT min_views         FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 10000, 'series min_views != 10000';
  ASSERT (SELECT posts_per_week    FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 1.2, 'series posts_per_week != 1.2';
  ASSERT (SELECT biography         FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = '글로우 크리에이터', 'series biography wrong';
  ASSERT (SELECT follower_band     FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = '1만~3만', 'series band wrong';
  ASSERT (SELECT primary_category  FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 'B', 'series primary_category != B';
  ASSERT (SELECT position_percentile FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 1.0000, 'series percentile != 1 (1만~3만·B 최상, erratic 대비)';
  ASSERT (SELECT first_uploaded_at  FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = timestamptz '2026-05-01 09:00:00+09', 'series first_uploaded_at wrong';
  ASSERT (SELECT views_cv          FROM analytics.v_creator_detail WHERE owner_username='dummy_series') BETWEEN 0.84 AND 0.85, 'series cv not ~0.846';
  ASSERT (SELECT volatility_label  FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 'mid', 'series volatility != mid';
  ASSERT (SELECT momentum_ratio    FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 3.0909, 'series momentum_ratio != 3.0909';
  ASSERT (SELECT momentum_pct      FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 209, 'series momentum_pct != 209';
  ASSERT (SELECT momentum_warning  FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = false, 'series momentum_warning != false';
  ASSERT (SELECT hit_count         FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 1, 'series hit_count != 1';
  ASSERT (SELECT hit_rate          FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 0.1667, 'series hit_rate != 0.1667';
  ASSERT (SELECT ad_count          FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 2, 'series ad_count != 2';
  ASSERT (SELECT ad_ratio          FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 0.3333, 'series ad_ratio != 0.3333';
  ASSERT (SELECT ad_avg_views      FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 35500.0, 'series ad_avg_views != 35500';
  ASSERT (SELECT ad_avg_engagement FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 3550.0, 'series ad_avg_engagement != 3550';
  ASSERT (SELECT non_ad_avg_views  FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 16000.0, 'series non_ad_avg != 16000';
  ASSERT (SELECT ad_avg_gap_days   FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = 21.0, 'series ad_gap != 21';
  ASSERT (SELECT last_ad_uploaded_at FROM analytics.v_creator_detail WHERE owner_username='dummy_series') = timestamptz '2026-06-05 09:00:00+09', 'series last_ad wrong';
END $$;

-- dummy_decline: 모멘텀 하락 경고 + 낮은 변동성
DO $$
BEGIN
  ASSERT (SELECT momentum_ratio   FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') = 0.5556, 'decline momentum_ratio != 0.5556';
  ASSERT (SELECT momentum_pct     FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') = -44, 'decline momentum_pct != -44';
  ASSERT (SELECT momentum_warning FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') = true, 'decline warning != true';
  ASSERT (SELECT views_cv         FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') BETWEEN 0.36 AND 0.37, 'decline cv not ~0.369';
  ASSERT (SELECT volatility_label FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') = 'low', 'decline volatility != low';
  ASSERT (SELECT posts_per_week   FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') = 1.3, 'decline posts_per_week != 1.3';
  ASSERT (SELECT follower_band    FROM analytics.v_creator_detail WHERE owner_username='dummy_decline') = '3만~5만', 'decline band wrong';
END $$;

-- dummy_erratic: 고변동성 라벨
DO $$
BEGIN
  ASSERT (SELECT views_cv         FROM analytics.v_creator_detail WHERE owner_username='dummy_erratic') BETWEEN 1.84 AND 1.86, 'erratic cv not ~1.849';
  ASSERT (SELECT volatility_label FROM analytics.v_creator_detail WHERE owner_username='dummy_erratic') = 'high', 'erratic volatility != high';
  ASSERT (SELECT position_percentile FROM analytics.v_creator_detail WHERE owner_username='dummy_erratic') = 0.0000, 'erratic percentile != 0 (1만~3만·B 최하)';
END $$;
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/10_creator_detail.test.sql`
Expected: FAIL — `relation "analytics.v_creator_detail" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/10_creator_detail.sql` 생성:

```sql
-- 그룹 10: 인플루언서 상세 (비LLM) — 정체성/성과/일관성/커머셜 기초
-- 모든 계정 집계는 Plan 1의 analytics.v_recent_content(최근 N개 윈도우)를 재사용한다.
-- 변동성·모멘텀 임계값은 app_setting을 뷰가 직접 읽어 재배포 없이 조정한다(Plan 1 recent-window와 동일 방식).

-- 계정 1행 요약: 헤더+정체성+성과+일관성+커머셜 스칼라.
CREATE OR REPLACE VIEW analytics.v_creator_detail AS
WITH win AS (
  SELECT * FROM analytics.v_recent_content
),
cfg AS (
  SELECT
    COALESCE((SELECT value::numeric FROM app_setting WHERE key = 'analytics.volatility-mid-cv'),  0.5) AS mid_cv,
    COALESCE((SELECT value::numeric FROM app_setting WHERE key = 'analytics.volatility-high-cv'), 1.0) AS high_cv,
    COALESCE((SELECT value::int     FROM app_setting WHERE key = 'analytics.detail-min-sample'),  4)   AS min_sample,
    COALESCE((SELECT value::numeric FROM app_setting WHERE key = 'analytics.momentum-drop-threshold'), 15) AS drop_threshold
),
profile AS (
  -- 헤더 프로필 기본: 계정별 최신 raw_profile payload에서 팔로잉·게시물 수·bio.
  SELECT DISTINCT ON (a.username)
    a.username                            AS owner_username,
    (rp.payload->>'followsCount')::bigint AS follows_count,
    (rp.payload->>'postsCount')::bigint   AS posts_count,
    rp.payload->>'biography'              AS biography
  FROM account a
  JOIN raw_profile rp ON rp.account_id = a.id
  ORDER BY a.username, rp.captured_at DESC, rp.id DESC
),
base AS (
  SELECT
    owner_username,
    count(*)                                                        AS sample_size,
    count(views)                                                   AS views_sample_size,
    max(followers)                                                 AS followers,
    round(avg(views), 1)                                           AS avg_views,
    round(percentile_cont(0.5) WITHIN GROUP (ORDER BY views)::numeric, 1) AS median_views,
    round(avg(engagement_rate), 4)                                 AS avg_engagement_rate,
    round(percentile_cont(0.5) WITHIN GROUP (ORDER BY engagement_rate)::numeric, 4) AS median_engagement_rate,
    round(stddev_samp(views) / NULLIF(avg(views), 0), 4)           AS views_cv,
    min(views)                                                     AS min_views,
    min(uploaded_at)                                              AS first_uploaded_at,
    max(uploaded_at)                                               AS last_uploaded_at,
    round(count(*)::numeric * 7
          / GREATEST(EXTRACT(EPOCH FROM (max(uploaded_at) - min(uploaded_at))) / 86400.0, 7)::numeric, 1) AS posts_per_week
  FROM win
  GROUP BY owner_username
),
acc_tier AS (
  SELECT
    owner_username,
    max(followers) AS followers,
    CASE
      WHEN max(followers) IS NULL   THEN 'unknown'
      WHEN max(followers) < 10000   THEN 'micro'
      WHEN max(followers) < 100000  THEN 'mid'
      ELSE 'macro'
    END AS tier,
    CASE
      WHEN max(followers) IS NULL   THEN NULL
      WHEN max(followers) < 10000   THEN '1만 미만'
      WHEN max(followers) < 30000   THEN '1만~3만'
      WHEN max(followers) < 50000   THEN '3만~5만'
      WHEN max(followers) < 100000  THEN '5만~10만'
      WHEN max(followers) < 300000  THEN '10만~30만'
      WHEN max(followers) < 500000  THEN '30만~50만'
      WHEN max(followers) < 1000000 THEN '50만~100만'
      ELSE '100만 이상'
    END AS follower_band
  FROM win
  GROUP BY owner_username
),
primary_cat AS (
  -- 주력 카테고리: 게시물 수 최다 main_group(동률 시 알파벳). 구간 포지션·정체성 라벨 근거.
  SELECT owner_username, main_group AS primary_category
  FROM (
    SELECT owner_username, main_group,
      row_number() OVER (PARTITION BY owner_username ORDER BY count(*) DESC, main_group ASC) AS rn
    FROM win
    WHERE main_group IS NOT NULL
    GROUP BY owner_username, main_group
  ) x
  WHERE rn = 1
),
tier_avg AS (
  SELECT t.tier, round(avg(w.views), 1) AS tier_avg_views
  FROM win w
  JOIN acc_tier t ON t.owner_username = w.owner_username
  GROUP BY t.tier
),
position_rank AS (
  -- 구간 내 포지션: 세분 팔로워밴드 × 주력 카테고리 동일군 안에서 평균 ER의 percent_rank.
  -- 도달 효율(조회수/coarse tier)과 달리 포지션은 동일 규모·카테고리 피어 대비 인게이지먼트 품질 순위(ER 확정).
  SELECT
    b.owner_username,
    round(percent_rank() OVER (
      PARTITION BY ab.follower_band, pc.primary_category
      ORDER BY b.avg_engagement_rate)::numeric, 4) AS position_percentile
  FROM base b
  JOIN acc_tier ab ON ab.owner_username = b.owner_username
  LEFT JOIN primary_cat pc ON pc.owner_username = b.owner_username
),
momentum AS (
  -- 최근 절반 vs 이전 절반 조회수. 오름차순 정렬 후 previous=앞 floor(n/2), recent=뒤 floor(n/2), 홀수 중앙 제외.
  SELECT
    owner_username,
    round(avg(views) FILTER (WHERE half = 'recent')
          / NULLIF(avg(views) FILTER (WHERE half = 'previous'), 0), 4) AS momentum_ratio,
    round((avg(views) FILTER (WHERE half = 'recent')
          / NULLIF(avg(views) FILTER (WHERE half = 'previous'), 0) - 1) * 100) AS momentum_pct
  FROM (
    SELECT owner_username, views,
      CASE
        WHEN rn <= cnt / 2       THEN 'previous'
        WHEN rn >  cnt - cnt / 2 THEN 'recent'
        ELSE 'middle'
      END AS half
    FROM (
      SELECT owner_username, views,
        row_number() OVER (PARTITION BY owner_username ORDER BY uploaded_at ASC, content_id ASC) AS rn,
        count(*)     OVER (PARTITION BY owner_username)                                          AS cnt
      FROM win
    ) ranked
  ) split
  GROUP BY owner_username
),
cat AS (
  SELECT owner_username,
    jsonb_agg(jsonb_build_object(
      'mainGroup',    main_group,
      'contentCount', cnt,
      'sharePct',     share_pct,
      'avgViews',     avg_views
    ) ORDER BY cnt DESC, main_group ASC) AS category_breakdown
  FROM (
    SELECT owner_username, main_group,
      count(*)                                                                        AS cnt,
      round(count(*)::numeric / sum(count(*)) OVER (PARTITION BY owner_username) * 100, 1) AS share_pct,
      round(avg(views), 1)                                                            AS avg_views
    FROM win
    WHERE main_group IS NOT NULL
    GROUP BY owner_username, main_group
  ) g
  GROUP BY owner_username
),
fmt AS (
  SELECT owner_username,
    jsonb_agg(jsonb_build_object(
      'contentFormat', content_format,
      'contentCount',  cnt,
      'sharePct',      share_pct,
      'avgViews',      avg_views
    ) ORDER BY cnt DESC, content_format ASC) AS format_breakdown
  FROM (
    SELECT owner_username, content_format,
      count(*)                                                                        AS cnt,
      round(count(*)::numeric / sum(count(*)) OVER (PARTITION BY owner_username) * 100, 1) AS share_pct,
      round(avg(views), 1)                                                            AS avg_views
    FROM win
    GROUP BY owner_username, content_format
  ) g
  GROUP BY owner_username
),
commercial AS (
  SELECT owner_username,
    count(*) FILTER (WHERE ad_marked)                                    AS ad_count,
    round(avg(CASE WHEN ad_marked THEN 1 ELSE 0 END)::numeric, 4)        AS ad_ratio,
    round(avg(views)                FILTER (WHERE ad_marked), 1)         AS ad_avg_views,
    round(avg(likes + comments_count) FILTER (WHERE ad_marked), 1)       AS ad_avg_engagement,
    round(avg(views)                FILTER (WHERE NOT ad_marked), 1)     AS non_ad_avg_views,
    max(uploaded_at)                FILTER (WHERE ad_marked)             AS last_ad_uploaded_at,
    CASE WHEN count(*) FILTER (WHERE ad_marked) >= 2
      THEN round(
        EXTRACT(EPOCH FROM (max(uploaded_at) FILTER (WHERE ad_marked)
                          - min(uploaded_at) FILTER (WHERE ad_marked))) / 86400.0
        / (count(*) FILTER (WHERE ad_marked) - 1), 1)
    END AS ad_avg_gap_days
  FROM win
  GROUP BY owner_username
)
SELECT
  b.owner_username,
  b.sample_size,
  t.tier,
  t.followers,
  -- 헤더 프로필 기본
  pr.follows_count,
  pr.posts_count,
  pr.biography,
  t.follower_band,
  -- 정체성
  cat.category_breakdown,
  fmt.format_breakdown,
  pc.primary_category,
  b.posts_per_week,
  b.first_uploaded_at,
  b.last_uploaded_at,
  -- 성과
  b.avg_views,
  b.median_views,
  b.avg_engagement_rate,
  b.median_engagement_rate,
  b.views_sample_size,
  round(b.avg_views / NULLIF(ta.tier_avg_views, 0), 2)               AS reach_efficiency,
  round((b.avg_views / NULLIF(ta.tier_avg_views, 0) - 1) * 100)      AS reach_efficiency_pct,
  ta.tier_avg_views,
  posr.position_percentile,
  CASE WHEN b.sample_size >= cfg.min_sample THEN m.momentum_ratio END AS momentum_ratio,
  CASE WHEN b.sample_size >= cfg.min_sample THEN m.momentum_pct   END AS momentum_pct,
  COALESCE((CASE WHEN b.sample_size >= cfg.min_sample THEN m.momentum_pct END) <= -cfg.drop_threshold, false) AS momentum_warning,
  -- 일관성
  s.hit_count,
  s.hit_rate,
  b.views_cv,
  CASE
    WHEN b.views_sample_size < cfg.min_sample OR b.views_cv IS NULL THEN NULL
    WHEN b.views_cv < cfg.mid_cv  THEN 'low'
    WHEN b.views_cv < cfg.high_cv THEN 'mid'
    ELSE 'high'
  END AS volatility_label,
  b.min_views,
  -- 커머셜
  co.ad_count,
  co.ad_ratio,
  co.ad_avg_views,
  co.ad_avg_engagement,
  co.non_ad_avg_views,
  co.last_ad_uploaded_at,
  co.ad_avg_gap_days
FROM base b
JOIN acc_tier t          ON t.owner_username = b.owner_username
LEFT JOIN profile pr     ON pr.owner_username = b.owner_username
LEFT JOIN tier_avg ta    ON ta.tier = t.tier
LEFT JOIN primary_cat pc ON pc.owner_username = b.owner_username
LEFT JOIN position_rank posr ON posr.owner_username = b.owner_username
LEFT JOIN momentum m     ON m.owner_username = b.owner_username
LEFT JOIN cat            ON cat.owner_username = b.owner_username
LEFT JOIN fmt            ON fmt.owner_username = b.owner_username
LEFT JOIN commercial co  ON co.owner_username = b.owner_username
LEFT JOIN analytics.v_author_summary s ON s.owner_username = b.owner_username
CROSS JOIN cfg;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh test/10_creator_detail.test.sql`
Expected: `PASS: test/10_creator_detail.test.sql` → `ALL GREEN`

- [ ] **Step 5: 전체 회귀**

Run: `cd analytics && ./test/run.sh`
Expected: 모든 테스트 `PASS` → `ALL GREEN`

- [ ] **Step 6: Commit**

```bash
git add analytics/views/10_creator_detail.sql analytics/test/10_creator_detail.test.sql
git commit -m "feat(analytics): 인플루언서 상세 계정 요약 뷰 (정체성·성과·일관성·커머셜)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 게시물별 조회수 시계열 + 협업 이력 1:N 뷰

**Files:**
- Modify: `analytics/views/10_creator_detail.sql` (파일 후반부 추가)
- Modify: `analytics/test/10_creator_detail.test.sql` (assert 추가)

- [ ] **Step 1: 실패하는 테스트 추가**

`analytics/test/10_creator_detail.test.sql` 끝에 추가 (픽스처는 Task 1에서 이미 삽입됨):

```sql
-- v_creator_view_series: 윈도우 전체 게시물 1건=1행 (히트 플래그 포함)
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_creator_view_series) = 19, 'view_series rows != 19';
  ASSERT (SELECT count(*) FROM analytics.v_creator_view_series WHERE owner_username='dummy_series') = 6, 'series series rows != 6';
  ASSERT (SELECT is_hit FROM analytics.v_creator_view_series WHERE short_code='dummy_s6') = true, 'series s6 not hit';
  ASSERT (SELECT is_hit FROM analytics.v_creator_view_series WHERE short_code='dummy_s1') = false, 'series s1 hit';
  -- 피드(views NULL)는 is_hit NULL
  ASSERT (SELECT is_hit FROM analytics.v_creator_view_series WHERE short_code='dummy_c2') IS NULL, 'c2 is_hit not null';
END $$;

-- v_creator_ad_history: 광고 게시물만 (골격 = 시기·성과·링크·카테고리)
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_creator_ad_history) = 3, 'ad_history rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_creator_ad_history WHERE owner_username='dummy_series') = 2, 'series ad_history != 2';
  ASSERT (SELECT is_hit FROM analytics.v_creator_ad_history WHERE short_code='dummy_s6') = true, 'ad s6 not hit';
  ASSERT (SELECT is_hit FROM analytics.v_creator_ad_history WHERE short_code='dummy_s3') = false, 'ad s3 hit';
  ASSERT (SELECT main_group FROM analytics.v_creator_ad_history WHERE short_code='dummy_c2') = 'A', 'c2 ad main_group != A';
END $$;
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/10_creator_detail.test.sql`
Expected: FAIL — `relation "analytics.v_creator_view_series" does not exist`

- [ ] **Step 3: 뷰 추가**

`analytics/views/10_creator_detail.sql` 끝에 추가:

```sql
-- 게시물별 조회수 시계열 (일관성 차트 원천). 계정×게시물 1행. 히트=조회수 >= 작성자 평균의 2배.
-- 미러 후 was가 owner_username으로 조회해 시간순 배열로 조립한다.
CREATE OR REPLACE VIEW analytics.v_creator_view_series AS
SELECT
  w.owner_username,
  w.content_id,
  w.short_code,
  w.uploaded_at,
  w.views,
  w.content_format,
  w.main_group,
  w.ad_marked,
  (w.views >= 2 * s.avg_views) AS is_hit
FROM analytics.v_recent_content w
LEFT JOIN analytics.v_author_summary s ON s.owner_username = w.owner_username;

-- 협업 이력 골격 (커머셜 섹션). 광고 표기 게시물만. 브랜드명·광고유형 라벨은 Plan 4·5에서 채운다.
CREATE OR REPLACE VIEW analytics.v_creator_ad_history AS
SELECT
  w.owner_username,
  w.content_id,
  w.short_code,
  w.uploaded_at,
  w.views,
  w.content_format,
  w.main_group,
  (w.views >= 2 * s.avg_views) AS is_hit
FROM analytics.v_recent_content w
LEFT JOIN analytics.v_author_summary s ON s.owner_username = w.owner_username
WHERE w.ad_marked = true;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh test/10_creator_detail.test.sql`
Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: 전체 회귀**

Run: `cd analytics && ./test/run.sh`
Expected: `ALL GREEN`

- [ ] **Step 6: Commit**

```bash
git add analytics/views/10_creator_detail.sql analytics/test/10_creator_detail.test.sql
git commit -m "feat(analytics): 게시물별 조회수 시계열 + 협업 이력 1:N 뷰

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: analysis DB 미러 등록 (신규 3종 + 미등록 08 기둥 4종)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java:28-37`

- [ ] **Step 1: VIEW_MAPPINGS에 등록**

`VIEW_MAPPINGS` 리스트 마지막 항목(Plan 1 병합 후엔 `v_post_detail`, 미병합 시 `v_hashtag_performance`) 뒤에 추가한다. 아래처럼 리스트 꼬리를 교체:

```java
			new ViewMapping("v_hashtag_performance", "hashtag_performance"),
			new ViewMapping("v_post_detail", "post_detail"),
			// Plan 2: 인플루언서 상세
			new ViewMapping("v_creator_detail", "creator_detail"),
			new ViewMapping("v_creator_view_series", "creator_view_series"),
			new ViewMapping("v_creator_ad_history", "creator_ad_history"),
			// roadmap 미등록분(08 크리에이터 기둥) — 이번에 함께 등록
			new ViewMapping("v_creator_card", "creator_card"),
			new ViewMapping("v_creator_authenticity", "creator_authenticity"),
			new ViewMapping("v_creator_top_contents", "creator_top_contents"),
			new ViewMapping("v_creator_comment_samples", "creator_comment_samples"));
```

> 주의: 마지막 원소의 닫는 괄호 `)`가 `List.of(...)`를 닫는다. Plan 1이 아직 병합되지 않았다면 `v_post_detail` 줄은 빼고, `v_hashtag_performance` 줄 끝 세미콜론/괄호를 제거한 뒤 위 신규 줄들을 이어 붙인다.

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :analytics:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 로컬 미러 실행 검증**

```bash
docker compose up -d
cd analytics && ./test/run.sh   # 뷰 적용(00→10) 겸 테스트
cd .. && ./gradlew :analytics:bootRun
docker exec -i crawler-postgres-1 psql -U crawler -d analysis \
  -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='creator_detail' ORDER BY ordinal_position;" \
  -c "SELECT count(*) FILTER (WHERE category_breakdown IS NOT NULL) AS cat_filled, count(*) FILTER (WHERE format_breakdown IS NOT NULL) AS fmt_filled, count(*) AS total FROM creator_detail;" \
  -c "SELECT count(*) FROM creator_view_series;" \
  -c "SELECT count(*) FROM creator_ad_history;"
```

Expected: `creator_detail` 테이블 생성, `category_breakdown`/`format_breakdown` 컬럼이 `jsonb`(그리고 `cat_filled`/`fmt_filled`가 0 아님 = jsonb 왕복 실제 채워짐 확인), `follows_count`/`posts_count`/`min_views` 등이 `bigint`, `posts_per_week`/`avg_views`/`position_percentile` 등이 `numeric`. analytics 로그에 `materialized creator_detail: N rows` / `creator_view_series` / `creator_ad_history` / `creator_card` 등.

> jsonb 미러 실런타임 검증: `information_schema`로 타입(jsonb)만이 아니라 위 `cat_filled > 0`로 `queryForList`→`PGobject`→`batchUpdate` 왕복이 실제로 채워졌는지 함께 확인한다(§9 안전 가정의 런타임 확증). 비면 다음 Step의 `::text` 폴백을 적용한다.

> ⚠️ jsonb 미러 실패 시(buildCreateTableSql DDL 또는 batchUpdate 오류) 뷰에서 `category_breakdown::text AS category_breakdown`, `format_breakdown::text AS format_breakdown`로 캐스팅해 text로 미러한다. was는 이미 `::text`로 읽으므로(Task 5) 이후 코드 변경 없음. (조사 §9상 jsonb는 안전하므로 통상 불필요.)

- [ ] **Step 4: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java
git commit -m "feat(analytics): 인플루언서 상세 뷰 3종 + 08 기둥 뷰 4종 미러 등록

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: was 조회 계층 (Row records + Repository, Testcontainers 검증)

**Files:**
- Create: `was/src/main/java/com/celfit/was/influencer/InfluencerDetailRow.java`
- Create: `was/src/main/java/com/celfit/was/influencer/CreatorViewSeriesRow.java`
- Create: `was/src/main/java/com/celfit/was/influencer/CreatorAdHistoryRow.java`
- Create: `was/src/main/java/com/celfit/was/influencer/InfluencerDetailRepository.java`
- Create: `was/src/test/java/com/celfit/was/influencer/InfluencerDetailRepositoryTest.java`

> 전제: `was/src/test/java/com/celfit/was/IntegrationTest.java`, `was/build.gradle`의 testcontainers 3종은 Plan 1 산출물을 재사용한다(추가 작업 없음).

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

`was/src/test/java/com/celfit/was/influencer/InfluencerDetailRepositoryTest.java`:

```java
package com.celfit.was.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class InfluencerDetailRepositoryTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    InfluencerDetailRepository repository;

    @BeforeEach
    void setUpTables() {
        // MaterializationService가 생성하는 미러 테이블과 동일 형상
        jdbcTemplate.execute("DROP TABLE IF EXISTS creator_detail");
        jdbcTemplate.execute("""
                CREATE TABLE creator_detail (
                  owner_username text, sample_size bigint, tier text, followers bigint,
                  follows_count bigint, posts_count bigint, biography text, follower_band text,
                  category_breakdown jsonb, format_breakdown jsonb,
                  primary_category text, posts_per_week numeric,
                  first_uploaded_at timestamptz, last_uploaded_at timestamptz,
                  avg_views numeric, median_views numeric,
                  avg_engagement_rate numeric, median_engagement_rate numeric, views_sample_size bigint,
                  reach_efficiency numeric, reach_efficiency_pct numeric, tier_avg_views numeric,
                  position_percentile numeric,
                  momentum_ratio numeric, momentum_pct numeric, momentum_warning boolean,
                  hit_count bigint, hit_rate numeric, views_cv numeric, volatility_label text, min_views bigint,
                  ad_count bigint, ad_ratio numeric, ad_avg_views numeric, ad_avg_engagement numeric,
                  non_ad_avg_views numeric, last_ad_uploaded_at timestamptz, ad_avg_gap_days numeric
                )
                """);
        jdbcTemplate.execute("DROP TABLE IF EXISTS creator_view_series");
        jdbcTemplate.execute("""
                CREATE TABLE creator_view_series (
                  owner_username text, content_id bigint, short_code text, uploaded_at timestamptz,
                  views bigint, content_format text, main_group text, ad_marked boolean, is_hit boolean
                )
                """);
        jdbcTemplate.execute("DROP TABLE IF EXISTS creator_ad_history");
        jdbcTemplate.execute("""
                CREATE TABLE creator_ad_history (
                  owner_username text, content_id bigint, short_code text, uploaded_at timestamptz,
                  views bigint, content_format text, main_group text, is_hit boolean
                )
                """);

        jdbcTemplate.update("""
                INSERT INTO creator_detail VALUES (
                  'glow_yeon', 12, 'mid', 24000,
                  312, 486, '뷰티 크리에이터입니다', '1만~3만',
                  '[{"mainGroup":"스킨케어","contentCount":7,"sharePct":58.3,"avgViews":34100.0}]'::jsonb,
                  '[{"contentFormat":"reel","contentCount":9,"sharePct":75.0,"avgViews":38200.0}]'::jsonb,
                  '스킨케어', 2.4,
                  '2026-06-03T09:00:00+09:00', '2026-07-08T09:00:00+09:00',
                  30600.0, 22100.0,
                  0.0430, 0.0390, 12,
                  1.30, 28, 23500.0,
                  0.9200,
                  0.8200, -18, true,
                  4, 0.3300, 0.6500, 'high', 9200,
                  2, 0.1700, 26900.0, 1150.0,
                  30600.0, '2026-06-17T09:00:00+09:00', 35.0
                )
                """);
        jdbcTemplate.update("INSERT INTO creator_view_series VALUES "
                + "('glow_yeon', 1, 'sc_a', '2026-06-01T09:00:00+09:00', 12000, 'reel', '스킨케어', false, false),"
                + "('glow_yeon', 2, 'sc_b', '2026-06-20T09:00:00+09:00', 70000, 'reel', '스킨케어', false, true)");
        jdbcTemplate.update("INSERT INTO creator_ad_history VALUES "
                + "('glow_yeon', 3, 'sc_ad', '2026-06-17T09:00:00+09:00', 26900, 'reel', '스킨케어', false)");
    }

    @Test
    void username으로_계정_요약_1건을_읽는다() {
        Optional<InfluencerDetailRow> found = repository.findByUsername("glow_yeon");

        assertThat(found).isPresent();
        InfluencerDetailRow row = found.get();
        assertThat(row.tier()).isEqualTo("mid");
        assertThat(row.sampleSize()).isEqualTo(12L);
        assertThat(row.followsCount()).isEqualTo(312L);
        assertThat(row.postsCount()).isEqualTo(486L);
        assertThat(row.biography()).isEqualTo("뷰티 크리에이터입니다");
        assertThat(row.followerBand()).isEqualTo("1만~3만");
        assertThat(row.primaryCategory()).isEqualTo("스킨케어");
        assertThat(row.firstUploadedAt()).isNotNull();
        assertThat(row.hitCount()).isEqualTo(4L);
        assertThat(row.volatilityLabel()).isEqualTo("high");
        assertThat(row.categoryBreakdownJson()).contains("스킨케어");
        assertThat(row.adAvgViews()).isNotNull();
    }

    @Test
    void 없는_username이면_empty를_반환한다() {
        assertThat(repository.findByUsername("nope")).isEmpty();
    }

    @Test
    void 조회수_시계열을_시간순으로_읽는다() {
        List<CreatorViewSeriesRow> series = repository.findViewSeries("glow_yeon");

        assertThat(series).hasSize(2);
        assertThat(series.get(0).shortCode()).isEqualTo("sc_a"); // 오래된 것 먼저
        assertThat(series.get(1).isHit()).isTrue();
    }

    @Test
    void 협업_이력을_읽는다() {
        List<CreatorAdHistoryRow> ads = repository.findAdHistory("glow_yeon");

        assertThat(ads).hasSize(1);
        assertThat(ads.get(0).shortCode()).isEqualTo("sc_ad");
        assertThat(ads.get(0).views()).isEqualTo(26900L);
    }
}
```

> creator_detail 인서트의 `volatility_label`은 'high'로 넣어 라벨 왕복을 검증한다(뷰 산출과 무관한 저장/조회 테스트).

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*InfluencerDetailRepositoryTest*'`
Expected: FAIL — `InfluencerDetailRepository`/`InfluencerDetailRow` 등 심볼 없음(컴파일 에러)

- [ ] **Step 3: Row record 3종 작성**

`was/src/main/java/com/celfit/was/influencer/InfluencerDetailRow.java`:

```java
package com.celfit.was.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** analysis DB의 creator_detail 미러 1행. snake_case 컬럼 ↔ camelCase 컴포넌트 자동 매핑. */
public record InfluencerDetailRow(
        String ownerUsername,
        Long sampleSize,
        String tier,
        Long followers,
        Long followsCount,
        Long postsCount,
        String biography,
        String followerBand,
        String categoryBreakdownJson,
        String formatBreakdownJson,
        String primaryCategory,
        BigDecimal postsPerWeek,
        OffsetDateTime firstUploadedAt,
        OffsetDateTime lastUploadedAt,
        BigDecimal avgViews,
        BigDecimal medianViews,
        BigDecimal avgEngagementRate,
        BigDecimal medianEngagementRate,
        Long viewsSampleSize,
        BigDecimal reachEfficiency,
        BigDecimal reachEfficiencyPct,
        BigDecimal tierAvgViews,
        BigDecimal positionPercentile,
        BigDecimal momentumRatio,
        BigDecimal momentumPct,
        Boolean momentumWarning,
        Long hitCount,
        BigDecimal hitRate,
        BigDecimal viewsCv,
        String volatilityLabel,
        Long minViews,
        Long adCount,
        BigDecimal adRatio,
        BigDecimal adAvgViews,
        BigDecimal adAvgEngagement,
        BigDecimal nonAdAvgViews,
        OffsetDateTime lastAdUploadedAt,
        BigDecimal adAvgGapDays) {
}
```

`was/src/main/java/com/celfit/was/influencer/CreatorViewSeriesRow.java`:

```java
package com.celfit.was.influencer;

import java.time.OffsetDateTime;

/** creator_view_series 미러 1행 (게시물별 조회수 시계열). */
public record CreatorViewSeriesRow(
        String shortCode,
        OffsetDateTime uploadedAt,
        Long views,
        Boolean isHit,
        String contentFormat,
        Boolean adMarked,
        String mainGroup) {
}
```

`was/src/main/java/com/celfit/was/influencer/CreatorAdHistoryRow.java`:

```java
package com.celfit.was.influencer;

import java.time.OffsetDateTime;

/** creator_ad_history 미러 1행 (협업 이력 골격). */
public record CreatorAdHistoryRow(
        String shortCode,
        OffsetDateTime uploadedAt,
        Long views,
        Boolean isHit,
        String contentFormat,
        String mainGroup) {
}
```

- [ ] **Step 4: Repository 작성**

`was/src/main/java/com/celfit/was/influencer/InfluencerDetailRepository.java`:

```java
package com.celfit.was.influencer;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InfluencerDetailRepository {

    private static final Logger log = LoggerFactory.getLogger(InfluencerDetailRepository.class);

    private final JdbcClient jdbcClient;

    public InfluencerDetailRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<InfluencerDetailRow> findByUsername(String username) {
        try {
            return jdbcClient.sql("""
                    SELECT owner_username, sample_size, tier, followers,
                           follows_count, posts_count, biography, follower_band,
                           category_breakdown::text AS category_breakdown_json,
                           format_breakdown::text   AS format_breakdown_json,
                           primary_category, posts_per_week,
                           first_uploaded_at, last_uploaded_at,
                           avg_views, median_views, avg_engagement_rate, median_engagement_rate, views_sample_size,
                           reach_efficiency, reach_efficiency_pct, tier_avg_views, position_percentile,
                           momentum_ratio, momentum_pct, momentum_warning,
                           hit_count, hit_rate, views_cv, volatility_label, min_views,
                           ad_count, ad_ratio, ad_avg_views, ad_avg_engagement, non_ad_avg_views,
                           last_ad_uploaded_at, ad_avg_gap_days
                    FROM creator_detail
                    WHERE owner_username = :username
                    """)
                    .param("username", username)
                    .query(InfluencerDetailRow.class)
                    .optional();
        } catch (DataAccessException e) {
            // 미러 테이블 부재 시에도 500 대신 우아하게 저하 (was 에러 컨벤션)
            log.warn("creator_detail 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<CreatorViewSeriesRow> findViewSeries(String username) {
        try {
            return jdbcClient.sql("""
                    SELECT short_code, uploaded_at, views, is_hit, content_format, ad_marked, main_group
                    FROM creator_view_series
                    WHERE owner_username = :username
                    ORDER BY uploaded_at ASC, content_id ASC
                    """)
                    .param("username", username)
                    .query(CreatorViewSeriesRow.class)
                    .list();
        } catch (DataAccessException e) {
            log.warn("creator_view_series 조회 실패, 빈 목록으로 대체합니다: {}", e.getMessage());
            return List.of();
        }
    }

    public List<CreatorAdHistoryRow> findAdHistory(String username) {
        try {
            return jdbcClient.sql("""
                    SELECT short_code, uploaded_at, views, is_hit, content_format, main_group
                    FROM creator_ad_history
                    WHERE owner_username = :username
                    ORDER BY uploaded_at DESC, content_id DESC
                    """)
                    .param("username", username)
                    .query(CreatorAdHistoryRow.class)
                    .list();
        } catch (DataAccessException e) {
            log.warn("creator_ad_history 조회 실패, 빈 목록으로 대체합니다: {}", e.getMessage());
            return List.of();
        }
    }
}
```

> `ORDER BY ... content_id`를 SQL에 두었으므로 SELECT에도 있어야 하는가? Postgres는 SELECT 목록에 없어도 ORDER BY에 원본 컬럼 사용 가능(뷰 컬럼이므로 OK). 미러 테이블에는 `content_id` 컬럼이 있으니 정렬 안전.

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*InfluencerDetailRepositoryTest*'`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add was/src/main/java/com/celfit/was/influencer/InfluencerDetailRow.java \
  was/src/main/java/com/celfit/was/influencer/CreatorViewSeriesRow.java \
  was/src/main/java/com/celfit/was/influencer/CreatorAdHistoryRow.java \
  was/src/main/java/com/celfit/was/influencer/InfluencerDetailRepository.java \
  was/src/test/java/com/celfit/was/influencer/InfluencerDetailRepositoryTest.java
git commit -m "feat(was): 인플루언서 상세 조회 리포지토리 + Testcontainers 테스트

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 응답 DTO + 어셈블러

**Files:**
- Create: `was/src/main/java/com/celfit/was/influencer/InfluencerDetailResponse.java`
- Create: `was/src/main/java/com/celfit/was/influencer/InfluencerDetailAssembler.java`
- Test: `was/src/test/java/com/celfit/was/influencer/InfluencerDetailAssemblerTest.java`

> `ClockConfig`(Clock 빈)는 Plan 1 산출물을 재사용한다.

- [ ] **Step 1: 실패하는 어셈블러 단위 테스트 작성**

`was/src/test/java/com/celfit/was/influencer/InfluencerDetailAssemblerTest.java`:

```java
package com.celfit.was.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InfluencerDetailAssemblerTest {

    // 2026-07-18T00:00Z 고정 — 마지막 게시(2026-07-08 09:00+09 = 2026-07-08 00:00Z)로부터 10일
    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);

    private final InfluencerDetailAssembler assembler =
            new InfluencerDetailAssembler(JsonMapper.builder().build(), fixedClock);

    private InfluencerDetailRow row() {
        return new InfluencerDetailRow(
                "glow_yeon", 12L, "mid", 24000L,
                312L, 486L, "뷰티 크리에이터입니다", "1만~3만",
                "[{\"mainGroup\":\"스킨케어\",\"contentCount\":7,\"sharePct\":58.3,\"avgViews\":34100.0},"
                        + "{\"mainGroup\":\"메이크업\",\"contentCount\":3,\"sharePct\":25.0,\"avgViews\":21700.0}]",
                "[{\"contentFormat\":\"reel\",\"contentCount\":9,\"sharePct\":75.0,\"avgViews\":38200.0},"
                        + "{\"contentFormat\":\"feed\",\"contentCount\":3,\"sharePct\":25.0,\"avgViews\":12400.0}]",
                "스킨케어", new BigDecimal("2.4"),
                OffsetDateTime.parse("2026-06-03T09:00:00+09:00"), OffsetDateTime.parse("2026-07-08T09:00:00+09:00"),
                new BigDecimal("30600.0"), new BigDecimal("22100.0"),
                new BigDecimal("0.0430"), new BigDecimal("0.0390"), 12L,
                new BigDecimal("1.30"), new BigDecimal("28"), new BigDecimal("23500.0"),
                new BigDecimal("0.9200"),
                new BigDecimal("0.8200"), new BigDecimal("-18"), true,
                4L, new BigDecimal("0.3300"), new BigDecimal("0.6500"), "mid", 9200L,
                2L, new BigDecimal("0.1700"), new BigDecimal("26900.0"), new BigDecimal("1150.0"),
                new BigDecimal("30600.0"), OffsetDateTime.parse("2026-06-17T09:00:00+09:00"),
                new BigDecimal("35.0"));
    }

    private List<CreatorViewSeriesRow> series() {
        return List.of(
                new CreatorViewSeriesRow("sc_a", OffsetDateTime.parse("2026-06-01T09:00:00+09:00"),
                        12000L, false, "reel", false, "스킨케어"),
                new CreatorViewSeriesRow("sc_b", OffsetDateTime.parse("2026-06-20T09:00:00+09:00"),
                        70000L, true, "reel", false, "스킨케어"));
    }

    private List<CreatorAdHistoryRow> ads() {
        return List.of(new CreatorAdHistoryRow("sc_ad",
                OffsetDateTime.parse("2026-06-17T09:00:00+09:00"), 26900L, false, "reel", "스킨케어"));
    }

    @Test
    void 행을_블록_구조로_조립한다() {
        InfluencerDetailResponse r = assembler.toResponse(row(), series(), ads());

        // 헤더 (프로필 기본 + 세분 팔로워 밴드 라벨)
        assertThat(r.username()).isEqualTo("glow_yeon");
        assertThat(r.header().tier()).isEqualTo("mid");
        assertThat(r.header().followers()).isEqualTo(24000L);
        assertThat(r.header().followsCount()).isEqualTo(312L);
        assertThat(r.header().postsCount()).isEqualTo(486L);
        assertThat(r.header().bio()).isEqualTo("뷰티 크리에이터입니다");
        assertThat(r.header().followerBandLabel()).isEqualTo("팔로워 1만~3만");
        // 정체성
        assertThat(r.identity().categoryBreakdown()).hasSize(2);
        assertThat(r.identity().categoryBreakdown().get(0).mainGroup()).isEqualTo("스킨케어");
        assertThat(r.identity().categoryBreakdown().get(0).sharePct()).isEqualByComparingTo("58.3");
        assertThat(r.identity().formatBreakdown().get(0).contentFormat()).isEqualTo("reel");
        assertThat(r.identity().postsPerWeek()).isEqualByComparingTo("2.4");
        assertThat(r.identity().firstUploadedAt()).isNotNull();
        assertThat(r.identity().daysSinceLastPost()).isEqualTo(10L);
        assertThat(r.identity().activeSpanWeeks()).isEqualTo(5L); // ceil(35일/7)
        // 성과 (구간 포지션 = 세분 밴드×주력 카테고리)
        assertThat(r.performance().avgViews()).isEqualByComparingTo("30600.0");
        assertThat(r.performance().medianViews()).isEqualByComparingTo("22100.0");
        assertThat(r.performance().reachEfficiency()).isEqualByComparingTo("1.30");
        assertThat(r.performance().positionTopPercent()).isEqualTo(8L); // round((1-0.92)*100)
        assertThat(r.performance().positionBandLabel()).isEqualTo("1만~3만");
        assertThat(r.performance().positionCategory()).isEqualTo("스킨케어");
        assertThat(r.performance().momentum().warning()).isTrue();
        // 일관성
        assertThat(r.consistency().hitCount()).isEqualTo(4L);
        assertThat(r.consistency().volatilityLabel()).isEqualTo("mid");
        assertThat(r.consistency().minViews()).isEqualTo(9200L);
        assertThat(r.consistency().viewSeries()).hasSize(2);
        assertThat(r.consistency().viewSeries().get(1).isHit()).isTrue();
        // 커머셜
        assertThat(r.commercial().adRatio()).isEqualByComparingTo("0.1700");
        assertThat(r.commercial().adAvgViews()).isEqualByComparingTo("26900.0");
        assertThat(r.commercial().adHistory()).hasSize(1);
        assertThat(r.commercial().adHistory().get(0).originalUrl())
                .isEqualTo("https://www.instagram.com/reel/sc_ad/");
    }

    @Test
    void 빈_브레이크다운과_null_필드를_견딘다() {
        InfluencerDetailRow feedOnly = new InfluencerDetailRow(
                "newbie", 1L, "micro", 5000L,
                null, null, null, "1만 미만",
                null, null,
                null, new BigDecimal("1.0"),
                null, OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                null, null, null, null, 0L,
                null, null, null, null,
                null, null, false,
                0L, new BigDecimal("0.0000"), null, null, null,
                0L, new BigDecimal("0.0000"), null, null, null,
                null, null);

        InfluencerDetailResponse r = assembler.toResponse(feedOnly, List.of(), List.of());

        assertThat(r.identity().categoryBreakdown()).isEmpty();
        assertThat(r.identity().formatBreakdown()).isEmpty();
        assertThat(r.performance().positionTopPercent()).isNull();
        assertThat(r.performance().momentum().ratio()).isNull();
        assertThat(r.consistency().viewSeries()).isEmpty();
        assertThat(r.commercial().adHistory()).isEmpty();
        assertThat(r.header().followerBandLabel()).isEqualTo("팔로워 1만 미만");
    }
}
```

> Jackson 3(`tools.jackson.*`)가 Spring Boot 4 기본 스택. 임포트 컴파일 에러 시 정확한 패키지는 `tools.jackson.*` 하위에서 확정(클래스명 `JsonMapper`/`ObjectMapper`/`TypeReference` 동일).

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*InfluencerDetailAssemblerTest*'`
Expected: FAIL — `InfluencerDetailResponse`/`InfluencerDetailAssembler` 심볼 없음

- [ ] **Step 3: 응답 record 작성**

`was/src/main/java/com/celfit/was/influencer/InfluencerDetailResponse.java`:

```java
package com.celfit.was.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 인플루언서 상세 v4 응답. 블록 구조가 확정안 화면 블록과 1:1 대응한다.
 * AI 협업 브리핑·브랜드 적합성·페르소나·광고유형 라벨은 Plan 4·5에서 additive하게 추가된다.
 */
public record InfluencerDetailResponse(
        String username,
        Header header,
        Identity identity,
        Performance performance,
        Consistency consistency,
        Commercial commercial) {

    /** 헤더 — 프로필 기본(팔로워·팔로잉·게시물 수·bio) + 팔로워 구간 라벨. 페르소나 한 줄은 Plan 5. */
    public record Header(
            String username,
            Long followers,
            Long followsCount,
            Long postsCount,
            String bio,
            String tier,
            String followerBandLabel) {
    }

    /** 정체성 — 주력 카테고리 비중, 콘텐츠 유형 구성, 게시 빈도·활동성(최초/최근 게시·활동 주수). */
    public record Identity(
            Long sampleSize,
            List<CategoryShare> categoryBreakdown,
            List<FormatShare> formatBreakdown,
            BigDecimal postsPerWeek,
            OffsetDateTime firstUploadedAt,
            OffsetDateTime lastUploadedAt,
            Long daysSinceLastPost,
            Long activeSpanWeeks) {
    }

    /** 카테고리 비중 요소(정체성) + 카테고리별 평균 조회수(성과 차트 재사용). */
    public record CategoryShare(
            String mainGroup,
            Long contentCount,
            BigDecimal sharePct,
            BigDecimal avgViews) {
    }

    /** 콘텐츠 유형 비중 요소 + 유형별 평균 조회수. */
    public record FormatShare(
            String contentFormat,
            Long contentCount,
            BigDecimal sharePct,
            BigDecimal avgViews) {
    }

    /**
     * 성과 — 평균·중앙값, 도달 효율(coarse tier 기준), 구간 포지션(세분 밴드×주력 카테고리 기준), 모멘텀.
     * positionBandLabel·positionCategory는 "상위 X% (1만~3만 · 스킨케어)" 라벨의 근거 필드다.
     */
    public record Performance(
            BigDecimal avgViews,
            BigDecimal medianViews,
            BigDecimal avgEngagementRate,
            BigDecimal medianEngagementRate,
            Long viewsSampleSize,
            BigDecimal reachEfficiency,
            BigDecimal reachEfficiencyPct,
            BigDecimal tierAvgViews,
            BigDecimal positionPercentile,
            Long positionTopPercent,
            String positionBandLabel,
            String positionCategory,
            Momentum momentum) {
    }

    /** 모멘텀 — 최근 절반 vs 이전 절반. warning은 하락 경고 칩 여부. */
    public record Momentum(
            BigDecimal ratio,
            BigDecimal pct,
            Boolean warning) {
    }

    /** 일관성 — 히트율, 변동성 라벨, 최저 성과 라인, 게시물별 조회수 시계열. */
    public record Consistency(
            Long hitCount,
            BigDecimal hitRate,
            BigDecimal viewsCv,
            String volatilityLabel,
            Long minViews,
            List<ViewPoint> viewSeries) {
    }

    /** 게시물별 조회수 시계열 한 점(히트 강조 플래그 포함). */
    public record ViewPoint(
            String shortCode,
            OffsetDateTime uploadedAt,
            Long views,
            Boolean isHit,
            String contentFormat,
            Boolean adMarked) {
    }

    /** 커머셜 — 광고 비율, 광고/비광고 평균, CPV·CPE 입력값, 협업 이력 골격. */
    public record Commercial(
            Long adCount,
            BigDecimal adRatio,
            BigDecimal adAvgViews,
            BigDecimal adAvgEngagement,
            BigDecimal nonAdAvgViews,
            OffsetDateTime lastAdUploadedAt,
            BigDecimal adAvgGapDays,
            List<AdPost> adHistory) {
    }

    /** 협업 이력 한 건 — 시기·성과·링크. 브랜드명·광고유형 라벨은 Plan 4·5. */
    public record AdPost(
            String shortCode,
            OffsetDateTime uploadedAt,
            Long views,
            Boolean isHit,
            String mainGroup,
            String originalUrl) {
    }
}
```

- [ ] **Step 4: 어셈블러 작성**

`was/src/main/java/com/celfit/was/influencer/InfluencerDetailAssembler.java`:

```java
package com.celfit.was.influencer;

import com.celfit.was.influencer.InfluencerDetailResponse.AdPost;
import com.celfit.was.influencer.InfluencerDetailResponse.CategoryShare;
import com.celfit.was.influencer.InfluencerDetailResponse.Commercial;
import com.celfit.was.influencer.InfluencerDetailResponse.Consistency;
import com.celfit.was.influencer.InfluencerDetailResponse.FormatShare;
import com.celfit.was.influencer.InfluencerDetailResponse.Header;
import com.celfit.was.influencer.InfluencerDetailResponse.Identity;
import com.celfit.was.influencer.InfluencerDetailResponse.Momentum;
import com.celfit.was.influencer.InfluencerDetailResponse.Performance;
import com.celfit.was.influencer.InfluencerDetailResponse.ViewPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class InfluencerDetailAssembler {

    private static final TypeReference<List<CategoryShare>> CATEGORY_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<FormatShare>> FORMAT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InfluencerDetailAssembler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public InfluencerDetailResponse toResponse(
            InfluencerDetailRow row,
            List<CreatorViewSeriesRow> series,
            List<CreatorAdHistoryRow> ads) {
        return new InfluencerDetailResponse(
                row.ownerUsername(),
                new Header(row.ownerUsername(), row.followers(), row.followsCount(), row.postsCount(),
                        row.biography(), row.tier(), followerBandLabel(row.followerBand())),
                new Identity(
                        row.sampleSize(),
                        parseList(row.categoryBreakdownJson(), CATEGORY_LIST),
                        parseList(row.formatBreakdownJson(), FORMAT_LIST),
                        row.postsPerWeek(),
                        row.firstUploadedAt(),
                        row.lastUploadedAt(),
                        daysSince(row.lastUploadedAt()),
                        activeSpanWeeks(row.firstUploadedAt(), row.lastUploadedAt())),
                new Performance(
                        row.avgViews(), row.medianViews(),
                        row.avgEngagementRate(), row.medianEngagementRate(), row.viewsSampleSize(),
                        row.reachEfficiency(), row.reachEfficiencyPct(), row.tierAvgViews(),
                        row.positionPercentile(), topPercent(row.positionPercentile()),
                        row.followerBand(), row.primaryCategory(),
                        new Momentum(row.momentumRatio(), row.momentumPct(), row.momentumWarning())),
                new Consistency(
                        row.hitCount(), row.hitRate(), row.viewsCv(), row.volatilityLabel(), row.minViews(),
                        series.stream().map(this::toViewPoint).toList()),
                new Commercial(
                        row.adCount(), row.adRatio(), row.adAvgViews(), row.adAvgEngagement(),
                        row.nonAdAvgViews(), row.lastAdUploadedAt(), row.adAvgGapDays(),
                        ads.stream().map(this::toAdPost).toList()));
    }

    private ViewPoint toViewPoint(CreatorViewSeriesRow r) {
        return new ViewPoint(r.shortCode(), r.uploadedAt(), r.views(), r.isHit(),
                r.contentFormat(), r.adMarked());
    }

    private AdPost toAdPost(CreatorAdHistoryRow r) {
        return new AdPost(r.shortCode(), r.uploadedAt(), r.views(), r.isHit(), r.mainGroup(),
                originalUrl(r.shortCode(), r.contentFormat()));
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, type);
    }

    private Long daysSince(OffsetDateTime uploadedAt) {
        if (uploadedAt == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(uploadedAt, OffsetDateTime.now(clock));
    }

    /** 구간 내 포지션 "상위 X%" = round((1 - percentile) * 100). percentile NULL이면 null. */
    private Long topPercent(BigDecimal percentile) {
        if (percentile == null) {
            return null;
        }
        return BigDecimal.ONE.subtract(percentile)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * 팔로워 구간 라벨 = "팔로워 " + 세분 밴드. 밴드는 뷰가 followers 실수치로 계산(1만~3만 등).
     * tier 문자열(micro/mid/macro)에서 파생하지 않는다 — 목업 "팔로워 1만~3만" 세분성 재현.
     */
    private String followerBandLabel(String followerBand) {
        return followerBand == null ? "팔로워 정보 없음" : "팔로워 " + followerBand;
    }

    /** 활동 구간(주) = first~last 업로드 span을 주 단위 올림(최소 1). 목업 "최근 N주간 꾸준히 게시"용. */
    private Long activeSpanWeeks(OffsetDateTime first, OffsetDateTime last) {
        if (first == null || last == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(first, last);
        return Math.max(1L, (days + 6) / 7);
    }

    private String originalUrl(String shortCode, String contentFormat) {
        String path = "reel".equals(contentFormat) ? "reel" : "p";
        return "https://www.instagram.com/%s/%s/".formatted(path, shortCode);
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*InfluencerDetailAssemblerTest*'`
Expected: 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add was/src/main/java/com/celfit/was/influencer/InfluencerDetailResponse.java \
  was/src/main/java/com/celfit/was/influencer/InfluencerDetailAssembler.java \
  was/src/test/java/com/celfit/was/influencer/InfluencerDetailAssemblerTest.java
git commit -m "feat(was): 인플루언서 상세 응답 DTO + 어셈블러 (블록 구조 = 확정안 v4)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 컨트롤러

**Files:**
- Create: `was/src/main/java/com/celfit/was/influencer/InfluencerDetailController.java`
- Test: `was/src/test/java/com/celfit/was/influencer/InfluencerDetailControllerTest.java`

> CORS(`/api/**`)는 Plan 1의 `WebConfig`가 이미 처리한다(신규 설정 없음).

- [ ] **Step 1: 실패하는 MockMvc 테스트 작성**

`was/src/test/java/com/celfit/was/influencer/InfluencerDetailControllerTest.java`:

```java
package com.celfit.was.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.ClockConfig;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InfluencerDetailController.class)
@Import({InfluencerDetailAssembler.class, ClockConfig.class})
class InfluencerDetailControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InfluencerDetailRepository repository;

    private InfluencerDetailRow row() {
        return new InfluencerDetailRow(
                "glow_yeon", 12L, "mid", 24000L,
                312L, 486L, "뷰티 크리에이터입니다", "1만~3만",
                "[{\"mainGroup\":\"스킨케어\",\"contentCount\":7,\"sharePct\":58.3,\"avgViews\":34100.0}]",
                "[{\"contentFormat\":\"reel\",\"contentCount\":9,\"sharePct\":75.0,\"avgViews\":38200.0}]",
                "스킨케어", new BigDecimal("2.4"),
                OffsetDateTime.parse("2026-06-03T09:00:00+09:00"), OffsetDateTime.parse("2026-07-08T09:00:00+09:00"),
                new BigDecimal("30600.0"), new BigDecimal("22100.0"),
                new BigDecimal("0.0430"), new BigDecimal("0.0390"), 12L,
                new BigDecimal("1.30"), new BigDecimal("28"), new BigDecimal("23500.0"),
                new BigDecimal("0.9200"),
                new BigDecimal("0.8200"), new BigDecimal("-18"), true,
                4L, new BigDecimal("0.3300"), new BigDecimal("0.6500"), "mid", 9200L,
                2L, new BigDecimal("0.1700"), new BigDecimal("26900.0"), new BigDecimal("1150.0"),
                new BigDecimal("30600.0"), OffsetDateTime.parse("2026-06-17T09:00:00+09:00"),
                new BigDecimal("35.0"));
    }

    @Test
    void 인플루언서_상세를_JSON으로_반환한다() throws Exception {
        given(repository.findByUsername("glow_yeon")).willReturn(Optional.of(row()));
        given(repository.findViewSeries("glow_yeon")).willReturn(List.of(
                new CreatorViewSeriesRow("sc_b", OffsetDateTime.parse("2026-06-20T09:00:00+09:00"),
                        70000L, true, "reel", false, "스킨케어")));
        given(repository.findAdHistory("glow_yeon")).willReturn(List.of(
                new CreatorAdHistoryRow("sc_ad", OffsetDateTime.parse("2026-06-17T09:00:00+09:00"),
                        26900L, false, "reel", "스킨케어")));

        mockMvc.perform(get("/api/influencers/glow_yeon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("glow_yeon"))
                .andExpect(jsonPath("$.header.followerBandLabel").value("팔로워 1만~3만"))
                .andExpect(jsonPath("$.identity.categoryBreakdown[0].mainGroup").value("스킨케어"))
                .andExpect(jsonPath("$.performance.positionTopPercent").value(8))
                .andExpect(jsonPath("$.performance.positionBandLabel").value("1만~3만"))
                .andExpect(jsonPath("$.performance.positionCategory").value("스킨케어"))
                .andExpect(jsonPath("$.performance.momentum.warning").value(true))
                .andExpect(jsonPath("$.consistency.volatilityLabel").value("mid"))
                .andExpect(jsonPath("$.consistency.viewSeries[0].isHit").value(true))
                .andExpect(jsonPath("$.commercial.adHistory[0].originalUrl")
                        .value("https://www.instagram.com/reel/sc_ad/"));
    }

    @Test
    void 없는_인플루언서면_404() throws Exception {
        given(repository.findByUsername("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/influencers/nope"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*InfluencerDetailControllerTest*'`
Expected: FAIL — `InfluencerDetailController` 심볼 없음

- [ ] **Step 3: 컨트롤러 작성**

`was/src/main/java/com/celfit/was/influencer/InfluencerDetailController.java`:

```java
package com.celfit.was.influencer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InfluencerDetailController {

    private final InfluencerDetailRepository repository;
    private final InfluencerDetailAssembler assembler;

    public InfluencerDetailController(InfluencerDetailRepository repository,
            InfluencerDetailAssembler assembler) {
        this.repository = repository;
        this.assembler = assembler;
    }

    @GetMapping("/api/influencers/{username}")
    public InfluencerDetailResponse influencerDetail(@PathVariable String username) {
        return repository.findByUsername(username)
                .map(row -> assembler.toResponse(
                        row,
                        repository.findViewSeries(username),
                        repository.findAdHistory(username)))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "인플루언서를 찾을 수 없습니다: " + username));
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*InfluencerDetailControllerTest*'`
Expected: 2 tests PASS

- [ ] **Step 5: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL (Repository·Assembler·Controller + Plan 1 테스트 전부 PASS)

- [ ] **Step 6: Commit**

```bash
git add was/src/main/java/com/celfit/was/influencer/InfluencerDetailController.java \
  was/src/test/java/com/celfit/was/influencer/InfluencerDetailControllerTest.java
git commit -m "feat(was): GET /api/influencers/{username} 인플루언서 상세 API

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: E2E 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — crawler·analytics·was 전 모듈 그린

- [ ] **Step 2: 파이프라인 관통 검증**

```bash
docker compose up -d
cd analytics && ./test/run.sh && cd ..        # 뷰 적용(00→10) + SQL 테스트
./gradlew :analytics:bootRun                   # analysis DB로 미러 (완료 후 자동 종료)
./gradlew :was:bootRun &                        # was 기동 (port 8081)
sleep 15
USERNAME=$(docker exec -i crawler-postgres-1 psql -U crawler -d analysis -tAc "SELECT owner_username FROM creator_detail LIMIT 1")
curl -s "http://localhost:8081/api/influencers/${USERNAME}" | head -c 3000; echo
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/influencers/__none__"
```

Expected:
- 첫 curl: `username`, `header`(tier·followers·followsCount·postsCount·bio·followerBandLabel), `identity`(categoryBreakdown·formatBreakdown·postsPerWeek·daysSinceLastPost·activeSpanWeeks), `performance`(avgViews·medianViews·reachEfficiency·positionTopPercent·positionBandLabel·positionCategory·momentum), `consistency`(hitRate·volatilityLabel·minViews·viewSeries[]), `commercial`(adRatio·adAvgViews·adAvgEngagement·adHistory[]) 필드가 채워진 JSON
- 둘째 curl: `404`

- [ ] **Step 3: was 종료 및 마무리**

```bash
kill %1 2>/dev/null
git status   # 잔여 변경 없음 확인
```

Expected: working tree clean

> **배포 메모:** 실서버 반영 순서 = ① `analytics/views/09_post_detail.sql`(Plan 1) + `10_creator_detail.sql`을 crawler DB에 적용 → ② analytics 모듈 1회 실행(미러 갱신) → ③ was 배포. was는 미러 테이블이 없어도 404/빈 목록으로 우아하게 저하되므로 순서가 어긋나도 500은 나지 않는다. Plan 1 미배포 상태로 Plan 2 뷰만 적용하면 `v_recent_content` 부재로 뷰 생성이 실패하니, 반드시 Plan 1 뷰를 먼저 적용한다.

---

## Self-Review 체크 결과

- **스펙 커버리지 (확정안 v4 비LLM 요소)**:
  - 헤더: 프로필 기본(username·followers·**followsCount·postsCount·bio**) ✅, 팔로워 구간 라벨(followers 실수치 기반 세분 밴드 `followerBandLabel`="팔로워 1만~3만") ✅. 페르소나·후보 상태/메모/공유는 범위 밖(Plan 5 / 프론트·별도 결정) — 응답에 필드 없음.
  - 정체성: 주력 카테고리 비중(categoryBreakdown.sharePct) ✅, 콘텐츠 유형 구성(formatBreakdown) ✅, 게시 빈도(postsPerWeek)·활동성(daysSinceLastPost·**firstUploadedAt·activeSpanWeeks** = "최근 N주간") ✅.
  - 성과: 평균·중앙값(조회수/ER) ✅, 도달 효율(reachEfficiency ×배수 + reachEfficiencyPct %, coarse tier 기준) ✅, 구간 내 포지션(**세분 팔로워밴드×주력 카테고리** 백분위 positionPercentile+positionTopPercent + 근거필드 positionBandLabel·positionCategory → "상위 8% (1만~3만 · 스킨케어)") ✅, 모멘텀(momentum.ratio/pct/warning) ✅, 유형별·카테고리별 평균 조회수(breakdown.avgViews 재사용) ✅.
  - 일관성: 히트율(hitRate) ✅, 변동성 라벨(volatilityLabel) ✅, 최저 성과 라인(minViews) ✅, 게시물별 조회수 시계열(viewSeries + isHit) ✅.
  - 커머셜 기초: 광고 비율(adRatio) ✅, 광고 vs 비광고 평균(adAvgViews/nonAdAvgViews) ✅, CPV/CPE 입력값(adAvgViews·adAvgEngagement) ✅, 협업 이력 골격(adHistory: 시기·성과·링크·카테고리; 브랜드명·광고유형은 Plan 4·5) ✅, 타이밍 요약(lastAdUploadedAt·adAvgGapDays) ✅.
  - 의도적 범위 밖(AI 브리핑·브랜드 적합성·페르소나·오디언스 질·베스트 콘텐츠·비교함 등) — 응답에 필드 없음(2차 이관).
  - 미등록 08 기둥 뷰 4종 미러 등록 ✅(Task 3).
- **플레이스홀더 스캔**: "TBD"·"적절히"·"유사"·"테스트를 작성하라"(코드 없이) 없음. 모든 SQL/Java 블록은 복붙 가능한 완성본. 기대값은 더미+픽스처로 직접 계산해 assert에 명시(§더미 데이터 기대값 근거). CV 3종만 sqrt 정밀도 리스크로 BETWEEN 범위 assert + 라벨 정확 assert 사용(의도적).
- **타입 일관성**: `v_creator_detail` 최종 SELECT 컬럼(**38개**) = Repository SELECT 별칭(38개) = `InfluencerDetailRow` 컴포넌트(38개) = Repository 테스트 DDL(38개; content_id는 자식 테이블 전용) = Assembler/Controller 테스트 생성자 인자(38개) 순서 동일 확인. 32→38 증분 = follows_count·posts_count·biography·follower_band·primary_category·first_uploaded_at 6컬럼(그리고 tier_er_percentile→position_percentile 개명). jsonb는 `::text`로 읽어 `categoryBreakdownJson`/`formatBreakdownJson`(String)로 매핑, Jackson이 camelCase 키(`mainGroup` 등, SQL `jsonb_build_object`가 camelCase로 생성)로 `CategoryShare`/`FormatShare` 역직렬화. SQL bigint→Java `Long`, numeric→`BigDecimal`, boolean→`Boolean`, timestamptz→`OffsetDateTime`, text→`String`. 자식 뷰 컬럼 = `CreatorViewSeriesRow`/`CreatorAdHistoryRow` 컴포넌트 일치. 응답 record는 InfluencerDetailRow(38)를 Header/Identity/Performance/Consistency/Commercial 블록으로 재배치(1:1 매핑 아님) — Assembler가 조립.
- **실 DB SQL 검증**: `10_creator_detail.sql` 7개 뷰 + 픽스처 + 전체 ASSERT 블록을 실 crawler Postgres에 `BEGIN…ROLLBACK`으로 실행해 EXIT=0·stderr 없음·오염 0(content category_id=999 → 0) 확인. 개정된 position/profile/band/first_uploaded 컬럼 값 전부 실제 출력으로 확정.

## 리뷰가 필요한 판단 지점

1. **Plan 1 의존(독립 배포 원칙과 상충)**: 설계 결정 1을 지켜 `v_recent_content`·`v_author_summary`·was `ClockConfig`/`WebConfig`/`IntegrationTest`를 재사용 → Plan 2는 Plan 1 병합을 전제로 한다. roadmap:42의 'Plan 1·2 병렬/독립' 문구와 배치. 승인 필요.
2. **구간 내 포지션 = (세분 팔로워밴드 × 주력 카테고리) 내 ER 백분위**: `positionPercentile`을 `PARTITION BY follower_band, primary_category ORDER BY avg_engagement_rate`의 `percent_rank`로 정의하고, 라벨 근거로 `positionBandLabel`(밴드)·`positionCategory`(주력 카테고리)를 함께 반환한다. 목업 "상위 8% (1만~3만 · 스킨케어)"를 그대로 재현. 지표는 ER로 **확정**(도달 효율=조회수/coarse tier와 분리해 포지션=동일 규모·카테고리 피어 대비 인게이지먼트 품질). 조회수/도달효율 기준을 원하면 `ORDER BY b.avg_views`로 변경 가능(그 경우 기대값 재산출 필요).
   - 부수: 팔로워 구간 라벨도 tier(micro/mid/macro) 파생이 아니라 `follower_band`(followers 실수치 세분: 1만 미만/1만~3만/…/100만 이상)에서 `"팔로워 "+밴드`로 만든다 — 목업 "팔로워 1만~3만" 세분성 재현. 밴드 경계(1/3/5/10/30/50/100만)는 제품 튜닝 여지.
3. **표본 가드 임계값(detail-min-sample=4)과 변동성/모멘텀 NULL 정책**: 표본 4 미만이면 모멘텀·변동성 라벨을 NULL로 내보낸다(값 대신 "표본 부족"은 프론트 처리). CV 경계(0.5/1.0)·하락 임계(15%)는 근거 기반 기본값이나 제품 튜닝 여지. app_setting 직접 조회라 SettingsService(int 전용) UI엔 노출 안 됨.
4. **게시 빈도 분모 하한(1주)**: `posts_per_week`가 짧은 구간에서 과대되지 않도록 활동 span을 최소 7일로 floor. 같은 날 2건인 dummy_micro가 14/주가 아닌 2.0/주가 되는 근거. 다른 정의(활동 주수 카운트 등)를 원하면 변경.
5. **CV assert를 BETWEEN 범위로**: dummy_series/decline/erratic의 CV는 sqrt 정밀도 때문에 정확값 대신 범위 assert(라벨은 정확 assert). 실 Postgres 검증 시 경계에 걸리면 범위를 좁히거나 소수 자릿수 재계산 필요.

## 리뷰에서 반영하지 않은 지적

- [MINOR] Task 1 Step 3 momentum 홀수 표본 커버리지(중앙 1건 제외 분기)용 5개 릴스 계정 추가 — 미반영. 로직 자체는 리뷰가 합성 데이터(n=5: 100..500 → previous{100,200}/recent{400,500}/middle{300} 제외)로 정상 동작을 이미 확증했고, 픽스처 추가는 `v_creator_view_series` 행수(19)·`v_creator_ad_history`(3)·포지션 파티션 기대값을 연쇄 재산출해야 해 38컬럼 정합 리스크만 늘린다. 회귀 안전성 대비 비용이 커 스코프에서 제외(로직 회귀 시 diag 합성 검증으로 커버).
