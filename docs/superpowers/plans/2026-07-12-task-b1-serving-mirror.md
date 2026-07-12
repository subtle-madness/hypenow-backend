# 태스크 B1: 서빙 뷰 3종 + 미러 테이블 + record Implementation Plan

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트가 소비할 서빙 데이터 3종(`accounts`·`contents`·`content_comments`)을 raw→analysis DB로 흘려보낸다 — 서빙 형태 뷰 3종 + analysis DB Flyway DDL + contract-analysis record 3종 + 미러 등록. 완료 시 `bootRun` 한 번으로 실데이터가 analysis DB에 채워진다.

**Architecture:** [스펙](../specs/2026-07-12-analytics-data-layer-design.md) §3·§4, ARCHITECTURE.md §4-3. 한 형태를 세 아티팩트(뷰 SQL=계산 / Flyway DDL=저장 / 공유 record=그릇)가 나눠 들고, 세 곳의 컬럼 이름·순서가 정확히 일치해야 한다 — 런타임은 MirrorJob 가드가, 테스트 타임은 새 FlywaySchemaTest가 지킨다.

**Tech Stack:** 태스크 A 산출물 위에서. Flyway (analysis DB 전용 수동 빈 — raw DB는 crawler 소유라 건드리지 않음), Testcontainers 2.x.

**전제:** 브랜치 `feat/task-a-analytics-foundation`에 이어서 커밋. 로컬 `crawler-postgres-1` 필요. 실측 payload 키: 프로필 `fullName`/`profilePicUrl`, 상세 `displayUrl`(썸네일)/`url`(원본 링크) — 2026-07-12 실DB 확인.

---

## File Structure

```
analytics/views/00_base.sql                          [수정] base 뷰에 display_name·profile_image_url·thumbnail_url·original_url 추가
analytics/seed/dummy.sql                             [수정] 시드 payload에 해당 키 추가
analytics/test/00_base.test.sql                      [수정] 새 컬럼 assert 추가
analytics/views/02_serving.sql                       [신규] 서빙 형태 뷰 3종 (미러 대상 1:1)
analytics/test/02_serving.test.sql                   [신규] hype_score·타입 매핑·마스킹 기대값
contract-analysis/src/main/java/com/celfit/contract/analysis/
  Account.java                                       [신규] 계정 record
  Content.java                                       [신규] 콘텐츠 record
  ContentComment.java                                [신규] 댓글 record
analytics/build.gradle                               [수정] flyway-core + flyway-database-postgresql
analytics/src/main/resources/db/migration/analysis/
  V1__serving_tables.sql                             [신규] 미러 테이블 3종 DDL
analytics/src/main/java/com/celfit/analytics/config/FlywayConfig.java  [신규] analysis DB 전용 Flyway 빈
analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java  [수정] 등록부에 3종 등록
analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java [신규] DDL 컬럼 ↔ record 대조
analytics/README.md                                  [수정] 뷰 목록·미러 대상 갱신
ARCHITECTURE.md                                      [수정] §5 B1 상태 ✅
```

**컬럼 계약 (뷰 = DDL = record, 이름·순서 완전 일치):**

- `accounts`: handle, display_name, profile_image_url, followers
- `contents`: short_code, account_handle, thumbnail_url, caption, posted_at, content_type, video_duration, original_url, views, likes, comments, hype_score
- `content_comments`: id, short_code, author_masked, body, like_count

숫자 경계 없음·타입 패밀리: posted_at timestamptz→OffsetDateTime, video_duration numeric→BigDecimal, 나머지 bigint→Long / text→String (MirrorSpec 계약 주석 준수).

---

### Task 1: base 뷰 확장 — 서빙에 필요한 payload 키 노출 (SQL식 TDD)

**Files:**
- Modify: `analytics/seed/dummy.sql`
- Modify: `analytics/test/00_base.test.sql`
- Modify: `analytics/views/00_base.sql`

- [ ] **Step 1: 시드에 payload 키 추가**

`seed/dummy.sql`의 raw_profile payload에 `fullName`·`profilePicUrl`, raw_post_detail payload에 `displayUrl`·`url`을 추가한다. 변경 후 각 행 (전체 payload 재기재):

