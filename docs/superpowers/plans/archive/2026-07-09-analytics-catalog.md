# 크롤링 데이터 분석 카탈로그 Implementation Plan

> 상태: ✅ 실행됨 — 산출물은 2026-07-12 초기화(태스크 A에서 재구축)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** crawler가 적재한 인스타 raw 데이터로 "무엇을 분석할지"를 재사용 가능한 SQL 뷰(그룹 1~7)로 정의하고, 실데이터 2건 + 더미데이터로 각 뷰를 검증한다.

**Architecture:** 운영 스키마(`public`)는 건드리지 않고 `analytics` 스키마에 읽기전용 뷰만 만든다. `00_base`가 "계정별 최신 프로필 / 콘텐츠별 최신 상세 / 참여율 계산 전 팩트 뷰"를 제공하고, `01~07`이 그 위에 지표를 얹는다. 테스트는 트랜잭션 안에서 더미데이터를 seed → 뷰 결과를 PL/pgSQL `ASSERT`로 검증 → `ROLLBACK`으로 실데이터를 원상복구한다.

**Tech Stack:** PostgreSQL 17 (Docker 컨테이너 `crawler-postgres-1`), psql, bash. 애플리케이션 코드 변경 없음(Flyway 마이그레이션 아님 — 독립 분석 스크립트).

**Spec:** [2026-07-09-analytics-catalog-design.md](../specs/2026-07-09-analytics-catalog-design.md)

---

## 사전 지식 (구현자용)

- DB 접속은 반드시 컨테이너 경유. 호스트에 psql이 없다:
  `docker exec -i crawler-postgres-1 psql -U crawler -d crawler`
- 뷰가 참조하는 실제 컬럼:
  - `content(id, short_code, content_type, owner_username, uploaded_at, category_id, main_group, subcategory, discovery_keyword, status, ad_marked, first_seen_at)`
  - `raw_post_detail(content_id, crawl_run_id, payload, captured_at)` + generated: `likes, comments_count, video_play_count`
  - `raw_profile(account_id, crawl_run_id, payload, captured_at)` + generated: `username, followers`
  - `raw_comment(content_id, crawl_run_id, payload, captured_at)` + generated: `writer, text, written_at`
  - `account(id, username)`
- `payload`(jsonb) 키: detail = `videoViewCount, videoDuration, type, productType, hashtags[], mentions[], childPosts[]`; profile = `followsCount, postsCount, verified, isBusinessAccount, businessCategoryName`; comment = `repliesCount`.
- 테스트 실행 규약: `analytics/test/run.sh` 가 (1) `views/*.sql`를 순서대로 적용하고 (2) 각 `test/*.test.sql`을 `BEGIN; <dummy> <asserts> ROLLBACK;` 로 감싸 실행한다. `ASSERT` 실패 시 psql이 exit 3을 내고 `set -e`가 잡는다.
- **TDD에서 "실패 확인"**: 뷰 파일을 만들기 전에 해당 test를 돌리면, 없는 뷰를 참조해 `relation "analytics.v_..." does not exist` 로 실패한다. 이게 red 단계다.

## 파일 구조

```
crawler/analytics/
  README.md                       -- Task 10
  test/run.sh                     -- Task 1 (테스트 러너, 뷰 적용 + 테스트 실행)
  seed/dummy.sql                  -- Task 1 (결정적 더미데이터 + 실데이터 격리)
  views/00_base.sql               -- Task 2
  views/01_content_performance.sql-- Task 3
  views/02_category_performance.sql-- Task 4
  views/03_creators.sql           -- Task 5
  views/04_content_type.sql       -- Task 6
  views/05_timing.sql             -- Task 7
  views/06_hashtags_comments.sql  -- Task 8
  views/07_ads.sql                -- Task 9
  test/00_smoke.test.sql          -- Task 1
  test/01_content_performance.test.sql -- Task 3
  test/02_category_performance.test.sql-- Task 4
  test/03_creators.test.sql       -- Task 5
  test/04_content_type.test.sql   -- Task 6
  test/05_timing.test.sql         -- Task 7
  test/06_hashtags_comments.test.sql -- Task 8
  test/07_ads.test.sql            -- Task 9
```

## 더미데이터 명세 (Task 1에서 확정, 이후 모든 assert의 기준)

5개 콘텐츠(모두 `category_id=999`), 4개 계정. 참여율 = (likes+comments)/followers.

| short_code | owner | media | followers | likes | comments | views | ER | main_group | keyword | ad |
|---|---|---|---|---|---|---|---|---|---|---|
| dummy_c1 | dummy_micro | Video | 5000 | 500 | 50 | 10000 | 0.1100 | A | makeup | f |
| dummy_c2 | dummy_mid | Image | 50000 | 2000 | 100 | (none) | 0.0420 | A | makeup | t |
| dummy_c3 | dummy_macro | Video | 500000 | 20000 | 500 | 400000 | 0.0410 | B | glow | f |
| dummy_c4 | dummy_over | Video | 8000 | 1600 | 200 | 30000 | 0.2250 | B | glow | f |
| dummy_c5 | dummy_micro | Video | 5000 | 300 | 30 | 8000 | 0.0660 | B | kbeauty | f |

