# 태스크 A: 분석 기반 (analytics 재구축 1단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 백지 상태의 analytics 모듈에 분석 층의 기반 4종을 세운다 — ① base 뷰(raw 접촉 격리) ② 최근 N개 윈도우 뷰 + 설정 키 ③ 타입 기반 미러 잡(뷰→record→TRUNCATE+INSERT) ④ SQL 테스트 하니스. 계약 모듈 `contract-analysis`도 골격을 만든다.

**Architecture:** [스펙](../specs/2026-07-12-analytics-data-layer-design.md) §4·§5, [ARCHITECTURE.md](../../../ARCHITECTURE.md) §4-3·§4-4. raw DB(crawler)의 `analytics` 스키마에 뷰를 정의하고, 미러 잡이 뷰 결과를 공유 record로 매핑해 analysis DB 테이블에 한 트랜잭션 TRUNCATE+INSERT로 붓는다. 이 태스크에서는 미러 **기계**까지만 만들고(대상 등록은 B1), 뷰는 base·윈도우 2층까지만 만든다(서빙 형태 뷰는 B1).

**Tech Stack:** Spring Boot 4.1 (JdbcTemplate ×2, web 없음), Java 21 record, PostgreSQL 16 (로컬: docker `crawler-postgres-1`, 포트 5433, raw=`crawler` DB / analysis=`analysis` DB, 계정 crawler/crawler), Testcontainers, bash+psql SQL 하니스.

**전제 조건:** 로컬에서 `docker ps`에 `crawler-postgres-1`이 떠 있어야 SQL 하니스가 돈다. Testcontainers는 Docker(colima) 필요. 코드 커밋은 `feat/detail-analysis-design` 브랜치가 아니라 **새 브랜치 `feat/task-a-analytics-foundation`**에서 한다 (develop 대상 PR 전략).

---

## File Structure

```
settings.gradle                                          [수정] contract-analysis 등록
contract-analysis/build.gradle                           [신규] 순수 JDK 계약 모듈 (의존성 0)
analytics/build.gradle                                   [수정] 의존성 재정의 (jdbc, testcontainers)
analytics/src/main/resources/application.yml             [신규] DataSource 2개 + 실행 게이트
analytics/src/main/java/com/celfit/analytics/
  AnalyticsApplication.java                              [신규] 부트 엔트리 (web 없음)
  config/DataSourceConfig.java                           [신규] raw/analysis DataSource·JdbcTemplate
  mirror/MirrorSpec.java                                 [신규] 미러 대상 1건 정의 (뷰·테이블·record)
  mirror/MirrorRegistry.java                             [신규] 미러 대상 목록 (A에서는 빈 목록)
  mirror/MirrorJob.java                                  [신규] 타입 기반 미러 실행기 + 컬럼 대조 가드
  mirror/MirrorConfig.java                               [신규] 빈 배선
  mirror/MirrorRunner.java                               [신규] 기동 시 등록부 순회 실행
analytics/src/test/java/com/celfit/analytics/mirror/
  MirrorJobTest.java                                     [신규] Testcontainers 검증 3건
analytics/views/00_base.sql                              [신규] base 뷰 4종 (raw 접촉 격리)
analytics/views/01_recent_window.sql                     [신규] 최근 N개 윈도우 뷰
analytics/seed/dummy.sql                                 [신규] 결정적 더미 시드
analytics/test/run.sh                                    [신규] SQL 하니스 러너
analytics/test/00_base.test.sql                          [신규] base 뷰 기대값
analytics/test/01_recent_window.test.sql                 [신규] 윈도우 뷰 기대값
analytics/README.md                                      [신규] 모듈 사용법 (간결)
ARCHITECTURE.md                                          [수정] §5 A 상태 갱신
```

각 파일의 책임: base 뷰만 raw 테이블·payload를 만진다(§4-4). 윈도우 뷰는 base 위의 집합 연산.
`MirrorJob`은 특정 뷰를 모른다 — `MirrorSpec` 목록(`MirrorRegistry`)이 유일한 등록 지점이고 B1에서 채워진다.

---

### Task 1: 모듈 골격 — contract-analysis 신설 + gradle 배선

**Files:**
- Create: `contract-analysis/build.gradle`
- Modify: `settings.gradle`
- Modify: `analytics/build.gradle`

- [ ] **Step 1: 새 브랜치 생성**

```bash
git checkout -b feat/task-a-analytics-foundation
```

- [ ] **Step 2: contract-analysis/build.gradle 작성**

```gradle
// 계약 모듈: 분석 결과의 record·enum만 담는다 (ARCHITECTURE.md §4-4).
// 순수 JDK — Spring/JPA 등 어떤 의존성도 추가하지 않는다.
plugins {
	id 'java-library'
}
```

