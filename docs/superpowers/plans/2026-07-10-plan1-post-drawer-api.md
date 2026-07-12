# Plan 1: 게시물 드로어 API (비LLM) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 게시물 드로어 v3의 비LLM 데이터 전부(헤더 작성자 요약·미리보기·성과+벤치마크·수집 댓글 수)를 반환하는 `GET /api/posts/{shortCode}` 엔드포인트를 만든다.

**Architecture:** 기존 데이터 흐름 그대로 — crawler DB에 analytics 뷰 추가(`09_post_detail.sql`) → `MaterializationService`가 analysis DB로 미러(`post_detail` 테이블) → was가 조회해 JSON 응답. "최근 N개 윈도우"는 crawler DB의 `app_setting` 키 `analytics.recent-window`(기본 12)를 뷰가 직접 읽는다. LLM 블록(감지·왜 잘됐나·댓글 감성)은 이 플랜 범위 밖 — 응답에 필드 자체가 없고 Plan 3·4에서 additive하게 추가된다.

**Tech Stack:** PostgreSQL 17 뷰(SQL) + analytics 모듈의 SQL 테스트 하니스(`analytics/test/run.sh`), Java 21 / Spring Boot 4.1 (was: JdbcClient, record DTO, Jackson 3 `tools.jackson.*`), Testcontainers 2.x, MockMvc(`spring-boot-starter-webmvc-test`).

**사전 조건:** 로컬 Docker에 `crawler-postgres-1` 컨테이너 기동 (`docker compose up -d`, 포트 5433, DB `crawler`/`analysis`).

**참고 파일 (컨벤션 출처):**
- 뷰 스타일: [analytics/views/03_creators.sql](../../analytics/views/03_creators.sql), [analytics/views/08_creator_pillars.sql](../../analytics/views/08_creator_pillars.sql)
- SQL 테스트: [analytics/test/run.sh](../../analytics/test/run.sh), [analytics/test/03_creators.test.sql](../../analytics/test/03_creators.test.sql), 더미 시드 [analytics/seed/dummy.sql](../../analytics/seed/dummy.sql)
- 미러: [analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java](../../analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java)
- was 컨벤션: [was/src/main/java/com/celfit/was/dashboard/AnalysisRepository.java](../../was/src/main/java/com/celfit/was/dashboard/AnalysisRepository.java)
- Testcontainers 패턴: [crawler/src/test/java/com/celfit/crawler/IntegrationTest.java](../../crawler/src/test/java/com/celfit/crawler/IntegrationTest.java)

**더미 데이터 기대값 근거** (`analytics/seed/dummy.sql` — 테스트마다 `BEGIN; seed; test; ROLLBACK;`으로 격리):

| 콘텐츠 | 작성자(팔로워/tier) | main_group | views | likes | comments | ER |
|---|---|---|---|---|---|---|
| dummy_c1 | dummy_micro (5000/micro) | A | 10000 | 500 | 50 | 0.1100 |
| dummy_c2 (FEED) | dummy_mid (50000/mid) | A | NULL | 2000 | 100 | 0.0420 |
| dummy_c3 | dummy_macro (500000/macro) | B | 400000 | 20000 | 500 | 0.0410 |
| dummy_c4 | dummy_over (8000/micro) | B | 30000 | 1600 | 200 | 0.2250 |
| dummy_c5 | dummy_micro | B | 8000 | 300 | 30 | 0.0660 |

- 댓글: dummy_c1에만 3건.
- 작성자 요약(dummy_micro): 표본 2, 평균 조회수 (10000+8000)/2 = **9000.0**, 평균 ER (0.1100+0.0660)/2 = **0.0880**
- tier 평균 조회수(micro): c1·c5·c4 → (10000+8000+30000)/3 = **16000.0**
- 카테고리(main_group) 평균 조회수: A = 10000.0 (c2는 views NULL 제외), B = (400000+30000+8000)/3 = **146000.0**
- 확산 배율(c1): 10000/5000 = **2.00**, (c4): 30000/8000 = **3.75**

---

### Task 1: 최근 N개 윈도우 + 작성자 요약 뷰

**Files:**
- Create: `analytics/views/09_post_detail.sql` (파일 전반부)
- Test: `analytics/test/09_post_detail.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/09_post_detail.test.sql` 생성:

```sql
-- 실DB에 이 키가 이미 있어도 결정적이도록 기본값(12)으로 강제
DELETE FROM app_setting WHERE key = 'analytics.recent-window';

-- v_recent_content: 기본 N=12에서는 더미 5건 전부 포함
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content) = 5, 'recent rows != 5';
END $$;

-- v_author_summary: 작성자별 최근 N개 집계 (표본·평균 ER·평균 조회수·히트)
DO $$
BEGIN
  ASSERT (SELECT sample_size FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 2, 'micro sample != 2';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 0.0880, 'micro avg ER != 0.088';
  ASSERT (SELECT avg_views FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 9000.0, 'micro avg views != 9000';
  -- 2개 표본에서는 산술적으로 히트(평균 2배 이상) 불가능
  ASSERT (SELECT hit_count FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 0, 'micro hit != 0';
  ASSERT (SELECT hit_rate FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 0.0000, 'micro hit_rate != 0';
  -- 조회수 전무(FEED만)인 작성자: avg_views NULL, 히트 0
  ASSERT (SELECT avg_views FROM analytics.v_author_summary WHERE owner_username='dummy_mid') IS NULL, 'mid avg views not null';
  ASSERT (SELECT hit_count FROM analytics.v_author_summary WHERE owner_username='dummy_mid') = 0, 'mid hit != 0';
END $$;
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/09_post_detail.test.sql`
Expected: FAIL — `relation "analytics.v_recent_content" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/09_post_detail.sql` 생성:

```sql
-- 그룹 9: 게시물 상세 드로어 (헤더 작성자 요약 + 게시물 상세 + 벤치마크)
-- 모든 계정 단위 집계는 "최근 N개" 윈도우 기준 (확정안 공통 결정사항).
-- N은 app_setting 'analytics.recent-window'로 런타임 조정 가능, 기본 12.

-- 계정별 최근 N개 콘텐츠 윈도우
CREATE OR REPLACE VIEW analytics.v_recent_content AS
SELECT *
FROM (
  SELECT p.*,
         row_number() OVER (PARTITION BY p.owner_username
                            ORDER BY p.uploaded_at DESC NULLS LAST) AS recency_rank
  FROM analytics.v_content_performance p
) ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);

-- 작성자 요약: 드로어 헤더의 "히트율 4/12 · 평균 ER 4.3%" 원천.
-- 히트 = 최근 윈도우 내에서 조회수가 작성자 평균의 2배 이상인 게시물 (확정안 일관성 섹션 정의와 동일).
CREATE OR REPLACE VIEW analytics.v_author_summary AS
WITH base AS (
  SELECT owner_username,
         count(*)                        AS sample_size,
         max(followers)                  AS followers,
         round(avg(engagement_rate), 4)  AS avg_engagement_rate,
         round(avg(views), 1)            AS avg_views
  FROM analytics.v_recent_content
  GROUP BY owner_username
)
SELECT
  b.owner_username,
  b.sample_size,
  b.followers,
  b.avg_engagement_rate,
  b.avg_views,
  count(*) FILTER (WHERE r.views >= 2 * b.avg_views)                                   AS hit_count,
  round(count(*) FILTER (WHERE r.views >= 2 * b.avg_views)::numeric
        / NULLIF(b.sample_size, 0), 4)                                                 AS hit_rate
FROM base b
JOIN analytics.v_recent_content r ON r.owner_username = b.owner_username
GROUP BY b.owner_username, b.sample_size, b.followers, b.avg_engagement_rate, b.avg_views;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh test/09_post_detail.test.sql`
Expected: `PASS: test/09_post_detail.test.sql` → `ALL GREEN`

- [ ] **Step 5: 기존 테스트 전체 회귀 확인**

Run: `cd analytics && ./test/run.sh`
Expected: 모든 테스트 `PASS` → `ALL GREEN` (신규 뷰가 기존 뷰를 건드리지 않으므로 영향 없음)

- [ ] **Step 6: Commit**