파생 기대값:
- main_group B 평균 ER = (0.041+0.225+0.066)/3 = **0.1107**, 콘텐츠 3건
- creator dummy_micro(c1,c5) 평균 ER = (0.11+0.066)/2 = **0.088**, 2건
- 팔로워 tier: 5000→micro, 50000→mid, 500000→macro
- micro tier ER median = median(0.088, 0.225) = 0.1565 → dummy_over(0.225) **overperforms=true**
- content_format reel 4건(c1,c3,c4,c5) / feed 1건(c2)
- 업로드 KST hour=9 → c1(09:00),c3(09:00),c5(09:30) = 3건
- hashtag 'makeup' 등장 콘텐츠 = c1,c3,c4 = 3건 (c1,c4 makeup 포함; c3 makeup 포함)
- dummy_c1 댓글 3건, 고유 작성자 2명, 대댓글 합 3, reply_ratio 1.0
- ad_marked=true 콘텐츠 1건(c2)

댓글(dummy_c1, content_id=9101): writer `dummy_fan1`(repliesCount 1), `dummy_fan2`(0), `dummy_fan1`(2).
해시태그: c1=`["makeup","kbeauty"]`, c2=`[]`, c3=`["makeup"]`, c4=`["makeup","glow"]`, c5=`["kbeauty"]`.

> `dummy.sql`은 insert 후 **실데이터 상세/댓글을 삭제**해 전역 집계 뷰가 더미만 보도록 격리한다. 항상 `ROLLBACK`되는 트랜잭션 안에서만 실행되므로 실데이터는 안전하다.

---

## Task 1: 테스트 러너 + 더미데이터 + analytics 스키마

**Files:**
- Create: `crawler/analytics/test/run.sh`
- Create: `crawler/analytics/seed/dummy.sql`
- Create: `crawler/analytics/test/00_smoke.test.sql`

- [ ] **Step 1: 테스트 러너 작성**

Create `crawler/analytics/test/run.sh`:

```bash
#!/usr/bin/env bash
# analytics 뷰를 적용하고 트랜잭션 격리로 테스트를 돌린다.
# 사용법: ./test/run.sh              (전체 테스트)
#         ./test/run.sh test/01_x.test.sql   (지정 테스트)
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."

PSQL=(docker exec -i crawler-postgres-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 -q)

# 1) 뷰 적용 (00→07 파일명 순, 멱등). 아직 뷰가 없으면 건너뛴다.
for v in views/*.sql; do
  echo "apply $v"
  "${PSQL[@]}" < "$v"
done

# 2) 테스트 실행. 각 테스트는 BEGIN; 더미 seed; assert; ROLLBACK; 으로 격리.
tests=("$@")
if [ ${#tests[@]} -eq 0 ]; then tests=(test/*.test.sql); fi
for t in "${tests[@]}"; do
  echo "== $t =="
  { echo 'BEGIN;'; cat seed/dummy.sql; cat "$t"; echo 'ROLLBACK;'; } | "${PSQL[@]}"
  echo "PASS: $t"
done
echo "ALL GREEN"
```

- [ ] **Step 2: 더미데이터 작성**

Create `crawler/analytics/seed/dummy.sql`:

