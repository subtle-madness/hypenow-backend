# 태스크 C1 — 인플루언서 상세 비LLM 집계 Implementation Plan

> 상태: ✅ 실행됨
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** celfit-front 인플루언서 패널(`AccountReport`)의 결정적(비LLM) 필드 전부를 계산하는 분석 뷰 3종을 만들고 analysis DB로 미러한다.

**Architecture:** [스펙](../../specs/2026-07-13-c1-account-detail-design.md) 그대로 — raw DB `analytics` 스키마에 뷰(`10_account_detail.sql`) → 타입 미러(뷰 SQL / Flyway `V10` DDL / contract record, `MirrorJob` 재사용·무수정) → `account_summaries`·`account_category_stats`·`account_content_series`. 산식 정본은 celfit-front `parse_accounts_recent.py`. 프로필 확장 컬럼 3개(`follows_count`·`posts_count`·`biography`)만 base 뷰에 additive 추가.

**Tech Stack:** PostgreSQL 뷰 + SQL 하니스(`analytics/test/run.sh`, BEGIN/ROLLBACK 격리), Java 21 record(contract-analysis, 순수 JDK), Flyway, Testcontainers(`postgres:16-alpine`).

**작업 위치:** 워크트리 `.worktrees/c1` (브랜치 `feat/task-c1-account-detail`). 모든 명령은 워크트리 루트에서 실행.

**사전 조건:** `docker start crawler-postgres-1` (포트 5433). run.sh는 이 컨테이너의 crawler DB에 뷰를 적용하고 트랜잭션 격리로 테스트한다 — 실DB 불변.

---

## 더미·픽스처 기대값 근거 (산식: parse_accounts_recent.py → 스펙 §3)

`seed/dummy.sql` 기존 계정 2개 (followers는 최신 프로필):

