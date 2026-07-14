# 태스크 B1 잔여분: content_metric_snapshots 미러 개통 Implementation Plan

> 상태: ✅ 구현/실행/반영됨 (2026-07-13)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 게시물 지표 스냅샷 이력(`content_metric_snapshots`)을 raw→analysis DB로 흘려보낸다 — 타입 미러 3점 세트(뷰 SQL / Flyway DDL / 계약 record) + 미러 등록. 완료 시 was의 as-of 조회(태스크 D)가 이 테이블을 읽을 수 있다.

**Architecture:** [스펙](../specs/2026-07-12-analytics-data-layer-design.md) §3·§4, ARCHITECTURE.md §4-3. B1 전례([archive/2026-07-12-task-b1-serving-mirror.md](archive/2026-07-12-task-b1-serving-mirror.md)) 패턴 그대로. base 뷰가 최신 1건(`v_base_detail`, DISTINCT ON)만 노출 중이므로 이력용 base 뷰 `v_base_detail_history`를 추가한다(스펙 §4-2가 예고한 확장 — §4-5 추가는 자유).

**Tech Stack:** 기존 미러 인프라(MirrorJob·MirrorRegistry·FlywayConfig) 재사용 — 신규 배선 없음. SQL 하니스(`analytics/test/run.sh`), Testcontainers(FlywaySchemaTest).

**전제:** 브랜치 `feat/task-b1-snapshot-mirror` (feat/task-a-analytics-foundation에서 분기). 로컬 `crawler-postgres-1` 필요. was 모듈 불가침(다른 세션 작업 중) — contract-analysis에는 record 추가만.

---

## File Structure

```
analytics/views/00_base.sql                          [수정] v_base_detail_history 추가 (이력 노출)
analytics/test/00_base.test.sql                      [수정] 이력 뷰 assert 추가
analytics/views/02_serving.sql                       [수정] v_content_metric_snapshots 추가
analytics/test/02_serving.test.sql                   [수정] 구/신 스냅샷 각 1행·hype_score assert
contract-analysis/src/main/java/com/celfit/contract/analysis/
  ContentMetricSnapshot.java                         [신규] 스냅샷 record
analytics/src/main/resources/db/migration/analysis/
  V4__content_metric_snapshots.sql                   [신규] 미러 테이블 DDL
analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java  [수정] 등록부에 추가
analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java [수정] 대조 테스트 추가
analytics/README.md                                  [수정] 뷰 목록·미러 대상 갱신
ARCHITECTURE.md                                      [수정] §5·§7 갱신
```

**컬럼 계약 (뷰 = DDL = record, 이름·순서 완전 일치):**

- `content_metric_snapshots`: id, short_code, captured_at, views, likes, comments, hype_score

id = raw_post_detail의 id (자연키 — 미러 전체 교체에도 안정). 타입 패밀리: captured_at timestamptz→OffsetDateTime, 나머지 bigint→Long / text→String. hype_score 행 단위 규칙은 v_contents와 동일(릴스=views, 피드=likes+comments).

**시드 재사용:** `seed/dummy.sql`의 9101(dummy_r1)이 이미 스냅샷 2건(구: views 10000·likes 500·comments 50 / 신: 11000·520·52)을 갖고 있어 이력 검증에 그대로 쓴다 — 시드 수정 불필요.

---

### Task 1: base 뷰 이력 노출 (SQL식 TDD)

**Files:**
- Modify: `analytics/test/00_base.test.sql`
- Modify: `analytics/views/00_base.sql`

- [ ] **Step 1: 00_base.test.sql에 assert 추가** (기존 DO 블록 안, 맨 뒤에)

```sql
  -- 스냅샷 이력 노출 (B1 잔여분): 9101은 구/신 2행, 구스냅샷 views=10000
  ASSERT (SELECT count(*) FROM analytics.v_base_detail_history WHERE content_id = 9101) = 2,
    'v_base_detail_history 9101 rows != 2';
  ASSERT (SELECT views FROM analytics.v_base_detail_history
          WHERE content_id = 9101 ORDER BY captured_at ASC LIMIT 1) = 10000,
    'v_base_detail_history 9101 oldest views != 10000';
  ASSERT (SELECT views FROM analytics.v_base_detail_history WHERE content_id = 9102) = 7000,
    'v_base_detail_history 9102 videoViewCount fallback != 7000';
```

- [ ] **Step 2: 하니스 실행 — 실패 확인** (`cd analytics && ./test/run.sh test/00_base.test.sql` → `relation "analytics.v_base_detail_history" does not exist`)

- [ ] **Step 3: 00_base.sql에 이력 뷰 추가** (v_base_detail 정의 바로 아래)