```sql
-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
-- 카테고리/실행
INSERT INTO category(id, name, enabled) VALUES (999, 'dummy_cat', true);
INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (9990, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

-- 계정 + 최신 프로필
INSERT INTO account(id, username) VALUES
 (9001,'dummy_micro'), (9002,'dummy_mid'), (9003,'dummy_macro'), (9004,'dummy_over');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9001,9990,'{"username":"dummy_micro","followersCount":5000,"followsCount":300,"postsCount":120,"verified":false,"isBusinessAccount":true,"businessCategoryName":"Health/Beauty"}'::jsonb, timestamptz '2026-06-05 00:00:00+09'),
 (9002,9990,'{"username":"dummy_mid","followersCount":50000,"followsCount":800,"postsCount":400,"verified":true,"isBusinessAccount":true,"businessCategoryName":"Beauty"}'::jsonb, timestamptz '2026-06-05 00:00:00+09'),
 (9003,9990,'{"username":"dummy_macro","followersCount":500000,"followsCount":200,"postsCount":900,"verified":true,"isBusinessAccount":true,"businessCategoryName":"Public Figure"}'::jsonb, timestamptz '2026-06-05 00:00:00+09'),
 (9004,9990,'{"username":"dummy_over","followersCount":8000,"followsCount":500,"postsCount":60,"verified":false,"isBusinessAccount":false,"businessCategoryName":null}'::jsonb, timestamptz '2026-06-05 00:00:00+09');

-- 콘텐츠
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9101,'dummy_c1','REELS','dummy_micro', timestamptz '2026-06-01 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', false),
 (9102,'dummy_c2','FEED', 'dummy_mid',   timestamptz '2026-06-01 14:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', true),
 (9103,'dummy_c3','REELS','dummy_macro', timestamptz '2026-06-02 09:00:00+09',999,'glow','AGGREGATED',  timestamptz '2026-06-02 00:00:00+09','glow_sub','B', false),
 (9104,'dummy_c4','REELS','dummy_over',  timestamptz '2026-06-03 20:00:00+09',999,'glow','AGGREGATED',  timestamptz '2026-06-03 00:00:00+09','glow_sub','B', false),
 (9105,'dummy_c5','REELS','dummy_micro', timestamptz '2026-06-01 09:30:00+09',999,'kbeauty','AGGREGATED',timestamptz '2026-06-01 00:00:00+09','kbeauty_sub','B', false);

-- 콘텐츠 상세 (payload의 likesCount/commentsCount/videoPlayCount는 generated 컬럼으로 노출됨)
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"shortCode":"dummy_c1","type":"Video","likesCount":500,"commentsCount":50,"videoPlayCount":10000,"videoDuration":30,"hashtags":["makeup","kbeauty"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (9102,9990,'{"shortCode":"dummy_c2","type":"Image","likesCount":2000,"commentsCount":100,"hashtags":[],"mentions":["brand_x"],"productType":"feed"}'::jsonb, timestamptz '2026-06-04 14:00:00+09'),
 (9103,9990,'{"shortCode":"dummy_c3","type":"Video","likesCount":20000,"commentsCount":500,"videoPlayCount":400000,"videoDuration":45,"hashtags":["makeup"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-05 09:00:00+09'),
 (9104,9990,'{"shortCode":"dummy_c4","type":"Video","likesCount":1600,"commentsCount":200,"videoPlayCount":30000,"videoDuration":15,"hashtags":["makeup","glow"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-06 20:00:00+09'),
 (9105,9990,'{"shortCode":"dummy_c5","type":"Video","likesCount":300,"commentsCount":30,"videoPlayCount":8000,"videoDuration":20,"hashtags":["kbeauty"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-04 09:30:00+09');

-- 댓글 (dummy_c1 = content_id 9101): 3건, 작성자 2명, 대댓글 합 3
INSERT INTO raw_comment(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"pretty","repliesCount":1,"timestamp":"2026-06-04T09:10:00Z"}'::jsonb, timestamptz '2026-06-04 09:10:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan2","text":"love it","repliesCount":0,"timestamp":"2026-06-04T09:11:00Z"}'::jsonb, timestamptz '2026-06-04 09:11:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"where to buy","repliesCount":2,"timestamp":"2026-06-04T09:12:00Z"}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 상세/댓글 제거 (트랜잭션 안이라 ROLLBACK으로 복구됨).
-- 전역 집계 뷰가 더미 5건만 보도록 만든다.
DELETE FROM raw_comment     WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_post_detail WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
```

- [ ] **Step 3: 스모크 테스트 작성 (실패 확인용)**

Create `crawler/analytics/test/00_smoke.test.sql`:

```sql
-- 더미데이터가 정상 적재되는지 + 실데이터 격리가 되는지 확인
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM content WHERE category_id = 999) = 5, 'dummy content != 5';
  ASSERT (SELECT count(*) FROM raw_post_detail) = 5, 'detail rows should be dummy-only 5';
  ASSERT (SELECT count(*) FROM raw_comment WHERE content_id = 9101) = 3, 'c1 comments != 3';
END $$;
```

- [ ] **Step 4: 실행 권한 부여 + 스모크 실행 (green 확인)**

Run:
```bash
cd crawler/analytics && chmod +x test/run.sh && ./test/run.sh test/00_smoke.test.sql
```
Expected: `apply` 라인 없음(뷰 아직 없음) → `== test/00_smoke.test.sql ==` → `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/test/run.sh analytics/seed/dummy.sql analytics/test/00_smoke.test.sql
git commit -m "test: analytics 테스트 러너 + 결정적 더미데이터"
```

---

## Task 2: 00_base — 팩트 뷰 (최신 프로필/상세 + 콘텐츠 지표)

**Files:**
- Create: `crawler/analytics/views/00_base.sql`
- Test: 재사용 — `test/00_smoke.test.sql`에 base 검증 추가

- [ ] **Step 1: base 검증을 스모크 테스트에 추가 (red)**

Edit `crawler/analytics/test/00_smoke.test.sql`, 파일 끝에 추가:

```sql
-- base 뷰 검증
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_content_metrics) = 5, 'metrics rows != 5';
  ASSERT (SELECT followers FROM analytics.v_content_metrics WHERE short_code='dummy_c4') = 8000, 'c4 followers != 8000';
  ASSERT (SELECT content_format FROM analytics.v_content_metrics WHERE short_code='dummy_c2') = 'feed', 'c2 not feed';
  ASSERT (SELECT video_play_count FROM analytics.v_content_metrics WHERE short_code='dummy_c2') IS NULL, 'feed should have null views';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/00_smoke.test.sql`