| 계정 | followers | 게시물(올린 순) | likes/comments/views | main_group | ad |
|---|---|---|---|---|---|
| dummy_a | 5500 | dummy_r1 (06-01) | 520/52/11000 | A | F |
| | | dummy_r2 (06-02) | 300/30/7000 | A | F |
| | | dummy_f1 (06-03, 피드) | 2000/100/**NULL** | B | **T** |
| dummy_b | 20000 | dummy_r3 (06-04) | 1000/80/40000 | B | F |

**dummy_a** (n=3, views_count=2):
- metric: 2 ≥ GREATEST(3, 3/2=1)=3 → 거짓 → **'likes'**
- avg_views=(11000+7000)/2=**9000**, views_per_follower=round(9000/5500,1)=**1.6**
- avg_er_pct=avg(572/5500, 330/5500, 2100/5500)×100=18.1939… → **18.2**
- avg_likes=round(2820/3)=**940**, avg_comments=round(182/3)=**61**
- 트렌드(likes 기준, 앞 절반=floor(3/2)=1개): older={520}→520, newer={300,2000}→1150.
  change=1150/520−1=1.2115 → **'up'**, change_pct=**121**, older_avg=**520**, newer_avg=**1150**
- 광고: sponsored_count=**1**(f1), organic(likes>0)={520,300}→avg **410**(모수 2), ad={2000}→**2000**(모수 1),
  ad_drop_pct=round((1−2000/410)×100)=**−388**, last_ad_posted_at=**2026-06-03 09:00+09**
- last_posted_at=**06-03 09:00+09**, avg_interval_days=2일/(3−1)=**1.0**
- 프로필 확장: 시드 payload에 followsCount·postsCount·biography 없음 → 셋 다 **NULL**

**dummy_b** (n=1, views_count=1):
- metric: 1 ≥ 3 거짓 → **'likes'**, avg_views=**40000**, views_per_follower=**2.0**
- avg_er_pct=(1080/20000)×100=**5.4**, avg_likes=**1000**, avg_comments=**80**
- 트렌드: 앞 절반 0개 → older_raw NULL → **'flat'**, change_pct **0**, older_avg **NULL**, newer_avg **1000**
- 광고: sponsored_count=**0**, organic_avg=**1000**(모수 1), ad_avg·ad_drop_pct·last_ad_posted_at **NULL**
- avg_interval_days **NULL**(n=1)

**추가 픽스처** (테스트 파일 내 트랜잭션 INSERT — 계정 9005~9006, 콘텐츠 9110~9123, 전부 main_group 'B', REELS):

dummy_v (9005, followers 10000, 주 간격 6건, 광고 2건 — metric 'views'·트렌드 down·양쪽 비교 검증):

| short | uploaded | views | likes | comments | ad |
|---|---|---|---|---|---|
| dummy_v1 | 05-01 | 20000 | 400 | 40 | F |
| dummy_v2 | 05-08 | 18000 | 300 | 30 | F |
| dummy_v3 | 05-15 | 22000 | 500 | 50 | **T** |
| dummy_v4 | 05-22 | 10000 | 200 | 20 | F |
| dummy_v5 | 05-29 | 8000 | 150 | 15 | **T** |
| dummy_v6 | 06-05 | 6000 | 100 | 10 | F |

- metric: 6 ≥ GREATEST(3,3)=3 → **'views'**, avg_views=84000/6=**14000**, views_per_follower=**1.4**
- avg_er_pct=avg(.044,.033,.055,.022,.0165,.011)×100=3.025 → **3.0**
- avg_likes=round(1650/6)=**275**, avg_comments=round(165/6=27.5)=**28**
- 트렌드: older={20000,18000,22000}→20000, newer={10000,8000,6000}→8000 → change −0.6 → **'down'**, pct **−60**
- 광고: sponsored_count=**2**, organic={20000,18000,10000,6000}→**13500**(모수 4), ad={22000,8000}→**15000**(모수 2),
  drop=round((1−15000/13500)×100)=**−11**, last_ad=**05-29 09:00+09**
- interval=35일/5=**7.0**. 프로필: follows_count **300**, posts_count **80**, biography **'글로우 크리에이터'**

dummy_flat (9006, followers 8000, 4건, 광고 없음 — 'flat'·±15% 이내 검증):

| short | uploaded | views | likes | comments |
|---|---|---|---|---|
| dummy_t1 | 05-01 | 10000 | 200 | 20 |
| dummy_t2 | 05-08 | 11000 | 210 | 21 |
| dummy_t3 | 05-15 | 9000 | 190 | 19 |
| dummy_t4 | 05-22 | 11500 | 205 | 25 |

- metric: 4 ≥ GREATEST(3,2)=3 → **'views'**, avg_views=41500/4=**10375**, views_per_follower=round(1.2969,1)=**1.3**
- avg_er_pct=avg(.0275,.028875,.026125,.02875)×100=2.78125 → **2.8**
- avg_likes=round(805/4=201.25)=**201**, avg_comments=round(85/4=21.25)=**21**
- 트렌드: older={10000,11000}→10500, newer={9000,11500}→10250 → change −0.0238(±0.15 이내) → **'flat'**, pct **−2**
- 광고: sponsored_count=**0**, organic_avg=**10375**(모수 4), ad 쪽 전부 NULL. interval=21/3=**7.0**

**자식 뷰**: category_stats — dummy_a {A:2, B:1}, dummy_b {B:1}, dummy_v {B:6}, dummy_flat {B:4} → 총 5행.
content_series — 3+1+6+4 = **총 14행**.

---

### Task 1: base 뷰 프로필 확장 (follows_count·posts_count·biography)

스펙 §2-6의 "기존 파일 무수정" 예외 — 프로필 payload 키 3개는 raw 접촉이라 base 뷰에만 둘 수 있다(ARCHITECTURE §4-4).
`CREATE OR REPLACE VIEW`는 기존 컬럼 뒤 추가만 허용하므로 끝에 붙인다(§4-5 추가는 자유).

**Files:**
- Modify: `analytics/views/00_base.sql` (v_base_profile)
- Modify: `analytics/test/00_base.test.sql` (assert 추가)
- Modify: `docs/superpowers/specs/2026-07-13-c1-account-detail-design.md` (§2-6 한 줄 정정)

- [ ] **Step 1: 실패하는 assert 추가**

`analytics/test/00_base.test.sql`의 `END $$;` 직전에 추가:

```sql
  -- 프로필 확장 (C1): 시드 payload에 키 없음 → NULL (키 있는 케이스는 10번 테스트 픽스처가 검증)
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_a') IS NULL,
    'v_base_profile dummy_a follows_count not null (seed has no key)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_a') IS NULL,
    'v_base_profile dummy_a posts_count not null (seed has no key)';
  ASSERT (SELECT biography FROM analytics.v_base_profile WHERE username = 'dummy_a') IS NULL,
    'v_base_profile dummy_a biography not null (seed has no key)';
```

- [ ] **Step 2: 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/00_base.test.sql && cd ..`
(run.sh는 `cd analytics` 기준 상대경로 인자만 받는다 — 내부에서 `cat "$t"`를 그대로 쓰기 때문)
Expected: FAIL — `column "follows_count" does not exist`

- [ ] **Step 3: v_base_profile에 컬럼 3개 추가**

`analytics/views/00_base.sql`의 v_base_profile을 다음으로 교체 (기존 컬럼 뒤에만 추가):

```sql
-- 계정별 최신 프로필
-- 신규 컬럼은 기존 컬럼 뒤에 추가 (CREATE OR REPLACE VIEW는 기존 위치의 컬럼명 변경을 허용하지 않음).
CREATE OR REPLACE VIEW analytics.v_base_profile AS
SELECT DISTINCT ON (account_id)
  account_id,
  username,
  followers,
  captured_at,
  payload->>'fullName'      AS display_name,
  payload->>'profilePicUrl' AS profile_image_url,
  (payload->>'followsCount')::bigint AS follows_count,
  (payload->>'postsCount')::bigint   AS posts_count,
  payload->>'biography'              AS biography
FROM raw_profile
ORDER BY account_id, captured_at DESC, id DESC;
```

- [ ] **Step 4: 전체 하니스 통과 확인 (기존 00~03 회귀 포함)**

Run: `cd analytics && ./test/run.sh && cd ..`
Expected: `ALL GREEN`

- [ ] **Step 5: 스펙 §2-6 정정**

`docs/superpowers/specs/2026-07-13-c1-account-detail-design.md` §2-6을 다음으로 교체:

```markdown
6. **기존 파일은 base 뷰 additive 확장만.** `followers`는 10번 파일 안의 밑판 뷰(`v_account_recent` =
   `v_recent_content` + `v_base_profile` 조인)에서 파생. 프로필 payload 키 3개(`followsCount`·
   `postsCount`·`biography`)는 raw 접촉이라 `00_base.sql` v_base_profile 끝에 컬럼 추가(§4-5 추가는
   자유 — raw 접촉은 base 뷰만 원칙 준수). 그 외 공유 파일 접점은 `MirrorConfig` 3줄 append뿐.
```

- [ ] **Step 6: Commit**

```bash
git add analytics/views/00_base.sql analytics/test/00_base.test.sql docs/superpowers/specs/2026-07-13-c1-account-detail-design.md
git commit -m "feat(analytics): v_base_profile 프로필 확장 3컬럼 — C1 헤더 재료 (additive)"
```

---

### Task 2: 뷰 4종 — `10_account_detail.sql` + SQL 하니스 테스트

**Files:**
- Create: `analytics/test/10_account_detail.test.sql`
- Create: `analytics/views/10_account_detail.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/10_account_detail.test.sql` 생성 (기대값 근거는 플랜 상단 표):

```sql
-- 그룹 10 기대값. 산식 정본: celfit-front parse_accounts_recent.py (스펙 §3).
-- 결정성: 이 그룹이 읽는 설정 키를 기본값으로 강제.
DELETE FROM app_setting WHERE key IN ('analytics.recent-window', 'analytics.trend-threshold');

-- ===== 추가 픽스처: metric 'views'·트렌드 down/flat·광고 비교 검증용 (계정 9005~9006) =====
INSERT INTO account(id, username) VALUES (9005,'dummy_v'), (9006,'dummy_flat');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9005,9990,'{"username":"dummy_v","followersCount":10000,"followsCount":300,"postsCount":80,"biography":"글로우 크리에이터"}'::jsonb, timestamptz '2026-06-06 00:00:00+09'),
 (9006,9990,'{"username":"dummy_flat","followersCount":8000}'::jsonb, timestamptz '2026-06-06 00:00:00+09');

INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9110,'dummy_v1','REELS','dummy_v',    timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9111,'dummy_v2','REELS','dummy_v',    timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9112,'dummy_v3','REELS','dummy_v',    timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', true),
 (9113,'dummy_v4','REELS','dummy_v',    timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false),
 (9114,'dummy_v5','REELS','dummy_v',    timestamptz '2026-05-29 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-29 00:00:00+09','glow_sub','B', true),
 (9115,'dummy_v6','REELS','dummy_v',    timestamptz '2026-06-05 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-06-05 00:00:00+09','glow_sub','B', false),
 (9120,'dummy_t1','REELS','dummy_flat', timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9121,'dummy_t2','REELS','dummy_flat', timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9122,'dummy_t3','REELS','dummy_flat', timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', false),
 (9123,'dummy_t4','REELS','dummy_flat', timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false);

INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9110,9990,'{"shortCode":"dummy_v1","type":"Video","likesCount":400,"commentsCount":40,"videoPlayCount":20000}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9111,9990,'{"shortCode":"dummy_v2","type":"Video","likesCount":300,"commentsCount":30,"videoPlayCount":18000}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9112,9990,'{"shortCode":"dummy_v3","type":"Video","likesCount":500,"commentsCount":50,"videoPlayCount":22000}'::jsonb, timestamptz '2026-05-16 09:00:00+09'),
 (9113,9990,'{"shortCode":"dummy_v4","type":"Video","likesCount":200,"commentsCount":20,"videoPlayCount":10000}'::jsonb, timestamptz '2026-05-23 09:00:00+09'),
 (9114,9990,'{"shortCode":"dummy_v5","type":"Video","likesCount":150,"commentsCount":15,"videoPlayCount":8000}'::jsonb,  timestamptz '2026-05-30 09:00:00+09'),
 (9115,9990,'{"shortCode":"dummy_v6","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":6000}'::jsonb,  timestamptz '2026-06-06 09:00:00+09'),
 (9120,9990,'{"shortCode":"dummy_t1","type":"Video","likesCount":200,"commentsCount":20,"videoPlayCount":10000}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9121,9990,'{"shortCode":"dummy_t2","type":"Video","likesCount":210,"commentsCount":21,"videoPlayCount":11000}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9122,9990,'{"shortCode":"dummy_t3","type":"Video","likesCount":190,"commentsCount":19,"videoPlayCount":9000}'::jsonb,  timestamptz '2026-05-16 09:00:00+09'),
 (9123,9990,'{"shortCode":"dummy_t4","type":"Video","likesCount":205,"commentsCount":25,"videoPlayCount":11500}'::jsonb, timestamptz '2026-05-23 09:00:00+09');

-- ===== v_account_summaries =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_summaries WHERE handle LIKE 'dummy_%') = 4,
    'summaries rows != 4';
END $$;

-- dummy_a: metric 'likes' 폴백 + 피드 NULL 함정 + 광고 비교 + 트렌드 up
DO $$
BEGIN
  ASSERT (SELECT followers          FROM analytics.v_account_summaries WHERE handle='dummy_a') = 5500,  'a followers != 5500';
  ASSERT (SELECT follows_count      FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a follows_count not null';
  ASSERT (SELECT posts_count        FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a posts_count not null';
  ASSERT (SELECT biography          FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a biography not null';
  ASSERT (SELECT analyzed_count     FROM analytics.v_account_summaries WHERE handle='dummy_a') = 3,     'a analyzed != 3';
  ASSERT (SELECT views_count        FROM analytics.v_account_summaries WHERE handle='dummy_a') = 2,     'a views_count != 2';
  ASSERT (SELECT metric             FROM analytics.v_account_summaries WHERE handle='dummy_a') = 'likes', 'a metric != likes (2 < max(3, 3/2))';
  ASSERT (SELECT avg_views          FROM analytics.v_account_summaries WHERE handle='dummy_a') = 9000,  'a avg_views != 9000';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1.6,   'a vpf != 1.6';
  ASSERT (SELECT avg_er_pct         FROM analytics.v_account_summaries WHERE handle='dummy_a') = 18.2,  'a avg_er_pct != 18.2';
  ASSERT (SELECT avg_likes          FROM analytics.v_account_summaries WHERE handle='dummy_a') = 940,   'a avg_likes != 940';
  ASSERT (SELECT avg_comments       FROM analytics.v_account_summaries WHERE handle='dummy_a') = 61,    'a avg_comments != 61';
  ASSERT (SELECT trend_direction    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 'up',  'a trend != up';
  ASSERT (SELECT trend_change_pct   FROM analytics.v_account_summaries WHERE handle='dummy_a') = 121,   'a trend_pct != 121';
  ASSERT (SELECT trend_older_avg    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 520,   'a older != 520';
  ASSERT (SELECT trend_newer_avg    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1150,  'a newer != 1150';
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1,     'a sponsored != 1';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_a') = 410,   'a organic_avg != 410';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_a') = 2000,  'a ad_avg != 2000';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_a') = -388,  'a drop != -388';
  ASSERT (SELECT comparison_organic_count FROM analytics.v_account_summaries WHERE handle='dummy_a') = 2, 'a cmp_og != 2';
  ASSERT (SELECT comparison_ad_count      FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1, 'a cmp_ad != 1';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_a') = timestamptz '2026-06-03 09:00:00+09', 'a last_ad wrong';
  ASSERT (SELECT last_posted_at     FROM analytics.v_account_summaries WHERE handle='dummy_a') = timestamptz '2026-06-03 09:00:00+09', 'a last_posted wrong';
  ASSERT (SELECT avg_interval_days  FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1.0,   'a interval != 1.0';
END $$;

-- dummy_b: 표본 1 — 트렌드 flat(앞 절반 없음)·interval NULL·광고 없음
DO $$
BEGIN
  ASSERT (SELECT metric             FROM analytics.v_account_summaries WHERE handle='dummy_b') = 'likes', 'b metric != likes (1 < 3)';
  ASSERT (SELECT avg_views          FROM analytics.v_account_summaries WHERE handle='dummy_b') = 40000,  'b avg_views != 40000';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_b') = 2.0,    'b vpf != 2.0';
  ASSERT (SELECT avg_er_pct         FROM analytics.v_account_summaries WHERE handle='dummy_b') = 5.4,    'b avg_er_pct != 5.4';
  ASSERT (SELECT trend_direction    FROM analytics.v_account_summaries WHERE handle='dummy_b') = 'flat', 'b trend != flat';
  ASSERT (SELECT trend_change_pct   FROM analytics.v_account_summaries WHERE handle='dummy_b') = 0,      'b trend_pct != 0';
  ASSERT (SELECT trend_older_avg    FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b older not null';
  ASSERT (SELECT trend_newer_avg    FROM analytics.v_account_summaries WHERE handle='dummy_b') = 1000,   'b newer != 1000';
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_b') = 0,      'b sponsored != 0';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_b') = 1000,   'b organic_avg != 1000';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b ad_avg not null';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b drop not null';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b last_ad not null';
  ASSERT (SELECT avg_interval_days  FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b interval not null (n=1)';
END $$;

-- dummy_v: metric 'views' + 트렌드 down + 광고 양쪽 비교 + 프로필 확장
DO $$
BEGIN
  ASSERT (SELECT follows_count      FROM analytics.v_account_summaries WHERE handle='dummy_v') = 300,    'v follows_count != 300';
  ASSERT (SELECT posts_count        FROM analytics.v_account_summaries WHERE handle='dummy_v') = 80,     'v posts_count != 80';
  ASSERT (SELECT biography          FROM analytics.v_account_summaries WHERE handle='dummy_v') = '글로우 크리에이터', 'v biography wrong';
  ASSERT (SELECT analyzed_count     FROM analytics.v_account_summaries WHERE handle='dummy_v') = 6,      'v analyzed != 6';
  ASSERT (SELECT metric             FROM analytics.v_account_summaries WHERE handle='dummy_v') = 'views', 'v metric != views';
  ASSERT (SELECT avg_views          FROM analytics.v_account_summaries WHERE handle='dummy_v') = 14000,  'v avg_views != 14000';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_v') = 1.4,    'v vpf != 1.4';
  ASSERT (SELECT avg_er_pct         FROM analytics.v_account_summaries WHERE handle='dummy_v') = 3.0,    'v avg_er_pct != 3.0';
  ASSERT (SELECT avg_likes          FROM analytics.v_account_summaries WHERE handle='dummy_v') = 275,    'v avg_likes != 275';
  ASSERT (SELECT avg_comments       FROM analytics.v_account_summaries WHERE handle='dummy_v') = 28,     'v avg_comments != 28';
  ASSERT (SELECT trend_direction    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 'down', 'v trend != down';
  ASSERT (SELECT trend_change_pct   FROM analytics.v_account_summaries WHERE handle='dummy_v') = -60,    'v trend_pct != -60';
  ASSERT (SELECT trend_older_avg    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 20000,  'v older != 20000';
  ASSERT (SELECT trend_newer_avg    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 8000,   'v newer != 8000';
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 2,      'v sponsored != 2';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_v') = 13500,  'v organic_avg != 13500';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_v') = 15000,  'v ad_avg != 15000';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_v') = -11,    'v drop != -11';
  ASSERT (SELECT comparison_organic_count FROM analytics.v_account_summaries WHERE handle='dummy_v') = 4, 'v cmp_og != 4';
  ASSERT (SELECT comparison_ad_count      FROM analytics.v_account_summaries WHERE handle='dummy_v') = 2, 'v cmp_ad != 2';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_v') = timestamptz '2026-05-29 09:00:00+09', 'v last_ad wrong';
  ASSERT (SELECT avg_interval_days  FROM analytics.v_account_summaries WHERE handle='dummy_v') = 7.0,    'v interval != 7.0';
END $$;

-- dummy_flat: ±15% 이내 → flat이지만 change_pct는 원값(-2) 유지
DO $$
BEGIN
  ASSERT (SELECT metric           FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 'views', 'flat metric != views';
  ASSERT (SELECT avg_views        FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 10375,   'flat avg_views != 10375';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 1.3,   'flat vpf != 1.3';
  ASSERT (SELECT avg_er_pct       FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 2.8,     'flat avg_er_pct != 2.8';
  ASSERT (SELECT avg_likes        FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 201,     'flat avg_likes != 201';
  ASSERT (SELECT avg_comments     FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 21,      'flat avg_comments != 21';
  ASSERT (SELECT trend_direction  FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 'flat',  'flat trend != flat';
  ASSERT (SELECT trend_change_pct FROM analytics.v_account_summaries WHERE handle='dummy_flat') = -2,      'flat trend_pct != -2';
  ASSERT (SELECT sponsored_count  FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 0,       'flat sponsored != 0';
  ASSERT (SELECT organic_avg      FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 10375,   'flat organic_avg != 10375';
  ASSERT (SELECT ad_avg           FROM analytics.v_account_summaries WHERE handle='dummy_flat') IS NULL,   'flat ad_avg not null';
  ASSERT (SELECT avg_interval_days FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 7.0,    'flat interval != 7.0';
END $$;

-- ===== v_account_category_stats =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_category_stats WHERE account_handle LIKE 'dummy_%') = 5,
    'category rows != 5';
  ASSERT (SELECT content_count FROM analytics.v_account_category_stats WHERE account_handle='dummy_a' AND main_group='A') = 2,
    'a/A count != 2';
  ASSERT (SELECT content_count FROM analytics.v_account_category_stats WHERE account_handle='dummy_a' AND main_group='B') = 1,
    'a/B count != 1';
  ASSERT (SELECT content_count FROM analytics.v_account_category_stats WHERE account_handle='dummy_v' AND main_group='B') = 6,
    'v/B count != 6';
END $$;

-- ===== v_account_content_series =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 14,
    'series rows != 14';
  -- 피드 views NULL 보존 + 광고 플래그 + content_type 소문자
  ASSERT (SELECT views     FROM analytics.v_account_content_series WHERE short_code='dummy_f1') IS NULL, 'f1 views not null';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code='dummy_f1') = true,  'f1 sponsored != true';
  ASSERT (SELECT content_type FROM analytics.v_account_content_series WHERE short_code='dummy_f1') = 'feed', 'f1 type != feed';
  ASSERT (SELECT views     FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 7000,  'r2 views != 7000';
  ASSERT (SELECT likes     FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 300,   'r2 likes != 300';
  ASSERT (SELECT comments  FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 30,    'r2 comments != 30';
  ASSERT (SELECT content_type FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 'reels', 'r2 type != reels';
  ASSERT (SELECT posted_at FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = timestamptz '2026-06-02 09:00:00+09', 'r2 posted_at wrong';
END $$;
```

- [ ] **Step 2: 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/10_account_detail.test.sql && cd ..`
Expected: FAIL — `relation "analytics.v_account_summaries" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/10_account_detail.sql` 생성:

```sql
-- 그룹 10: 인플루언서 상세 (비LLM) — celfit-front AccountReport의 결정 지표.
-- 산식 정본: celfit-front scripts/real-data-pipeline/parse_accounts_recent.py
-- (스펙: docs/superpowers/specs/2026-07-13-c1-account-detail-design.md §3).
-- 서빙 뷰 3종은 미러 1:1 — 컬럼 이름·순서 = V10 DDL = contract record.

-- 밑판 (미러 안 함): 윈도우 행 + 팔로워. 프로필 없는 계정은 서빙에서 제외 (INNER JOIN 의도 — 프론트가 팔로워를 요구).
CREATE OR REPLACE VIEW analytics.v_account_recent AS
SELECT r.*, p.followers AS profile_followers
FROM analytics.v_recent_content r
JOIN analytics.v_base_profile p ON p.username = r.owner_username;

-- 계정 1행 요약.
-- 기준 지표(metric) 폴백: 조회수 있는 게시물이 max(3, n/2) 미만이면 좋아요 기준 (프론트 상수 — 키로 빼지 않음).
-- 트렌드/광고 비교는 metric 값 > 0인 게시물만 (피드 views NULL은 자연 제외).
CREATE OR REPLACE VIEW analytics.v_account_summaries AS
WITH cfg AS (
  SELECT COALESCE((SELECT value::numeric FROM app_setting
                   WHERE key = 'analytics.trend-threshold'), 0.15) AS trend_threshold
),
win AS (
  SELECT owner_username, content_id, uploaded_at, likes, comments_count, views, ad_marked,
         profile_followers AS followers,
         row_number() OVER (PARTITION BY owner_username ORDER BY uploaded_at ASC, content_id ASC) AS seq,
         count(*)     OVER (PARTITION BY owner_username)                                          AS n
  FROM analytics.v_account_recent
),
base AS (
  SELECT owner_username,
         max(followers)                                     AS followers,
         count(*)                                           AS analyzed_count,
         count(*) FILTER (WHERE views > 0)                  AS views_count,
         round(avg(views) FILTER (WHERE views > 0))::bigint AS avg_views,
         round(avg((likes + comments_count)::numeric / NULLIF(followers, 0)) * 100, 1) AS avg_er_pct,
         round(avg(likes))::bigint                          AS avg_likes,
         round(avg(comments_count))::bigint                 AS avg_comments,
         min(uploaded_at)                                   AS first_posted_at,
         max(uploaded_at)                                   AS last_posted_at
  FROM win
  GROUP BY owner_username
),
metric AS (
  SELECT owner_username,
         CASE WHEN views_count >= GREATEST(3, analyzed_count / 2) THEN 'views' ELSE 'likes' END AS metric
  FROM base
),
mrow AS (
  SELECT w.*, CASE WHEN m.metric = 'views' THEN w.views ELSE w.likes END AS mval
  FROM win w
  JOIN metric m USING (owner_username)
),
-- 올린 순 앞 절반(floor(n/2)) vs 뒤 절반(나머지 — 홀수 중앙은 뒤에 포함). 절반 판정 후 metric>0만 평균.
trend AS (
  SELECT owner_username,
         avg(mval) FILTER (WHERE seq <= n / 2 AND mval > 0) AS older_raw,
         avg(mval) FILTER (WHERE seq >  n / 2 AND mval > 0) AS newer_raw
  FROM mrow
  GROUP BY owner_username
),
ads AS (
  SELECT owner_username,
         count(*) FILTER (WHERE ad_marked)                   AS sponsored_count,
         avg(mval)  FILTER (WHERE NOT ad_marked AND mval > 0) AS organic_raw,
         avg(mval)  FILTER (WHERE ad_marked AND mval > 0)     AS ad_raw,
         count(*)   FILTER (WHERE NOT ad_marked AND mval > 0) AS comparison_organic_count,
         count(*)   FILTER (WHERE ad_marked AND mval > 0)     AS comparison_ad_count,
         max(uploaded_at) FILTER (WHERE ad_marked)            AS last_ad_posted_at
  FROM mrow
  GROUP BY owner_username
)
SELECT
  b.owner_username AS handle,
  b.followers,
  p.follows_count,
  p.posts_count,
  p.biography,
  b.analyzed_count,
  b.views_count,
  m.metric,
  b.avg_views,
  round(b.avg_views::numeric / NULLIF(b.followers, 0), 1) AS views_per_follower,
  b.avg_er_pct,
  b.avg_likes,
  b.avg_comments,
  CASE
    WHEN t.older_raw > 0 AND t.newer_raw > 0 THEN
      CASE WHEN t.newer_raw / t.older_raw - 1 >  cfg.trend_threshold THEN 'up'
           WHEN t.newer_raw / t.older_raw - 1 < -cfg.trend_threshold THEN 'down'
           ELSE 'flat' END
    ELSE 'flat'
  END AS trend_direction,
  CASE WHEN t.older_raw > 0 AND t.newer_raw > 0
       THEN round((t.newer_raw / t.older_raw - 1) * 100)::int
       ELSE 0 END AS trend_change_pct,
  round(t.older_raw)::bigint AS trend_older_avg,
  round(t.newer_raw)::bigint AS trend_newer_avg,
  a.sponsored_count,
  round(a.organic_raw)::bigint AS organic_avg,
  round(a.ad_raw)::bigint      AS ad_avg,
  CASE WHEN a.organic_raw > 0 AND a.ad_raw IS NOT NULL
       THEN round((1 - a.ad_raw / a.organic_raw) * 100)::int
  END AS ad_drop_pct,
  a.comparison_organic_count,
  a.comparison_ad_count,
  a.last_ad_posted_at,
  b.last_posted_at,
  -- 스팬/(n-1): 연속 간격 평균의 절사 없는 정의 (스펙 §3 — 프론트 절사 평균과 소수점만 다를 수 있음)
  CASE WHEN b.analyzed_count > 1
       THEN round((EXTRACT(EPOCH FROM (b.last_posted_at - b.first_posted_at)) / 86400.0
                   / (b.analyzed_count - 1))::numeric, 1)
  END AS avg_interval_days
FROM base b
JOIN metric m USING (owner_username)
JOIN trend  t USING (owner_username)
JOIN ads    a USING (owner_username)
JOIN analytics.v_base_profile p ON p.username = b.owner_username
CROSS JOIN cfg;

-- 카테고리 믹스 (라벨 = crawler main_group 어휘 — 스펙 §3. 정렬은 was 몫)
CREATE OR REPLACE VIEW analytics.v_account_category_stats AS
SELECT owner_username AS account_handle,
       main_group,
       count(*) AS content_count
FROM analytics.v_account_recent
WHERE main_group IS NOT NULL
GROUP BY owner_username, main_group;

-- 게시물 시계열 (차트 막대·광고 스트립·최근 콘텐츠 탭 재료. 올린 순 정렬은 was 몫)
-- views NULL(피드) 보존 — "0 = 미공개"는 프론트 표현 규약이라 여기서 변환하지 않는다.
CREATE OR REPLACE VIEW analytics.v_account_content_series AS
SELECT short_code,
       owner_username AS account_handle,
       uploaded_at AS posted_at,
       lower(content_type) AS content_type,
       views,
       likes,
       comments_count AS comments,
       ad_marked AS sponsored
FROM analytics.v_account_recent;
```

- [ ] **Step 4: 실행 — 통과 확인 (전체 회귀 포함)**

Run: `cd analytics && ./test/run.sh && cd ..`
Expected: `ALL GREEN` (00~03 + 10 전부)

- [ ] **Step 5: Commit**

```bash
git add analytics/views/10_account_detail.sql analytics/test/10_account_detail.test.sql
git commit -m "feat(analytics): 인플루언서 상세 뷰 4종 — AccountReport 결정 지표 (metric 폴백·트렌드·광고 비교)"
```

---

### Task 3: Flyway V10 DDL + contract record 3종 + FlywaySchemaTest

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountSummary.java`
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountCategoryStat.java`
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountContentPoint.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V10__account_detail_tables.sql`
- Modify: `analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java`

- [ ] **Step 1: 실패하는 테스트 추가**

`FlywaySchemaTest.java`의 기존 테스트 메서드 뒤에 추가 (import에 `AccountSummary`, `AccountCategoryStat`, `AccountContentPoint` 추가):

```java
	@Test
	void account_summaries_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_summaries", AccountSummary.class);
	}

	@Test
	void account_category_stats_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_category_stats", AccountCategoryStat.class);
	}

	@Test
	void account_content_series_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_content_series", AccountContentPoint.class);
	}
```

- [ ] **Step 2: record 3종 작성** (테스트가 record 없이는 컴파일조차 안 되므로 record 먼저 — 컴포넌트 순서 = 뷰 컬럼 순서)

`contract-analysis/src/main/java/com/celfit/contract/analysis/AccountSummary.java`:

```java
package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 인플루언서 상세 계정 요약 1행 (미러: analytics.v_account_summaries → account_summaries).
 * celfit-front AccountReport의 결정(비LLM) 지표 — 산식은 스펙 2026-07-13-c1-account-detail-design.md §3.
 * metric: 'views'|'likes' — 조회수 데이터 부족 계정의 기준 지표 폴백. 트렌드·광고 비교가 이 축을 따른다.
 * avgErPct: 계정 평균 ER(팔로워 분모, %) — 게시물 ER(조회수 분모)과 정의가 다르다.
 */