- [ ] **Step 3: settings.gradle에 모듈 등록**

```gradle
rootProject.name = 'hypenow-backend'

include 'crawler'
include 'analytics'
include 'contract-analysis'
include 'was'
```

- [ ] **Step 4: analytics/build.gradle 재작성**

```gradle
plugins {
	id 'org.springframework.boot'
	id 'io.spring.dependency-management'
}

dependencies {
	implementation project(':contract-analysis')
	implementation 'org.springframework.boot:spring-boot-starter-jdbc'
	runtimeOnly 'org.postgresql:postgresql'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.testcontainers:testcontainers-postgresql'
	testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 5: 빌드 확인**

Run: `./gradlew :contract-analysis:build :analytics:compileJava -q`
Expected: BUILD SUCCESSFUL (analytics는 아직 소스가 없어 compileJava가 NO-SOURCE — 정상)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle contract-analysis/build.gradle analytics/build.gradle
git commit -m "chore: contract-analysis 모듈 골격 + analytics 의존성 재정의"
```

---

### Task 2: analytics 부트 골격 — DataSource 2개

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/AnalyticsApplication.java`
- Create: `analytics/src/main/java/com/celfit/analytics/config/DataSourceConfig.java`
- Create: `analytics/src/main/resources/application.yml`

- [ ] **Step 1: AnalyticsApplication.java 작성**

```java
package com.celfit.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnalyticsApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalyticsApplication.class, args);
	}
}
```

- [ ] **Step 2: DataSourceConfig.java 작성**

```java
package com.celfit.analytics.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataSourceConfig {

	@Bean
	@Primary
	@ConfigurationProperties("app.datasource.raw")
	public DataSource rawDataSource() {
		return DataSourceBuilder.create().build();
	}

	@Bean
	@ConfigurationProperties("app.datasource.analysis")
	public DataSource analysisDataSource() {
		return DataSourceBuilder.create().build();
	}

	@Bean
	@Primary
	public JdbcTemplate rawJdbcTemplate(@Qualifier("rawDataSource") DataSource ds) {
		return new JdbcTemplate(ds);
	}

	@Bean
	public JdbcTemplate analysisJdbcTemplate(@Qualifier("analysisDataSource") DataSource ds) {
		return new JdbcTemplate(ds);
	}
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
spring:
  application:
    name: analytics
  # 기본 DataSource 자동설정 비활성 (수동 2개 정의)
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
  main:
    web-application-type: none

analytics:
  mirror-on-startup: true

app:
  datasource:
    raw:
      jdbc-url: jdbc:postgresql://localhost:5433/crawler
      username: crawler
      password: crawler
      driver-class-name: org.postgresql.Driver
    analysis:
      jdbc-url: jdbc:postgresql://localhost:5433/analysis
      username: crawler
      password: crawler
      driver-class-name: org.postgresql.Driver
```

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew :analytics:build -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add analytics/src
git commit -m "feat(analytics): 부트 골격 — raw/analysis DataSource 2개"
```

---

### Task 3: SQL 하니스 + base 뷰 (SQL식 TDD)

base 뷰 4종: `v_base_profile`(계정별 최신 프로필) / `v_base_detail`(콘텐츠별 최신 상세 + 조회수 폴백) /
`v_base_content`(콘텐츠 메타) / `v_base_comment`(댓글 평탄화). **raw 테이블·payload를 만지는 SQL은 이 파일이 유일하다.**

**Files:**
- Create: `analytics/test/run.sh` (실행권한 필요)
- Create: `analytics/seed/dummy.sql`
- Create: `analytics/test/00_base.test.sql`
- Create: `analytics/views/00_base.sql`

- [ ] **Step 1: run.sh 작성** (검증된 구버전 하니스를 그대로 복원)

```bash
#!/usr/bin/env bash
# analytics 뷰를 적용하고 트랜잭션 격리로 테스트를 돌린다.
# 사용법: ./test/run.sh              (전체 테스트)
#         ./test/run.sh test/01_x.test.sql   (지정 테스트)
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."

PSQL=(docker exec -i crawler-postgres-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 -q)

# 1) 뷰 적용 (파일명 순, 멱등). 아직 뷰가 없으면 건너뛴다.
for v in views/*.sql; do
  echo "apply $v"
  "${PSQL[@]}" < "$v"
done

# 2) 테스트 실행. 각 테스트는 BEGIN; 더미 seed; assert; ROLLBACK; 으로 격리.
tests=("$@")
if [ ${#tests[@]} -eq 0 ]; then tests=(test/*.test.sql); fi
# nullglob 하에서 매칭되는 테스트가 없으면 tests가 비어 "${tests[@]}"가 set -u에서 크래시.
# 확장 전에 개수를 가드한다.
if [ ${#tests[@]} -eq 0 ]; then echo "no tests found"; exit 1; fi
for t in "${tests[@]}"; do
  echo "== $t =="
  { echo 'BEGIN;'; cat seed/dummy.sql; cat "$t"; echo 'ROLLBACK;'; } | "${PSQL[@]}"
  echo "PASS: $t"
done
echo "ALL GREEN"
```

작성 후: `chmod +x analytics/test/run.sh`

- [ ] **Step 2: seed/dummy.sql 작성**

설계된 기대값(각 테스트 파일 주석에 재기재):
- `dummy_a` 프로필 스냅샷 2개 → 최신 followers **5500**
- 콘텐츠 9101: 상세 스냅샷 2개 → 최신 likes **520**, views **11000**
- 콘텐츠 9102: videoPlayCount 없음 → views = videoViewCount 폴백 **7000**
- 콘텐츠 9103: 피드 → views **NULL**
- 댓글 3건 (9101), like_count 최대 **7**

```sql
-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
--
-- 고정 fixture ID (bigserial 실 ID와 충돌하지 않는 높은 값):
--   category 999 / crawl_run 9990 / account 9001~9002 / content 9101~9104

INSERT INTO category(id, name, enabled) VALUES (999, 'dummy_cat', true);
INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (9990, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

-- 계정 2개. dummy_a는 프로필 스냅샷 2개(최신 선택 검증용).
INSERT INTO account(id, username) VALUES (9001,'dummy_a'), (9002,'dummy_b');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9001,9990,'{"username":"dummy_a","followersCount":5000}'::jsonb,  timestamptz '2026-06-01 00:00:00+09'),
 (9001,9990,'{"username":"dummy_a","followersCount":5500}'::jsonb,  timestamptz '2026-06-05 00:00:00+09'),
 (9002,9990,'{"username":"dummy_b","followersCount":20000}'::jsonb, timestamptz '2026-06-01 00:00:00+09');

-- 콘텐츠 4개: dummy_a 릴스2+피드1, dummy_b 릴스1
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9101,'dummy_r1','REELS','dummy_a', timestamptz '2026-06-01 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', false),
 (9102,'dummy_r2','REELS','dummy_a', timestamptz '2026-06-02 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-02 00:00:00+09','makeup_sub','A', false),
 (9103,'dummy_f1','FEED', 'dummy_a', timestamptz '2026-06-03 09:00:00+09',999,'glow','AGGREGATED',   timestamptz '2026-06-03 00:00:00+09','glow_sub','B', true),
 (9104,'dummy_r3','REELS','dummy_b', timestamptz '2026-06-04 09:00:00+09',999,'glow','AGGREGATED',   timestamptz '2026-06-04 00:00:00+09','glow_sub','B', false);

-- 상세. 9101은 스냅샷 2개(최신 선택 검증), 9102는 videoPlayCount 없이 videoViewCount만(폴백 검증),
-- 9103은 피드(조회수 필드 없음 → views NULL 검증).
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"shortCode":"dummy_r1","type":"Video","caption":"cap r1 old","likesCount":500,"commentsCount":50,"videoPlayCount":10000,"videoDuration":30}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (9101,9990,'{"shortCode":"dummy_r1","type":"Video","caption":"cap r1","likesCount":520,"commentsCount":52,"videoPlayCount":11000,"videoDuration":30}'::jsonb,     timestamptz '2026-06-05 09:00:00+09'),
 (9102,9990,'{"shortCode":"dummy_r2","type":"Video","caption":"cap r2","likesCount":300,"commentsCount":30,"videoViewCount":7000,"videoDuration":20}'::jsonb,      timestamptz '2026-06-05 09:00:00+09'),
 (9103,9990,'{"shortCode":"dummy_f1","type":"Image","caption":"cap f1","likesCount":2000,"commentsCount":100}'::jsonb,                                             timestamptz '2026-06-06 09:00:00+09'),
 (9104,9990,'{"shortCode":"dummy_r3","type":"Video","caption":"cap r3","likesCount":1000,"commentsCount":80,"videoPlayCount":40000,"videoDuration":15}'::jsonb,    timestamptz '2026-06-07 09:00:00+09');

-- 댓글 3건 (9101). like_count 7 / NULL / 2.
INSERT INTO raw_comment(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"pretty","likesCount":7,"timestamp":"2026-06-04T09:10:00Z"}'::jsonb,       timestamptz '2026-06-04 09:10:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan2","text":"love it","timestamp":"2026-06-04T09:11:00Z"}'::jsonb,                     timestamptz '2026-06-04 09:11:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"where to buy","likesCount":2,"timestamp":"2026-06-04T09:12:00Z"}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 상세/댓글/프로필 제거 (트랜잭션 안이라 ROLLBACK으로 복구됨).
-- base 뷰를 조인하는 상위 뷰는 더미 데이터만 보게 된다.
DELETE FROM raw_comment     WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_post_detail WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_profile     WHERE account_id NOT IN (SELECT id FROM account WHERE username LIKE 'dummy_%');
```

- [ ] **Step 3: 00_base.test.sql 작성 (뷰보다 먼저 — 실패 확인용)**

```sql
-- base 뷰 기대값. 시드 근거:
--   dummy_a 프로필 최신 followers=5500 (06-01 5000 → 06-05 5500)
--   9101 최신 상세 likes=520, views=11000 (06-04 스냅샷은 구버전)
--   9102 views=7000 (videoPlayCount 없음 → videoViewCount 폴백)
--   9103 피드 → views NULL
--   9101 댓글 3건, like_count = {7, NULL, 2}
DO $$
BEGIN
  -- v_base_profile: 계정별 1행, 최신 스냅샷 선택
  ASSERT (SELECT count(*) FROM analytics.v_base_profile WHERE username LIKE 'dummy_%') = 2,
    'v_base_profile dummy rows != 2';
  ASSERT (SELECT followers FROM analytics.v_base_profile WHERE username = 'dummy_a') = 5500,
    'v_base_profile dummy_a followers != 5500 (latest snapshot)';

  -- v_base_detail: 콘텐츠별 1행, 최신 스냅샷 + 조회수 폴백
  ASSERT (SELECT count(*) FROM analytics.v_base_detail WHERE content_id BETWEEN 9101 AND 9104) = 4,
    'v_base_detail dummy rows != 4';
  ASSERT (SELECT likes FROM analytics.v_base_detail WHERE content_id = 9101) = 520,
    'v_base_detail 9101 likes != 520 (latest snapshot)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9101) = 11000,
    'v_base_detail 9101 views != 11000';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9102) = 7000,
    'v_base_detail 9102 views != 7000 (videoViewCount fallback)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9103) IS NULL,
    'v_base_detail 9103 views is not NULL (feed)';
  ASSERT (SELECT caption FROM analytics.v_base_detail WHERE content_id = 9101) = 'cap r1',
    'v_base_detail 9101 caption != cap r1 (latest snapshot)';

  -- v_base_content: 콘텐츠 메타 노출
  ASSERT (SELECT count(*) FROM analytics.v_base_content WHERE category_id = 999) = 4,
    'v_base_content dummy rows != 4';
  ASSERT (SELECT ad_marked FROM analytics.v_base_content WHERE short_code = 'dummy_f1') = true,
    'v_base_content dummy_f1 ad_marked != true';

  -- v_base_comment: 평탄화 + like_count 추출
  ASSERT (SELECT count(*) FROM analytics.v_base_comment WHERE content_id = 9101) = 3,
    'v_base_comment 9101 rows != 3';
  ASSERT (SELECT max(like_count) FROM analytics.v_base_comment WHERE content_id = 9101) = 7,
    'v_base_comment 9101 max like_count != 7';
END $$;
```

- [ ] **Step 4: 하니스 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh`
Expected: FAIL — `relation "analytics.v_base_profile" does not exist` (뷰가 아직 없음)

- [ ] **Step 5: views/00_base.sql 작성**

```sql
-- base 뷰: raw 테이블·payload를 직접 만지는 유일한 SQL (ARCHITECTURE.md §4-4).
-- crawler가 payload 구조를 바꾸면 이 파일만 고친다.
CREATE SCHEMA IF NOT EXISTS analytics;

-- 계정별 최신 프로필
CREATE OR REPLACE VIEW analytics.v_base_profile AS
SELECT DISTINCT ON (account_id)
  account_id,
  username,
  followers,
  captured_at
FROM raw_profile
ORDER BY account_id, captured_at DESC, id DESC;

-- 콘텐츠별 최신 상세. 조회수 = videoPlayCount, 폴백 videoViewCount (§6 데이터 제약).
-- 피드는 두 필드 모두 없어 views가 NULL — 상위 뷰는 NULL 규칙을 항상 의식할 것.
CREATE OR REPLACE VIEW analytics.v_base_detail AS
SELECT DISTINCT ON (content_id)
  content_id,
  caption,
  likes,
  comments_count,
  COALESCE(video_play_count, (payload->>'videoViewCount')::bigint) AS views,
  (payload->>'videoDuration')::numeric AS video_duration,
  payload->>'type'                     AS media_type,
  captured_at
FROM raw_post_detail
ORDER BY content_id, captured_at DESC, id DESC;

-- 콘텐츠 메타 (content 테이블 노출)
CREATE OR REPLACE VIEW analytics.v_base_content AS
SELECT
  id AS content_id,
  short_code,
  content_type,
  owner_username,
  uploaded_at,
  category_id,
  main_group,
  subcategory,
  discovery_keyword,
  ad_marked
FROM content;

-- 댓글 평탄화
CREATE OR REPLACE VIEW analytics.v_base_comment AS
SELECT
  id AS comment_id,
  content_id,
  writer,
  text,
  (payload->>'likesCount')::bigint AS like_count,
  written_at
FROM raw_comment;
```

- [ ] **Step 6: 하니스 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh`
Expected: `PASS: test/00_base.test.sql` … `ALL GREEN`

- [ ] **Step 7: Commit**

```bash
git add analytics/views analytics/seed analytics/test
git commit -m "feat(analytics): base 뷰 4종 + SQL 하니스 재구축 (raw 접촉 격리)"
```

---

### Task 4: 최근 N개 윈도우 뷰 + 설정 키 (SQL식 TDD)

**Files:**
- Create: `analytics/test/01_recent_window.test.sql`
- Create: `analytics/views/01_recent_window.sql`

- [ ] **Step 1: 01_recent_window.test.sql 작성 (뷰보다 먼저)**

```sql
-- 윈도우 뷰 기대값. 시드 근거:
--   더미 콘텐츠 4건 (dummy_a 3건 + dummy_b 1건) — 기본 N=12에서 전부 포함
--   dummy_a 최신순: dummy_f1(06-03) > dummy_r2(06-02) > dummy_r1(06-01)
--   N=1이면 계정별 최신 1건만: dummy_f1, dummy_r3
-- 결정적 실행을 위해 키를 기본값 상태로 강제
DELETE FROM app_setting WHERE key = 'analytics.recent-window';

DO $$
BEGIN
  -- 기본 N=12: 더미 전부 포함
  ASSERT (SELECT count(*) FROM analytics.v_recent_content) = 4,
    'v_recent_content rows != 4 (default N=12)';
  ASSERT (SELECT count(*) FROM analytics.v_recent_content WHERE owner_username = 'dummy_a') = 3,
    'v_recent_content dummy_a rows != 3';
  -- 최신 게시물이 rank 1
  ASSERT (SELECT short_code FROM analytics.v_recent_content
          WHERE owner_username = 'dummy_a' AND recency_rank = 1) = 'dummy_f1',
    'v_recent_content dummy_a rank1 != dummy_f1';
  -- base 조인으로 지표가 붙는다
  ASSERT (SELECT views FROM analytics.v_recent_content WHERE short_code = 'dummy_r1') = 11000,
    'v_recent_content dummy_r1 views != 11000';
END $$;

-- N=1로 런타임 조정: 계정별 최신 1건만 남는다
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '1');

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content) = 2,
    'v_recent_content rows != 2 (N=1)';
  ASSERT (SELECT count(*) FROM analytics.v_recent_content
          WHERE short_code IN ('dummy_f1','dummy_r3')) = 2,
    'v_recent_content N=1 must keep only each account latest';
END $$;
```

- [ ] **Step 2: 하니스 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/01_recent_window.test.sql`
Expected: FAIL — `relation "analytics.v_recent_content" does not exist`

- [ ] **Step 3: views/01_recent_window.sql 작성**

```sql
-- 최근 N개 윈도우 (ARCHITECTURE.md §4-1): 모든 계정 단위 지표의 공통 밑판.
-- N은 app_setting 'analytics.recent-window' (기본 12) — 재배포 없이 런타임 조정.
CREATE OR REPLACE VIEW analytics.v_recent_content AS
WITH ranked AS (
  SELECT
    c.content_id,
    c.short_code,
    c.owner_username,
    c.uploaded_at,
    c.content_type,
    c.category_id,
    c.main_group,
    c.ad_marked,
    d.likes,
    d.comments_count,
    d.views,
    d.video_duration,
    d.media_type,
    row_number() OVER (PARTITION BY c.owner_username ORDER BY c.uploaded_at DESC) AS recency_rank
  FROM analytics.v_base_content c
  JOIN analytics.v_base_detail d USING (content_id)
)
SELECT *
FROM ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);
```

- [ ] **Step 4: 하니스 전체 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh`
Expected: 00·01 모두 PASS, `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/01_recent_window.sql analytics/test/01_recent_window.test.sql
git commit -m "feat(analytics): 최근 N개 윈도우 뷰 + analytics.recent-window 설정 키"
```