Expected: FAIL — `relation "analytics.v_content_metrics" does not exist`.

- [ ] **Step 3: base 뷰 작성**

Create `crawler/analytics/views/00_base.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS analytics;

-- 계정별 최신 프로필
CREATE OR REPLACE VIEW analytics.v_latest_profile AS
SELECT DISTINCT ON (account_id)
  account_id,
  username,
  followers,
  (payload->>'followsCount')::bigint      AS follows,
  (payload->>'postsCount')::bigint        AS posts,
  (payload->>'verified')::boolean         AS verified,
  (payload->>'isBusinessAccount')::boolean AS is_business,
  payload->>'businessCategoryName'        AS business_category
FROM raw_profile
ORDER BY account_id, captured_at DESC;

-- 콘텐츠별 최신 상세
CREATE OR REPLACE VIEW analytics.v_latest_detail AS
SELECT DISTINCT ON (content_id)
  content_id,
  likes,
  comments_count,
  video_play_count,
  (payload->>'videoViewCount')::bigint AS video_view_count,
  (payload->>'videoDuration')::numeric AS video_duration,
  payload->>'type'                     AS media_type,
  payload->>'productType'              AS product_type,
  payload->'hashtags'                  AS hashtags,
  payload->'mentions'                  AS mentions,
  jsonb_array_length(COALESCE(payload->'childPosts','[]'::jsonb)) AS child_post_count
FROM raw_post_detail
ORDER BY content_id, captured_at DESC;

-- 콘텐츠 팩트 (성과 지표 계산 전 원자료 + 팔로워)
CREATE OR REPLACE VIEW analytics.v_content_metrics AS
SELECT
  c.id AS content_id,
  c.short_code,
  c.content_type,
  c.owner_username,
  c.uploaded_at,
  c.category_id,
  c.main_group,
  c.subcategory,
  c.discovery_keyword,
  c.ad_marked,
  d.likes,
  d.comments_count,
  d.video_play_count,
  d.video_view_count,
  d.video_duration,
  d.media_type,
  d.product_type,
  d.hashtags,
  d.mentions,
  d.child_post_count,
  p.followers,
  p.verified,
  p.is_business,
  p.business_category,
  CASE
    WHEN d.media_type = 'Video'              THEN 'reel'
    WHEN d.media_type IN ('Image','Sidecar') THEN 'feed'
    ELSE 'other'
  END AS content_format
FROM content c
JOIN analytics.v_latest_detail d ON d.content_id = c.id
LEFT JOIN account a              ON a.username = c.owner_username
LEFT JOIN analytics.v_latest_profile p ON p.account_id = a.id;
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/00_smoke.test.sql`
Expected: `apply views/00_base.sql` → `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/00_base.sql analytics/test/00_smoke.test.sql
git commit -m "feat(analytics): base 팩트 뷰 (최신 프로필/상세 + 콘텐츠 지표)"
```

---

## Task 3: 01_content_performance — 그룹 1 콘텐츠 성과

**Files:**
- Create: `crawler/analytics/views/01_content_performance.sql`
- Create: `crawler/analytics/test/01_content_performance.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/01_content_performance.test.sql`:

```sql
DO $$
BEGIN
  -- 참여율 = (likes+comments)/followers
  ASSERT (SELECT engagement_rate FROM analytics.v_content_performance WHERE short_code='dummy_c4') = 0.2250, 'c4 ER wrong';
  ASSERT (SELECT engagement_rate FROM analytics.v_content_performance WHERE short_code='dummy_c1') = 0.1100, 'c1 ER wrong';
  -- 조회수 대비 좋아요율 = likes/views
  ASSERT (SELECT like_view_rate FROM analytics.v_content_performance WHERE short_code='dummy_c1') = 0.0500, 'c1 like/view wrong';
  -- 피드(조회수 없음)는 NULL
  ASSERT (SELECT like_view_rate FROM analytics.v_content_performance WHERE short_code='dummy_c2') IS NULL, 'c2 like/view should be null';
  -- 최고 참여율 콘텐츠는 c4
  ASSERT (SELECT short_code FROM analytics.v_content_performance ORDER BY engagement_rate DESC LIMIT 1) = 'dummy_c4', 'top ER not c4';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/01_content_performance.test.sql`
Expected: FAIL — `relation "analytics.v_content_performance" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/01_content_performance.sql`:

```sql
-- 그룹 1: 콘텐츠 성과 (참여율 및 조회수 대비 비율)
CREATE OR REPLACE VIEW analytics.v_content_performance AS
SELECT
  m.*,
  round((m.likes + m.comments_count)::numeric / NULLIF(m.followers, 0), 4)      AS engagement_rate,
  round(m.likes::numeric          / NULLIF(m.video_play_count, 0), 4)           AS like_view_rate,
  round(m.comments_count::numeric / NULLIF(m.video_play_count, 0), 4)           AS comment_view_rate
FROM analytics.v_content_metrics m;
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/01_content_performance.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/01_content_performance.sql analytics/test/01_content_performance.test.sql
git commit -m "feat(analytics): 그룹1 콘텐츠 성과 뷰"
```