public record AccountSummary(String handle, Long followers, Long followsCount, Long postsCount,
		String biography, Long analyzedCount, Long viewsCount, String metric, Long avgViews,
		BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
		String trendDirection, Integer trendChangePct, Long trendOlderAvg, Long trendNewerAvg,
		Long sponsoredCount, Long organicAvg, Long adAvg, Integer adDropPct,
		Long comparisonOrganicCount, Long comparisonAdCount, OffsetDateTime lastAdPostedAt,
		OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
}
```

`contract-analysis/src/main/java/com/celfit/contract/analysis/AccountCategoryStat.java`:

```java
package com.celfit.contract.analysis;

/**
 * 계정 카테고리 믹스 1행 (미러: analytics.v_account_category_stats → account_category_stats).
 * mainGroup은 crawler 분류 어휘 — was는 전달만.
 */
public record AccountCategoryStat(String accountHandle, String mainGroup, Long contentCount) {
}
```

`contract-analysis/src/main/java/com/celfit/contract/analysis/AccountContentPoint.java`:

```java
package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 윈도우 내 게시물 시계열 1행 (미러: analytics.v_account_content_series → account_content_series).
 * 차트 막대·광고 스트립·최근 콘텐츠 탭 재료. views NULL = 피드(미공개) — 표현 규약은 was가 정한다.
 */