```sql
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9001,9990,'{"username":"dummy_a","fullName":"더미 에이","profilePicUrl":"https://pic/a_old.jpg","followersCount":5000}'::jsonb,  timestamptz '2026-06-01 00:00:00+09'),
 (9001,9990,'{"username":"dummy_a","fullName":"더미 에이","profilePicUrl":"https://pic/a.jpg","followersCount":5500}'::jsonb,  timestamptz '2026-06-05 00:00:00+09'),
 (9002,9990,'{"username":"dummy_b","fullName":"더미 비","profilePicUrl":"https://pic/b.jpg","followersCount":20000}'::jsonb, timestamptz '2026-06-01 00:00:00+09');
```

raw_post_detail 5행에는 각각 `"displayUrl":"https://thumb/<short_code>.jpg"`, `"url":"https://www.instagram.com/p/<short_code>/"` 키를 추가한다 (예: 9101 최신 스냅샷 → `"displayUrl":"https://thumb/dummy_r1.jpg","url":"https://www.instagram.com/p/dummy_r1/"`; 9101 구스냅샷은 `"displayUrl":"https://thumb/dummy_r1_old.jpg"`로 최신 선택 검증).

- [ ] **Step 2: 00_base.test.sql에 assert 추가** (기존 assert 유지, DO 블록 안에 추가)

```sql
  -- 서빙용 payload 키 노출 (B1)
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_a') = '더미 에이',
    'v_base_profile dummy_a display_name != 더미 에이';
  ASSERT (SELECT profile_image_url FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://pic/a.jpg',
    'v_base_profile dummy_a profile_image_url != latest a.jpg';
  ASSERT (SELECT thumbnail_url FROM analytics.v_base_detail WHERE content_id = 9101) = 'https://thumb/dummy_r1.jpg',
    'v_base_detail 9101 thumbnail_url != latest';
  ASSERT (SELECT original_url FROM analytics.v_base_detail WHERE content_id = 9101) = 'https://www.instagram.com/p/dummy_r1/',
    'v_base_detail 9101 original_url mismatch';
```

- [ ] **Step 3: 하니스 실행 — 실패 확인** (`cd analytics && ./test/run.sh test/00_base.test.sql` → 컬럼 없음 에러)

- [ ] **Step 4: 00_base.sql 컬럼 추가**

`v_base_profile` SELECT에 (username 다음):
```sql
  payload->>'fullName'      AS display_name,
  payload->>'profilePicUrl' AS profile_image_url,
```
`v_base_detail` SELECT에 (caption 다음):
```sql
  payload->>'displayUrl' AS thumbnail_url,
  payload->>'url'        AS original_url,
```

- [ ] **Step 5: 하니스 전체 실행 — ALL GREEN 확인**

- [ ] **Step 6: Commit** — `feat(analytics): base 뷰에 서빙용 payload 키 노출 (프로필 표시명·이미지, 썸네일·원본 링크)`

---

### Task 2: 서빙 형태 뷰 3종 (SQL식 TDD)

**Files:**
- Create: `analytics/test/02_serving.test.sql`
- Create: `analytics/views/02_serving.sql`

- [ ] **Step 1: 02_serving.test.sql 작성 (뷰보다 먼저)**

```sql
-- 서빙 뷰 기대값. 시드 근거:
--   v_accounts 2행 (dummy_a followers=5500 최신)
--   v_contents: 상세 있는 콘텐츠만 (4건 전부 상세 있음) = 4행
--     hype_score: 릴스=views → dummy_r1=11000, dummy_r2=7000, dummy_r3=40000
--                 피드=likes+comments → dummy_f1=2000+100=2100
--   content_type 소문자 매핑: REELS→reels, FEED→feed
--   v_content_comments 3행, author_masked = left(writer,3)||'***' → dummy_fan1→dum***
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2';
  ASSERT (SELECT followers FROM analytics.v_accounts WHERE handle = 'dummy_a') = 5500,
    'v_accounts dummy_a followers != 5500';
  ASSERT (SELECT display_name FROM analytics.v_accounts WHERE handle = 'dummy_a') = '더미 에이',
    'v_accounts dummy_a display_name mismatch';

  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE short_code LIKE 'dummy_%') = 4,
    'v_contents dummy rows != 4';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents dummy_r1 hype_score != views 11000';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 2100,
    'v_contents dummy_f1 hype_score != likes+comments 2100';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 'feed',
    'v_contents dummy_f1 content_type != feed (lowercase)';
  ASSERT (SELECT account_handle FROM analytics.v_contents WHERE short_code = 'dummy_r3') = 'dummy_b',
    'v_contents dummy_r3 account_handle != dummy_b';

  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments dummy_r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments masking != dum*** (left 3 + ***)';
  ASSERT (SELECT max(like_count) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 7,
    'v_content_comments max like_count != 7';
END $$;
```