```bash
git add analytics/views/09_post_detail.sql analytics/test/09_post_detail.test.sql
git commit -m "feat(analytics): 최근 N개 윈도우 + 작성자 요약 뷰 (드로어 헤더)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 게시물 상세 뷰 (미리보기 + 성과 + 벤치마크)

**Files:**
- Modify: `analytics/views/09_post_detail.sql` (파일 후반부 추가)
- Modify: `analytics/test/09_post_detail.test.sql` (assert 추가)

- [ ] **Step 1: 실패하는 테스트 추가**

`analytics/test/09_post_detail.test.sql` 끝에 추가:

```sql
-- v_post_detail: 게시물 1건 = 1행. 미리보기 + 성과 + 벤치마크 3종.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_post_detail) = 5, 'post_detail rows != 5';
  -- c1: 성과 원값
  ASSERT (SELECT views FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 10000, 'c1 views wrong';
  ASSERT (SELECT engagement_rate FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 0.1100, 'c1 ER wrong';
  ASSERT (SELECT collected_comment_count FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 3, 'c1 comments != 3';
  -- c1: 확산 배율 = views / followers
  ASSERT (SELECT follower_reach_multiple FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 2.00, 'c1 reach multiple != 2.00';
  ASSERT (SELECT follower_reach_multiple FROM analytics.v_post_detail WHERE short_code='dummy_c4') = 3.75, 'c4 reach multiple != 3.75';
  -- c2(FEED, views 없음): 확산 배율 NULL, 댓글 0
  ASSERT (SELECT follower_reach_multiple FROM analytics.v_post_detail WHERE short_code='dummy_c2') IS NULL, 'c2 reach multiple not null';
  ASSERT (SELECT collected_comment_count FROM analytics.v_post_detail WHERE short_code='dummy_c2') = 0, 'c2 comments != 0';
END $$;

DO $$
BEGIN
  -- c1: 벤치마크 3종 — 작성자 평균 / 구간(tier) 평균 / 카테고리(main_group) 평균
  ASSERT (SELECT author_avg_views FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 9000.0, 'c1 author avg != 9000';
  ASSERT (SELECT author_sample_size FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 2, 'c1 sample != 2';
  ASSERT (SELECT tier FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 'micro', 'c1 tier wrong';
  ASSERT (SELECT tier_avg_views FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 16000.0, 'c1 tier avg != 16000';
  ASSERT (SELECT category_avg_views FROM analytics.v_post_detail WHERE short_code='dummy_c1') = 10000.0, 'c1 category avg != 10000';
  ASSERT (SELECT category_avg_views FROM analytics.v_post_detail WHERE short_code='dummy_c4') = 146000.0, 'c4 category avg != 146000';
  -- 미리보기 필드
  ASSERT (SELECT ad_marked FROM analytics.v_post_detail WHERE short_code='dummy_c2') = true, 'c2 ad_marked wrong';
  ASSERT (SELECT caption FROM analytics.v_post_detail WHERE short_code='dummy_c1') IS NULL, 'dummy caption should be null';
  ASSERT (SELECT hashtags::text FROM analytics.v_post_detail WHERE short_code='dummy_c1') = '["makeup", "kbeauty"]', 'c1 hashtags wrong';
END $$;

-- 히트 로직 검증: dummy_over에 게시물 2건 추가(트랜잭션 내라 이 파일에만 영향).
-- dummy_over: 30000, 1000, 200000 → 평균 77000.0, 히트 = 200000(>=154000) 1건.
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9106,'dummy_c6','REELS','dummy_over', timestamptz '2026-06-04 10:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-06-04 00:00:00+09','glow_sub','B', false),
 (9107,'dummy_c7','REELS','dummy_over', timestamptz '2026-06-05 10:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-06-05 00:00:00+09','glow_sub','B', false);
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9106,9990,'{"shortCode":"dummy_c6","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":1000,"videoDuration":10,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-07 10:00:00+09'),
 (9107,9990,'{"shortCode":"dummy_c7","type":"Video","likesCount":9000,"commentsCount":900,"videoPlayCount":200000,"videoDuration":12,"hashtags":[],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-08 10:00:00+09');

DO $$
BEGIN
  ASSERT (SELECT sample_size FROM analytics.v_author_summary WHERE owner_username='dummy_over') = 3, 'over sample != 3';
  ASSERT (SELECT avg_views FROM analytics.v_author_summary WHERE owner_username='dummy_over') = 77000.0, 'over avg views != 77000';
  ASSERT (SELECT hit_count FROM analytics.v_author_summary WHERE owner_username='dummy_over') = 1, 'over hit != 1';
  ASSERT (SELECT hit_rate FROM analytics.v_author_summary WHERE owner_username='dummy_over') = 0.3333, 'over hit_rate != 0.3333';
END $$;

-- N 윈도우 설정 검증: N=1이면 작성자별 최신 1건만 집계 (파일 마지막 — 이후 assert 없음)
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '1');
DO $$
BEGIN
  -- dummy_micro 최신작 = c5(06-01 09:30) → 표본 1, ER 0.0660
  ASSERT (SELECT sample_size FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 1, 'N=1 micro sample != 1';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_author_summary WHERE owner_username='dummy_micro') = 0.0660, 'N=1 micro ER != 0.066';
END $$;
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/09_post_detail.test.sql`
Expected: FAIL — `relation "analytics.v_post_detail" does not exist`

- [ ] **Step 3: 뷰 추가**

`analytics/views/09_post_detail.sql` 끝에 추가:

```sql
-- 캡션은 v_latest_detail에 없어 별도 최신 스냅샷으로 가져온다 (base 뷰는 건드리지 않음).
CREATE OR REPLACE VIEW analytics.v_latest_caption AS
SELECT DISTINCT ON (content_id)
  content_id,
  caption
FROM raw_post_detail
ORDER BY content_id, captured_at DESC, id DESC;

-- 게시물 상세: 드로어의 미리보기 + 성과 블록 원천. 게시물 1건 = 1행.
-- 벤치마크 기준선(확정안 미결사항의 잠정 정의):
--   작성자 평균 = 최근 N개 평균 조회수 / 구간 평균 = 팔로워 tier 콘텐츠 평균 / 카테고리 평균 = main_group 콘텐츠 평균
CREATE OR REPLACE VIEW analytics.v_post_detail AS
WITH tier_avg AS (
  SELECT t.tier, round(avg(p.views), 1) AS avg_views
  FROM analytics.v_content_performance p
  JOIN analytics.v_follower_tier t ON t.username = p.owner_username
  GROUP BY t.tier
),
category_avg AS (
  SELECT main_group, round(avg(views), 1) AS avg_views
  FROM analytics.v_content_performance
  WHERE main_group IS NOT NULL
  GROUP BY main_group
)
SELECT
  p.content_id,
  p.short_code,
  p.owner_username,
  p.uploaded_at,
  p.content_format,
  p.video_duration,
  p.ad_marked,
  p.main_group,
  cap.caption,
  p.hashtags,
  p.mentions,
  p.views,
  p.engagement_rate,
  p.likes,
  p.comments_count,
  p.followers,
  round(p.views::numeric / NULLIF(p.followers, 0), 2) AS follower_reach_multiple,
  a.sample_size          AS author_sample_size,
  a.avg_views            AS author_avg_views,
  a.avg_engagement_rate  AS author_avg_engagement_rate,
  a.hit_count            AS author_hit_count,
  a.hit_rate             AS author_hit_rate,
  t.tier,
  ta.avg_views           AS tier_avg_views,
  ca.avg_views           AS category_avg_views,
  COALESCE(cc.collected_comment_count, 0) AS collected_comment_count
FROM analytics.v_content_performance p
LEFT JOIN analytics.v_latest_caption cap ON cap.content_id = p.content_id
LEFT JOIN analytics.v_author_summary a   ON a.owner_username = p.owner_username
LEFT JOIN analytics.v_follower_tier t    ON t.username = p.owner_username
LEFT JOIN tier_avg ta                    ON ta.tier = t.tier
LEFT JOIN category_avg ca                ON ca.main_group = p.main_group
LEFT JOIN (
  SELECT content_id, count(*) AS collected_comment_count
  FROM raw_comment
  GROUP BY content_id
) cc ON cc.content_id = p.content_id;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh test/09_post_detail.test.sql`
Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: 전체 회귀**

Run: `cd analytics && ./test/run.sh`
Expected: `ALL GREEN`

- [ ] **Step 6: Commit**

```bash
git add analytics/views/09_post_detail.sql analytics/test/09_post_detail.test.sql
git commit -m "feat(analytics): 게시물 상세 뷰 — 미리보기·성과·벤치마크 3종·확산 배율

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: analysis DB 미러 등록

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java:28-37`

- [ ] **Step 1: VIEW_MAPPINGS에 등록**

`VIEW_MAPPINGS` 리스트 마지막(`v_hashtag_performance` 항목 뒤)에 추가:

```java
			new ViewMapping("v_hashtag_performance", "hashtag_performance"),
			new ViewMapping("v_post_detail", "post_detail"));
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :analytics:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 로컬 미러 실행 검증**

```bash
docker compose up -d
cd analytics && ./test/run.sh   # 뷰 적용(00→09) 겸 테스트
cd .. && ./gradlew :analytics:bootRun
docker exec -i crawler-postgres-1 psql -U crawler -d analysis \
  -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='post_detail' ORDER BY ordinal_position;" \
  -c "SELECT count(*) FROM post_detail;"
```

Expected: `post_detail` 테이블 생성, `hashtags`/`mentions` 컬럼이 `jsonb` 타입, 행 수 = crawler DB의 AGGREGATED 콘텐츠 수. analytics 로그에 `materialized post_detail: N rows`.

> ⚠️ jsonb 미러가 실패하면(`buildCreateTableSql`이 만든 DDL 또는 batchUpdate 삽입 오류) 뷰에서 `hashtags::text AS hashtags`, `mentions::text AS mentions`로 캐스팅해 text로 미러한다. was는 이미 text로 읽으므로(Task 4) 이후 코드는 변경 없음.

- [ ] **Step 4: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java
git commit -m "feat(analytics): post_detail 뷰를 analysis DB로 미러

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: was 조회 계층 (Row record + Repository, Testcontainers 검증)

**Files:**
- Modify: `was/build.gradle`
- Create: `was/src/test/java/com/celfit/was/IntegrationTest.java`
- Create: `was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailRow.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java`

- [ ] **Step 1: 테스트 의존성 추가**

`was/build.gradle`의 dependencies 블록에 추가 (crawler와 동일 좌표):

```groovy
	testImplementation 'org.springframework.boot:spring-boot-testcontainers'
	testImplementation 'org.testcontainers:testcontainers-postgresql'
	testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
```

- [ ] **Step 2: 통합 테스트 베이스 작성** (crawler의 IntegrationTest 패턴 복제)

`was/src/test/java/com/celfit/was/IntegrationTest.java`:

```java
package com.celfit.was;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 통합 테스트 공통 베이스. Postgres 컨테이너 1개를 JVM 전체에서 공유(싱글턴 패턴). */
@SpringBootTest
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

- [ ] **Step 3: 실패하는 리포지토리 테스트 작성**

`was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java`:

```java
package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostDetailRepositoryTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PostDetailRepository repository;

    @BeforeEach
    void setUpTable() {
        // MaterializationService가 v_post_detail 메타데이터로 생성하는 미러 테이블과 동일한 형상
        jdbcTemplate.execute("DROP TABLE IF EXISTS post_detail");
        jdbcTemplate.execute("""
                CREATE TABLE post_detail (
                  content_id bigint, short_code text, owner_username text, uploaded_at timestamptz,
                  content_format text, video_duration numeric, ad_marked boolean, main_group text,
                  caption text, hashtags jsonb, mentions jsonb,
                  views bigint, engagement_rate numeric, likes bigint, comments_count bigint,
                  followers bigint, follower_reach_multiple numeric,
                  author_sample_size bigint, author_avg_views numeric, author_avg_engagement_rate numeric,
                  author_hit_count bigint, author_hit_rate numeric,
                  tier text, tier_avg_views numeric, category_avg_views numeric,
                  collected_comment_count bigint
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO post_detail VALUES (
                  1, 'abc123', 'glow_yeon', '2026-06-29T09:00:00+09:00',
                  'reel', 18.0, false, '스킨케어',
                  '수분크림 리뷰', '["수분크림", "스킨케어"]'::jsonb, '["roundlab_official"]'::jsonb,
                  128400, 0.082, 9800, 214,
                  24000, 6.10,
                  12, 30600.0, 0.043,
                  4, 0.3333,
                  'mid', 38900.0, 45100.0,
                  214
                )
                """);
    }

    @Test
    void shortCode로_게시물_상세_1건을_읽는다() {
        Optional<PostDetailRow> found = repository.findByShortCode("abc123");

        assertThat(found).isPresent();
        PostDetailRow row = found.get();
        assertThat(row.ownerUsername()).isEqualTo("glow_yeon");
        assertThat(row.views()).isEqualTo(128400L);
        assertThat(row.followerReachMultiple()).isEqualByComparingTo(new BigDecimal("6.10"));
        assertThat(row.authorHitCount()).isEqualTo(4L);
        assertThat(row.hashtagsJson()).contains("수분크림");
        assertThat(row.tier()).isEqualTo("mid");
        assertThat(row.collectedCommentCount()).isEqualTo(214L);
    }

    @Test
    void 없는_shortCode면_empty를_반환한다() {
        assertThat(repository.findByShortCode("nope")).isEmpty();
    }
}
```

- [ ] **Step 4: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailRepositoryTest*'`
Expected: FAIL — `PostDetailRepository`/`PostDetailRow` 심볼 없음 (컴파일 에러)

- [ ] **Step 5: Row record 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailRow.java`:

```java
package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** analysis DB의 post_detail 미러 테이블 1행. 컬럼명(snake_case)과 컴포넌트명(camelCase)이 자동 매핑된다. */
public record PostDetailRow(
        String shortCode,
        String ownerUsername,
        OffsetDateTime uploadedAt,
        String contentFormat,
        BigDecimal videoDuration,
        Boolean adMarked,
        String mainGroup,
        String caption,
        String hashtagsJson,
        String mentionsJson,
        Long views,
        BigDecimal engagementRate,
        Long likes,
        Long commentsCount,
        Long followers,
        BigDecimal followerReachMultiple,
        Long authorSampleSize,
        BigDecimal authorAvgViews,
        BigDecimal authorAvgEngagementRate,
        Long authorHitCount,
        BigDecimal authorHitRate,
        String tier,
        BigDecimal tierAvgViews,
        BigDecimal categoryAvgViews,
        Long collectedCommentCount) {
}
```

- [ ] **Step 6: Repository 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java`:

```java
package com.celfit.was.postdetail;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostDetailRepository {

    private static final Logger log = LoggerFactory.getLogger(PostDetailRepository.class);

    private final JdbcClient jdbcClient;

    public PostDetailRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<PostDetailRow> findByShortCode(String shortCode) {
        try {
            return jdbcClient.sql("""
                    SELECT short_code, owner_username, uploaded_at, content_format, video_duration,
                           ad_marked, main_group, caption,
                           hashtags::text AS hashtags_json, mentions::text AS mentions_json,
                           views, engagement_rate, likes, comments_count, followers,
                           follower_reach_multiple,
                           author_sample_size, author_avg_views, author_avg_engagement_rate,
                           author_hit_count, author_hit_rate,
                           tier, tier_avg_views, category_avg_views, collected_comment_count
                    FROM post_detail
                    WHERE short_code = :shortCode
                    """)
                    .param("shortCode", shortCode)
                    .query(PostDetailRow.class)
                    .optional();
        } catch (DataAccessException e) {
            // 미러 테이블이 아직 없을 때도 500 대신 404로 우아하게 저하 (AnalysisRepository 컨벤션)
            log.warn("post_detail 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 7: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailRepositoryTest*'`
Expected: 2 tests PASS

- [ ] **Step 8: Commit**

```bash
git add was/build.gradle was/src/test/java/com/celfit/was/IntegrationTest.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailRow.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java
git commit -m "feat(was): post_detail 조회 리포지토리 + Testcontainers 테스트 기반

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 응답 DTO + 어셈블러

**Files:**
- Create: `was/src/main/java/com/celfit/was/config/ClockConfig.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`
- Test: `was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`

- [ ] **Step 1: 실패하는 어셈블러 단위 테스트 작성**

`was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`:

```java
package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostDetailAssemblerTest {

    // 2026-07-09T09:00Z 고정 — 게시일(6/29 09:00Z)로부터 10일 경과
    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-07-09T09:00:00Z"), ZoneOffset.UTC);

    private final PostDetailAssembler assembler =
            new PostDetailAssembler(JsonMapper.builder().build(), fixedClock);

    private PostDetailRow row() {
        return new PostDetailRow(
                "abc123", "glow_yeon",
                OffsetDateTime.parse("2026-06-29T09:00:00Z"),
                "reel", new BigDecimal("18.0"), false, "스킨케어",
                "수분크림 리뷰", "[\"수분크림\", \"스킨케어\"]", "[\"roundlab_official\"]",
                128400L, new BigDecimal("0.0820"), 9800L, 214L,
                24000L, new BigDecimal("6.10"),
                12L, new BigDecimal("30600.0"), new BigDecimal("0.0430"),
                4L, new BigDecimal("0.3333"),
                "mid", new BigDecimal("38900.0"), new BigDecimal("45100.0"),
                214L);
    }

    @Test
    void 행을_드로어_블록_구조로_조립한다() {
        PostDetailResponse response = assembler.toResponse(row());

        assertThat(response.shortCode()).isEqualTo("abc123");
        assertThat(response.header().username()).isEqualTo("glow_yeon");
        assertThat(response.header().hitCount()).isEqualTo(4L);
        assertThat(response.header().sampleSize()).isEqualTo(12L);
        assertThat(response.preview().daysSincePosted()).isEqualTo(10L);
        assertThat(response.preview().hashtags()).containsExactly("수분크림", "스킨케어");
        assertThat(response.preview().mentions()).containsExactly("roundlab_official");
        assertThat(response.preview().originalUrl()).isEqualTo("https://www.instagram.com/reel/abc123/");
        assertThat(response.performance().followerReachMultiple())
                .isEqualByComparingTo(new BigDecimal("6.10"));
        assertThat(response.performance().benchmark().tierAvgViews())
                .isEqualByComparingTo(new BigDecimal("38900.0"));
        assertThat(response.commentStats().collectedCount()).isEqualTo(214L);
    }

    @Test
    void 해시태그가_null이면_빈_리스트다() {
        PostDetailRow feedRow = new PostDetailRow(
                "feed1", "user", OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                "feed", null, true, null,
                null, null, null,
                null, null, 100L, 5L,
                1000L, null,
                1L, null, null, 0L, new BigDecimal("0.0000"),
                "micro", null, null, 0L);

        PostDetailResponse response = assembler.toResponse(feedRow);

        assertThat(response.preview().hashtags()).isEmpty();
        assertThat(response.preview().mentions()).isEmpty();
        // feed는 /p/ 경로
        assertThat(response.preview().originalUrl()).isEqualTo("https://www.instagram.com/p/feed1/");
    }
}
```

> Jackson 3(`tools.jackson.*`)는 Spring Boot 4의 기본 스택. 임포트가 컴파일 에러를 내면 정확한 패키지는 `tools.jackson.*` 하위에서 컴파일러 제안으로 확정할 것 (클래스명은 `JsonMapper`/`ObjectMapper`/`TypeReference` 그대로).

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: FAIL — `PostDetailResponse`/`PostDetailAssembler` 심볼 없음

- [ ] **Step 3: Clock 빈 등록**

`was/src/main/java/com/celfit/was/config/ClockConfig.java`:

```java
package com.celfit.was.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: 응답 record 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`:

```java
package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 게시물 드로어 v3 응답. 블록 구조가 확정안의 화면 블록과 1:1 대응한다.
 * LLM 블록(감지·왜 잘됐나·댓글 감성 분석)은 Plan 3·4에서 additive하게 추가된다.
 */
public record PostDetailResponse(
        String shortCode,
        Header header,
        Preview preview,
        Performance performance,
        CommentStats commentStats) {

    /** 드로어 헤더 — 작성자 요약. hitCount/sampleSize가 "히트율 4/12", avgEngagementRate가 "평균 ER". */
    public record Header(
            String username,
            Long followers,
            Long hitCount,
            Long sampleSize,
            BigDecimal hitRate,
            BigDecimal avgEngagementRate) {
    }

    /** 미리보기 블록 — 게시 정보·캡션·해시태그·멘션. 감지 정보는 Plan 4에서 별도 블록으로 추가. */
    public record Preview(
            OffsetDateTime uploadedAt,
            Long daysSincePosted,
            String contentFormat,
            BigDecimal videoDurationSeconds,
            Boolean adMarked,
            String caption,
            List<String> hashtags,
            List<String> mentions,
            String originalUrl) {
    }

    /** 성과 블록 — 스탯 4종 + 확산 배율 + 벤치마크 바 3종. */
    public record Performance(
            Long views,
            BigDecimal engagementRate,
            Long likes,
            Long comments,
            BigDecimal followerReachMultiple,
            Benchmark benchmark) {
    }

    public record Benchmark(
            BigDecimal authorAvgViews,
            BigDecimal tierAvgViews,
            BigDecimal categoryAvgViews,
            String tier,
            String mainGroup) {
    }

    /** 댓글 블록의 비LLM 부분 — 수집(분석 대상) 댓글 수. 감성·키워드·구매의도는 Plan 3. */
    public record CommentStats(Long collectedCount) {
    }
}
```

- [ ] **Step 5: 어셈블러 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`:

```java
package com.celfit.was.postdetail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class PostDetailAssembler {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PostDetailAssembler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PostDetailResponse toResponse(PostDetailRow row) {
        return new PostDetailResponse(
                row.shortCode(),
                new PostDetailResponse.Header(
                        row.ownerUsername(), row.followers(),
                        row.authorHitCount(), row.authorSampleSize(),
                        row.authorHitRate(), row.authorAvgEngagementRate()),
                new PostDetailResponse.Preview(
                        row.uploadedAt(), daysSincePosted(row.uploadedAt()),
                        row.contentFormat(), row.videoDuration(), row.adMarked(),
                        row.caption(), parseList(row.hashtagsJson()), parseList(row.mentionsJson()),
                        originalUrl(row)),
                new PostDetailResponse.Performance(
                        row.views(), row.engagementRate(), row.likes(), row.commentsCount(),
                        row.followerReachMultiple(),
                        new PostDetailResponse.Benchmark(
                                row.authorAvgViews(), row.tierAvgViews(), row.categoryAvgViews(),
                                row.tier(), row.mainGroup())),
                new PostDetailResponse.CommentStats(row.collectedCommentCount()));
    }

    private Long daysSincePosted(OffsetDateTime uploadedAt) {
        if (uploadedAt == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(uploadedAt, OffsetDateTime.now(clock));
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, STRING_LIST);
    }

    private String originalUrl(PostDetailRow row) {
        String path = "reel".equals(row.contentFormat()) ? "reel" : "p";
        return "https://www.instagram.com/%s/%s/".formatted(path, row.shortCode());
    }
}
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: 2 tests PASS

- [ ] **Step 7: Commit**

```bash
git add was/src/main/java/com/celfit/was/config/ClockConfig.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java
git commit -m "feat(was): 드로어 응답 DTO + 어셈블러 (블록 구조 = 확정안 v3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 컨트롤러 + CORS

**Files:**
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`
- Create: `was/src/main/java/com/celfit/was/config/WebConfig.java`
- Modify: `was/src/main/resources/application.yml`
- Test: `was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`

- [ ] **Step 1: 실패하는 MockMvc 테스트 작성**

`was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`:

```java
package com.celfit.was.postdetail;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.ClockConfig;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PostDetailController.class)
@Import({PostDetailAssembler.class, ClockConfig.class})
class PostDetailControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PostDetailRepository repository;

    private PostDetailRow row() {
        return new PostDetailRow(
                "abc123", "glow_yeon",
                OffsetDateTime.parse("2026-06-29T09:00:00Z"),
                "reel", new BigDecimal("18.0"), false, "스킨케어",
                "수분크림 리뷰", "[\"수분크림\"]", "[]",
                128400L, new BigDecimal("0.0820"), 9800L, 214L,
                24000L, new BigDecimal("6.10"),
                12L, new BigDecimal("30600.0"), new BigDecimal("0.0430"),
                4L, new BigDecimal("0.3333"),
                "mid", new BigDecimal("38900.0"), new BigDecimal("45100.0"),
                214L);
    }

    @Test
    void 게시물_상세를_JSON으로_반환한다() throws Exception {
        given(repository.findByShortCode("abc123")).willReturn(Optional.of(row()));

        mockMvc.perform(get("/api/posts/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.header.username").value("glow_yeon"))
                .andExpect(jsonPath("$.header.hitCount").value(4))
                .andExpect(jsonPath("$.preview.hashtags[0]").value("수분크림"))
                .andExpect(jsonPath("$.performance.views").value(128400))
                .andExpect(jsonPath("$.performance.benchmark.tier").value("mid"))
                .andExpect(jsonPath("$.commentStats.collectedCount").value(214));
    }

    @Test
    void 없는_게시물이면_404() throws Exception {
        given(repository.findByShortCode("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/posts/nope"))
                .andExpect(status().isNotFound());
    }
}
```

> Spring Boot 4에서 `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지다. 임포트 에러가 나면 이 패키지 기준으로 확인.

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailControllerTest*'`
Expected: FAIL — `PostDetailController` 심볼 없음

- [ ] **Step 3: 컨트롤러 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`:

```java
package com.celfit.was.postdetail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PostDetailController {

    private final PostDetailRepository repository;
    private final PostDetailAssembler assembler;

    public PostDetailController(PostDetailRepository repository, PostDetailAssembler assembler) {
        this.repository = repository;
        this.assembler = assembler;
    }

    @GetMapping("/api/posts/{shortCode}")
    public PostDetailResponse postDetail(@PathVariable String shortCode) {
        return repository.findByShortCode(shortCode)
                .map(assembler::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode));
    }
}
```

- [ ] **Step 4: CORS 설정 작성** (프론트가 별도 오리진: celfit-front.vercel.app)

`was/src/main/java/com/celfit/was/config/WebConfig.java`:

```java
package com.celfit.was.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${was.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET");
    }
}
```

`was/src/main/resources/application.yml` 끝에 추가:

```yaml
was:
  cors:
    allowed-origins: http://localhost:3000,https://celfit-front.vercel.app
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailControllerTest*'`
Expected: 2 tests PASS