---

### Task 5: MirrorJob — 타입 기반 미러 기계 (TDD)

뷰 SELECT → record 매핑 → TRUNCATE+INSERT 한 트랜잭션. 시작 시 뷰 컬럼↔record 필드 대조(순서 포함)로
무언 드리프트를 쓰기 시점에 차단한다. **테스트에서 raw/analysis는 같은 컨테이너 DB를 가리켜도 된다** —
MirrorJob은 DataSource 2개를 받을 뿐 물리 분리 여부와 무관하다.

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorSpec.java`
- Create: `analytics/src/test/java/com/celfit/analytics/mirror/MirrorJobTest.java`
- Create: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorJob.java`

- [ ] **Step 1: MirrorSpec.java 작성** (테스트가 컴파일되도록 정의 먼저)

```java
package com.celfit.analytics.mirror;

/**
 * 미러 대상 1건: raw DB의 뷰 → analysis DB의 테이블, 사이의 자바 그릇은 record.
 * record 컴포넌트(camelCase)를 snake_case로 바꾼 이름·순서가 뷰 컬럼·테이블 컬럼과 일치해야 한다.
 */
public record MirrorSpec<T extends Record>(String viewName, String tableName, Class<T> recordType) {
}
```

- [ ] **Step 2: MirrorJobTest.java 작성 (실패하는 테스트)**