- [ ] **Step 2: 하니스 실행 — 실패 확인** (`relation "analytics.v_accounts" does not exist`)

- [ ] **Step 3: views/02_serving.sql 작성**

```sql
-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- 계정 (자연키 handle = 인스타 username)
CREATE OR REPLACE VIEW analytics.v_accounts AS
SELECT
  username AS handle,
  display_name,
  profile_image_url,
  followers
FROM analytics.v_base_profile;

-- 콘텐츠 팩트 (상세 수집 완료된 콘텐츠만 — INNER JOIN 의도).
-- hype_score: 릴스=조회수, 피드=좋아요+댓글 (노션 스키마 확정안). 릴스인데 views NULL이면 NULL — 정렬은 NULLS LAST.
CREATE OR REPLACE VIEW analytics.v_contents AS
SELECT
  c.short_code,
  c.owner_username AS account_handle,
  d.thumbnail_url,
  d.caption,
  c.uploaded_at AS posted_at,
  lower(c.content_type) AS content_type,
  d.video_duration,
  d.original_url,
  d.views,
  d.likes,
  d.comments_count AS comments,
  CASE WHEN lower(c.content_type) = 'reels' THEN d.views
       ELSE d.likes + d.comments_count END AS hype_score
FROM analytics.v_base_content c
JOIN analytics.v_base_detail d USING (content_id);

-- 댓글 (작성자는 마스킹해 서빙 — 원문 계정명은 raw에만 둔다)
CREATE OR REPLACE VIEW analytics.v_content_comments AS
SELECT
  m.comment_id AS id,
  c.short_code,
  left(m.writer, 3) || '***' AS author_masked,
  m.text AS body,
  m.like_count
FROM analytics.v_base_comment m
JOIN analytics.v_base_content c USING (content_id);
```

- [ ] **Step 4: 하니스 전체 실행 — ALL GREEN**

- [ ] **Step 5: Commit** — `feat(analytics): 서빙 형태 뷰 3종 (accounts·contents·content_comments)`

---

### Task 3: contract-analysis record 3종 + Flyway DDL + 스키마 대조 테스트 (TDD)

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/Account.java`
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/Content.java`
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/ContentComment.java`
- Create: `analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V1__serving_tables.sql`
- Modify: `analytics/build.gradle`

- [ ] **Step 1: record 3종 작성** (순수 JDK — import는 java.* 만)

```java
package com.celfit.contract.analysis;

/** 서빙 계정 1행 (미러: analytics.v_accounts → accounts). handle = 인스타 username. */
public record Account(String handle, String displayName, String profileImageUrl, Long followers) {
}
```

```java
package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 서빙 콘텐츠 1행 (미러: analytics.v_contents → contents).
 * hypeScore: 릴스=조회수, 피드=좋아요+댓글. 릴스인데 조회수 NULL이면 NULL (정렬은 NULLS LAST).
 */
public record Content(String shortCode, String accountHandle, String thumbnailUrl, String caption,
		OffsetDateTime postedAt, String contentType, BigDecimal videoDuration, String originalUrl,
		Long views, Long likes, Long comments, Long hypeScore) {
}
```

```java
package com.celfit.contract.analysis;