- [ ] **Step 6: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL (Repository·Assembler·Controller 테스트 전부 PASS)

- [ ] **Step 7: Commit**

```bash
git add was/src/main/java/com/celfit/was/postdetail/PostDetailController.java \
  was/src/main/java/com/celfit/was/config/WebConfig.java \
  was/src/main/resources/application.yml \
  was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java
git commit -m "feat(was): GET /api/posts/{shortCode} 드로어 API + CORS

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
cd analytics && ./test/run.sh && cd ..        # 뷰 적용 + SQL 테스트
./gradlew :analytics:bootRun                   # analysis DB로 미러 (완료 후 자동 종료)
./gradlew :was:bootRun &                       # was 기동 (port 8081)
sleep 15
# 실데이터에서 아무 short_code나 하나 집어 호출
SHORT_CODE=$(docker exec -i crawler-postgres-1 psql -U crawler -d analysis -tAc "SELECT short_code FROM post_detail LIMIT 1")
curl -s "http://localhost:8081/api/posts/${SHORT_CODE}" | head -c 2000; echo
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/posts/__none__"
```

Expected:
- 첫 curl: `shortCode`, `header`(username·followers·hitCount·sampleSize·hitRate·avgEngagementRate), `preview`(uploadedAt·daysSincePosted·hashtags·originalUrl…), `performance`(views·benchmark…), `commentStats` 필드가 채워진 JSON
- 둘째 curl: `404`