---

## Task 4: 02_category_performance — 그룹 2 분류별 집계

**Files:**
- Create: `crawler/analytics/views/02_category_performance.sql`
- Create: `crawler/analytics/test/02_category_performance.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/02_category_performance.test.sql`:

```sql
DO $$
BEGIN
  -- main_group B 집계 (subcategory/keyword 롤업 전체)
  ASSERT (SELECT content_count FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='(all)' AND keyword='(all)') = 3, 'B count != 3';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='(all)' AND keyword='(all)') = 0.1107, 'B avg ER != 0.1107';
  -- 발굴 키워드 glow (main_group B) = c3,c4 = 2건
  ASSERT (SELECT content_count FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='glow_sub' AND keyword='glow') = 2, 'glow count != 2';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/02_category_performance.test.sql`
Expected: FAIL — `relation "analytics.v_category_performance" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/02_category_performance.sql`:

```sql
-- 그룹 2: 분류별 집계 (main_group > subcategory > keyword 롤업)
-- GROUPING SETS로 3개 레벨을 한 뷰에서 제공. 롤업된 하위 레벨은 '(all)'로 표기.
CREATE OR REPLACE VIEW analytics.v_category_performance AS
SELECT
  main_group,
  COALESCE(subcategory, '(all)')       AS subcategory,
  COALESCE(discovery_keyword, '(all)') AS keyword,
  count(*)                             AS content_count,
  round(avg(engagement_rate), 4)       AS avg_engagement_rate,
  round(avg(likes), 1)                 AS avg_likes,
  round(avg(video_play_count), 1)      AS avg_views
FROM analytics.v_content_performance
GROUP BY GROUPING SETS (
  (main_group),
  (main_group, subcategory),
  (main_group, subcategory, discovery_keyword)
);
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/02_category_performance.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/02_category_performance.sql analytics/test/02_category_performance.test.sql
git commit -m "feat(analytics): 그룹2 분류별 집계 뷰"
```

---

## Task 5: 03_creators — 그룹 3 크리에이터/계정

**Files:**
- Create: `crawler/analytics/views/03_creators.sql`
- Create: `crawler/analytics/test/03_creators.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/03_creators.test.sql`:

```sql
DO $$
BEGIN
  -- 팔로워 구간
  ASSERT (SELECT tier FROM analytics.v_follower_tier WHERE username='dummy_micro') = 'micro', 'micro tier wrong';
  ASSERT (SELECT tier FROM analytics.v_follower_tier WHERE username='dummy_mid')   = 'mid',   'mid tier wrong';
  ASSERT (SELECT tier FROM analytics.v_follower_tier WHERE username='dummy_macro') = 'macro', 'macro tier wrong';
  -- 계정별 성과
  ASSERT (SELECT content_count FROM analytics.v_creator_performance WHERE owner_username='dummy_micro') = 2, 'micro content_count != 2';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_creator_performance WHERE owner_username='dummy_micro') = 0.0880, 'micro avg ER != 0.088';
  -- 오버퍼폼: dummy_over가 micro tier 중앙값 초과
  ASSERT (SELECT overperforms FROM analytics.v_creator_overperformance WHERE owner_username='dummy_over') = true, 'dummy_over should overperform';
  ASSERT (SELECT overperforms FROM analytics.v_creator_overperformance WHERE owner_username='dummy_micro') = false, 'dummy_micro should not overperform';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/03_creators.test.sql`
Expected: FAIL — `relation "analytics.v_follower_tier" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/03_creators.sql`:

```sql
-- 그룹 3: 크리에이터/계정
-- 팔로워 구간 경계 (조정 가능): micro < 10000 <= mid < 100000 <= macro
CREATE OR REPLACE VIEW analytics.v_follower_tier AS
SELECT
  account_id,
  username,
  followers,
  CASE
    WHEN followers < 10000  THEN 'micro'
    WHEN followers < 100000 THEN 'mid'
    ELSE 'macro'
  END AS tier
FROM analytics.v_latest_profile;

-- 계정별 성과
CREATE OR REPLACE VIEW analytics.v_creator_performance AS
SELECT
  owner_username,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate,
  round(avg(likes), 1)           AS avg_likes,
  max(followers)                 AS followers
FROM analytics.v_content_performance
GROUP BY owner_username;

-- 오버퍼폼: 같은 팔로워 구간 중앙 ER을 초과하는 계정 (협업 후보)
CREATE OR REPLACE VIEW analytics.v_creator_overperformance AS
WITH creator AS (
  SELECT
    owner_username,
    avg(engagement_rate) AS er,
    max(followers)       AS followers,
    CASE
      WHEN max(followers) < 10000  THEN 'micro'
      WHEN max(followers) < 100000 THEN 'mid'
      ELSE 'macro'
    END AS tier
  FROM analytics.v_content_performance
  GROUP BY owner_username
),
tier_median AS (
  SELECT tier, percentile_cont(0.5) WITHIN GROUP (ORDER BY er) AS med
  FROM creator
  GROUP BY tier
)
SELECT
  c.owner_username,
  c.tier,
  round(c.er, 4)  AS avg_engagement_rate,
  round(m.med, 4) AS tier_median_er,
  (c.er > m.med)  AS overperforms
FROM creator c
JOIN tier_median m USING (tier);
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/03_creators.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/03_creators.sql analytics/test/03_creators.test.sql
git commit -m "feat(analytics): 그룹3 크리에이터/계정 뷰 (구간·성과·오버퍼폼)"
```