public record AccountContentPoint(String shortCode, String accountHandle, OffsetDateTime postedAt,
		String contentType, Long views, Long likes, Long comments, Boolean sponsored) {
}
```

- [ ] **Step 3: 실행 — DDL이 없어 실패 확인**

Run: `./gradlew :analytics:test --tests '*FlywaySchemaTest*'`
Expected: FAIL — 신규 3개 테스트가 `account_summaries` 등 테이블 부재로 실패 (기존 3개는 통과)

- [ ] **Step 4: Flyway DDL 작성**

`analytics/src/main/resources/db/migration/analysis/V10__account_detail_tables.sql` 생성
(V2~V9는 다른 트랙 몫으로 비워둔 번호대 예약 — 스펙 §2-4):

```sql
-- 인플루언서 상세 미러 3종 (ARCHITECTURE.md §4-3: 저장은 Flyway DDL 소유).
-- 컬럼 이름·순서 = 서빙 뷰(10_account_detail.sql) = contract record. 자연키 PK.
-- 분석 층 테이블과의 FK는 걸지 않는다 (TRUNCATE와 충돌 — 논리 참조만).
CREATE TABLE account_summaries (
    handle                   text PRIMARY KEY,
    followers                bigint,
    follows_count            bigint,
    posts_count              bigint,
    biography                text,
    analyzed_count           bigint,
    views_count              bigint,
    metric                   text,
    avg_views                bigint,
    views_per_follower       numeric,
    avg_er_pct               numeric,
    avg_likes                bigint,
    avg_comments             bigint,
    trend_direction          text,
    trend_change_pct         integer,
    trend_older_avg          bigint,
    trend_newer_avg          bigint,
    sponsored_count          bigint,
    organic_avg              bigint,
    ad_avg                   bigint,
    ad_drop_pct              integer,
    comparison_organic_count bigint,
    comparison_ad_count      bigint,
    last_ad_posted_at        timestamptz,
    last_posted_at           timestamptz,
    avg_interval_days        numeric
);