/** 서빙 댓글 1행 (미러: analytics.v_content_comments → content_comments). 작성자는 마스킹된 값만. */
public record ContentComment(Long id, String shortCode, String authorMasked, String body, Long likeCount) {
}
```

- [ ] **Step 2: analytics/build.gradle에 Flyway 의존성 추가** (dependencies 블록에)

```gradle
	implementation 'org.flywaydb:flyway-core'
	runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

주의: flyway-database-postgresql은 테스트에서도 필요하므로 `runtimeOnly`면 testRuntime에도 포함된다(gradle 기본 상속). 컴파일에는 flyway-core만 쓰인다.

- [ ] **Step 3: FlywaySchemaTest.java 작성 (실패하는 테스트 — V1이 아직 없음)**

```java
package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
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
 * Flyway DDL ↔ contract record 대조: 세 아티팩트(뷰/DDL/record) 중 "DDL=record" 경계를
 * 테스트 타임에 고정한다 ("뷰=record"는 MirrorJob 런타임 가드 담당).
 */
@Testcontainers
class FlywaySchemaTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis").load().migrate();
		db = new JdbcTemplate(ds);
	}

	@Test
	void accounts_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("accounts", Account.class);
	}

	@Test
	void contents_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("contents", Content.class);
	}

	@Test
	void content_comments_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("content_comments", ContentComment.class);
	}

	private void assertColumnsMatch(String table, Class<? extends Record> recordType) {
		List<String> tableColumns = db.queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = ?
				ORDER BY ordinal_position""", String.class, table);
		List<String> recordColumns = Arrays.stream(recordType.getRecordComponents())
				.map(RecordComponent::getName)
				.map(MirrorJob::toSnakeCase)
				.toList();
		assertEquals(recordColumns, tableColumns, table + " 컬럼이 record와 다름");
	}
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인** (`./gradlew :analytics:test --tests '*FlywaySchemaTest*'` → 마이그레이션 없음/테이블 없음으로 FAIL)

- [ ] **Step 5: V1__serving_tables.sql 작성**

```sql
-- 미러 테이블 3종 (ARCHITECTURE.md §4-3: 저장은 Flyway DDL 소유).
-- 컬럼 이름·순서 = 서빙 뷰 = contract-analysis record. 자연키 PK (미러 전체 교체에도 id 안정).
-- 분석 층 테이블과의 FK는 걸지 않는다 (TRUNCATE와 충돌 — 논리 참조만).
CREATE TABLE accounts (
    handle            text PRIMARY KEY,
    display_name      text,
    profile_image_url text,
    followers         bigint
);

CREATE TABLE contents (
    short_code     text PRIMARY KEY,
    account_handle text NOT NULL,
    thumbnail_url  text,
    caption        text,
    posted_at      timestamptz,
    content_type   text,
    video_duration numeric,
    original_url   text,
    views          bigint,
    likes          bigint,
    comments       bigint,
    hype_score     bigint
);
CREATE INDEX idx_contents_hype_score ON contents (hype_score DESC NULLS LAST);
CREATE INDEX idx_contents_account_handle ON contents (account_handle);

CREATE TABLE content_comments (
    id            bigint PRIMARY KEY,
    short_code    text NOT NULL,
    author_masked text,
    body          text,
    like_count    bigint
);
CREATE INDEX idx_content_comments_short_code ON content_comments (short_code);
```

- [ ] **Step 6: 테스트 실행 — 통과 확인** (FlywaySchemaTest 3개 + 기존 MirrorJobTest 3개 모두)

- [ ] **Step 7: Commit** — `feat(analytics): 계약 record 3종 + 미러 테이블 Flyway DDL + 스키마 대조 테스트`

---

### Task 4: Flyway 배선 + 미러 등록

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/config/FlywayConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java`

- [ ] **Step 1: FlywayConfig.java 작성**

```java
package com.celfit.analytics.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * analysis DB 전용 Flyway (분석 결과 스키마는 analytics가 소유 — ARCHITECTURE.md §3).
 * raw DB에는 절대 걸지 않는다 (crawler 소유). 수동 빈이므로 Boot 자동설정은 backoff.
 * baseline: analysis DB에는 과거 산출물 테이블이 남아 있을 수 있어 baselineOnMigrate,
 * 단 baselineVersion=0으로 두어 V1부터 전부 적용되게 한다.
 */
@Configuration
public class FlywayConfig {

	@Bean(initMethod = "migrate")
	public Flyway analysisFlyway(@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return Flyway.configure()
				.dataSource(analysisDataSource)
				.locations("classpath:db/migration/analysis")
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.load();
	}
}
```