```java
package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MirrorJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");
	// Testcontainers 2.x의 PostgreSQLContainer는 비제네릭 — 1.x의 <SELF> 패턴 아님

	JdbcTemplate db;
	MirrorJob job;

	record FixtureRow(Long id, String name, Long score) {}

	record MismatchRow(Long id, String label) {}

	static final MirrorSpec<FixtureRow> SPEC =
			new MirrorSpec<>("analytics.v_fixture", "fixture_row", FixtureRow.class);

	@BeforeEach
	void setUp() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		job = new MirrorJob(db, ds);
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		db.update("DROP TABLE IF EXISTS fixture_src");
		db.update("DROP TABLE IF EXISTS fixture_row");
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE fixture_src (id bigint, name text, score bigint)");
		db.update("CREATE VIEW analytics.v_fixture AS SELECT id, name, score FROM fixture_src");
		db.update("CREATE TABLE fixture_row (id bigint, name text, score bigint)");
		db.update("INSERT INTO fixture_src VALUES (1,'a',10),(2,'b',20)");
	}

	@Test
	void 뷰_결과를_record로_매핑해_테이블에_붓는다() {
		int copied = job.mirror(SPEC);

		assertEquals(2, copied);
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM fixture_row ORDER BY id");
		assertEquals(2, rows.size());
		assertEquals(1L, rows.get(0).get("id"));
		assertEquals("a", rows.get(0).get("name"));
		assertEquals(10L, rows.get(0).get("score"));
	}

	@Test
	void 재실행은_전체_교체다_잔재가_남지_않는다() {
		job.mirror(SPEC);
		db.update("DELETE FROM fixture_src WHERE id = 1");
		db.update("INSERT INTO fixture_src VALUES (3,'c',30)");

		job.mirror(SPEC);

		List<Long> ids = db.queryForList("SELECT id FROM fixture_row ORDER BY id", Long.class);
		assertEquals(List.of(2L, 3L), ids);
	}

	@Test
	void 뷰_컬럼과_record_필드가_다르면_즉시_실패하고_테이블은_건드리지_않는다() {
		db.update("INSERT INTO fixture_row VALUES (99,'keep',0)");
		MirrorSpec<MismatchRow> bad =
				new MirrorSpec<>("analytics.v_fixture", "fixture_row", MismatchRow.class);

		assertThrows(IllegalStateException.class, () -> job.mirror(bad));

		assertEquals(1, db.queryForObject("SELECT count(*) FROM fixture_row", Long.class));
	}
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `./gradlew :analytics:test --tests '*MirrorJobTest*' 2>&1 | tail -20`
Expected: FAIL — `MirrorJob` 클래스가 없어 컴파일 에러

- [ ] **Step 4: MirrorJob.java 작성**

```java
package com.celfit.analytics.mirror;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 타입 기반 미러 (ARCHITECTURE.md §4-3): raw DB의 뷰를 SELECT → 공유 record로 매핑 →
 * analysis DB 테이블에 TRUNCATE+INSERT 한 트랜잭션(읽는 쪽에 공백 없음).
 * 시작 시 뷰 컬럼↔record 필드를 이름·순서까지 대조해 불일치면 즉시 실패한다.
 */