CREATE TABLE account_category_stats (
    account_handle text NOT NULL,
    main_group     text NOT NULL,
    content_count  bigint,
    PRIMARY KEY (account_handle, main_group)
);

CREATE TABLE account_content_series (
    short_code     text PRIMARY KEY,
    account_handle text NOT NULL,
    posted_at      timestamptz,
    content_type   text,
    views          bigint,
    likes          bigint,
    comments       bigint,
    sponsored      boolean
);
CREATE INDEX idx_account_content_series_handle ON account_content_series (account_handle);
-- account_category_stats는 PK 선두 컬럼이 account_handle이라 별도 인덱스 불필요.
```

- [ ] **Step 5: 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests '*FlywaySchemaTest*'`
Expected: PASS (6개 테스트)

- [ ] **Step 6: Commit**

```bash
git add contract-analysis/src/main/java/com/celfit/contract/analysis/AccountSummary.java \
        contract-analysis/src/main/java/com/celfit/contract/analysis/AccountCategoryStat.java \
        contract-analysis/src/main/java/com/celfit/contract/analysis/AccountContentPoint.java \
        analytics/src/main/resources/db/migration/analysis/V10__account_detail_tables.sql \
        analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java
git commit -m "feat(analytics): 인플루언서 상세 계약 record 3종 + V10 미러 테이블 DDL"
```