- [ ] **Step 2: MirrorConfig의 등록부 채우기** — `mirrorRegistry()` 교체:

```java
	/** 미러 대상 등록부 — 서빙 뷰 3종. 컬럼 계약은 각 record의 Javadoc과 V1__serving_tables.sql 참조. */
	@Bean
	public MirrorRegistry mirrorRegistry() {
		return new MirrorRegistry(List.of(
				new MirrorSpec<>("analytics.v_accounts", "accounts", Account.class),
				new MirrorSpec<>("analytics.v_contents", "contents", Content.class),
				new MirrorSpec<>("analytics.v_content_comments", "content_comments", ContentComment.class)));
	}
```

(import 추가: `com.celfit.contract.analysis.Account`, `Content`, `ContentComment`)

- [ ] **Step 3: 빌드+전체 테스트** — `./gradlew :analytics:build` → BUILD SUCCESSFUL

- [ ] **Step 4: E2E 스모크 — 실제 미러 실행**

```bash
./gradlew :analytics:bootRun 2>&1 | grep -E "mirrored|mirror complete|APPLICATION FAILED"
```
Expected: `mirrored 5 rows: analytics.v_accounts -> accounts` / `mirrored 2 rows: ... contents` / `mirrored 71 rows: ... content_comments` / `mirror complete (3 targets)` (행 수는 실DB 상태에 따라 다를 수 있음 — 0이 아니고 에러가 없으면 통과)

analysis DB 확인:

```bash
docker exec -i crawler-postgres-1 psql -U crawler -d analysis -c \
  "SELECT short_code, account_handle, content_type, views, hype_score FROM contents ORDER BY hype_score DESC NULLS LAST;"
```
Expected: 미러된 행이 보이고 hype_score 정렬이 동작

- [ ] **Step 5: Commit** — `feat(analytics): analysis DB Flyway 배선 + 미러 대상 3종 등록 — B1 서빙 데이터 개통`

---

### Task 5: 문서 갱신

**Files:**
- Modify: `analytics/README.md` (구성 절의 views 목록에 02 추가, 미러 대상 3종 표기)
- Modify: `ARCHITECTURE.md` (§5 B1 행 ⬜→✅)

- [ ] **Step 1: README 구성 절 갱신** — views 목록에 아래 한 줄 추가, mirror 설명에 "대상: accounts·contents·content_comments (등록: MirrorConfig)" 반영:

```markdown
  - `02_serving.sql` — 서빙 형태 뷰 3종 (`v_accounts`·`v_contents`·`v_content_comments`) — 미러 대상과 1:1
```

- [ ] **Step 2: ARCHITECTURE.md §5의 B1 행 상태 ⬜→✅** (내용은 유지, 상태만)

- [ ] **Step 3: 최종 검증** — `cd analytics && ./test/run.sh && cd .. && ./gradlew :analytics:build -q`

- [ ] **Step 4: Commit** — `docs: B1 완료 반영 (서빙 뷰·미러 등록)`

---

## 완료 기준 (DoD)

- SQL 하니스 ALL GREEN (00·01·02)
- FlywaySchemaTest 3개 + MirrorJobTest 3개 통과
- bootRun 1회로 analysis DB의 `accounts`·`contents`·`content_comments`가 실데이터로 채워짐 (mirror complete (3 targets))
- was가 이어받을 계약이 명확: contract-analysis의 record 3종 + analysis DB 테이블 3종

## 다루지 않는 것

- was의 조회 API (태스크 D) — 이 계획은 데이터 준비까지
- 댓글 ai_category·content_analyses — B2·B3
- analysis DB의 과거 산출물 테이블(content_ranking 등) 정리 — was 대시보드가 아직 읽으므로 보류 (§8 미결)