public final class MirrorJob {

	private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
			long.class, Long.class,
			int.class, Integer.class,
			boolean.class, Boolean.class,
			double.class, Double.class,
			short.class, Short.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final TransactionTemplate analysisTx;

	public MirrorJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.analysisTx = new TransactionTemplate(new DataSourceTransactionManager(analysisDataSource));
	}

	/** @return 옮긴 행 수 */
	public <T extends Record> int mirror(MirrorSpec<T> spec) {
		List<T> rows = raw.query("SELECT * FROM " + spec.viewName(),
				(ResultSetExtractor<List<T>>) rs -> readAll(rs, spec));
		String insertSql = insertSql(spec);
		RecordComponent[] components = spec.recordType().getRecordComponents();
		analysisTx.executeWithoutResult(tx -> {
			analysis.update("TRUNCATE TABLE " + spec.tableName());
			analysis.batchUpdate(insertSql, rows, 500, (ps, row) -> {
				for (int i = 0; i < components.length; i++) {
					ps.setObject(i + 1, componentValue(row, components[i]));
				}
			});
		});
		return rows.size();
	}

	private <T extends Record> List<T> readAll(ResultSet rs, MirrorSpec<T> spec) throws SQLException {
		RecordComponent[] components = spec.recordType().getRecordComponents();
		verifyColumns(rs.getMetaData(), components, spec);
		Constructor<T> ctor = canonicalConstructor(spec.recordType(), components);
		List<T> rows = new ArrayList<>();
		while (rs.next()) {
			Object[] args = new Object[components.length];
			for (int i = 0; i < components.length; i++) {
				Class<?> type = components[i].getType();
				args[i] = rs.getObject(i + 1, (Class<?>) WRAPPERS.getOrDefault(type, type));
			}
			try {
				rows.add(ctor.newInstance(args));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("record 생성 실패: " + spec.recordType().getSimpleName(), e);
			}
		}
		return rows;
	}

	/** 뷰 컬럼(이름·순서) ↔ record 컴포넌트(snake_case 변환) 대조 — 무언 드리프트 차단. */
	private void verifyColumns(ResultSetMetaData meta, RecordComponent[] components, MirrorSpec<?> spec)
			throws SQLException {
		List<String> viewColumns = new ArrayList<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			viewColumns.add(meta.getColumnName(i));
		}
		List<String> recordColumns = Arrays.stream(components)
				.map(c -> toSnakeCase(c.getName()))
				.toList();
		if (!viewColumns.equals(recordColumns)) {
			throw new IllegalStateException(
					"미러 컬럼 불일치 %s: 뷰 %s ↔ record(%s) %s".formatted(
							spec.viewName(), viewColumns, spec.recordType().getSimpleName(), recordColumns));
		}
	}

	private String insertSql(MirrorSpec<?> spec) {
		List<String> columns = Arrays.stream(spec.recordType().getRecordComponents())
				.map(c -> toSnakeCase(c.getName()))
				.toList();
		String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
		return "INSERT INTO %s (%s) VALUES (%s)"
				.formatted(spec.tableName(), String.join(", ", columns), placeholders);
	}

	private <T extends Record> Constructor<T> canonicalConstructor(Class<T> type, RecordComponent[] components) {
		Class<?>[] paramTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
		try {
			return type.getDeclaredConstructor(paramTypes);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("canonical constructor 없음: " + type.getSimpleName(), e);
		}
	}

	private Object componentValue(Record row, RecordComponent component) {
		try {
			return component.getAccessor().invoke(row);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("record 접근 실패: " + component.getName(), e);
		}
	}

	static String toSnakeCase(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests '*MirrorJobTest*' 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 3 tests passed (Docker/colima 필요)

- [ ] **Step 6: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/mirror analytics/src/test
git commit -m "feat(analytics): 타입 기반 MirrorJob — 컬럼 대조 가드 + TRUNCATE+INSERT 단일 트랜잭션"
```

---

### Task 6: 미러 배선 — 등록부·러너

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorRegistry.java`
- Create: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java`
- Create: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorRunner.java`

- [ ] **Step 1: MirrorRegistry.java 작성**

```java
package com.celfit.analytics.mirror;

import java.util.List;

/** 미러 대상 등록부. B1부터 서빙 형태 뷰 3종(accounts·contents·content_comments)이 추가된다. */
public record MirrorRegistry(List<MirrorSpec<?>> specs) {
}
```

- [ ] **Step 2: MirrorConfig.java 작성**

```java
package com.celfit.analytics.mirror;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class MirrorConfig {

	@Bean
	public MirrorJob mirrorJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new MirrorJob(rawJdbcTemplate, analysisDataSource);
	}

	@Bean
	public MirrorRegistry mirrorRegistry() {
		return new MirrorRegistry(List.of());
	}
}
```

- [ ] **Step 3: MirrorRunner.java 작성**

```java
package com.celfit.analytics.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기동 시 등록부의 미러를 순서대로 실행하는 1회성 배치. */
@Component
@ConditionalOnProperty(name = "analytics.mirror-on-startup", havingValue = "true")
public class MirrorRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(MirrorRunner.class);

	private final MirrorJob job;
	private final MirrorRegistry registry;

	public MirrorRunner(MirrorJob job, MirrorRegistry registry) {
		this.job = job;
		this.registry = registry;
	}

	@Override
	public void run(String... args) {
		for (MirrorSpec<?> spec : registry.specs()) {
			int rows = job.mirror(spec);
			log.info("mirrored {} rows: {} -> {}", rows, spec.viewName(), spec.tableName());
		}
		log.info("mirror complete ({} targets)", registry.specs().size());
	}
}
```

- [ ] **Step 4: 빌드 + 전체 테스트**

Run: `./gradlew :analytics:build 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: (로컬 DB가 떠 있으면) 부트 스모크**

Run: `./gradlew :analytics:bootRun 2>&1 | grep -E "mirror complete|APPLICATION FAILED" `
Expected: `mirror complete (0 targets)` 로그 후 정상 종료 (등록부가 비어 있으므로 no-op)

- [ ] **Step 6: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/mirror
git commit -m "feat(analytics): 미러 등록부·기동 러너 (등록은 B1에서)"
```

---

### Task 7: 문서 — README 재작성 + ARCHITECTURE.md 상태 갱신

**Files:**
- Create: `analytics/README.md`
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표의 A 행, §9 문서 맵은 README 재작성으로 링크 자동 복구)