---

### Task 4: MirrorConfig 등록 + 전체 검증

뷰↔record 정합은 `MirrorJob`의 런타임 컬럼 대조 가드가, DDL↔record는 FlywaySchemaTest가 잡는다 —
등록부는 스펙 3건 append가 전부다.

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java`

- [ ] **Step 1: 미러 스펙 3건 append**

`MirrorConfig.java`의 import에 3종 추가:

```java
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
```

`mirrorRegistry()`를 다음으로 교체 (기존 3건 뒤에 append):

```java
	/** 미러 대상 등록부 — 서빙 뷰 3종(B1) + 인플루언서 상세 3종(C1). 컬럼 계약은 각 record의 Javadoc과 V1·V10 DDL 참조. */
	@Bean
	public MirrorRegistry mirrorRegistry() {
		return new MirrorRegistry(List.of(
				new MirrorSpec<>("analytics.v_accounts", "accounts", Account.class),
				new MirrorSpec<>("analytics.v_contents", "contents", Content.class),
				new MirrorSpec<>("analytics.v_content_comments", "content_comments", ContentComment.class),
				new MirrorSpec<>("analytics.v_account_summaries", "account_summaries", AccountSummary.class),
				new MirrorSpec<>("analytics.v_account_category_stats", "account_category_stats", AccountCategoryStat.class),
				new MirrorSpec<>("analytics.v_account_content_series", "account_content_series", AccountContentPoint.class)));
	}