```sql
-- 상세 스냅샷 이력 — 중복 크롤링 누적분 전체 (최신 1건 선택은 v_base_detail).
-- 조회수 폴백 규칙은 v_base_detail과 동일하게 유지할 것.
CREATE OR REPLACE VIEW analytics.v_base_detail_history AS
SELECT
  id,
  content_id,
  likes,
  comments_count,
  COALESCE(video_play_count, (payload->>'videoViewCount')::bigint) AS views,
  captured_at
FROM raw_post_detail;
```

- [ ] **Step 4: 하니스 전체 실행 — ALL GREEN 확인** (`./test/run.sh`)

- [ ] **Step 5: Commit** — `feat(analytics): base 뷰에 상세 스냅샷 이력 노출 (v_base_detail_history)`

---

### Task 2: 서빙 뷰 v_content_metric_snapshots (SQL식 TDD)

**Files:**
- Modify: `analytics/test/02_serving.test.sql`
- Modify: `analytics/views/02_serving.sql`

- [ ] **Step 1: 02_serving.test.sql에 assert 추가** (기존 DO 블록 안, 맨 뒤에 + 파일 머리 주석에 근거 추가)

머리 주석에 추가:
```sql
--   v_content_metric_snapshots: 스냅샷 전체 = 5행 (9101 2행 + 나머지 3행)
--     dummy_r1 구=10000/신=11000 (릴스: hype=views), dummy_f1 = 2000+100=2100 (피드: hype=likes+comments)
```

DO 블록에 추가:
```sql
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code LIKE 'dummy_%') = 5,
    'v_content_metric_snapshots dummy rows != 5';
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1') = 2,
    'v_content_metric_snapshots dummy_r1 snapshots != 2 (구/신)';
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots
          WHERE short_code = 'dummy_r1' ORDER BY captured_at ASC LIMIT 1) = 10000,
    'v_content_metric_snapshots dummy_r1 old hype_score != views 10000';
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots
          WHERE short_code = 'dummy_r1' ORDER BY captured_at DESC LIMIT 1) = 11000,
    'v_content_metric_snapshots dummy_r1 new hype_score != views 11000';
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_f1') = 2100,
    'v_content_metric_snapshots dummy_f1 hype_score != likes+comments 2100';
```

- [ ] **Step 2: 하니스 실행 — 실패 확인** (`./test/run.sh test/02_serving.test.sql` → relation does not exist)

- [ ] **Step 3: 02_serving.sql에 뷰 추가** (파일 맨 뒤)

```sql
-- 지표 스냅샷 이력 (게시물 × 수집 시점 1행). contents는 이 중 최신 1건을 편 것 —
-- 랭킹 기본 경로는 contents, as-of 조회·추이만 이 뷰를 쓴다 (스펙 §3).
-- id = raw_post_detail의 id (자연키). hype_score 규칙은 v_contents와 동일.
CREATE OR REPLACE VIEW analytics.v_content_metric_snapshots AS
SELECT
  h.id,
  c.short_code,
  h.captured_at,
  h.views,
  h.likes,
  h.comments_count AS comments,
  CASE WHEN lower(c.content_type) = 'reels' THEN h.views
       ELSE h.likes + h.comments_count END AS hype_score
FROM analytics.v_base_detail_history h
JOIN analytics.v_base_content c USING (content_id);
```

- [ ] **Step 4: 하니스 전체 실행 — ALL GREEN**

- [ ] **Step 5: Commit** — `feat(analytics): 지표 스냅샷 이력 서빙 뷰 (v_content_metric_snapshots)`

---

### Task 3: 계약 record + Flyway DDL + 스키마 대조 테스트 (TDD)

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/ContentMetricSnapshot.java`
- Modify: `analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V4__content_metric_snapshots.sql`

- [ ] **Step 1: record 작성** (순수 JDK)

```java
package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 지표 스냅샷 1행 (미러: analytics.v_content_metric_snapshots → content_metric_snapshots).
 * 게시물 × 수집 시점 1행 — as-of 조회·추이의 재료. id = raw_post_detail의 id.
 * hypeScore: 릴스=조회수, 피드=좋아요+댓글 (Content.hypeScore와 동일 규칙).
 */
public record ContentMetricSnapshot(Long id, String shortCode, OffsetDateTime capturedAt,
		Long views, Long likes, Long comments, Long hypeScore) {
}
```

- [ ] **Step 2: FlywaySchemaTest에 테스트 추가** (import에 `ContentMetricSnapshot` 추가)

```java
	@Test
	void content_metric_snapshots_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("content_metric_snapshots", ContentMetricSnapshot.class);
	}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인** (`./gradlew :analytics:test --tests '*FlywaySchemaTest*'` → 새 테스트만 FAIL: 테이블 없음)