- [ ] **Step 3: was 종료 및 마무리**

```bash
kill %1 2>/dev/null
git status   # 잔여 변경 없음 확인
```

Expected: working tree clean

> **배포 메모:** 실서버 반영 시 순서 = ① `analytics/views/*.sql`을 crawler DB에 적용(기존 `analytics/view.sh` 흐름) → ② analytics 모듈 1회 실행(미러 갱신) → ③ was 배포. was는 미러 테이블이 없어도 404로 우아하게 저하되므로 순서가 어긋나도 500은 나지 않는다.

---

## Self-Review 체크 결과 (작성 시 수행)

- **스펙 커버리지**: 드로어 v3의 비LLM 요소 — 헤더(작성자 요약+히트율+평균 ER+저장/원본/공유 중 `originalUrl`) ✅, 미리보기(게시 일자·유형·광고 표기·경과일·캡션·해시태그·멘션) ✅, 성과(스탯 4종+확산 뱃지 배율+벤치마크 바 3종) ✅, 댓글 수집 수 ✅. 감지·왜 잘됐나·감성/키워드/구매의도는 의도적으로 범위 밖(Plan 3·4). 저장/공유 버튼은 프론트 전용 동작이라 백엔드 무관, "인플루언서 상세 →" 링크는 Plan 2의 URL로 프론트가 조립.
- **플레이스홀더 없음**: 모든 코드 블록은 완성본. 단 Jackson 3 임포트 경로와 `@WebMvcTest` 패키지는 SB4 모듈 재편으로 컴파일러 확인 단서를 명시함.
- **타입 일관성**: `PostDetailRow` 25개 컴포넌트 = Repository SELECT 25개 컬럼 = 테스트 DDL 26개 컬럼(-content_id, 조회 제외) 매핑 확인. `hit_count`는 SQL bigint → Java `Long`.