```

- [ ] **Step 2: 모듈 전체 테스트**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL (MirrorJobTest·FlywaySchemaTest 포함 전부 PASS)

- [ ] **Step 3: SQL 하니스 전체 재확인**

Run: `cd analytics && ./test/run.sh && cd ..`
Expected: `ALL GREEN`

- [ ] **Step 4: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java
git commit -m "feat(analytics): 미러 등록부에 인플루언서 상세 3종 추가 — C1 서빙 데이터 개통"
```

---

### Task 5: 실 DB 미러 실행 확인 + 문서 갱신

- [ ] **Step 1: 실 DB에 뷰 적용 + 미러 실행**

```bash
docker start crawler-postgres-1
cd analytics && ./test/run.sh && cd ..   # run.sh가 뷰를 실 crawler DB에 적용(멱등)
./gradlew :analytics:bootRun
```

Expected: Flyway가 analysis DB에 V10 적용 → 미러 6종 실행 로그(행 수 출력) → 정상 종료.
확인 쿼리:

```bash
docker exec -i crawler-postgres-1 psql -U crawler -d analysis -c \
  "SELECT (SELECT count(*) FROM account_summaries) AS summaries,
          (SELECT count(*) FROM account_category_stats) AS categories,
          (SELECT count(*) FROM account_content_series) AS series;"
```