---

## Task 6: 04_content_type — 그룹 4 콘텐츠 타입 비교

**Files:**
- Create: `crawler/analytics/views/04_content_type.sql`
- Create: `crawler/analytics/test/04_content_type.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/04_content_type.test.sql`:

```sql
DO $$
BEGIN
  ASSERT (SELECT content_count FROM analytics.v_content_type_performance WHERE content_format='reel') = 4, 'reel count != 4';
  ASSERT (SELECT content_count FROM analytics.v_content_type_performance WHERE content_format='feed') = 1, 'feed count != 1';
  -- 영상 길이 구간별 (15초짜리 c4 포함되는 short 구간 존재)
  ASSERT (SELECT count(*) FROM analytics.v_video_duration_performance) >= 1, 'duration buckets empty';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/04_content_type.test.sql`
Expected: FAIL — `relation "analytics.v_content_type_performance" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/04_content_type.sql`:

```sql
-- 그룹 4: 콘텐츠 타입/형식 비교
-- 릴스 vs 피드
CREATE OR REPLACE VIEW analytics.v_content_type_performance AS
SELECT
  content_format,
  count(*)                        AS content_count,
  round(avg(engagement_rate), 4)  AS avg_engagement_rate,
  round(avg(likes), 1)            AS avg_likes,
  round(avg(video_play_count), 1) AS avg_views
FROM analytics.v_content_performance
GROUP BY content_format;

-- 영상 길이 구간 ↔ 성과 (영상만)
CREATE OR REPLACE VIEW analytics.v_video_duration_performance AS
SELECT
  CASE
    WHEN video_duration < 15 THEN '0-15s'
    WHEN video_duration < 30 THEN '15-30s'
    WHEN video_duration < 60 THEN '30-60s'
    ELSE '60s+'
  END AS duration_bucket,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate,
  round(avg(video_play_count),1) AS avg_views
FROM analytics.v_content_performance
WHERE video_duration IS NOT NULL
GROUP BY 1;
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/04_content_type.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/04_content_type.sql analytics/test/04_content_type.test.sql
git commit -m "feat(analytics): 그룹4 콘텐츠 타입/영상길이 비교 뷰"
```

---

## Task 7: 05_timing — 그룹 5 업로드 타이밍

**Files:**
- Create: `crawler/analytics/views/05_timing.sql`
- Create: `crawler/analytics/test/05_timing.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/05_timing.test.sql`:

```sql
DO $$
BEGIN
  -- KST 기준 오전 9시 업로드 = c1(09:00), c3(09:00), c5(09:30) = 3건
  ASSERT (SELECT sum(content_count) FROM analytics.v_timing_performance WHERE hour = 9) = 3, 'hour=9 count != 3';
  -- 월요일(2026-06-01)에 c1,c2,c5 = 3건 (isodow 1)
  ASSERT (SELECT sum(content_count) FROM analytics.v_timing_performance WHERE dow = 1) = 3, 'monday count != 3';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/05_timing.test.sql`
Expected: FAIL — `relation "analytics.v_timing_performance" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/05_timing.sql`:

```sql
-- 그룹 5: 업로드 타이밍 ↔ (3일 시점) 성과. 시각은 KST 기준.
-- dow: ISO 요일 (1=월 ... 7=일)
CREATE OR REPLACE VIEW analytics.v_timing_performance AS
SELECT
  extract(isodow FROM uploaded_at AT TIME ZONE 'Asia/Seoul')::int AS dow,
  extract(hour   FROM uploaded_at AT TIME ZONE 'Asia/Seoul')::int AS hour,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate
FROM analytics.v_content_performance
GROUP BY 1, 2;
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/05_timing.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/05_timing.sql analytics/test/05_timing.test.sql
git commit -m "feat(analytics): 그룹5 업로드 타이밍 뷰 (KST 요일/시간대)"
```

---

## Task 8: 06_hashtags_comments — 그룹 6 해시태그·멘션·댓글

**Files:**
- Create: `crawler/analytics/views/06_hashtags_comments.sql`
- Create: `crawler/analytics/test/06_hashtags_comments.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/06_hashtags_comments.test.sql`:

```sql
DO $$
BEGIN
  -- 해시태그 makeup: c1,c3,c4 = 3건
  ASSERT (SELECT content_count FROM analytics.v_hashtag_performance WHERE tag='makeup') = 3, 'makeup count != 3';
  ASSERT (SELECT content_count FROM analytics.v_hashtag_performance WHERE tag='kbeauty') = 2, 'kbeauty count != 2';
  -- 멘션 brand_x: c2 = 1건
  ASSERT (SELECT content_count FROM analytics.v_mention_performance WHERE mention='brand_x') = 1, 'brand_x mention != 1';
  -- 댓글 통계 (dummy_c1 = 9101)
  ASSERT (SELECT comment_count   FROM analytics.v_content_comment_stats WHERE content_id=9101) = 3, 'c1 comment_count != 3';
  ASSERT (SELECT unique_writers  FROM analytics.v_content_comment_stats WHERE content_id=9101) = 2, 'c1 unique_writers != 2';
  ASSERT (SELECT reply_ratio     FROM analytics.v_content_comment_stats WHERE content_id=9101) = 1.0000, 'c1 reply_ratio != 1.0';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/06_hashtags_comments.test.sql`
Expected: FAIL — `relation "analytics.v_hashtag_performance" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/06_hashtags_comments.sql`:

```sql
-- 그룹 6: 해시태그·멘션·댓글 (텍스트/감성 분석은 범위 밖 — 나중 Python)

-- 캡션 해시태그별 성과
CREATE OR REPLACE VIEW analytics.v_hashtag_performance AS
SELECT
  tag,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate
FROM analytics.v_content_performance cp
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(cp.hashtags, '[]'::jsonb)) AS tag
GROUP BY tag;

-- 멘션(협업/태그)별 빈도
CREATE OR REPLACE VIEW analytics.v_mention_performance AS
SELECT
  mention,
  count(*) AS content_count
FROM analytics.v_content_performance cp
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(cp.mentions, '[]'::jsonb)) AS mention
GROUP BY mention;

-- 콘텐츠별 댓글 통계 (작성자 다양성·대댓글 비율)
CREATE OR REPLACE VIEW analytics.v_content_comment_stats AS
SELECT
  content_id,
  count(*)                                                              AS comment_count,
  count(DISTINCT writer)                                                AS unique_writers,
  sum((payload->>'repliesCount')::int)                                  AS total_replies,
  round(sum((payload->>'repliesCount')::int)::numeric
        / NULLIF(count(*), 0), 4)                                       AS reply_ratio
FROM raw_comment
GROUP BY content_id;
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/06_hashtags_comments.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/06_hashtags_comments.sql analytics/test/06_hashtags_comments.test.sql
git commit -m "feat(analytics): 그룹6 해시태그/멘션/댓글 통계 뷰"
```

---

## Task 9: 07_ads — 그룹 7 광고/협찬

**Files:**
- Create: `crawler/analytics/views/07_ads.sql`
- Create: `crawler/analytics/test/07_ads.test.sql`

- [ ] **Step 1: 테스트 작성 (red)**

Create `crawler/analytics/test/07_ads.test.sql`:

```sql
DO $$
BEGIN
  -- 광고 표기 콘텐츠 = c2 = 1건
  ASSERT (SELECT content_count FROM analytics.v_ad_performance WHERE ad_marked = true)  = 1, 'ad count != 1';
  ASSERT (SELECT content_count FROM analytics.v_ad_performance WHERE ad_marked = false) = 4, 'non-ad count != 4';
  -- 전체 광고 비율 = 1/5 = 0.2
  ASSERT (SELECT ad_ratio FROM analytics.v_ad_ratio) = 0.2000, 'ad_ratio != 0.2';
END $$;
```

- [ ] **Step 2: 실패 확인**

Run: `cd crawler/analytics && ./test/run.sh test/07_ads.test.sql`
Expected: FAIL — `relation "analytics.v_ad_performance" does not exist`.

- [ ] **Step 3: 뷰 작성**

Create `crawler/analytics/views/07_ads.sql`:

```sql
-- 그룹 7: 광고/협찬
-- 광고 vs 비광고 성과 비교
CREATE OR REPLACE VIEW analytics.v_ad_performance AS
SELECT
  ad_marked,
  count(*)                        AS content_count,
  round(avg(engagement_rate), 4)  AS avg_engagement_rate,
  round(avg(likes), 1)            AS avg_likes,
  round(avg(video_play_count), 1) AS avg_views
FROM analytics.v_content_performance
GROUP BY ad_marked;

-- 전체 광고 표기 비율
CREATE OR REPLACE VIEW analytics.v_ad_ratio AS
SELECT
  round(avg(CASE WHEN ad_marked THEN 1 ELSE 0 END)::numeric, 4) AS ad_ratio,
  count(*)                                                      AS total_content
FROM analytics.v_content_performance;
```

- [ ] **Step 4: green 확인**