- [ ] **Step 1: analytics/README.md 작성**

```markdown
# analytics — 분석 층

raw DB(crawler)를 읽어 분석 결과를 analysis DB에 내놓는 모듈.
설계: [../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md](../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)

## 구성

- `views/` — raw DB `analytics` 스키마의 뷰. 파일명 번호순 적용.
  - `00_base.sql` — base 뷰 4종. **raw 테이블·payload를 만지는 유일한 SQL.**
  - `01_recent_window.sql` — 계정별 최근 N개 윈도우 (`v_recent_content`)
- `mirror/` — 타입 기반 미러: 뷰 SELECT → 공유 record 매핑 → analysis DB 테이블
  TRUNCATE+INSERT (한 트랜잭션, 컬럼↔record 대조 가드). 대상 등록은 `MirrorConfig`.
- `test/` — SQL 하니스. 더미 시드를 BEGIN/ROLLBACK으로 격리해 뷰 기대값을 고정.

## 실행

    ./test/run.sh                    # 뷰 적용 + SQL 테스트 전체 (crawler-postgres-1 필요)
    ./test/run.sh test/00_base.test.sql   # 지정 테스트
    ../gradlew :analytics:test       # Java 테스트 (Docker 필요)
    ../gradlew :analytics:bootRun    # 미러 1회 실행 (analytics.mirror-on-startup=true)

## app_setting 런타임 키 (뷰가 직접 읽음)

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.recent-window` | 12 | 계정 단위 지표의 최근 N개 윈도우 (§4-1) |
```

- [ ] **Step 2: ARCHITECTURE.md §5의 A 행 상태를 ⬜ → ✅로 갱신**

§5 작업 트랙 표에서 태스크 A 행만 수정:

```markdown
| A | 분석 기반 | base 뷰·최근 N개 윈도우 뷰 재작성(raw 접촉은 base 뷰만) + 설정 키 + `contract-analysis` 골격 + 타입 미러·SQL 테스트 하니스 구축 | — | ✅ |
```

- [ ] **Step 3: 최종 확인 — 전체 검증 일괄 실행**

```bash
cd analytics && ./test/run.sh && cd .. && ./gradlew :analytics:build :contract-analysis:build -q
```
Expected: `ALL GREEN` + BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add analytics/README.md ARCHITECTURE.md
git commit -m "docs: analytics README 재작성 + 태스크 A 완료 반영"
```

---

## 완료 기준 (Definition of Done)

- `./test/run.sh` ALL GREEN (base 4종 + 윈도우 뷰 기대값 고정)
- `./gradlew :analytics:test` 통과 (MirrorJob 3케이스: 복사·전체 교체·가드)
- `./gradlew :analytics:bootRun`이 빈 등록부로 no-op 정상 종료
- B1이 이어받을 접점이 명확: 뷰 추가는 `views/NN_*.sql`, 미러 등록은 `MirrorConfig.mirrorRegistry()`, record는 `contract-analysis`

## 이 계획이 다루지 않는 것 (후속 태스크)

- 서빙 형태 뷰 3종·미러 테이블 DDL(Flyway)·공유 record 실물 — **B1**
- analysis DB Flyway 배선 — 첫 테이블이 생기는 **B1**에서 도입 (A에서 미리 깔면 빈 이력만 남음 — YAGNI)
- 기준선 집계 뷰·카테고리 맥락 뷰 — **B3** (분석 잡이 소비)
- LLM/VLM 스파이크·호출 골격 — **F**