Expected: 세 테이블 모두 0보다 큰 행 수 (실데이터 규모에 따라 다름).

- [ ] **Step 2: ARCHITECTURE.md 갱신**

- §5 작업 트랙 표의 C1 행을 ✅로, 내용을 실 계약 기준으로:

```markdown
| C1 | 인플루언서 비LLM 집계 | AccountReport 결정 지표 — 계정 요약·카테고리 믹스·게시물 시계열 3종 뷰 + 미러 | A | ✅ |
```

- §7 결정 기록 맨 위에 추가:

```markdown
| 2026-07-13 | C1은 **celfit-front 실계약(AccountReport) 기준**으로 구현 — v4 목업 지표(중앙값·히트율·변동성·구간포지션 등) 폐기. 계정 평균 ER은 **followers 분모**(`avg_er_pct`, 게시물 ER의 views 분모와 공존), 기준 지표 폴백 `metric`('views'\|'likes')은 데이터에 확정 | [specs/2026-07-13-c1-account-detail-design.md](../../specs/2026-07-13-c1-account-detail-design.md) |
```

- [ ] **Step 3: 플랜 아카이브 + Commit**

```bash
git mv docs/superpowers/plans/2026-07-13-c1-account-detail.md docs/superpowers/plans/archive/
git add ARCHITECTURE.md
git commit -m "docs: C1 완료 반영 — 인플루언서 상세 미러 3종 개통 + 계획 아카이브"
```

(아카이브 시 플랜 첫머리 상태 헤더를 `> 상태: ✅ 실행됨`으로 바꾼 뒤 이동.)

---

## Self-Review 결과

- **스펙 커버리지**: §1 범위(스탯·트렌드·시계열·믹스·광고·활동성) → Task 2, §4(DDL·record·등록부) → Task 3·4, §5 설정 키 → 뷰 cfg CTE + 테스트 DELETE, §6 검증 → Task 2(하니스)·3(FlywaySchemaTest)·5(실 DB). §2-6은 base 뷰 확장이 필요해 Task 1에서 스펙을 함께 정정.
- **플레이스홀더**: 없음 — 전 스텝 실코드·실명령·기대값 포함.
- **타입 일관성**: record 컴포넌트 순서 = 뷰 SELECT 순서 = DDL 순서 26·3·8열 대조 완료. `toSnakeCase` 함정(`avgErPct`→`avg_er_pct`) 확인. numeric→BigDecimal, `::int`→Integer, `::bigint`→Long, timestamptz→OffsetDateTime, boolean→Boolean.
- **기대값 재검산**: dummy_a ER 18.1939→18.2, drop −387.8→−388, dummy_v ER 3.025→3.0(0.5 미만 절사), flat pct −2.38→−2 등 반올림 경계 재확인.