Run: `cd crawler/analytics && ./test/run.sh test/07_ads.test.sql`
Expected: `PASS` → `ALL GREEN`.

- [ ] **Step 5: 커밋**

```bash
cd crawler && git add analytics/views/07_ads.sql analytics/test/07_ads.test.sql
git commit -m "feat(analytics): 그룹7 광고/협찬 뷰"
```

---

## Task 10: README + 전체 테스트 통과

**Files:**
- Create: `crawler/analytics/README.md`

- [ ] **Step 1: 전체 테스트 실행 (전 그룹 green 확인)**

Run: `cd crawler/analytics && ./test/run.sh`
Expected: `views/00..07` 8개 apply → 각 `test/*.test.sql` PASS → `ALL GREEN`.

- [ ] **Step 2: README 작성**

Create `crawler/analytics/README.md`:

```markdown
# analytics — 크롤링 데이터 분석 카탈로그

crawler가 적재한 인스타 raw 데이터로 콘텐츠·크리에이터를 분석하는 읽기전용 SQL 뷰 모음.
운영 스키마(`public`)는 건드리지 않고 `analytics` 스키마에만 뷰를 만든다.

설계: [../docs/superpowers/specs/2026-07-09-analytics-catalog-design.md](../docs/superpowers/specs/2026-07-09-analytics-catalog-design.md)

## 뷰 적용

```bash
for v in analytics/views/*.sql; do
  docker exec -i crawler-postgres-1 psql -U crawler -d crawler -q < "$v"
done
```

## 지표 뷰 목록

| 뷰 | 그룹 | 내용 |
|---|---|---|
| `v_latest_profile` / `v_latest_detail` / `v_content_metrics` | base | 계정별 최신 프로필 / 콘텐츠별 최신 상세 / 콘텐츠 팩트 |
| `v_content_performance` | 1 | 콘텐츠별 참여율·조회수 대비 좋아요/댓글율 |
| `v_category_performance` | 2 | main_group>subcategory>keyword 롤업 집계 |
| `v_follower_tier` / `v_creator_performance` / `v_creator_overperformance` | 3 | 팔로워 구간 / 계정별 성과 / 오버퍼폼(협업 후보) |
| `v_content_type_performance` / `v_video_duration_performance` | 4 | 릴스 vs 피드 / 영상 길이 구간별 |
| `v_timing_performance` | 5 | KST 요일·시간대별 성과 |
| `v_hashtag_performance` / `v_mention_performance` / `v_content_comment_stats` | 6 | 해시태그 / 멘션 / 댓글 통계 |
| `v_ad_performance` / `v_ad_ratio` | 7 | 광고 vs 비광고 / 광고 비율 |

## 예시 쿼리

```sql
-- 카테고리 안에서 참여율 상위 콘텐츠 10개
SELECT short_code, owner_username, main_group, engagement_rate
FROM analytics.v_content_performance
ORDER BY engagement_rate DESC NULLS LAST
LIMIT 10;

-- 협업 후보(구간 대비 오버퍼폼)
SELECT * FROM analytics.v_creator_overperformance WHERE overperforms;
```

## 테스트

```bash
cd analytics && ./test/run.sh          # 전체
./test/run.sh test/01_content_performance.test.sql   # 지정
```

더미데이터(`seed/dummy.sql`)를 트랜잭션에 seed → 뷰 결과를 `ASSERT`로 검증 → `ROLLBACK`.
실데이터는 변경되지 않는다.

## 데이터 한계 (해석 주의)

- 저장·공유·도달·노출 지표 없음 (Apify 응답에 없음).
- 성과는 업로드 +3일 단일 스냅샷 (성장곡선 아님, 콘텐츠 간 비교는 공정).
- 팔로워는 qualify 시점 값.
- 텍스트/감성 분석, BI 대시보드, 파이프라인 운영지표는 범위 밖.
```

- [ ] **Step 3: 커밋**

```bash
cd crawler && git add analytics/README.md
git commit -m "docs(analytics): 카탈로그 README + 사용법"
```

---

## Self-Review 결과

- **Spec 커버리지:** 그룹 1~7 모두 뷰+테스트 존재 (그룹6 텍스트/감성은 스펙대로 제외). 운영지표(구 그룹8)는 스펙에서 범위 밖 → 태스크 없음(의도됨).
- **Placeholder:** 없음. 모든 SQL·assert·기대값 구체화.
- **타입/이름 일관성:** 뷰명·컬럼명(`engagement_rate`, `content_format`, `owner_username`, `ad_marked`, `tier`, `overperforms`)이 base→그룹 뷰→테스트 전반에서 일치. 더미 기대값(0.225/0.1107/0.088/tier/3건 등)이 명세 표와 assert에서 일치.
- **격리:** `dummy.sql`이 비-더미 상세/댓글을 트랜잭션 안에서 삭제해 전역 집계 뷰의 오염 방지. `LEFT JOIN account` 라 owner가 account에 없어도 성과 뷰에서 콘텐츠 누락되지 않음(팔로워만 NULL).