- [ ] **Step 4: V4__content_metric_snapshots.sql 작성**

```sql
-- 지표 스냅샷 이력 미러 테이블 (ARCHITECTURE.md §4-3). 컬럼 이름·순서 = v_content_metric_snapshots = ContentMetricSnapshot.
-- id = raw_post_detail의 id (자연키 PK). FK 없음 — contents와는 short_code 논리 참조만 (TRUNCATE와 충돌 방지).
CREATE TABLE content_metric_snapshots (
    id          bigint PRIMARY KEY,
    short_code  text NOT NULL,
    captured_at timestamptz NOT NULL,
    views       bigint,
    likes       bigint,
    comments    bigint,
    hype_score  bigint
);
-- as-of 조회 경로: 게시물별 시점 범위 스캔
CREATE INDEX idx_content_metric_snapshots_code_captured ON content_metric_snapshots (short_code, captured_at);
```

- [ ] **Step 5: 테스트 실행 — 통과 확인** (FlywaySchemaTest 4개 전부)

- [ ] **Step 6: Commit** — `feat(analytics): 지표 스냅샷 계약 record + 미러 테이블 DDL + 스키마 대조`

---

### Task 4: 미러 등록 + E2E

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java`

- [ ] **Step 1: 등록부에 추가** (import `ContentMetricSnapshot` 추가, 주석 "3종"→"4종")

```java
				new MirrorSpec<>("analytics.v_content_metric_snapshots", "content_metric_snapshots",
						ContentMetricSnapshot.class)));
```

- [ ] **Step 2: 빌드+전체 테스트** — `./gradlew :analytics:build` → BUILD SUCCESSFUL

- [ ] **Step 3: E2E 스모크 — 실제 미러 실행**

```bash
./gradlew :analytics:bootRun 2>&1 | grep -E "mirrored|mirror complete|APPLICATION FAILED"
```
Expected: `mirrored N rows: analytics.v_content_metric_snapshots -> content_metric_snapshots` 포함, `mirror complete (4 targets)` (N ≥ contents 행 수, 에러 없음)

analysis DB 확인:

```bash
docker exec -i crawler-postgres-1 psql -U crawler -d analysis -c \
  "SELECT short_code, count(*) snapshots, min(captured_at), max(captured_at) FROM content_metric_snapshots GROUP BY short_code ORDER BY snapshots DESC LIMIT 5;"
```
Expected: 게시물별 스냅샷 수가 보이고, 중복 크롤링된 게시물은 2개 이상

- [ ] **Step 4: Commit** — `feat(analytics): content_metric_snapshots 미러 등록 — B1 잔여분 개통`

---

### Task 5: 문서 갱신

**Files:**
- Modify: `analytics/README.md` (02 뷰 목록·미러 대상에 스냅샷 추가, base 뷰 "4종"→"5종")
- Modify: `ARCHITECTURE.md` (§5 운영 상태·§7 결정 기록 한 줄)
- Modify: `docs/superpowers/plans/2026-07-13-task-b1-snapshot-mirror.md` (상태 헤더 ✅)

- [ ] **Step 1: README 갱신** — `00_base.sql` 설명 "base 뷰 4종"→"base 뷰 5종", 02 행에 `v_content_metric_snapshots` 추가("서빙 형태 뷰 4종"), 미러 대상에 content_metric_snapshots 추가

- [ ] **Step 2: ARCHITECTURE.md 갱신** — §7 결정 기록 맨 위에 한 줄(2026-07-13, 스냅샷 이력 미러 개통 — as-of 조회 재료, 근거 링크 = 이 계획). §5 B1 행은 이미 ✅이므로 내용에 스냅샷 포함을 명시.

- [ ] **Step 3: 최종 검증** — `cd analytics && ./test/run.sh && cd .. && ./gradlew :analytics:build -q`

- [ ] **Step 4: Commit** — `docs: B1 잔여분 완료 반영 (지표 스냅샷 미러)`

---

## 완료 기준 (DoD)

- SQL 하니스 ALL GREEN (00·01·02·03)
- FlywaySchemaTest 4개 + 기존 Java 테스트 전부 통과, `:analytics:build` 그린
- bootRun 1회로 analysis DB `content_metric_snapshots`가 실데이터로 채워짐 (mirror complete (4 targets))
- was가 이어받을 계약: `ContentMetricSnapshot` record + `content_metric_snapshots` 테이블

## 다루지 않는 것

- as-of 선택 규칙(기간 끝 시점 vs 기간 내 최신) — 태스크 D(API) 소관 (스펙 §10)
- was 모듈 일체 (다른 세션 작업 중)
- 추이 그래프 UI — 데이터만 보존 (기획 미결)
