# 콘텐츠 AI 분석 2단계 분리 구현 계획

> 상태: ✅ 실행됨 (2026-09-03)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 게시물 업로드 후 4일간 광고 판정(`content_analyses.ad_type`)·카테고리가 비어 보이는 문제를 없앤다. 통합 LLM 1콜을 파트 A(사실 추출, 캡션만 의존)와 파트 B(해석 5필드, 3일 고정 지표·기준선 인용)로 쪼개, 파트 A는 D+1 새벽 배치로 먼저 채우고 파트 B만 성숙(D+4) 후 채운다. 전환·롤백은 `app_setting` 키 `analytics.analyze-mode`(`unified` 기본 / `split`) 한 줄이며, 토글을 켜기 전까지 운영 행동은 100% 현행과 동일해야 한다.

**Architecture:** 3티어 경계(ARCHITECTURE §2·§4-4)를 그대로 지킨다. 후보 자격 판정은 raw DB의 분석 뷰(`analytics/views/04_analysis_candidates.sql`)가 정본이고, 제외 게이트(행 존재·댓글 미분류·파트 B 완료)는 analysis DB 상태라 Java 셋 대조로 남는다. 후보 뷰를 둘로 나눈다: 새 `analytics.v_fact_candidates`(성숙 무관, `mature` 컬럼 노출)가 파트 A 입구이고, 기존 `analytics.v_analysis_candidates`는 그 위의 `WHERE mature` 투영으로 재정의해 컬럼·행 집합·`timely` 의미를 현행과 동치로 고정한다(기존 소비자 무수정). Java는 새 클래스를 만들지 않고 `ContentAnalysisJob`에 `Phase`(UNIFIED / FACTS / SYNTHESIS) 축을 추가한다 - 후보 조회·기준선 로딩·청크 분할·배치 제출·429 이월 배관이 이미 거기 있고 파트 B 배치도 같은 배관을 탄다. `content_analyses` 한 행의 상태는 컬럼 추가 없이 `metric_timeliness`의 신규 어휘 `'pending'`(파트 A만 채워짐)으로 표현하고, 파트 B 수거가 같은 행을 `timely` / `late_backfill`로 UPDATE한다. 배치 수거가 응답 스키마를 알아야 하므로 `content_batch_jobs.kind`(`analyze` / `facts` / `synthesis`)로 파서를 분기한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Gradle 멀티모듈(crawler / analytics / was / monitoring), PostgreSQL 16(로컬 테스트) · 17(was 통합 테스트), Flyway(버전 공간 4개 분리 소유), JdbcTemplate(analytics) / JdbcClient(was), Jackson 3(`tools.jackson.*`), Testcontainers 2.x(`org.testcontainers.postgresql.PostgreSQLContainer`), JUnit 5 + AssertJ + Mockito, Thymeleaf 어드민(analytics `/ui`), Vertex AI Batch(gemini-3.1-flash-lite), SQL 하니스(`analytics/test/run.sh`).

---

## 사전 준비 (모든 태스크 공통)

작업 루트는 워크트리다. 아래 셸 변수를 매 세션 첫 명령으로 export 한다 - Bash 툴은 턴마다 cwd가 리셋되므로 **모든 경로는 절대 경로**로 쓴다.

```bash
export REPO=/Users/woomin/Project/hypenow-backend/.claude/worktrees/serene-lederberg-bc067a
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```

`DOCKER_HOST`가 없으면 Testcontainers 테스트가 대량 실패한다(07-30 실측: `:was:test` 714개 중 331개 실패). 테스트 코드 결함으로 오진하기 쉬우니 대량 실패를 보면 이것부터 확인한다. colima는 8 CPU / 12 GiB 이상으로 기동한다(`colima stop && colima start --cpu 8 --memory 12`).

SQL 하니스(`analytics/test/run.sh`)는 실데이터 postgres 컨테이너가 필요하다. 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르므로 다르면 `PG_CONTAINER`로 오버라이드한다.

```bash
docker ps --format '{{.Names}}' | grep postgres      # 이름 확인
export PG_CONTAINER=crawler-postgres-1               # 기본값. 다르면 위 출력값으로
```

전체 `./gradlew test`(4개 모듈 동시 Testcontainers)는 **PR 직전 최종 확인 1회만** 돌린다. 그 외에는 모듈 단위 또는 클래스 단위로 돈다.

---

## 파일 구조

### 신규 파일

| 경로 | 책임 |
|---|---|
| `analytics/src/main/resources/db/migration/analysis/V<UTC>__content_analyses_timeliness_pending.sql` | `metric_timeliness` CHECK에 `'pending'` 추가 + `pending` 부분 인덱스 |
| `analytics/src/main/resources/db/migration/analysis/V<UTC>__content_batch_jobs_kind.sql` | `content_batch_jobs.kind` 컬럼(`analyze` 기본) 추가 |
| `crawler/src/main/resources/db/migration/V<UTC>__analytics_analyze_mode.sql` | raw `app_setting`에 `analytics.analyze-mode` = `unified` 기준값 시드 |
| `analytics/src/main/java/com/celfit/analytics/llm/ContentFactsPort.java` | 파트 A 전용 LLM 포트(캡션·유료 파트너십 태그 입력 → `ContentAttributes`) |
| `analytics/src/main/java/com/celfit/analytics/analyze/StoredFacts.java` | 저장된 파트 A 산출물을 파트 B 프롬프트 입력 맵으로 옮기는 단일 원천(키 목록·조회) |
| `analytics/src/test/java/com/celfit/analytics/mirror/ContentAnalysesTimelinessTest.java` | 마이그레이션 계약: CHECK가 `'pending'` 허용·어휘 밖 거부, 부분 인덱스 존재 |
| `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisWriterTest.java` | `insertFacts` → "A만" 상태 → `updateSynthesis` → "A+B" 상태 전이 계약 |
| `docs/contracts/v1-content-report-nullable-fields.md` | 6.3 드로어 응답의 null 가능 필드 계약(FE 통지용) |

### 수정 파일

| 경로 | 변경 요지 |
|---|---|
| `analytics/views/04_analysis_candidates.sql` | `v_fact_candidates` 신설(배리어 유지·`mature` 컬럼 승격), `v_analysis_candidates`를 그 위의 `WHERE mature` 투영으로 재정의 |
| `analytics/test/04_analysis_candidates.test.sql` | 기존 기대값 전량 유지 + `v_fact_candidates` 케이스·`mature` 경계·컬럼 대조 추가 |
| `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java` | `analytics.analyze-mode` 키·`analyzeMode()`·`splitAnalyzeMode()` 추가 |
| `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java` | 파트 A 블록 상수화(`FACTS_RULES`), `factsInstructions()`·`RESPONSE_SCHEMA_FACTS`·`userTextFacts()`·`parseFacts()` 추가, `ContentFactsPort` 구현 |
| `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentSynthesizer.java` | `RESPONSE_SCHEMA`·`MAX_OUTPUT_TOKENS` 가시성을 public으로(배치 라인 조립이 다른 패키지에서 읽는다) |
| `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java` | `contentFactsPort` 빈 배선(Gemini/Vertex 전용) |
| `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisWriter.java` | `insertFacts()` 신설, `updateSynthesis()`에 `metricTimeliness` 파라미터 추가 |
| `analytics/src/main/java/com/celfit/analytics/analyze/GeminiBatchLines.java` | `factsRequestLine`·`synthesisRequestLine`·`processFactsResultLine`·`processSynthesisResultLine` 추가 |
| `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` | `Phase` 축, `resolveTargets(phase, timely)`, `runFacts()`, phase별 제출·온라인 루프, `kind` 기록 |
| `analytics/src/main/java/com/celfit/analytics/analyze/ContentBatchCollectJob.java` | `kind`로 수거 파서 분기(`analyze` / `facts` / `synthesis`) |
| `analytics/src/main/java/com/celfit/analytics/analyze/ContentSynthesisRefreshJob.java` | 대상 SQL에 pending 제외 가드 추가, `updateSynthesis` 호출에 기존 마킹 전달, 사실 조립을 `StoredFacts`로 위임 |
| `analytics/src/main/java/com/celfit/analytics/admin/JobName.java` | `FACT_ANALYZE` 추가 |
| `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java` | `FACT_ANALYZE` 디스패치 + `DERIVED_INPUT_JOBS` 편입 |
| `analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java` | `analytics.schedule.fact-analyze-cron` 스케줄 훅 |
| `analytics/src/main/java/com/celfit/analytics/admin/ScheduleInfo.java` | `FACT_ANALYZE` 다음 발화 시각 계산 |
| `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java` | `scheduleInfo` 빈에 fact-analyze 크론 주입 |
| `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java` | 잡 카드에 `FACT_ANALYZE` 추가, 퍼널 뷰에 "사실만(pending)" 수치 |
| `analytics/src/main/java/com/celfit/analytics/admin/PipelineStatsService.java` | 후보 스캔을 `v_fact_candidates` 1회로 통합, 트랙별 "사실만" 분리, 파트 B 미완을 잔여로 계산 |
| `analytics/src/main/resources/templates/fragments/board.html` | 콘텐츠 보드에 "사실만" 칩 |
| `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` | `ContentAnalysisJob`에 facts 진행률·파트 A/파트 B 포트 주입 |
| `analytics/src/main/java/com/celfit/analytics/coverage/CoverageRepository.java` | 드로어 카피 3종·댓글 신뢰도 분모를 "해석 단계까지 온 행"으로 한정 |
| `analytics/check/pending.sql` | `'pending'`을 "사실만·해석 대기" 상태 코드로 분리(11 / 24 / 34 / 42) |
| `analytics/check/coverage.sql` | `CoverageRepository`와 쌍이므로 동일 변경 |
| `deploy/compose.yaml` | `ANALYTICS_SCHEDULE_FACT_ANALYZE_CRON` 신설 + ANALYZE 크론 30분 뒤로 |
| `deploy/README.md` | §17 적용·롤백·첫날 관찰 런북 |
| `was/src/test/java/com/celfit/was/v1/content/V1ContentRepositoryTest.java` | `'pending'` 행이 6.1 랭킹에서 제외되는 케이스 |
| `was/src/test/java/com/celfit/was/v1/content/V1ContentReportRepositoryTest.java` | `'pending'` 행이 6.3 조회에 잡히고 카테고리 표본에서는 빠지는 케이스 |
| `was/src/test/java/com/celfit/was/v1/content/V1ContentReportAssemblerTest.java` | `'pending'` 행은 비교 블록·백분위가 null인 케이스 |
| `docs/superpowers/specs/2026-09-03-content-analysis-two-phase-split-design.md` | 마무리 PR에서 상태 헤더를 구현됨으로 |

---

## Task 1: 마이그레이션 3종 (analysis CHECK 확장 · `content_batch_jobs.kind` · crawler `analyze-mode` 시드)

전부 expand 단계다. 롤링 창에서 구 코드는 `'pending'`을 쓰지 않고, 읽어도 `= 'timely' OR IS NULL` 필터의 제외 분기로 떨어진다. `kind`는 DEFAULT `'analyze'`라 구 코드가 넣은 pending 행을 신 수거 잡이 통합 파서로 처리한다.

**Files:**
- `analytics/src/main/resources/db/migration/analysis/V<UTC>__content_analyses_timeliness_pending.sql` (신규)
- `analytics/src/main/resources/db/migration/analysis/V<UTC>__content_batch_jobs_kind.sql` (신규)
- `crawler/src/main/resources/db/migration/V<UTC>__analytics_analyze_mode.sql` (신규)
- `analytics/src/test/java/com/celfit/analytics/mirror/ContentAnalysesTimelinessTest.java` (신규)

### 채번

- [ ] 현재 각 버전 공간의 최대값을 확인한다. 실행 시점 기준 최대값은 analysis가 `V20260901075954__peer_axis_gmed_initplan.sql`, crawler가 `V20260827090717__influencer_home_living.sql`이다. 정수 연번(`V1`~`V49`)은 Flyway가 숫자로 비교하므로 14자리 타임스탬프보다 항상 작다 - 신경 쓸 필요 없고 **rename은 절대 금지**다.

```bash
ls $REPO/analytics/src/main/resources/db/migration/analysis/ | sort | tail -3
ls $REPO/crawler/src/main/resources/db/migration/ | sort | tail -3
```

- [ ] 파일명에 쓸 UTC 타임스탬프를 **실행 시점에** 만든다. KST 채번은 미래 번호 선점으로 뒤따르는 정상 채번을 전부 Flyway out-of-order 거부에 빠뜨린다(08-12 운영 크래시루프 2회). CI 가드 v4가 UTC+1h 초과를 차단한다.

```bash
date -u +%Y%m%d%H%M%S     # 예: 20260903061500. 아래 세 파일에 1분 간격으로 세 값을 쓴다
```

세 파일은 서로 다른 버전 공간(analysis 2개는 같은 공간)이므로 analysis 두 파일만 순서를 가진다. `date -u`를 세 번 호출해 얻은 값을 각각 `TS1`(CHECK) `TS2`(kind) `TS3`(crawler 시드)로 두고 아래에서 `<TS1>` 자리에 넣는다.

### 실패하는 테스트 먼저

- [ ] `analytics/src/test/java/com/celfit/analytics/mirror/ContentAnalysesTimelinessTest.java`를 만든다. `FlywaySchemaTest`와 같은 패턴(공유 컨테이너 + `TestDb.resetAndMigrate`)이다.

```java
package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.celfit.analytics.testsupport.TestDb;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * content_analyses의 지표 시점 어휘 계약(2026-09-03 2단계 분리 §4-2).
 * 파트 A 행의 시점 값 'pending'을 CHECK가 허용해야 하고, 어휘 밖 값은 계속 거부해야 한다.
 * NULL을 쓰면 랭킹 6.1·카테고리 벤치마크 6.3이 레거시 timely로 취급해 미성숙 지표가 노출되고,
 * 'immature'를 재사용하면 어드민 퍼널의 종결 상태 집계가 오염된다 - 그래서 신규 어휘다.
 */
class ContentAnalysesTimelinessTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
	}

	private static void insertWithTimeliness(String shortCode, String timeliness) {
		db.update("INSERT INTO content_analyses (short_code, model, metric_timeliness) VALUES (?, ?, ?)",
				shortCode, "test-model", timeliness);
	}

	@Test
	void pending_어휘를_허용한다() {
		insertWithTimeliness("ca_pending", "pending");

		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'ca_pending'",
				String.class));
	}

	@Test
	void 기존_어휘_3종도_그대로_허용한다() {
		insertWithTimeliness("ca_timely", "timely");
		insertWithTimeliness("ca_late", "late_backfill");
		insertWithTimeliness("ca_immature", "immature");

		assertEquals(3L, db.queryForObject("""
				SELECT count(*) FROM content_analyses
				WHERE short_code IN ('ca_timely', 'ca_late', 'ca_immature')""", Long.class));
	}

	@Test
	void 어휘_밖_값은_거부한다() {
		assertThrows(DataIntegrityViolationException.class,
				() -> insertWithTimeliness("ca_bogus", "processing"));
	}

	@Test
	void pending_부분_인덱스가_존재한다() {
		// 파트 B 후보를 '후보 ∩ pending' 포함 집합으로 좁히는 조회가 이 인덱스에 기댄다
		assertEquals(1L, db.queryForObject("""
				SELECT count(*) FROM pg_indexes
				WHERE schemaname = 'public' AND tablename = 'content_analyses'
				  AND indexname = 'idx_content_analyses_timeliness_pending'""", Long.class));
	}

	@Test
	void content_batch_jobs_kind_기본값은_analyze다() {
		// 롤링 창·롤백 직후 구 코드가 제출한 pending 행은 통합 파서로 처리돼야 한다
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count)
				VALUES ('batches/legacy', true, 1)""");

		assertEquals("analyze", db.queryForObject(
				"SELECT kind FROM content_batch_jobs WHERE batch_name = 'batches/legacy'", String.class));
	}

	@Test
	void content_batch_jobs_kind는_어휘_3종만_허용한다() {
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, kind)
				VALUES ('batches/facts', false, 1, 'facts')""");
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, kind)
				VALUES ('batches/synth', true, 1, 'synthesis')""");

		assertThrows(DataIntegrityViolationException.class, () -> db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, kind)
				VALUES ('batches/bogus', true, 1, 'unified')"""));
	}
}
```

- [ ] 실패를 확인한다. `pending_어휘를_허용한다`는 CHECK 위반으로, `pending_부분_인덱스가_존재한다`는 0건으로, `content_batch_jobs_kind_*` 2건은 컬럼 부재로 실패한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.mirror.ContentAnalysesTimelinessTest"
```

기대 실패: `6 tests completed, 4 failed` 안팎. 메시지에 `new row for relation "content_analyses" violates check constraint "content_analyses_metric_timeliness_check"`와 `column "kind" of relation "content_batch_jobs" does not exist`가 보이면 정상이다.

### 마이그레이션 작성

- [ ] `analytics/src/main/resources/db/migration/analysis/V<TS1>__content_analyses_timeliness_pending.sql`

```sql
-- 지표 시점 어휘에 'pending' 추가 (2026-09-03 콘텐츠 분석 2단계 분리 설계 §4-2).
--   pending = 파트 A(사실)만 채워진 행. 파트 B(해석 5필드 + 기준선 스냅샷)가 아직 없어
--             "지표 시점 미확정"이며, 파트 B 수거가 timely / late_backfill로 확정한다.
-- 왜 신규 어휘인가:
--   NULL       : 랭킹 6.1·카테고리 벤치마크 6.3이 `= 'timely' OR IS NULL`로 레거시 timely 취급 →
--                미성숙 지표 행이 랭킹에 들어가 하향 편향. V33이 막으려던 바로 그 사고다.
--   'immature' : V33 정의는 "가드 도입 전 영구 고정 누수"라는 종결 상태다. 전이 상태로 재사용하면
--                어드민 퍼널 immaturePool·check/pending.sh의 레거시 집계가 오염된다.
-- expand 단계: 구 코드는 이 값을 쓰지 않고, 읽어도 제외 분기로 떨어진다.
-- 파괴 패턴(DROP TABLE/COLUMN·RENAME·타입 변경·SET NOT NULL) 아님 - allow-destructive 불요.
-- 제약 이름을 하드코딩하지 않는 이유: V33이 ADD COLUMN 인라인 CHECK로 만들어 이름이 자동
-- 생성됐다. 이름이 다르면 DROP IF EXISTS가 조용히 통과하고 구 CHECK가 살아남아 'pending'이
-- 계속 거부되는데, 마이그레이션은 성공으로 기록돼 원인 추적이 어려워진다. 못 찾으면 실패시킨다.
DO $$
DECLARE constraint_name text;
BEGIN
  SELECT conname INTO constraint_name
  FROM pg_constraint
  WHERE conrelid = 'content_analyses'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) LIKE '%metric_timeliness%';
  IF constraint_name IS NULL THEN
    RAISE EXCEPTION 'metric_timeliness CHECK 제약을 찾지 못했다 - V33 형상 확인 필요';
  END IF;
  EXECUTE format('ALTER TABLE content_analyses DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE content_analyses
    ADD CONSTRAINT content_analyses_metric_timeliness_check
    CHECK (metric_timeliness IN ('timely', 'late_backfill', 'immature', 'pending'));

-- 파트 B 후보 좁히기 전용 부분 인덱스 - 잡이 "후보 ∩ pending" 포함 집합을 매 실행 읽는다.
-- V38의 idx_content_analyses_synthesis_stale과 같은 관용구(부분 인덱스로 대상만 좁게).
CREATE INDEX idx_content_analyses_timeliness_pending
    ON content_analyses (short_code) WHERE metric_timeliness = 'pending';
```

- [ ] `analytics/src/main/resources/db/migration/analysis/V<TS2>__content_batch_jobs_kind.sql`

```sql
-- 배치 제출 종류 구분 (2026-09-03 2단계 분리 설계 §4-5).
-- 수거 잡(ContentBatchCollectJob)이 응답 스키마를 알아야 파서를 고를 수 있다:
--   analyze   : 통합 1콜(레거시·롤백 경로) → ContentAnalysisWriter.insert
--   facts     : 파트 A            → ContentAnalysisWriter.insertFacts (metric_timeliness='pending')
--   synthesis : 파트 B            → ContentAnalysisWriter.updateSynthesis (사이드카 timely로 마킹)
-- DEFAULT 'analyze': 롤링 창·롤백 직후에 구 코드가 남긴 pending 행을 신 수거 잡이 통합 파서로
-- 처리한다(구 코드는 이 컬럼을 모르므로 INSERT 목록에 넣지 않는다).
-- timely 컬럼은 facts에서 의미가 없다 - false 고정으로 넣고 수거가 무시한다. NOT NULL 완화는
-- contract 단계 얘기라 하지 않는다.
ALTER TABLE content_batch_jobs
    ADD COLUMN kind text NOT NULL DEFAULT 'analyze';

ALTER TABLE content_batch_jobs
    ADD CONSTRAINT content_batch_jobs_kind_check
    CHECK (kind IN ('analyze', 'facts', 'synthesis'));
```

- [ ] `crawler/src/main/resources/db/migration/V<TS3>__analytics_analyze_mode.sql`

`app_setting` 테이블 자체가 crawler Flyway 소관이라 시드도 여기 둔다(V16 확립). `ON CONFLICT DO NOTHING`이라 운영 런타임 오버라이드를 되돌리지 않는다.

```sql
-- 콘텐츠 분석 단계 분리 토글 기준값 (2026-09-03 설계 §4-6).
--   unified(기본) : 현행 통합 1콜. FACT_ANALYZE 잡은 no-op 로그만 남기고 끝난다.
--   split         : 파트 A(FACT_ANALYZE, 성숙 무관 D+1)와 파트 B(ANALYZE / LATE_BACKFILL_ANALYZE,
--                   성숙 후)로 분리. 잡 시작마다 읽으므로 재기동 없이 전환·롤백된다.
-- 기본값을 unified로 두는 이유: 배포 자체로는 운영 행동이 하나도 바뀌지 않게 하고, 골드셋 대조
-- (파트 A 정확도 회귀 확인) 후 UPDATE 한 줄로 켠다. 롤백도 같은 한 줄이다.
INSERT INTO app_setting (key, value)
VALUES ('analytics.analyze-mode', 'unified')
ON CONFLICT (key) DO NOTHING;
```

### 검증·커밋

- [ ] 테스트가 통과하는지 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.mirror.ContentAnalysesTimelinessTest"
```

기대: `BUILD SUCCESSFUL`, 6개 테스트 전부 통과.

- [ ] crawler 마이그레이션이 적용되는지 확인한다(crawler 모듈 테스트가 Flyway 전량 재생을 돈다).

```bash
cd $REPO && ./gradlew :crawler:test --tests "*AppSetting*"
```

기대: `BUILD SUCCESSFUL`. 매칭되는 테스트가 없으면 `NO-SOURCE` 대신 실패가 나므로, 그 경우 아래로 대체한다.

```bash
cd $REPO && ./gradlew :crawler:test
```

기대: `BUILD SUCCESSFUL` (Flyway 마이그레이션 파싱 오류가 있으면 컨텍스트 로딩에서 죽는다).

- [ ] CI 가드를 로컬에서 미리 돌려 채번·파괴 패턴을 확인한다.

```bash
cd $REPO && bash .github/scripts/check-migration-safety.sh
```

기대: 에러 없음. `미래 시각 채번` 에러가 나면 UTC가 아니라 KST로 채번한 것이니 `date -u`로 다시 만들어 rename한다.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/resources/db/migration/analysis crawler/src/main/resources/db/migration analytics/src/test/java/com/celfit/analytics/mirror/ContentAnalysesTimelinessTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): 지표 시점 어휘 pending·배치 kind 컬럼·analyze-mode 시드 추가

콘텐츠 분석 2단계 분리(파트 A 사실 D+1 · 파트 B 해석 D+4)의 스키마 선행분.
CHECK 확장은 제약 이름을 동적으로 찾아 교체한다 - V33이 인라인 CHECK로 만들어
이름이 자동 생성이라, 하드코딩하면 이름이 다를 때 구 CHECK가 조용히 살아남는다.
전부 expand 단계로 구 코드와 호환되며, analyze-mode 기본값 unified라 행동 변화 0.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `AnalyticsSettings.analyzeMode()` (split | unified)

`analyzeTransport()`와 같은 규약이다 - 캐시 없이 잡 시작마다 읽어 재기동 없이 전환된다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java` (키 상수는 63행 `KEY_ACCOUNT_ANALYZE_TRANSPORT` 뒤, 기본값 상수는 82행 `DEFAULT_ACCOUNT_ANALYZE_TRANSPORT` 뒤, 메서드는 189행 `accountBatchTransportEnabled()` 뒤)
- `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (541행 `전송_방식_기본은_online이고_app_setting으로_batch_전환된다` 뒤에 케이스 추가)

### 실패하는 테스트

- [ ] `ContentAnalysisJobTest`에 설정 케이스를 추가한다. 이 클래스에 이미 `프로바이더_기본은_gemini고_app_setting으로_롤백된다`·`전송_방식_기본은_online이고...` 같은 설정 계약 테스트가 모여 있다.

```java
	@Test
	void 분석_모드_기본은_unified고_app_setting으로_split_전환된다() {
		AnalyticsSettings settings = new AnalyticsSettings(db);
		assertEquals("unified", settings.analyzeMode());
		assertFalse(settings.splitAnalyzeMode());

		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-mode', 'split')");
		assertEquals("split", settings.analyzeMode());
		assertTrue(settings.splitAnalyzeMode());
	}
```

- [ ] 컴파일 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"
```

기대 실패: 컴파일 에러 `cannot find symbol: method analyzeMode()`.

### 구현

- [ ] 키 상수를 추가한다(`KEY_ACCOUNT_ANALYZE_TRANSPORT` 선언 바로 아래).

```java
	/**
	 * 콘텐츠 분석 단계 분리 토글 - unified(기본, 통합 1콜) | split(파트 A 사실 / 파트 B 해석 분리).
	 * 2026-09-03 2단계 분리 설계. 전송 토글(analytics.analyze-transport)과 독립이며,
	 * 잡 실행 시점마다 매번 읽으므로 재기동 없이 전환된다. 롤백은 값을 unified로 되돌리는 UPDATE 한 줄.
	 */
	public static final String KEY_ANALYZE_MODE = "analytics.analyze-mode";
```

- [ ] 기본값 상수를 추가한다(`DEFAULT_ACCOUNT_ANALYZE_TRANSPORT` 선언 바로 아래).

```java
	static final String DEFAULT_ANALYZE_MODE = "unified";
```

- [ ] 리더 메서드를 추가한다(`accountBatchTransportEnabled()` 바로 아래).

```java
	/** 잡 실행 시점마다 매번 읽는다(캐시 없음) - 재기동 없이 unified↔split 전환. */
	public String analyzeMode() {
		return read(KEY_ANALYZE_MODE).orElse(DEFAULT_ANALYZE_MODE);
	}

	/**
	 * true면 콘텐츠 분석이 파트 A(FACT_ANALYZE)와 파트 B(ANALYZE / LATE_BACKFILL_ANALYZE)로 갈린다.
	 * false(기본)면 현행 통합 1콜이고 FACT_ANALYZE는 no-op이다.
	 */
	public boolean splitAnalyzeMode() {
		return "split".equals(analyzeMode());
	}
```

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"
```

기대: `BUILD SUCCESSFUL`, 신규 케이스 포함 전량 통과.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): analytics.analyze-mode 런타임 토글(unified|split) 리더 추가

analyze-transport와 같은 규약 - 캐시 없이 잡 시작마다 읽어 재기동 없이 전환·롤백된다.
기본값 unified라 이 커밋만으로는 행동이 바뀌지 않는다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 후보 뷰 04 분할 (`v_fact_candidates` + `v_analysis_candidates`)

현재 04는 안쪽 서브쿼리(`OFFSET 0` 배리어) 안에서 캡션·성숙 가드를 WHERE로 자르고 `timely`·`in_window`를 계산한 뒤, 바깥에서 `timely OR in_window`를 건다. 성숙 WHERE를 `mature boolean` 컬럼으로 승격하고(식은 동일), 바깥 조건을 `NOT mature OR timely OR in_window`로 바꾼 것이 `v_fact_candidates`다. `v_analysis_candidates`는 그 위의 `WHERE mature` 투영이라 결과가 `mature AND (timely OR in_window)`로 현행과 정확히 동치다.

**왜 "성숙 무관 전량"이 아닌가:** 성숙했는데 timely도 아니고 최근 12 윈도우 밖인 게시물은 현행에서도 영구 제외 대상이다. 이걸 파트 A에 열면 운영 백로그(계정당 12개 밖 옛 게시물 수만 건)가 한 번에 후보가 된다. 신규 게시물은 D+1에 `recency_rank`=1이라 `NOT mature`로 잡히므로, "제때창을 놓쳐 영구 제외되던 게시물"은 이 규칙만으로 전부 파트 A가 채워진다. 옛 백로그 개방은 별도 결정이다.

**Files:**
- `analytics/views/04_analysis_candidates.sql` (76~149행을 아래 두 CREATE 문으로 교체. 1~74행 = 헤더 주석 + `v_analysis_source` 정의는 **한 글자도 바꾸지 않는다**)
- `analytics/test/04_analysis_candidates.test.sql` (69행 첫 `DO $$` 블록 종료 직후에 블록 1개 삽입, 132행 `recent-window=1` 블록 안에 단언 1개 추가)

### 실패하는 테스트 먼저

- [ ] `analytics/test/04_analysis_candidates.test.sql`의 69행(`END $$;`) 바로 다음에 아래 블록을 삽입한다. 이 위치는 `app_setting` 변조 전(slack=1, recent-window=12 기본)이라 기본 설정 케이스를 검증할 수 있다. 파일 전체가 `run.sh`에 의해 BEGIN/ROLLBACK으로 감싸이므로 이후 블록들의 설정 변조가 여기 영향을 주지 않는다.

```sql
-- ── 2026-09-03 2단계 분리: 파트 A 입구 뷰(v_fact_candidates) ─────────────────────
-- 파트 A(사실 추출)는 캡션만 의존하므로 성숙(제때창 완전 경과)을 기다릴 이유가 없다.
-- v_fact_candidates = 04 배리어 안쪽은 동일하고, 성숙 가드를 WHERE에서 mature 컬럼으로
-- 승격한 뒤 바깥 조건을 `NOT mature OR timely OR in_window`로 넓힌 뷰다.
-- v_analysis_candidates(파트 B 입구)는 그 위의 `WHERE mature` 투영이라 기존과 동치다.
DO $$
BEGIN
  -- ① 미성숙 신규 게시물이 파트 A 후보에 든다 (이 트랙의 목표).
  --    dummy_rn은 어제 업로드라 제때창이 아직 안 닫혔다 - 현행 v_analysis_candidates에선 제외다.
  ASSERT EXISTS (SELECT 1 FROM analytics.v_fact_candidates WHERE short_code = 'dummy_rn'),
    'rn(미성숙 신규)이 v_fact_candidates에 없음 - 파트 A가 D+1에 못 돈다';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_rn'),
    'rn(미성숙)이 v_analysis_candidates에 새어들어옴 - 파트 B 성숙 가드 회귀';

  -- ② mature 컬럼 경계: 업로드 오늘-4는 창이 어제 닫혀 true, 오늘-3은 창이 오늘이라 false.
  --    식은 현행 성숙 가드와 동일: 업로드일(KST) + pin(3) + slack(1) <= 오늘(KST).
  ASSERT (SELECT mature FROM analytics.v_fact_candidates WHERE short_code = 'dummy_cl') IS TRUE,
    'dummy_cl(업로드 오늘-4)의 mature가 true가 아님';
  ASSERT (SELECT mature FROM analytics.v_fact_candidates WHERE short_code = 'dummy_op') IS FALSE,
    'dummy_op(업로드 오늘-3)의 mature가 false가 아님';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_op'),
    'dummy_op(미성숙)이 파트 B 후보에 있음';

  -- ③ 성숙·제때 크롤분은 양쪽 뷰에 그대로 남는다 (회귀 고정).
  ASSERT EXISTS (SELECT 1 FROM analytics.v_fact_candidates WHERE short_code = 'dummy_f1' AND timely),
    'f1(성숙·timely)이 v_fact_candidates에서 빠짐';
  -- ④ 성숙·늦크롤·윈도우 안도 그대로 남는다.
  ASSERT EXISTS (SELECT 1 FROM analytics.v_fact_candidates WHERE short_code = 'dummy_r1' AND NOT timely),
    'r1(성숙·늦크롤·윈도우 안)이 v_fact_candidates에서 빠짐';
  -- ⑤ 캡션 결측은 배리어 안쪽 WHERE라 파트 A에서도 제외된다 (LLM 입력이 없다).
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_fact_candidates WHERE short_code = 'dummy_r3'),
    'r3(캡션 결측)이 v_fact_candidates에 있음';

  -- ⑥ 건수: 파트 B 후보 6건(f1·cl·r1·r2·ra1·fb1) + 미성숙 2건(rn·op) = 8건.
  ASSERT (SELECT count(*) FROM analytics.v_fact_candidates WHERE account_handle LIKE 'dummy_%') = 8,
    'v_fact_candidates 기본 후보 != 8 (파트 B 6건 + 미성숙 rn·op)';
  -- ⑦ 항등식: v_analysis_candidates = v_fact_candidates 중 mature인 것.
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%')
       = (SELECT count(*) FROM analytics.v_fact_candidates
          WHERE account_handle LIKE 'dummy_%' AND mature),
    'v_analysis_candidates != v_fact_candidates WHERE mature (투영 정의 깨짐)';

  -- ⑧ 컬럼 계약: 기존 소비자(잡·pending.sh·어드민 퍼널)가 무수정이려면 파트 B 뷰의 컬럼이
  --    현행 그대로여야 한다. mature는 파트 A 뷰에만 노출한다.
  ASSERT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'analytics' AND table_name = 'v_fact_candidates'
                   AND column_name = 'mature'),
    'v_fact_candidates에 mature 컬럼이 없음';
  ASSERT NOT EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_schema = 'analytics' AND table_name = 'v_analysis_candidates'
                       AND column_name = 'mature'),
    'v_analysis_candidates에 mature 컬럼이 노출됨 - 기존 소비자 계약 변경';
  ASSERT (SELECT count(*) FROM information_schema.columns
          WHERE table_schema = 'analytics' AND table_name = 'v_fact_candidates')
       = (SELECT count(*) FROM information_schema.columns
          WHERE table_schema = 'analytics' AND table_name = 'v_analysis_candidates') + 1,
    'v_fact_candidates 컬럼 수가 v_analysis_candidates + 1(mature)이 아님';
END $$;
```

- [ ] 132행 부근의 `recent-window=1` 블록(`ASSERT NOT EXISTS ... dummy_pd ...`가 있는 `DO $$` 블록) 안, 기존 단언 바로 뒤에 아래를 추가한다. "성숙·늦크롤·윈도우 밖은 파트 A도 열지 않는다"가 §4-1의 핵심 판단이다.

```sql
  -- 2026-09-03: 성숙했는데 timely도 아니고 윈도우 밖인 건 파트 A도 열지 않는다.
  -- 열면 운영 백로그(계정당 12개 밖 옛 게시물 수만 건)가 한 번에 파트 A 후보가 된다.
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_fact_candidates WHERE short_code = 'dummy_pd'),
    'recent-window=1인데 성숙·늦크롤·윈도우 밖 pd가 v_fact_candidates에 있음 (옛 백로그 개방)';
```

- [ ] 하니스를 돌려 실패를 확인한다.

```bash
cd $REPO/analytics && PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1} ./test/run.sh test/04_analysis_candidates.test.sql
```

기대 실패: `ERROR:  relation "analytics.v_fact_candidates" does not exist`.

### 뷰 재작성

- [ ] `analytics/views/04_analysis_candidates.sql`의 76행부터 파일 끝(149행)까지를 아래로 교체한다. 1~74행(헤더 주석 + `v_analysis_source`)은 손대지 않는다 - 배리어·제때 가드·날짜기준 판정의 근거가 거기 적혀 있고 그대로 유효하다.

```sql
-- 파트 A(사실 추출) 입구 뷰 (2026-09-03 2단계 분리 설계 §4-1).
-- 파트 A는 캡션(+유료 파트너십 태그)만 의존하므로 성숙(제때창 완전 경과)을 기다릴 이유가 없다.
-- 구조는 구 v_analysis_candidates와 같다: 배리어(OFFSET 0) 안쪽에서 캡션 가드와
-- timely·in_window·mature를 확정하고, OR 조건은 배리어 밖에서 적용한다.
-- 달라진 점은 둘뿐이다.
--   ① 성숙 가드를 안쪽 WHERE에서 mature 컬럼으로 승격 (식은 동일: 업로드일 + pin + slack <= 오늘 KST)
--   ② 바깥 조건이 `NOT mature OR timely OR in_window`
-- 왜 "성숙 무관 전량"이 아닌가: 성숙했는데 timely도 아니고 최근 N개 윈도우 밖인 게시물은
-- 현행에서도 영구 제외 대상이다. 여기 열면 운영 백로그(계정당 12개 밖 옛 게시물 수만 건)가
-- 한 번에 후보가 된다. 신규 게시물은 D+1에 recency_rank=1이라 NOT mature로 잡히므로,
-- "제때창을 놓쳐 영구 제외되던 게시물"은 이 규칙만으로 전부 파트 A가 채워진다.
-- 플랜 주의: 미성숙 행에도 timely LATERAL(content_snapshot_cache EXISTS)이 계산된다.
-- 미성숙 행은 최근 3일치뿐이고 EXISTS는 인덱스 세미조인이라 증분은 작지만, 07-20의 배리어
-- 회귀 전례가 있으므로 운영 적용 후 EXPLAIN으로 실행 시간을 재확인할 것(기준 ~150ms 대,
-- 9초대로 튀면 배리어 무력화).
CREATE OR REPLACE VIEW analytics.v_fact_candidates AS
SELECT
  short_code,
  content_type,
  account_handle,
  uploaded_at,
  caption,
  thumbnail_url,
  followers,
  views,
  likes,
  comments,
  metric_captured_at,
  timely,
  -- 인스타 유료 파트너십 태그 - 소스 뷰가 이미 핀 스냅샷에서 들고 있어 그대로 통과시킨다
  -- (조인 추가 없음 = 플랜 불변). LLM 프롬프트에 확정 사실로 싣는 용도.
  ad_marked,
  -- 성숙(제때창 완전 경과) 여부. 파트 B 입구(v_analysis_candidates)가 이 컬럼으로 걸러진다.
  mature
FROM (
  SELECT
    v.short_code,
    v.content_type,
    v.account_handle,
    v.posted_at AS uploaded_at,
    v.caption,
    v.thumbnail_url,
    v.followers,
    v.views,
    v.likes,
    v.comments,
    v.metric_captured_at,
    v.ad_marked,
    t.timely,
    -- 최근 N개 윈도우 포함 여부도 배리어 안에서 미리 계산해 바깥 OR과 분리한다.
    -- 08-31: 01 뷰(뷰티 게이트) 위임 → 소스 뷰의 recency_rank 비교로 교체.
    (v.recency_rank <= COALESCE(
       (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12)) AS in_window,
    -- 성숙: 제때창이 완전히 지난 날인가 (업로드일 + pin + slack <= 오늘 KST).
    -- 09-03 이전엔 이 식이 안쪽 WHERE였다. 컬럼으로 승격했을 뿐 식은 한 글자도 바뀌지 않았다 -
    -- 파트 B 입구가 `WHERE mature`로 이 조건을 그대로 다시 걸어 현행과 동치를 유지한다.
    ((v.posted_at AT TIME ZONE 'Asia/Seoul')::date
       + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
       + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
     <= (now() AT TIME ZONE 'Asia/Seoul')::date) AS mature
  FROM analytics.v_analysis_source v
  CROSS JOIN LATERAL (
    SELECT EXISTS (
      -- 캡처 캘린더일(KST)이 [업로드일+pin, 업로드일+pin+slack)에 드는 usable 스냅샷이 있는가.
      -- 성능: captured_at을 행마다 date로 변환하지 않고, 캘린더일 경계를 KST 자정 timestamptz로
      -- 계산해 captured_at을 그대로 범위 비교한다(sargable - 세미조인 플랜 유지, 스냅샷 뷰 1회 계산).
      -- 캡처가 KST일 X에 든다 ⟺ [KST자정(X), KST자정(X+1)) 이므로 결과는 날짜 변환과 완전 동치.
      -- 08-31: 구 버전은 content_id를 얻으려 v_serving_content(뷰티 게이트)를 조인했다 -
      -- 그대로 두면 F&B는 timely가 영원히 false다. 소스 뷰가 content_id를 직접 들고 있어 조인이 없어졌다.
      SELECT 1
      FROM analytics.content_snapshot_cache s
      WHERE s.content_id = v.content_id
        AND s.captured_at >= (((v.posted_at AT TIME ZONE 'Asia/Seoul')::date
              + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
            )::timestamp AT TIME ZONE 'Asia/Seoul')
        AND s.captured_at <  (((v.posted_at AT TIME ZONE 'Asia/Seoul')::date
              + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
              + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
            )::timestamp AT TIME ZONE 'Asia/Seoul')
        AND s.likes IS NOT NULL AND s.comments_count IS NOT NULL
        AND (v.content_type <> 'reels' OR s.views IS NOT NULL)
    ) AS timely
  ) t
  WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
  -- 최적화 배리어: OFFSET 0은 실제로 행을 건너뛰지 않지만(0개), 플래너가 이 서브쿼리 경계를 넘어
  -- 바깥 WHERE를 안쪽 스캔까지 밀어넣지 못하게 막는다(PG 관용구).
  -- 비공식 동작(언어 계약 아님) - CTE 기본 인라인화(PG12) 전례처럼 무력화될 수 있으니
  -- PG 메이저 업그레이드 시 EXPLAIN으로 배리어 유효성 재확인할 것.
  OFFSET 0
) candidates
-- 미성숙(창이 아직 안 닫힘) 신규분 + 제때 크롤분 + 늦크롤이지만 최근 N개 윈도우 안.
-- 나머지(성숙 ∧ 늦크롤 ∧ 윈도우 밖)는 현행과 같이 영구 제외다.
WHERE NOT mature
   OR timely
   OR in_window;

-- 파트 B(해석) 입구 뷰 - 09-03 이전의 v_analysis_candidates와 컬럼·행 집합·timely 의미가
-- 정확히 동치다. `NOT mature OR timely OR in_window` ∧ `mature` = `mature ∧ (timely OR in_window)`
-- 이므로 구 정의(성숙 WHERE + 바깥 timely OR in_window)와 같은 집합이다.
-- 뷰를 둘로 나눈 이유: mature 컬럼만 추가하고 소비자가 각자 `AND mature`를 붙이게 하면
-- 기존 소비자 3곳(잡·check/pending.sh·어드민 퍼널) 중 하나만 빠뜨려도 미성숙 행이 파트 B
-- 후보로 새어 07-28 계열(수식 이원화) 사고가 재현된다. 이름의 의미를 뷰가 고정한다.
CREATE OR REPLACE VIEW analytics.v_analysis_candidates AS
SELECT
  short_code,
  content_type,
  account_handle,
  uploaded_at,
  caption,
  thumbnail_url,
  followers,
  views,
  likes,
  comments,
  metric_captured_at,
  timely,
  ad_marked
FROM analytics.v_fact_candidates
WHERE mature;
```

- [ ] 하니스를 다시 돌린다. `run.sh`는 `views/*.sql`을 파일명 순으로 먼저 적용하므로 새 뷰가 자동으로 만들어진다.

```bash
cd $REPO/analytics && PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1} ./test/run.sh test/04_analysis_candidates.test.sql
```

기대: `PASS: test/04_analysis_candidates.test.sql` 후 `ALL GREEN`.

- [ ] 다른 뷰 테스트가 04 변경으로 깨지지 않았는지 전체 하니스를 돌린다(뷰 전량 재적용 포함).

```bash
cd $REPO/analytics && PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1} ./test/run.sh
```

기대: 모든 테스트가 `PASS`, 마지막 줄 `ALL GREEN`.

- [ ] 실데이터에서 플랜을 확인한다. 07-20에 배리어가 무력화돼 152ms에서 9.5초대로 폭주한 전례가 있다. 기준은 ~150ms 대이고, 9초대가 나오면 배리어가 죽은 것이니 이 태스크를 중단하고 EXPLAIN 출력을 들고 재설계한다.

```bash
docker exec -i ${PG_CONTAINER:-crawler-postgres-1} psql -U crawler -d crawler -c \
  "EXPLAIN ANALYZE SELECT count(*) FROM analytics.v_analysis_candidates;"
docker exec -i ${PG_CONTAINER:-crawler-postgres-1} psql -U crawler -d crawler -c \
  "EXPLAIN ANALYZE SELECT count(*) FROM analytics.v_fact_candidates;"
```

기대: 두 쿼리의 `Execution Time`이 수백 ms 이내. 로컬 실데이터 컨테이너가 없으면 이 단계는 운영 적용 런북(Task 13)으로 이월하고, 이월했다는 사실을 커밋 메시지에 남긴다.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/views/04_analysis_candidates.sql analytics/test/04_analysis_candidates.test.sql
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): 분석 후보 뷰를 파트 A(v_fact_candidates)와 파트 B로 분리

성숙 가드를 안쪽 WHERE에서 mature 컬럼으로 승격하고, 파트 A 입구는
NOT mature OR timely OR in_window로 넓혔다. v_analysis_candidates는 그 위의
WHERE mature 투영이라 컬럼·행 집합·timely 의미가 현행과 동치이며 기존 소비자
(분석 잡·check/pending.sh·어드민 퍼널)는 무수정이다. OFFSET 0 배리어는 그대로 유지.

성숙·늦크롤·윈도우 밖(영구 제외)은 파트 A에도 열지 않는다 - 열면 옛 백로그가
한 번에 후보가 된다. 신규분은 D+1에 recency_rank=1이라 NOT mature로 들어온다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```
---

## Task 4: `GeminiContentAnalyzer` 파트 A 전용 프롬프트·스키마·파서

통합 프롬프트에서 파트 B 규칙과 지표·기준선·댓글 분포 입력을 뺀 경로를 만든다. **파트 A 블록 텍스트는 복제하지 않고 상수로 뽑아 통합·분리 프롬프트가 공유한다** - 07-21에 계정 카피 프롬프트가 두 클래스에 복제돼 한쪽만 고쳐진 사고가 있었고, `SYNTHESIS_RULES` 주석이 그 재발 방지 규약을 명시한다.

**통합 프롬프트의 최종 문자열은 이 태스크 후에도 바이트 단위로 동일해야 한다.** 파트 A 블록을 상수로 옮길 때 텍스트를 다시 타이핑하지 말고 기존 줄을 그대로 잘라 옮긴다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/llm/ContentFactsPort.java` (신규)
- `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java` (18행 클래스 선언, 27~59행 `RESPONSE_SCHEMA` 뒤에 `RESPONSE_SCHEMA_FACTS`, 93~138행 `instructions`, 141~153행 `userText` 뒤에 `userTextFacts`, 180~189행 `parse` 뒤에 `parseFacts`, 206~213행 `Output` record 뒤에 `FactsOutput`)
- `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java` (85행 `contentInsightPort` 빈 뒤)
- `analytics/src/test/java/com/celfit/analytics/llm/GeminiContentAnalyzerTest.java` (198행 마지막 테스트 뒤)

### 실패하는 테스트 먼저

- [ ] `GeminiContentAnalyzerTest` 끝에 아래 케이스를 추가한다. 기존 상수(`RESPONSE`·`taxonomy`·`fakeApi`·`content()`)를 그대로 쓴다. 파트 A 응답은 통합 응답에서 해석 5필드를 뺀 것이라 별도 상수를 둔다.

```java
	static final String FACTS_RESPONSE = """
			{"detectedBrands":[{"name":"브랜드A","evidence":"캡션 언급"}],
			 "sponsoredSignalLevel":"high","sponsoredSignalReasons":["#협찬"],
			 "adDisclosure":"표기 있음","detectedProductCategories":["클렌징폼"],
			 "detectedProducts":[{"name":"딥클렌징폼","brand":null}],
			 "vlmAttributes":[],"isRelevant":true,"mainCategory":"cleansing",
			 "subCategories":["클렌징폼/젤","클렌징폼"],
			 "detectedDistributors":["올리브영"],"adType":"sponsored"}""";

	/** 파트 A 스키마에는 해석 5필드가 없어야 한다 - 있으면 배치가 해석까지 만들어 D+1에 미성숙 수치를 인용한다. */
	@Test
	void 사실_스키마에는_파트B_5필드가_없다() {
		String schema = GeminiContentAnalyzer.RESPONSE_SCHEMA_FACTS;

		assertTrue(schema.contains("\"detectedBrands\""));
		assertTrue(schema.contains("\"isRelevant\""));
		assertTrue(schema.contains("\"adType\""));
		assertFalse(schema.contains("aiContentSummary"));
		assertFalse(schema.contains("contentsPattern"));
		assertFalse(schema.contains("aiCommentInsight"));
		assertFalse(schema.contains("commentAuthenticityGrade"));
		assertFalse(schema.contains("commentAuthenticityNote"));
	}

	/** 파트 A 유저 텍스트에는 지표·기준선·댓글 분포 줄이 없어야 한다(성숙 대기 이유가 이 세 줄이다). */
	@Test
	void 사실_유저텍스트에는_지표_기준선_댓글분포_줄이_없다() {
		String user = GeminiContentAnalyzer.userTextFacts(content("reels", true));

		assertTrue(user.contains("캡션A"));
		assertTrue(user.contains("인스타 유료 파트너십 태그: 있음"));
		assertFalse(user.contains("지표:"));
		assertFalse(user.contains("계정 기준선:"));
		assertFalse(user.contains("댓글 분류 분포:"));
	}

	/**
	 * Vertex 배치 출력에는 key가 없어 GeminiBatchLines가 에코된 유저 텍스트 첫 줄
	 * "콘텐츠: {shortCode} (" 에서 short_code를 복원한다. 파트 A 텍스트도 이 형식을 지켜야 한다.
	 */
	@Test
	void 사실_유저텍스트_첫줄은_에코_복원_형식을_지킨다() {
		String user = GeminiContentAnalyzer.userTextFacts(content("reels", true));

		assertTrue(user.startsWith("콘텐츠: post_a (@acct1, reels)"), user);
	}

	/** 파트 A 지시문에는 파트 B 규칙이 없고, 파트 A 규칙·분류표는 통합과 같은 문장을 공유한다. */
	@Test
	void 사실_지시문은_파트B_규칙을_빼고_파트A_규칙과_분류표는_공유한다() {
		String facts = GeminiContentAnalyzer.factsInstructions(taxonomy);
		String unified = GeminiContentAnalyzer.instructions(taxonomy);

		assertFalse(facts.contains("파트 B"), facts);
		assertFalse(facts.contains("aiContentSummary"), facts);
		assertTrue(facts.contains("detectedBrands"));
		assertTrue(facts.contains("fnb 축으로 분류하라"));
		assertTrue(facts.contains("공동구매(공구)"));
		assertTrue(facts.contains("클렌징폼/젤")); // 분류표
		// 파트 A 규칙 본문은 복제가 아니라 공유여야 한다 - 통합 프롬프트가 같은 문장을 담는다
		assertTrue(unified.contains(GeminiContentAnalyzer.FACTS_RULES
				.formatted(taxonomy.distributorsPrompt())));
	}

	/** 파트 A 파서는 속성만 돌려주고 sanitize(어휘 밖 제거·축 파생 is_beauty)를 통합과 공유한다. */
	@Test
	void parseFacts는_속성만_돌려주고_sanitize를_적용한다() {
		ContentAttributes attrs = GeminiContentAnalyzer.parseFacts(
				new tools.jackson.databind.ObjectMapper(), FACTS_RESPONSE, taxonomy);

		assertEquals("브랜드A", attrs.detectedBrands().get(0).name());
		assertEquals("cleansing", attrs.mainCategory());
		assertEquals(Boolean.TRUE, attrs.isRelevant());
		assertEquals(Boolean.TRUE, attrs.isBeauty()); // cleansing의 축이 beauty
		assertEquals(List.of("올리브영"), attrs.detectedDistributors());
	}

	/** 포트로 호출하면 파트 A 스키마·프롬프트로 1콜이 나간다(온라인 폴백 경로). */
	@Test
	void extractFacts는_파트A_스키마로_1콜을_보낸다() {
		ContentFactsPort port = new GeminiContentAnalyzer(fakeApi(FACTS_RESPONSE), () -> "m", () -> taxonomy);

		ContentAttributes attrs = port.extractFacts(content("reels", true), null);

		assertEquals(1, calls.size());
		assertFalse(calls.get(0).schema().contains("aiContentSummary"));
		assertFalse(calls.get(0).user().contains("계정 기준선:"));
		assertEquals("cleansing", attrs.mainCategory());
	}
```

`assertFalse`·`assertEquals` static import가 필요하다. 파일 상단 import 블록(3~5행)에 아래를 추가한다.

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.llm.GeminiContentAnalyzerTest"
```

기대 실패: 컴파일 에러 `cannot find symbol: RESPONSE_SCHEMA_FACTS`, `userTextFacts`, `factsInstructions`, `FACTS_RULES`, `parseFacts`, `ContentFactsPort`.

### 구현

- [ ] `analytics/src/main/java/com/celfit/analytics/llm/ContentFactsPort.java`를 만든다. 기존 `ContentAttributePort`(캡션·썸네일 2인자)로는 유료 파트너십 태그를 실을 수 없어 새 포트가 필요하다 - 태그를 안 실으면 태그가 붙은 게시물을 LLM이 organic으로 뒤집는다(운영 실측 87건).

```java
package com.celfit.analytics.llm;

/**
 * 파트 A(사실 추출) 전용 포트 - 캡션과 인스타 유료 파트너십 태그만 입력받아
 * {@link ContentAttributes}를 낸다(2026-09-03 2단계 분리 설계 §4-4).
 *
 * <p>{@link ContentAttributePort}(캡션·썸네일 2인자)로는 유료 파트너십 태그를 실을 수 없어
 * 별도로 둔다 - 태그를 안 실으면 태그가 붙은 게시물을 LLM이 organic으로 뒤집는다(운영 실측 87건).
 *
 * <p>온라인 폴백 경로 전용이다. 배치 경로는 {@code GeminiBatchLines.factsRequestLine}이
 * 같은 프롬프트·스키마를 JSONL로 조립한다.
 */
public interface ContentFactsPort {

	/** @param thumbnailUrl null이면 캡션만으로 추출한다(배치 경로는 항상 null). */
	ContentAttributes extractFacts(ContentToAnalyze content, String thumbnailUrl);
}
```

- [ ] `GeminiContentAnalyzer` 클래스 선언(18행)에 포트를 추가한다.

```java
public final class GeminiContentAnalyzer implements ContentInsightPort, ContentFactsPort {
```

- [ ] `RESPONSE_SCHEMA`(59행에서 끝난다) 바로 뒤에 파트 A 스키마를 추가한다. 통합 스키마에서 해석 5필드를 `properties`·`required`·`propertyOrdering` 세 곳 모두에서 뺀 것이다.

```java
	/**
	 * 파트 A(사실) 전용 스키마 - 통합 스키마({@link #RESPONSE_SCHEMA})에서 해석 5필드
	 * (aiContentSummary·contentsPattern·aiCommentInsight·commentAuthenticityGrade·
	 * commentAuthenticityNote)를 properties·required·propertyOrdering 세 곳에서 뺀 것이다.
	 * 나머지 11필드의 정의·순서는 통합과 완전히 같다(2026-09-03 2단계 분리 설계 §4-4).
	 */
	public static final String RESPONSE_SCHEMA_FACTS = """
			{"type":"object","properties":{
			  "detectedBrands":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"evidence":{"type":"string","nullable":true}},
			    "required":["name","evidence"]}},
			  "sponsoredSignalLevel":{"type":"string","nullable":true},
			  "sponsoredSignalReasons":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adDisclosure":{"type":"string","nullable":true},
			  "detectedProductCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedProducts":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"brand":{"type":"string","nullable":true}},
			    "required":["name","brand"]}},
			  "vlmAttributes":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"label":{"type":"string"},"value":{"type":"string"}},
			    "required":["label","value"]}},
			  "isRelevant":{"type":"boolean"},
			  "mainCategory":{"type":"string","nullable":true},
			  "subCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedDistributors":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adType":{"type":"string","nullable":true}},
			 "required":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","isRelevant","mainCategory","subCategories",
			  "detectedDistributors","adType"],
			 "propertyOrdering":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","isRelevant","mainCategory","subCategories",
			  "detectedDistributors","adType"]}""";
```

- [ ] 파트 A 규칙 블록을 상수로 뽑는다. **텍스트를 다시 타이핑하지 않는다.** 현재 `instructions()` 텍스트 블록의 98행(`[파트 A` 로 시작하는 줄)부터 129행(`sponsored로 판정하라.`)까지를 그대로 잘라내, `SYNTHESIS_RULES` 선언(78~90행) 바로 위에 아래 형태로 붙인다. 들여쓰기(탭 4개 + 텍스트 블록 기준선)도 그대로 유지한다.

```java
	/**
	 * 파트 A(캡션 속성 추출) 규칙 - 통합 콜과 사실 전용 콜({@link #factsInstructions})이
	 * <b>공유</b>한다. 복제해 두면 한쪽만 고쳐져 통합 분석분과 파트 A 분석분의 판정 기준이 갈린다
	 * (07-21 계정 카피 프롬프트 복제 사고와 같은 함정 - SYNTHESIS_RULES 주석 참조).
	 * {@code %s} 자리는 {@code taxonomy.distributorsPrompt()}다.
	 */
	static final String FACTS_RULES = """
			<<< 여기에 기존 instructions() 본문 98~129행을 그대로 붙여넣는다 >>>""";
```

붙여넣은 뒤 `instructions()` 본문에서 **두 곳만** 고친다. 나머지 줄(도입 2줄, 빈 줄, 파트 B 절제 규칙 헤더, 분류표 헤더)은 한 글자도 건드리지 않는다 - 결과 문자열이 바이트 단위로 이전과 같아야 하기 때문이다.

1. 텍스트 블록의 98~129행(방금 잘라낸 파트 A 블록)이 있던 자리에 `%s` 한 줄만 남긴다. 그 위(96~97행 도입부)와 아래(130행 빈 줄부터 137행까지)는 그대로다.
2. `formatted` 인자에서 첫 인자를 바꾼다.

```java
	// 변경 전
	.formatted(taxonomy.distributorsPrompt(), SYNTHESIS_RULES, LlmGuard.BODY, taxonomy.promptTable());

	// 변경 후 - 첫 %s가 파트 A 블록 자리가 됐으므로 유통사 목록은 FACTS_RULES 안으로 들어간다
	.formatted(FACTS_RULES.formatted(taxonomy.distributorsPrompt()),
			SYNTHESIS_RULES, LlmGuard.BODY, taxonomy.promptTable());
```

편집 후 텍스트 블록의 `%s` 자리표시는 위에서부터 파트 A 블록 / 파트 B 문구 정의 / 절제 규칙 본문 / 분류표 순서로 4개다.

- [ ] 파트 A 전용 지시문을 추가한다(`instructions` 바로 뒤).

```java
	/**
	 * 파트 A(사실) 전용 시스템 프롬프트 - 통합에서 파트 B 규칙과 절제 규칙 블록만 뺐다.
	 * 파트 A 규칙 본문·분류표는 {@link #FACTS_RULES}·{@code taxonomy.promptTable()}로 통합과 공유한다.
	 * LlmGuard(절제 규칙)를 싣지 않는 이유: 그 규칙은 문구 생성(해석)의 과장·조언을 막는 장치라
	 * 사실 추출에는 적용 대상이 없다. 파트 B 콜이 그대로 싣는다.
	 */
	public static String factsInstructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다.
				캡션 속성 추출만 수행한다 - 성과 해석·종합 문구는 다른 단계에서 만든다. 한국어로 답한다.

				%s

				[분류표 - [축] 대분류(한글): 중분류[소분류, ...]]
				%s""".formatted(FACTS_RULES.formatted(taxonomy.distributorsPrompt()), taxonomy.promptTable());
	}
```

- [ ] 파트 A 유저 텍스트를 추가한다(`userText` 바로 뒤, 155행 `adMarkedText` 주석 위).

```java
	/**
	 * 파트 A 유저 입력 - 통합({@link #userText})에서 지표·계정 기준선·댓글 분포 3줄을 뺐다.
	 * 그 세 줄이 성숙(D+4)을 기다리게 하던 유일한 이유이므로, 빼면 D+1에 돌 수 있다.
	 *
	 * <p>첫 줄 형식은 통합과 반드시 같아야 한다 - Vertex 배치 출력에는 key가 없어
	 * {@code GeminiBatchLines.shortCodeFromEcho}가 에코된 "콘텐츠: {shortCode} (" 에서
	 * short_code를 복원한다.
	 */
	public static String userTextFacts(ContentToAnalyze c) {
		return """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				인스타 유료 파트너십 태그: %s

				위 콘텐츠의 캡션 속성을 추출하라.""".formatted(c.shortCode(), c.accountHandle(),
				c.contentType(), c.caption() == null ? "(없음)" : c.caption(), adMarkedText(c));
	}
```

- [ ] 파트 A 파서를 추가한다(`parse` 바로 뒤, `parseSynthesis` 위).

```java
	/**
	 * 파트 A 응답 JSON → 속성(sanitize). 어휘 밖 값 제거·축 파생 is_beauty는 통합 파서와 같은
	 * {@code AnthropicContentAttributeAnalyzer.sanitize}를 공유한다 - 통합 분석분과 파트 A
	 * 분석분의 저장 값이 갈리지 않게 하는 지점이다.
	 */
	public static ContentAttributes parseFacts(ObjectMapper om, String json, BeautyTaxonomy taxonomy) {
		FactsOutput o = om.readValue(json, FactsOutput.class);
		return AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType(), o.isRelevant(), null), taxonomy);
	}
```

- [ ] 포트 구현을 추가한다(`analyze` 메서드 바로 뒤).

```java
	@Override
	public ContentAttributes extractFacts(ContentToAnalyze content, String thumbnailUrl) {
		BeautyTaxonomy tx = taxonomy.get();
		GeminiApi.InlineImage image = thumbnailUrl == null ? null : download(thumbnailUrl);
		String out = api.generateJson(model.get(), factsInstructions(tx),
				userTextFacts(content), image, RESPONSE_SCHEMA_FACTS, MAX_OUTPUT_TOKENS);
		return parseFacts(om, out, tx);
	}
```

`MAX_OUTPUT_TOKENS`는 8192를 그대로 쓴다. 사실 배열이 출력의 대부분이라 줄일 근거가 없다(설계 §4-4).

- [ ] `Output` record 바로 뒤에 파트 A record를 추가한다.

```java
	/** 파트 A 산출 - RESPONSE_SCHEMA_FACTS와 1:1(해석 5필드 없음). */
	record FactsOutput(List<ContentAttributes.Brand> detectedBrands, String sponsoredSignalLevel,
			List<String> sponsoredSignalReasons, String adDisclosure,
			List<String> detectedProductCategories, List<ContentAttributes.Product> detectedProducts,
			List<ContentAttributes.Attribute> vlmAttributes, Boolean isRelevant, String mainCategory,
			List<String> subCategories, List<String> detectedDistributors, String adType) {}
```

- [ ] `LlmConfig`에 파트 A 포트 빈을 추가한다(`contentInsightPort` 빈 뒤). `contentSynthesisPort`와 같은 이유로 Gemini/Vertex 전용이다 - split 모드는 Gemini/Vertex 프로바이더에서만 지원하고, anthropic 롤백 경로는 unified 모드를 쓴다.

```java
	/**
	 * 파트 A(사실 추출) 전용 포트 - 2단계 분리(analytics.analyze-mode=split)의 온라인 폴백이 쓴다.
	 * {@link ContentSynthesisPort}와 같은 이유로 Gemini/Vertex만 지원한다: split 모드 자체가
	 * 배치 전송을 전제로 설계됐고, anthropic은 롤백 경로(unified)로 남는다.
	 * 프로바이더가 anthropic이면 JobConfig가 이 빈을 조회하지 않는다(batchApiOrNull과 같은 관용구).
	 */
	@Bean
	@Lazy
	public ContentFactsPort contentFactsPort(AnalyticsSettings settings,
			ObjectProvider<GeminiApi> gemini, BeautyTaxonomyLoader taxonomyLoader) {
		return new GeminiContentAnalyzer(gemini.getObject(), settings::geminiModel, taxonomyLoader::get);
	}
```

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.llm.GeminiContentAnalyzerTest"
```

기대: `BUILD SUCCESSFUL`. 특히 `사실_지시문은_파트B_규칙을_빼고_파트A_규칙과_분류표는_공유한다`가 통과해야 파트 A 블록이 복제가 아니라 공유임이 고정된다.

- [ ] 통합 프롬프트가 회귀하지 않았는지 llm 패키지 전체를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.llm.*"
```

기대: `BUILD SUCCESSFUL`.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics/llm analytics/src/test/java/com/celfit/analytics/llm/GeminiContentAnalyzerTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): 파트 A(사실) 전용 프롬프트·스키마·파서와 ContentFactsPort 추가

통합 프롬프트의 파트 A 블록을 FACTS_RULES 상수로 뽑아 통합·분리 프롬프트가 공유한다
(복제하면 한쪽만 고쳐져 판정 기준이 갈린다 - 07-21 계정 카피 복제 사고).
통합 프롬프트 최종 문자열은 바이트 단위로 이전과 동일하다.
파트 A 유저 텍스트에서 지표·기준선·댓글 분포 3줄을 뺐다 - 그 세 줄이 성숙을
기다리게 하던 유일한 이유다. 에코 복원용 첫 줄 형식은 통합과 동일하게 유지.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `ContentAnalysisWriter.insertFacts` + `updateSynthesis`의 `metricTimeliness`

`content_analyses` 한 행의 상태 전이를 만드는 지점이다.

| 상태 | 판별식 | 사실(파트 A) | 해석 5필드·기준선 10컬럼 | `metric_timeliness` | `synthesis_version`·`synthesized_at` |
|---|---|---|---|---|---|
| A만 | `metric_timeliness = 'pending'` | 채움 | NULL | `'pending'` | NULL |
| A+B | `synthesized_at IS NOT NULL` | 채움 | 채움 | `timely` / `late_backfill` | `Synthesis.VERSION`·시각 |

A만 → A+B 전이는 UPDATE 1회다. 파트 A 컬럼은 건드리지 않고, 기존 5필드+기준선 10컬럼+model+version에 `metric_timeliness` SET만 추가한다. `WHERE metric_timeliness = 'pending'` 같은 조건은 걸지 않는다 - 재생성 잡(`ContentSynthesisRefreshJob`)이 같은 메서드로 이미 확정된 행을 갱신하기 때문이다.

파트 A 행에 기준선 스냅샷을 넣지 않는 이유: D+1 기준선은 미성숙 지표를 포함해 드로어 벤치마크에 하향 편향을 주고, 어차피 파트 B가 D+4 기준선으로 덮는다. `V1ContentReportAssembler.comparableMetric`은 `metric_timeliness`가 timely 또는 NULL일 때만 비교 블록을 만들므로 `'pending'`이면 자동 억제된다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/analyze/StoredFacts.java` (신규)
- `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisWriter.java` (56행 `insert` 뒤에 `insertFacts`, 67~86행 `updateSynthesis` 시그니처·SQL 수정)
- `analytics/src/main/java/com/celfit/analytics/analyze/ContentSynthesisRefreshJob.java` (101~113행 조회 SQL, 139행 호출부, 143~153행 `facts` 메서드)
- `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisWriterTest.java` (신규)

### 실패하는 테스트 먼저

- [ ] `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisWriterTest.java`를 만든다.

```java
package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.testsupport.TestDb;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * content_analyses 행의 2단계 상태 전이 계약(2026-09-03 설계 §3):
 * insertFacts로 "A만"(pending·해석 NULL·기준선 NULL) → updateSynthesis로 "A+B"(timely 확정·version).
 * 재생성 잡이 같은 updateSynthesis를 쓰므로 기존 마킹 보존도 여기서 고정한다.
 */
class ContentAnalysisWriterTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	JdbcTemplate db;
	DataSource ds;
	ObjectMapper json = new ObjectMapper();

	static final Baseline BASELINE = new Baseline(9000L, 1, 2, 3, new BigDecimal("0.0496"),
			940L, 61L, 67, 19333L, 3L);

	static ContentAttributes facts() {
		return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "캡션 언급")), "high",
				List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징폼"),
				List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
				List.of(new ContentAttributes.Attribute("무드", "화사함")), "cleansing",
				List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), "sponsored", true, true);
	}

	static Synthesis synthesis() {
		return new Synthesis("요약", "패턴", "댓글 인사이트", "high", "판정 근거");
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
	}

	@Test
	void insertFacts는_사실만_채우고_pending으로_남긴다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_a", "gemini-test", facts());

		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("sponsored", db.queryForObject(
				"SELECT ad_type FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals(Boolean.TRUE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'sc_a'", Boolean.class));
		// 해석 5필드·기준선 10컬럼은 비어 있다 - D+1 기준선은 미성숙 지표를 물어 드로어를 하향 편향시킨다
		assertNull(db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT comment_authenticity_grade FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'sc_a'", Long.class));
		assertNull(db.queryForObject(
				"SELECT synthesis_version FROM content_analyses WHERE short_code = 'sc_a'", Integer.class));
		assertNull(db.queryForObject(
				"SELECT synthesized_at FROM content_analyses WHERE short_code = 'sc_a'",
				java.time.OffsetDateTime.class));
		// analyzed_at은 파트 A INSERT 시각(DEFAULT now()) - 파트 B가 갱신하지 않는다
		assertNotNull(db.queryForObject(
				"SELECT analyzed_at FROM content_analyses WHERE short_code = 'sc_a'",
				java.time.OffsetDateTime.class));
	}

	@Test
	void insertFacts는_중복_제출에도_행을_덮지_않는다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_a", "gemini-test", facts());
		ContentAnalysisWriter.updateSynthesis(db, "sc_a", "gemini-test", BASELINE, synthesis(), "timely");

		// 같은 배치가 두 번 수거돼도 파트 B 결과를 지우면 안 된다(ON CONFLICT DO NOTHING)
		ContentAnalysisWriter.insertFacts(db, json, "sc_a", "gemini-test", facts());

		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void updateSynthesis가_A만_행을_A더하기B로_전이시킨다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_a", "facts-model", facts());

		int updated = ContentAnalysisWriter.updateSynthesis(
				db, "sc_a", "synth-model", BASELINE, synthesis(), "timely");

		assertEquals(1, updated);
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'sc_a'", Long.class));
		assertEquals(Synthesis.VERSION, db.queryForObject(
				"SELECT synthesis_version FROM content_analyses WHERE short_code = 'sc_a'", Integer.class));
		assertNotNull(db.queryForObject(
				"SELECT synthesized_at FROM content_analyses WHERE short_code = 'sc_a'",
				java.time.OffsetDateTime.class));
		// 파트 A 컬럼은 건드리지 않는다
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		// model은 파트 B가 덮는다(기록 규칙 명시 - 실제로는 둘 다 같은 모델)
		assertEquals("synth-model", db.queryForObject(
				"SELECT model FROM content_analyses WHERE short_code = 'sc_a'", String.class));
	}

	@Test
	void updateSynthesis는_늦크롤이면_late_backfill로_마킹한다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_b", "facts-model", facts());

		ContentAnalysisWriter.updateSynthesis(db, "sc_b", "m", BASELINE, synthesis(), "late_backfill");

		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_b'", String.class));
	}

	@Test
	void 재생성_경로가_기존_마킹을_보존한다() {
		// 재생성 잡은 저장된 값을 그대로 넘긴다 - 지표 시점은 수집 시점 사실이라 갱신 대상이 아니다
		ContentAnalysisWriter.insertFacts(db, json, "sc_c", "m", facts());
		ContentAnalysisWriter.updateSynthesis(db, "sc_c", "m", BASELINE, synthesis(), "late_backfill");

		ContentAnalysisWriter.updateSynthesis(db, "sc_c", "m", BASELINE,
				new Synthesis("새 요약", "새 패턴", "새 댓글", "normal", "새 근거"), "late_backfill");

		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_c'", String.class));
		assertEquals("새 요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_c'", String.class));
	}

	@Test
	void 행이_없으면_updateSynthesis는_0행이다() {
		assertEquals(0, ContentAnalysisWriter.updateSynthesis(
				db, "sc_missing", "m", BASELINE, synthesis(), "timely"));
	}
}
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisWriterTest"
```

기대 실패: 컴파일 에러 `cannot find symbol: method insertFacts` 및 `updateSynthesis(...)` 인자 개수 불일치.

### 구현

- [ ] `ContentAnalysisWriter.insert` 바로 뒤에 `insertFacts`를 추가한다.

```java
	/**
	 * 파트 A(사실)만 INSERT - 해석 5필드·기준선 10컬럼은 NULL로 두고 {@code metric_timeliness}를
	 * 신규 어휘 {@code 'pending'}으로 남긴다(2026-09-03 2단계 분리 설계 §3·§4-4).
	 *
	 * <p>기준선 스냅샷을 넣지 않는 이유: D+1 기준선은 미성숙 지표를 포함해 드로어 벤치마크에
	 * 하향 편향을 주고, 어차피 파트 B가 D+4 기준선으로 덮는다. was의
	 * {@code V1ContentReportAssembler.comparableMetric}은 timely 또는 NULL일 때만 비교 블록을
	 * 만들므로 'pending'이면 자동 억제된다.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} 고정 - 같은 배치가 두 번 수거되거나 파트 A 제출이 겹쳐도
	 * 이미 파트 B까지 채워진 행을 되돌리면 안 된다.
	 */
	static void insertFacts(JdbcTemplate analysis, ObjectMapper json, String shortCode, String model,
			ContentAttributes attrs) {
		analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, detected_products, vlm_attributes, main_category,
				  sub_categories, detected_distributors, ad_type, is_beauty, metric_timeliness)
				VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?,
				        ?::jsonb, ?::jsonb, ?, ?, 'pending')
				ON CONFLICT (short_code) DO NOTHING""",
				shortCode, model,
				toJson(json, attrs == null ? null : attrs.detectedBrands()),
				attrs == null ? null : attrs.sponsoredSignalLevel(),
				toJson(json, attrs == null ? null : attrs.sponsoredSignalReasons()),
				attrs == null ? null : attrs.adDisclosure(),
				toJson(json, attrs == null ? null : attrs.detectedProductCategories()),
				toJson(json, attrs == null ? null : attrs.detectedProducts()),
				toJson(json, attrs == null ? null : attrs.vlmAttributes()),
				attrs == null ? null : attrs.mainCategory(),
				toJson(json, attrs == null ? null : attrs.subCategories()),
				toJson(json, attrs == null ? null : attrs.detectedDistributors()),
				attrs == null ? null : attrs.adType(),
				attrs == null ? null : attrs.isBeauty());
	}
```

- [ ] `updateSynthesis`에 `metricTimeliness` 파라미터를 추가한다. javadoc의 "metric_timeliness는 갱신 대상이 아니다" 문장은 더 이상 사실이 아니므로 함께 고친다.

```java
	/**
	 * 해석 문구만 갱신 - 사실 추출 컬럼(브랜드·카테고리·ad_type 등)은 손대지 않는다.
	 *
	 * <p>기준선 스냅샷도 함께 갱신한다: 문구가 인용하는 수치와 저장된 스냅샷이 갈리면
	 * 동결의 의미("LLM이 본 것 = LLM이 말한 것")가 깨지기 때문이다.
	 *
	 * <p>2026-09-03(2단계 분리): {@code metric_timeliness}도 SET한다. 파트 A가 만든 'pending'
	 * 행을 파트 B가 timely / late_backfill로 확정하는 지점이 여기다. 재생성 잡
	 * ({@code ContentSynthesisRefreshJob})은 저장된 값을 그대로 넘겨 동작이 불변이다.
	 * {@code WHERE ... AND metric_timeliness = 'pending'} 같은 조건은 걸지 않는다 -
	 * 재생성 잡이 이미 확정된 행에 같은 메서드를 쓰기 때문이다.
	 *
	 * @param metricTimeliness 지표 시점 마킹(V33 어휘 + 09-03 'pending'). 파트 B 수거는
	 *        사이드카의 timely로, 재생성 잡은 저장된 기존 값으로 넘긴다.
	 * @return 갱신된 행 수 (0이면 그 사이 행이 사라진 것)
	 */
	static int updateSynthesis(JdbcTemplate analysis, String shortCode, String model,
			Baseline b, Synthesis s, String metricTimeliness) {
		return analysis.update("""
				UPDATE content_analyses SET
				  ai_content_summary = ?, contents_pattern = ?, ai_comment_insight = ?,
				  comment_authenticity_grade = ?, comment_authenticity_note = ?,
				  recent_reels_avg_views = ?, rank_in_recent_reels = ?, recent_reels_count = ?,
				  recent_contents_count = ?, recent12_avg_engagement_rate = ?,
				  recent12_avg_like_count = ?, recent12_avg_comment_count = ?,
				  category_top_percentile = ?, category_avg_views = ?, category_sample_size = ?,
				  model = ?, metric_timeliness = ?, synthesis_version = ?, synthesized_at = now()
				WHERE short_code = ?""",
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(),
				b.recentContentsCount(), b.recent12AvgEngagementRate(),
				b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				model, metricTimeliness, Synthesis.VERSION, shortCode);
	}
```

- [ ] `analytics/src/main/java/com/celfit/analytics/analyze/StoredFacts.java`를 만든다. 파트 B 프롬프트에 싣는 "확인된 사실" 조립을 한 곳으로 모은다 - 재생성 잡과 파트 B 배치 제출이 같은 9키를 써야 두 경로의 산출물이 갈리지 않는다.

```java
package com.celfit.analytics.analyze;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * content_analyses에 저장된 파트 A 산출물을 파트 B 프롬프트의 "확인된 사실"로 옮기는 단일 원천.
 * 재생성 잡({@link ContentSynthesisRefreshJob})과 파트 B 배치 제출({@link ContentAnalysisJob})이
 * 같은 키 집합을 써야 두 경로의 문구가 서로 다른 사실을 근거로 삼는 일이 없다.
 * jsonb 컬럼은 문자열 그대로 넘긴다(LLM은 다시 판정하지 않고 주어진 대로 받는다).
 */
final class StoredFacts {

	/** 프롬프트에 싣는 사실 9키 - 순서가 프롬프트 표기 순서다. */
	static final List<String> KEYS = List.of("main_category", "sub_categories", "ad_type",
			"ad_disclosure", "detected_brands", "detected_products", "detected_product_categories",
			"sponsored_signal_level", "is_beauty");

	private StoredFacts() {
	}

	/** 조회 행(위 9키를 컬럼으로 가진 맵) → 프롬프트 입력 맵. */
	static Map<String, Object> of(Map<String, Object> row) {
		Map<String, Object> facts = new LinkedHashMap<>();
		for (String k : KEYS) {
			Object v = row.get(k);
			facts.put(k, v == null ? null : v.toString());
		}
		return facts;
	}

	/**
	 * 파트 A만 채워진 행(metric_timeliness='pending') 전량의 사실을 1회 조회로 맵에 담는다.
	 * 파트 B 배치가 콘텐츠마다 조회하면 제출 자체가 DB 왕복에 잠긴다(기준선 로딩과 같은 이유).
	 * 대상은 부분 인덱스 idx_content_analyses_timeliness_pending로 좁혀진다.
	 */
	static Map<String, Map<String, Object>> loadPending(JdbcTemplate analysis) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		analysis.query("""
				SELECT short_code, main_category, sub_categories, ad_type, ad_disclosure,
				       detected_brands, detected_products, detected_product_categories,
				       sponsored_signal_level, is_beauty
				FROM content_analyses
				WHERE metric_timeliness = 'pending'""",
				rs -> {
					Map<String, Object> row = new LinkedHashMap<>();
					for (String k : KEYS) {
						row.put(k, rs.getObject(k));
					}
					out.put(rs.getString("short_code"), of(row));
				});
		return out;
	}
}
```

- [ ] `ContentSynthesisRefreshJob`을 새 계약에 맞춘다.

조회 SQL(101~113행)의 SELECT 목록에 `a.metric_timeliness`를 추가한다.

```java
		Map<String, Object> row = analysis.queryForMap("""
				SELECT a.short_code, a.main_category, a.sub_categories, a.ad_type, a.ad_disclosure,
				       a.detected_brands, a.detected_products, a.detected_product_categories,
				       a.sponsored_signal_level, a.is_beauty, a.metric_timeliness,
				       COALESCE(c.account_handle, s.account_handle) AS account_handle,
				       COALESCE(c.content_type, s.content_type)     AS content_type,
				       COALESCE(c.views, s.views)                   AS views,
				       COALESCE(c.likes, s.likes)                   AS likes,
				       COALESCE(c.comments, s.comments)             AS comments
				FROM content_analyses a
				LEFT JOIN contents c ON c.short_code = a.short_code
				LEFT JOIN account_content_series s ON s.short_code = a.short_code
				WHERE a.short_code = ?""", shortCode);
```

프롬프트 입력 조립(133행)을 `StoredFacts.of(row)`로 바꾸고, 저장 호출(139행)에 기존 마킹을 그대로 넘긴다.

```java
		Synthesis s = port.synthesize(new ContentToSynthesize(shortCode,
				(String) row.get("account_handle"), (String) row.get("content_type"),
				(Long) row.get("views"), (Long) row.get("likes"), (Long) row.get("comments"),
				PromptBaseline.of(b), categoryCounts, StoredFacts.of(row)));

		// 빈 종합은 저장하지 않는다 - 기존 문구가 낡았어도 빈 문구보다는 낫다(가드는 통합 잡과 동일 취지).
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("해석 문구가 비어 있음: " + shortCode);
		}
		// 지표 시점은 수집 시점 사실이라 재생성이 바꾸지 않는다 - 저장된 값을 그대로 되돌려 넣는다
		// (2026-09-03 updateSynthesis가 metric_timeliness를 SET하게 되면서 생긴 호출 계약).
		ContentAnalysisWriter.updateSynthesis(analysis, shortCode, model, b, s,
				(String) row.get("metric_timeliness"));
		return true;
```

143~153행의 private `facts(Map)` 메서드는 삭제한다(`StoredFacts.of`가 대체). `java.util.List` import가 다른 곳에서 쓰이지 않게 되면 함께 정리한다.

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisWriterTest" --tests "com.celfit.analytics.analyze.ContentSynthesisRefreshJobTest"
```

기대: `BUILD SUCCESSFUL`. `ContentSynthesisRefreshJobTest.사실_추출_컬럼은_보존된다`가 `gone_c`의 `late_backfill` 마킹 보존을 이미 검증하므로 회귀 가드가 된다.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics/analyze analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisWriterTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): insertFacts 신설 + updateSynthesis에 metric_timeliness 확정 추가

파트 A는 사실만 INSERT하고 pending으로 남긴다(기준선 스냅샷은 넣지 않는다 - D+1
기준선은 미성숙 지표를 물어 드로어를 하향 편향시키고 어차피 파트 B가 덮는다).
파트 B UPDATE가 같은 행의 시점을 timely/late_backfill로 확정한다. 재생성 잡은
저장된 값을 그대로 넘겨 동작이 불변이며, 사실 조립은 StoredFacts로 단일화했다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `GeminiBatchLines` phase별 요청·사이드카·결과 라인

사이드카는 kind와 무관하게 기존 `SIDECAR_KEYS`(기준선 10키 + caption + timely)를 그대로 쓴다. 파트 A는 기준선 키가 전부 null로 실리고 timely는 false 고정이며 수거가 읽지 않는다. **키 집합을 kind마다 다르게 하면 `parseSidecar`가 kind를 알아야 해 수거 경로가 두 겹으로 갈린다** - 사이드카는 하나로 두고 수거 분기만 kind로 한다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentSynthesizer.java` (17행 `MAX_OUTPUT_TOKENS`, 19행 `RESPONSE_SCHEMA` 가시성)
- `analytics/src/main/java/com/celfit/analytics/analyze/GeminiBatchLines.java` (71행 `requestLine` 뒤에 2종 추가, 194행 `processResultLine` 뒤에 2종 추가)
- `analytics/src/test/java/com/celfit/analytics/analyze/GeminiBatchLinesTest.java` (신규)

### 실패하는 테스트 먼저

- [ ] `analytics/src/test/java/com/celfit/analytics/analyze/GeminiBatchLinesTest.java`를 만든다. 라인 조립은 DB가 필요 없어 순수 단위 테스트로 둔다.

```java
package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * phase별 배치 JSONL 라인 조립 계약(2026-09-03 2단계 분리 설계 §4-4·§5).
 * 파트 A 라인은 캡션 전용 스키마·유저 텍스트를, 파트 B 라인은 해석 5필드 스키마와
 * 저장된 사실·지표·기준선을 싣는다. 사이드카 키는 kind와 무관하게 한 벌이다.
 */
class GeminiBatchLinesTest {

	ObjectMapper om = new ObjectMapper();

	private static Map<String, Object> row() {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("account_handle", "acct1");
		r.put("caption", "캡션A");
		r.put("content_type", "reels");
		r.put("views", 11000L);
		r.put("likes", 520L);
		r.put("comments", 52L);
		r.put("ad_marked", Boolean.TRUE);
		r.put("recent_reels_avg_views", 9000L);
		r.put("rank_in_recent_reels", 1);
		r.put("recent_reels_count", 2);
		r.put("recent_contents_count", 3);
		r.put("recent12_avg_engagement_rate", new java.math.BigDecimal("0.0496"));
		r.put("recent12_avg_like_count", 940L);
		r.put("recent12_avg_comment_count", 61L);
		r.put("category_top_percentile", 67);
		r.put("category_avg_views", 19333L);
		r.put("category_sample_size", 3L);
		r.put("timely", Boolean.TRUE);
		return r;
	}

	private String userTextOf(ObjectNode line) {
		return line.path("request").path("contents").get(0).path("parts").get(0).path("text").asString();
	}

	private String schemaOf(ObjectNode line) {
		return line.path("request").path("generationConfig").path("responseSchema").toString();
	}

	@Test
	void 파트A_라인은_캡션만_싣고_해석_스키마가_없다() {
		ObjectNode line = GeminiBatchLines.factsRequestLine(om, "post_a", row(), "SYSTEM_FACTS");

		assertEquals("post_a", line.path("key").asString());
		String user = userTextOf(line);
		assertTrue(user.startsWith("콘텐츠: post_a (@acct1, reels)"), user);
		assertTrue(user.contains("인스타 유료 파트너십 태그: 있음"));
		assertFalse(user.contains("지표:"));
		assertFalse(user.contains("계정 기준선:"));
		assertFalse(schemaOf(line).contains("aiContentSummary"));
		assertEquals("SYSTEM_FACTS", line.path("request").path("systemInstruction")
				.path("parts").get(0).path("text").asString());
	}

	@Test
	void 파트B_라인은_사실과_지표_기준선_댓글분포를_싣는다() {
		Map<String, Object> facts = Map.of("main_category", "cleansing", "ad_type", "sponsored");

		ObjectNode line = GeminiBatchLines.synthesisRequestLine(om, "post_a", row(),
				Map.of("purchase", 1L), facts, "SYSTEM_SYNTH");

		assertEquals("post_a", line.path("key").asString());
		String user = userTextOf(line);
		assertTrue(user.startsWith("콘텐츠: post_a (@acct1, reels)"), user);
		assertTrue(user.contains("확인된 사실:"));
		assertTrue(user.contains("cleansing"));
		assertTrue(user.contains("views=11000"));
		assertTrue(user.contains("purchase=1"));
		// 기준선은 화면과 같은 퍼센트 단위로 실린다(PromptBaseline 규약)
		assertTrue(user.contains("recent12_avg_engagement_rate_pct"));
		String schema = schemaOf(line);
		assertTrue(schema.contains("aiContentSummary"));
		assertFalse(schema.contains("detectedBrands"));
	}

	@Test
	void 사이드카_키는_kind와_무관하게_한_벌이다() {
		ObjectNode factsSidecar = GeminiBatchLines.sidecarLine(om, "post_a", row());

		for (String key : GeminiBatchLines.SIDECAR_KEYS) {
			assertTrue(factsSidecar.has(key), "사이드카에 " + key + " 누락");
		}
		assertEquals("post_a", factsSidecar.path("short_code").asString());
	}
}
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.GeminiBatchLinesTest"
```

기대 실패: 컴파일 에러 `cannot find symbol: method factsRequestLine` / `synthesisRequestLine`.

### 구현

- [ ] `GeminiContentSynthesizer`의 두 상수를 public으로 넓힌다(`GeminiBatchLines`가 다른 패키지에 있다).

```java
	/** 텍스트 5필드 - 통합(8192)보다 작다. 배치 라인 조립(analyze 패키지)도 읽으므로 public. */
	public static final int MAX_OUTPUT_TOKENS = 2048;

	/** 해석 5필드 스키마 - 온라인 콜과 배치 라인이 공유한다. */
	public static final String RESPONSE_SCHEMA = """
```

- [ ] `GeminiBatchLines.requestLine`(71행에서 끝난다) 뒤에 파트 A·파트 B 라인 조립을 추가한다. import에 `com.celfit.analytics.llm.ContentToSynthesize`·`com.celfit.analytics.llm.GeminiContentSynthesizer`를 더한다.

```java
	/**
	 * 파트 A(사실) 요청 라인 - 캡션과 유료 파트너십 태그만 싣는다(2026-09-03 2단계 분리 §4-4).
	 * 지표·기준선·댓글 분포를 안 실으므로 성숙(D+4)을 기다릴 필요가 없다.
	 * 응답 스키마는 해석 5필드가 빠진 {@code RESPONSE_SCHEMA_FACTS}다.
	 */
	static ObjectNode factsRequestLine(ObjectMapper om, String shortCode, Map<String, Object> r,
			String system) {
		ContentToAnalyze content = new ContentToAnalyze(shortCode, (String) r.get("account_handle"),
				(String) r.get("caption"), (String) r.get("content_type"),
				null, null, null, Map.of(), Map.of(), (Boolean) r.get("ad_marked"));
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentAnalyzer.userTextFacts(content));
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiContentAnalyzer.RESPONSE_SCHEMA_FACTS));
		gen.put("maxOutputTokens", GeminiContentAnalyzer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/**
	 * 파트 B(해석) 요청 라인 - 저장된 사실 + 핀 지표 + 기준선 스냅샷 + 댓글 분포.
	 * 프롬프트·스키마는 온라인 재생성 경로({@link GeminiContentSynthesizer})와 같은 것을 쓴다 -
	 * 복제하면 배치 생성분과 재생성분의 문구 의미가 갈린다(07-21 사고와 같은 함정).
	 *
	 * @param facts {@code StoredFacts.of}가 만든 "확인된 사실" 9키.
	 */
	static ObjectNode synthesisRequestLine(ObjectMapper om, String shortCode, Map<String, Object> r,
			Map<String, Long> commentCategoryCounts, Map<String, Object> facts, String system) {
		ContentToSynthesize content = new ContentToSynthesize(shortCode,
				(String) r.get("account_handle"), (String) r.get("content_type"),
				numberOf(r.get("views")), numberOf(r.get("likes")), numberOf(r.get("comments")),
				PromptBaseline.ofRow(r), commentCategoryCounts, facts);
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentSynthesizer.userText(content));
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiContentSynthesizer.RESPONSE_SCHEMA));
		gen.put("maxOutputTokens", GeminiContentSynthesizer.MAX_OUTPUT_TOKENS);
		return line;
	}
```

- [ ] `processResultLine`(194행에서 끝난다) 뒤에 kind별 결과 처리를 추가한다. 라인 해석(에코 복원·오류 응답 판정)은 세 kind가 같으므로 헬퍼로 뽑는다.

```java
	/** 결과 라인에서 (short_code, 응답 텍스트)를 꺼낸다 - 실패면 null. kind 3종이 공유. */
	private static String[] shortCodeAndText(ObjectMapper om, String line) {
		JsonNode node = om.readTree(line);
		String vertexStatus = node.path("status").asString("");
		if (!vertexStatus.isEmpty()) {
			log.warn("배치 실패 라인 (status={}): {}", vertexStatus, abbreviate(line));
			return null;
		}
		String shortCode = node.path("key").asString("");
		if (shortCode.isEmpty()) {
			shortCode = shortCodeFromEcho(node);
		}
		JsonNode text = node.path("response").path("candidates").path(0)
				.path("content").path("parts").path(0).path("text");
		if (shortCode.isEmpty() || text.isMissingNode()) {
			log.warn("결과 라인 해석 불가/오류 응답: {}", abbreviate(line));
			return null;
		}
		return new String[] {shortCode, text.asString()};
	}

	/**
	 * 파트 A 결과 한 줄 처리: 파싱 → sanitize → ON CONFLICT DO NOTHING INSERT(pending).
	 * 분류 대상인데 대분류를 못 얻은 경우는 통합 경로와 같은 처방으로 미분류 종결한다
	 * (temperature 0 결정론이라 재대상해도 같은 결과 - 무한 재시도 방지).
	 *
	 * @return true=저장 성공, false=실패(다음 실행이 재대상 흡수)
	 */
	static boolean processFactsResultLine(JdbcTemplate analysis, ObjectMapper om, String line,
			Map<String, Map<String, String>> sidecar, String model, BeautyTaxonomy taxonomy) {
		try {
			String[] parsed = shortCodeAndText(om, line);
			if (parsed == null) {
				return false;
			}
			String shortCode = parsed[0];
			Map<String, String> base = sidecar.get(shortCode);
			if (base == null) {
				log.warn("사이드카에 없는 key: {}", shortCode);
				return false;
			}
			boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
			ContentAttributes attrs = GeminiContentAnalyzer.parseFacts(om, parsed[1], taxonomy);
			if (Boolean.TRUE.equals(attrs.isRelevant()) && attrs.mainCategory() == null) {
				log.info("분류 대상이나 대분류 미도출 - 미분류로 종결 저장(재시도 루프 방지): {}", shortCode);
				attrs = attrs.asUnclassified();
			}
			ContentAnalysisWriter.insertFacts(analysis, om, shortCode, model, hasCaption ? attrs : null);
			return true;
		} catch (Exception e) {
			log.warn("파트 A 결과 라인 저장 실패: {}", abbreviate(line), e);
			return false;
		}
	}

	/**
	 * 파트 B 결과 한 줄 처리: 파싱 → 해석 5필드 + 기준선 스냅샷 UPDATE + 시점 확정.
	 * 마킹은 사이드카에 실린 뷰의 timely 판정을 승계한다(제출 시점 고정 - 수거 시점 재계산 금지).
	 *
	 * @return true=갱신 성공, false=실패 또는 0행(그 사이 행이 사라짐)
	 */
	static boolean processSynthesisResultLine(JdbcTemplate analysis, ObjectMapper om, String line,
			Map<String, Map<String, String>> sidecar, String model) {
		try {
			String[] parsed = shortCodeAndText(om, line);
			if (parsed == null) {
				return false;
			}
			String shortCode = parsed[0];
			Map<String, String> base = sidecar.get(shortCode);
			if (base == null) {
				log.warn("사이드카에 없는 key: {}", shortCode);
				return false;
			}
			Synthesis s = GeminiContentAnalyzer.parseSynthesis(om, parsed[1]);
			// 빈 종합은 저장하지 않는다 - 저장하면 pending이 풀려 다시 대상이 되지 않는다
			if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
				log.warn("해석 문구가 비어 있음 - 저장하지 않음(다음 실행 재대상): {}", shortCode);
				return false;
			}
			boolean timely = "true".equals(base.get("timely"));
			int updated = ContentAnalysisWriter.updateSynthesis(analysis, shortCode, model,
					baselineOf(base), s, timely ? "timely" : "late_backfill");
			if (updated == 0) {
				log.warn("해석 UPDATE 0행 - 제출~수거 사이에 행이 사라짐: {}", shortCode);
				return false;
			}
			return true;
		} catch (Exception e) {
			log.warn("파트 B 결과 라인 저장 실패: {}", abbreviate(line), e);
			return false;
		}
	}
```

import에 `com.celfit.analytics.llm.ContentAttributes`·`com.celfit.analytics.llm.Synthesis`를 추가한다. 기존 `processResultLine`은 위 헬퍼를 쓰도록 정리해도 되지만 **이번 태스크에서는 손대지 않는다** - 통합 경로 회귀 위험을 만들지 않는다.

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.GeminiBatchLinesTest" --tests "com.celfit.analytics.analyze.GeminiBackfillRunnerTest"
```

기대: `BUILD SUCCESSFUL`. 백필 러너는 기존 `requestLine`·`processResultLine`을 그대로 쓰므로 회귀가 없어야 한다.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics analytics/src/test/java/com/celfit/analytics/analyze/GeminiBatchLinesTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): 배치 JSONL에 파트 A·파트 B 요청/결과 라인 추가

파트 A 라인은 캡션 전용 스키마와 유저 텍스트를, 파트 B 라인은 GeminiContentSynthesizer의
프롬프트·스키마를 그대로 싣는다(복제하면 배치 생성분과 재생성분의 문구 의미가 갈린다).
사이드카는 kind와 무관하게 한 벌로 두고 수거 분기만 kind로 한다 - 키를 kind마다 나누면
parseSidecar가 kind를 알아야 해 수거 경로가 두 겹으로 갈린다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `ContentAnalysisJob`에 Phase 축 추가

새 클래스를 만들지 않는다 - 후보 조회·제외 게이트·기준선 로딩·청크 분할·배치 제출·429 이월 배관이 이미 여기 있고 파트 B 배치도 같은 배관을 탄다.

```
enum Phase { UNIFIED, FACTS, SYNTHESIS }
JobName.FACT_ANALYZE           → FACTS      (성숙·timely 무관)
JobName.ANALYZE                → split ? SYNTHESIS(timely=true)  : UNIFIED(timely=true)
JobName.LATE_BACKFILL_ANALYZE  → split ? SYNTHESIS(timely=false) : UNIFIED(timely=false)
```

| phase | 후보 소스 | 제외 |
|---|---|---|
| UNIFIED(현행) | `v_analysis_candidates WHERE timely = ?` | ① 행 존재 ② 댓글 미분류 |
| FACTS | `v_fact_candidates`(timely 무관) | ① 행 존재(어떤 상태든) |
| SYNTHESIS | `v_analysis_candidates WHERE timely = ?` | ① A 행 부재 ② B 완료 ③ 댓글 미분류 |

SYNTHESIS의 ①②는 "후보 ∩ pending 집합"이라는 **포함 집합**으로 한 번에 처리한다(부분 인덱스로 좁혀진 집합이라 통짜 로드가 싸다).

**Files:**
- `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (60~65행 SQL 상수, 82~110행 생성자, 120~200행 진입점·resolveTargets, 207~306행 배치 제출, 308~363행 온라인 루프, 403~458행 analyzeOne)
- `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` (74~92행 `contentAnalysisJob` 빈, 118~125행 헬퍼 옆)
- `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (192~220행 fixture, 테스트 추가)

### 실패하는 테스트 먼저

- [ ] `ContentAnalysisJobTest`의 `setUp()`에 파트 A 후보 뷰 대역을 추가한다. 실제 뷰에서는 `v_analysis_candidates`가 `v_fact_candidates`의 투영이지만, 잡 테스트는 뷰가 주는 결과만 신뢰하므로 fixture 두 개를 별도로 둔다(자격 판정은 SQL 하니스 04가 검증한다).

`db.update("""CREATE VIEW analytics.v_analysis_candidates AS SELECT * FROM analytics.candidates_fixture""")` 바로 뒤에 추가한다.

```java
		// 2026-09-03 2단계 분리: 파트 A 입구 뷰 대역. 성숙 무관이라 별도 fixture를 두고,
		// 미성숙 신규분(fact_only_1)이 파트 A에만 잡히는지 검증한다.
		db.update("""
				CREATE TABLE analytics.fact_candidates_fixture (
				    short_code         text PRIMARY KEY,
				    timely             boolean NOT NULL,
				    mature             boolean NOT NULL,
				    metric_captured_at timestamptz,
				    account_handle     text,
				    caption            text,
				    content_type       text,
				    thumbnail_url      text,
				    views              bigint,
				    likes              bigint,
				    comments           bigint,
				    ad_marked          boolean
				)""");
		db.update("""
				CREATE VIEW analytics.v_fact_candidates AS SELECT * FROM analytics.fact_candidates_fixture""");
		db.update("""
				INSERT INTO analytics.fact_candidates_fixture VALUES
				  ('post_a', true, true, now() - interval '6 days 18 hours', 'acct1', '캡션A', 'reels',
				   'https://img/a.jpg', 11000, 520, 52, true),
				  ('post_b', true, true, now() - interval '6 days 6 hours', 'acct1', '캡션B', 'feed',
				   'https://img/b.jpg', NULL, 2000, 100, false),
				  ('post_c', true, true, now() - interval '6 days 12 hours', 'acct1', '캡션C', 'reels',
				   'https://img/c.jpg', 7000, 300, 30, false),
				  ('post_new', false, false, now() - interval '1 hour', 'acct1', '어제 올린 캡션', 'reels',
				   'https://img/new.jpg', 1200, 60, 6, false)""");
```

- [ ] split 모드를 켜는 헬퍼와 파트 A/파트 B 포트 대역을 추가한다(`enableBatchTransport()` 옆).

```java
	void enableSplitMode() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-mode', 'split')");
	}

	List<ContentToAnalyze> factsCalls;
	List<ContentToSynthesize> synthesisCalls;

	/** fake ContentFactsPort - 호출 기록 + 고정 속성(통합 fake와 같은 값). */
	com.celfit.analytics.llm.ContentFactsPort fakeFactsPort() {
		return (content, thumbnailUrl) -> {
			factsCalls.add(content);
			return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), "high",
					List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징폼"),
					List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
					List.of(new ContentAttributes.Attribute("무드", "화사함")), "cleansing",
					List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), "sponsored", true, true);
		};
	}

	/** fake ContentSynthesisPort - 호출 기록 + 고정 해석 5필드. */
	com.celfit.analytics.llm.ContentSynthesisPort fakeSynthesisPort() {
		return content -> {
			synthesisCalls.add(content);
			return new Synthesis("해석: " + content.shortCode(), "패턴", "댓글 인사이트", "high", "근거");
		};
	}

	/** split 경로 재배선 - 온라인/배치 공용. batchApi=null이면 온라인 폴백. */
	void rewireSplitJob(GeminiBatchApi batchApi) {
		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, ProgressReporter.NOOP, ProgressReporter.NOOP,
				batchApi, new BeautyTaxonomyLoader(ds),
				ProgressReporter.NOOP, fakeFactsPort(), fakeSynthesisPort());
	}
```

`setUp()`의 리스트 초기화 블록에 두 줄을 더한다.

```java
		factsCalls = java.util.Collections.synchronizedList(new ArrayList<>());
		synthesisCalls = java.util.Collections.synchronizedList(new ArrayList<>());
```

import에 `com.celfit.analytics.llm.ContentToSynthesize`를 추가한다.

- [ ] phase 계약 케이스를 추가한다(파일 끝).

```java
	// ── 2026-09-03 2단계 분리 (analytics.analyze-mode=split) ─────────────────────

	@Test
	void unified_모드에서_runFacts는_no_op이다() {
		// 기본값(unified)에서는 통합 콜이 사실까지 만들므로 파트 A 잡이 돌면 안 된다 - 배포 후에도
		// 토글을 켜기 전까지 행동 변화 0이어야 한다.
		rewireSplitJob(null);

		JobResult result = job.runFacts();

		assertEquals(0, result.processed());
		assertTrue(factsCalls.isEmpty());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void split_모드_runFacts는_미성숙_신규분까지_사실만_저장한다() {
		enableSplitMode();
		rewireSplitJob(null); // 온라인 폴백 경로

		JobResult result = job.runFacts();

		// 파트 A 제외는 '행 존재' 하나뿐 - 댓글 미분류(post_c)도 대상이다(파트 A는 댓글을 안 본다)
		assertEquals(4, result.processed());
		assertEquals(4L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		assertTrue(factsCalls.stream().anyMatch(c -> c.shortCode().equals("post_new")));
		assertTrue(factsCalls.stream().anyMatch(c -> c.shortCode().equals("post_c")));
		// 사실만 채워지고 시점은 pending
		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_new'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_new'", String.class));
		assertNull(db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_new'", String.class));
		// 통합 포트는 한 번도 안 탄다
		assertTrue(insightCalls.isEmpty());
	}

	@Test
	void split_모드_runFacts는_행이_있으면_건너뛴다() {
		enableSplitMode();
		rewireSplitJob(null);
		job.runFacts();
		factsCalls.clear();

		assertEquals(0, job.runFacts().processed());
		assertTrue(factsCalls.isEmpty());
	}

	@Test
	void split_모드_run은_A_행이_있는_후보만_해석한다() {
		enableSplitMode();
		rewireSplitJob(null);
		job.runFacts(); // post_a·post_b·post_c·post_new에 pending 행 생성

		JobResult result = job.run();

		// 파트 B 후보 = v_analysis_candidates(timely=true) ∩ pending - 댓글 게이트
		// post_new는 파트 B 후보 뷰에 없고, post_c는 댓글 미분류라 제외 → post_a·post_b
		assertEquals(2, result.processed());
		assertEquals(List.of("post_b", "post_a"), synthesisCalls.stream()
				.map(ContentToSynthesize::shortCode).sorted(java.util.Comparator.reverseOrder()).toList());
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("해석: post_a", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
		// 파트 A 컬럼은 그대로
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
		// 기준선 스냅샷은 파트 B가 채운다
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		// post_new는 아직 pending
		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_new'", String.class));
	}

	@Test
	void split_모드_run은_A_행이_없으면_대상이_아니다() {
		// 파트 A 수거가 아직 안 끝난 상태 - 파트 B는 다음 실행에서 자연 재대상한다
		enableSplitMode();
		rewireSplitJob(null);

		JobResult result = job.run();

		assertEquals(0, result.processed());
		assertTrue(synthesisCalls.isEmpty());
	}

	@Test
	void split_모드_run은_B_완료분을_다시_해석하지_않는다() {
		enableSplitMode();
		rewireSplitJob(null);
		job.runFacts();
		job.run();
		synthesisCalls.clear();

		assertEquals(0, job.run().processed());
		assertTrue(synthesisCalls.isEmpty());
	}

	@Test
	void split_모드_runLateBackfill은_늦크롤분을_late_backfill로_확정한다() {
		db.update("UPDATE analytics.candidates_fixture SET timely = false WHERE short_code = 'post_a'");
		enableSplitMode();
		rewireSplitJob(null);
		job.runFacts();

		int backfill = job.runLateBackfill().processed();

		assertEquals(1, backfill);
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void split_배치_제출은_kind와_배치_이름_접두사를_구분한다() {
		enableSplitMode();
		enableBatchTransport();
		rewireSplitJob(fakeBatchApi());

		job.runFacts();

		assertEquals("facts", db.queryForObject("SELECT kind FROM content_batch_jobs", String.class));
		assertEquals(Boolean.FALSE, db.queryForObject(
				"SELECT timely FROM content_batch_jobs", Boolean.class)); // facts는 timely 개념이 없다
		assertTrue(batchUploadNames.get(0).startsWith("hypenow-facts-"), batchUploadNames.get(0));
		// 파트 A JSONL에는 해석 스키마가 없다
		String jsonl = new String(batchUploads.get(0), StandardCharsets.UTF_8);
		assertFalse(jsonl.contains("aiContentSummary"));
	}

	@Test
	void split_배치_파트B_제출은_synthesis_kind로_기록된다() {
		enableSplitMode();
		rewireSplitJob(null);
		job.runFacts();             // 온라인으로 pending 행 생성
		enableBatchTransport();
		rewireSplitJob(fakeBatchApi());

		JobResult result = job.run();

		assertEquals(2, result.processed()); // post_a·post_b
		assertEquals("synthesis", db.queryForObject("SELECT kind FROM content_batch_jobs", String.class));
		assertTrue(batchUploadNames.get(0).startsWith("hypenow-synth-"), batchUploadNames.get(0));
		String jsonl = new String(batchUploads.get(0), StandardCharsets.UTF_8);
		assertTrue(jsonl.contains("aiContentSummary"));   // 해석 스키마
		assertTrue(jsonl.contains("확인된 사실"));           // 저장된 파트 A 사실이 실린다
		assertFalse(jsonl.contains("detectedBrands\":{"));  // 사실 추출 스키마는 없다
	}

	@Test
	void unified_모드는_현행_그대로_통합_1콜이다() {
		// 회귀 고정: 토글을 켜지 않으면 파트 A/파트 B 포트는 한 번도 안 탄다
		rewireSplitJob(null);

		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals(2, insightCalls.size());
		assertTrue(factsCalls.isEmpty());
		assertTrue(synthesisCalls.isEmpty());
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"
```

기대 실패: 컴파일 에러 `constructor ContentAnalysisJob ... cannot be applied` (13인자 생성자 부재), `cannot find symbol: method runFacts()`.

### 구현

- [ ] 후보 SQL 상수를 추가한다(`CANDIDATES_SQL` 뒤).

```java
	// 파트 A(사실) 입구 - 성숙·timely 무관. 뷰가 `NOT mature OR timely OR in_window`로 이미 잘라
	// 준다(성숙 ∧ 늦크롤 ∧ 윈도우 밖 = 영구 제외는 파트 A에도 열지 않는다 - 04 뷰 주석 참조).
	// 컬럼 이름은 CANDIDATES_SQL과 1:1로 맞춘다 - GeminiBatchLines가 이 키 계약에 의존한다.
	private static final String FACT_CANDIDATES_SQL = """
			SELECT short_code, account_handle, caption, content_type, thumbnail_url,
			       views, likes, comments, ad_marked
			FROM v_fact_candidates
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""";
```

- [ ] 필드·상수·enum을 추가한다.

```java
	/** 분석 단계 축(2026-09-03). UNIFIED=현행 통합 1콜, FACTS=파트 A(사실), SYNTHESIS=파트 B(해석). */
	public enum Phase { UNIFIED, FACTS, SYNTHESIS }

	/** 파트 A는 기준선을 안 쓴다 - 뷰 스캔(운영 실측 분 단위)을 통째로 건너뛰기 위한 상수. */
	private static final Baselines EMPTY_BASELINES = new Baselines(Map.of(), Map.of());
```

`private final ContentBatchCollectJob collectJob;` 아래에 필드 세 개를 추가한다.

```java
	private final ProgressReporter factsReporter; // runFacts() 진행률 - JobName.FACT_ANALYZE
	private final ContentFactsPort factsPort;       // null이면 split 미지원 프로바이더(anthropic)
	private final ContentSynthesisPort synthesisPort; // null이면 split 미지원 프로바이더
```

- [ ] 생성자를 3단으로 정리한다. 기존 8인자·10인자 생성자는 시그니처를 바꾸지 않는다(테스트 21곳 호환).

```java
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter) {
		this(rawJdbcTemplate, analysisDataSource, insight, settings, thumbnailEnabled, thumbnailAlive,
				reporter, backfillReporter, null, null);
	}

	/**
	 * @param batchApi 배치 전송 제출·상태 확인용 - null이면 배치 미지원 프로바이더(온라인 폴백).
	 * @param taxonomyLoader 배치 요청의 시스템 프롬프트 조립용(뷰티 분류표).
	 */
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter,
			GeminiBatchApi batchApi, BeautyTaxonomyLoader taxonomyLoader) {
		this(rawJdbcTemplate, analysisDataSource, insight, settings, thumbnailEnabled, thumbnailAlive,
				reporter, backfillReporter, batchApi, taxonomyLoader,
				ProgressReporter.NOOP, null, null);
	}

	/**
	 * 2단계 분리(analytics.analyze-mode=split) 지원 생성자.
	 *
	 * @param factsPort 파트 A 온라인 폴백 - null이면 split 미지원 프로바이더(anthropic 롤백 경로).
	 * @param synthesisPort 파트 B 온라인 폴백 - 같은 규칙.
	 */
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter,
			GeminiBatchApi batchApi, BeautyTaxonomyLoader taxonomyLoader,
			ProgressReporter factsReporter, ContentFactsPort factsPort,
			ContentSynthesisPort synthesisPort) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
		this.reporter = reporter;
		this.backfillReporter = backfillReporter;
		this.batchApi = batchApi;
		this.taxonomyLoader = taxonomyLoader;
		this.factsReporter = factsReporter;
		this.factsPort = factsPort;
		this.synthesisPort = synthesisPort;
		this.collectJob = new ContentBatchCollectJob(analysisDataSource, batchApi, taxonomyLoader, settings);
	}
```

- [ ] 진입점 3종을 교체한다(120~133행).

```java
	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>mode=unified면 현행 통합 1콜, split이면 파트 B(해석)만 만든다. 어느 쪽이든 후보는
	 * 성숙한 timely 분이라 랭킹 진입 시점은 변하지 않는다.
	 */
	public JobResult run() {
		return runQuery(settings.splitAnalyzeMode() ? Phase.SYNTHESIS : Phase.UNIFIED, true, reporter);
	}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>후보 뷰의 NOT timely 후보(= 최근 N개 윈도우 안 늦크롤) 전량. run()과 상호 배타 -
	 * 같은 뷰의 timely 컬럼으로 서로소 분할이라 같은 short_code가 두 진입점에 동시에 잡히지 않는다.
	 */
	public JobResult runLateBackfill() {
		return runQuery(settings.splitAnalyzeMode() ? Phase.SYNTHESIS : Phase.UNIFIED, false,
				backfillReporter);
	}

	/**
	 * 파트 A(사실) 전용 진입점 - JobName.FACT_ANALYZE. 성숙·timely와 무관하게 캡션만 보고 돌므로
	 * D+1 새벽에 실행할 수 있다(2026-09-03 2단계 분리 설계 §2-2).
	 *
	 * <p>mode=unified면 통합 콜이 사실까지 만들므로 no-op 로그만 남기고 끝난다 - 배포 후에도
	 * 토글을 켜기 전까지 운영 행동은 하나도 바뀌지 않는다.
	 */
	public JobResult runFacts() {
		if (!settings.splitAnalyzeMode()) {
			log.info("analytics.analyze-mode=unified - 파트 A 잡 no-op(통합 콜이 사실까지 만든다)");
			return new JobResult(0, 0, false);
		}
		return runQuery(Phase.FACTS, false, factsReporter);
	}
```

- [ ] `runQuery`를 phase 축으로 바꾼다.

```java
	private JobResult runQuery(Phase phase, boolean timely, ProgressReporter progress) {
		if (phase != Phase.UNIFIED && (factsPort == null || synthesisPort == null)) {
			// batchApiOrNull과 같은 관용구로 JobConfig가 anthropic이면 null을 넣는다.
			// 잡을 조용히 no-op으로 두면 "왜 안 도는지"를 로그로 알 수 없어 명시적으로 죽인다.
			throw new IllegalStateException(
					"analytics.analyze-mode=split은 gemini/vertex 프로바이더에서만 지원한다 - "
					+ "롤백하려면 app_setting analytics.analyze-mode를 unified로");
		}
		// 파트 A는 기준선을 인용하지 않는다 - 뷰 스캔(운영 실측 분 단위)을 통째로 건너뛴다.
		Baselines baselines = phase == Phase.FACTS ? EMPTY_BASELINES : loadBaselines();
		List<Map<String, Object>> targets = resolveTargets(phase, timely);

		if (settings.batchTransportEnabled()) {
			// 썸네일 첨부는 사실 추출(파트 A·통합)에만 의미가 있다 - 파트 B는 이미지를 안 보내므로
			// vlm-enabled=true여도 배치로 내려가는 게 정상이다.
			if (thumbnailEnabled && phase != Phase.SYNTHESIS) {
				log.warn("analytics.analyze-transport=batch인데 vlm-enabled=true - 배치는 캡션 전용이라"
						+ " 온라인 경로로 폴백(썸네일 첨부 보존)");
			} else if (batchApi != null) {
				return submitBatch(phase, timely, targets, baselines);
			} else {
				log.warn("analytics.analyze-transport=batch인데 GeminiApi가 배치 미지원 - 온라인 경로로 폴백");
			}
		}
		return runOnline(phase, timely, targets, baselines, progress);
	}
```

- [ ] `resolveTargets`를 phase 축으로 바꾼다.

```java
	/**
	 * 후보 뷰 조회(재료 포함) + phase별 제외 게이트. 자격(캘린더일 timely·성숙·윈도우)은 뷰 소관,
	 * 제외는 여기 Java diff 소관이다(클래스 상단 주석 참조).
	 *
	 * <p>2026-09-03 phase별 제외:
	 * <ul>
	 * <li>UNIFIED: 행 존재 · 댓글 미분류 (현행)
	 * <li>FACTS: 행 존재만. 상태 불문 - 파트 A만 있는 행도 다시 만들지 않는다.
	 *     댓글 게이트는 걸지 않는다(파트 A는 댓글 분포를 입력으로 쓰지 않는다).
	 * <li>SYNTHESIS: "후보 ∩ pending 집합"이라는 포함 집합으로 A 행 부재와 B 완료를 한 번에
	 *     처리한다(부분 인덱스로 좁혀진 집합이라 통짜 로드가 싸다). 여기에 댓글 게이트를 뺀다.
	 * </ul>
	 *
	 * @return 후보 행 목록. 키는 short_code·account_handle·caption·content_type·thumbnail_url·
	 *         views·likes·comments·ad_marked (하위 조립이 이 이름에 의존).
	 */
	private List<Map<String, Object>> resolveTargets(Phase phase, boolean timely) {
		List<Map<String, Object>> candidates = new ArrayList<>();
		org.springframework.jdbc.core.RowCallbackHandler collect = rs -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("short_code", rs.getString("short_code"));
			row.put("account_handle", rs.getString("account_handle"));
			row.put("caption", rs.getString("caption"));
			row.put("content_type", rs.getString("content_type"));
			row.put("thumbnail_url", rs.getString("thumbnail_url"));
			row.put("views", rs.getObject("views"));
			row.put("likes", rs.getObject("likes"));
			row.put("comments", rs.getObject("comments"));
			row.put("ad_marked", rs.getObject("ad_marked"));
			candidates.add(row);
		};
		if (phase == Phase.FACTS) {
			raw.query(FACT_CANDIDATES_SQL, collect);
		} else {
			raw.query(CANDIDATES_SQL, collect, timely);
		}

		if (phase == Phase.FACTS) {
			Set<String> analyzed = new HashSet<>(
					analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
			List<Map<String, Object>> targets = new ArrayList<>();
			for (Map<String, Object> row : candidates) {
				if (!analyzed.contains((String) row.get("short_code"))) {
					targets.add(row);
				}
			}
			return targets;
		}

		// 댓글이 미러됐는데 분류가 아직인 콘텐츠는 댓글 인사이트 입력이 미완이라 보류(기존 게이트 유지)
		Set<String> commentBlocked = new HashSet<>(analysis.queryForList("""
				SELECT DISTINCT m.short_code FROM content_comments m
				WHERE NOT EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = m.short_code)""",
				String.class));
		if (phase == Phase.SYNTHESIS) {
			Set<String> factsOnly = new HashSet<>(analysis.queryForList(
					"SELECT short_code FROM content_analyses WHERE metric_timeliness = 'pending'",
					String.class));
			List<Map<String, Object>> targets = new ArrayList<>();
			for (Map<String, Object> row : candidates) {
				String shortCode = (String) row.get("short_code");
				if (factsOnly.contains(shortCode) && !commentBlocked.contains(shortCode)) {
					targets.add(row);
				}
			}
			return targets;
		}

		Set<String> analyzed = new HashSet<>(
				analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
		List<Map<String, Object>> targets = new ArrayList<>();
		for (Map<String, Object> row : candidates) {
			String shortCode = (String) row.get("short_code");
			if (analyzed.contains(shortCode) || commentBlocked.contains(shortCode)) {
				continue;
			}
			targets.add(row);
		}
		return targets;
	}
```

- [ ] 배치 제출을 phase 축으로 바꾼다.

```java
	/** content_batch_jobs.kind - 수거 잡이 응답 스키마를 고르는 값(§4-5). */
	private static String kindOf(Phase phase) {
		return switch (phase) {
			case UNIFIED -> "analyze";
			case FACTS -> "facts";
			case SYNTHESIS -> "synthesis";
		};
	}

	/** 배치·업로드 표시 이름 접두사 - GCS 콘솔에서도 단계를 구분할 수 있게 한다. */
	private static String namePrefixOf(Phase phase) {
		return switch (phase) {
			case UNIFIED -> "hypenow-analyze";
			case FACTS -> "hypenow-facts";
			case SYNTHESIS -> "hypenow-synth";
		};
	}

	private JobResult submitBatch(Phase phase, boolean timely, List<Map<String, Object>> targets,
			Baselines baselines) {
		JobResult swept = collectJob.run();
		if (swept.processed() > 0 || swept.failed() > 0) {
			log.info("배치 제출 전 pending 수거 - {}건 저장, {}건 실패", swept.processed(), swept.failed());
		}
		if (targets.isEmpty()) {
			log.info("배치 제출 대상 없음 - 제출 생략 (phase={}, timely={})", phase, timely);
			return new JobResult(0, 0, false);
		}
		// 파트 B는 저장된 사실을 프롬프트에 실어야 한다 - 콘텐츠마다 조회하면 제출이 DB 왕복에
		// 잠기므로 pending 행 전량을 1회 조회로 받아 둔다(기준선 로딩과 같은 이유).
		Map<String, Map<String, Object>> storedFacts = phase == Phase.SYNTHESIS
				? StoredFacts.loadPending(analysis) : Map.of();
		int chunkSize = settings.batchChunkSize();
		int submitted = 0;
		int chunks = 0;
		for (int from = 0; from < targets.size(); from += chunkSize) {
			List<Map<String, Object>> chunk =
					targets.subList(from, Math.min(from + chunkSize, targets.size()));
			// 업로드 이름은 청크마다 유일해야 한다 - 실구현(VertexHttpApi)의 GCS 객체 경로가
			// displayName 그대로라, 같은 이름이면 뒤 청크가 앞 청크 입력 파일을 덮어쓴다
			// (2026-08-31 운영 실발생 - 3,000건 배치가 795건 결과·전원 사이드카 매칭 실패).
			submitOneChunk(phase, timely, chunk, baselines, storedFacts,
					"%s-%d-c%d".formatted(namePrefixOf(phase), System.currentTimeMillis(), chunks));
			submitted += chunk.size();
			chunks++;
		}
		log.info("분석 배치 제출 완료 - phase={}, 총 {}건, 청크 {}개(상한 {}), timely={}",
				phase, submitted, chunks, chunkSize, timely);
		return new JobResult(submitted, 0, false);
	}

	private void submitOneChunk(Phase phase, boolean timely, List<Map<String, Object>> targets,
			Baselines baselines, Map<String, Map<String, Object>> storedFacts, String uploadName) {
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		String system = switch (phase) {
			case UNIFIED -> GeminiContentAnalyzer.instructions(taxonomy);
			case FACTS -> GeminiContentAnalyzer.factsInstructions(taxonomy);
			case SYNTHESIS -> GeminiContentSynthesizer.instructions();
		};
		String model = settings.activeLlmModel();
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		for (Map<String, Object> content : targets) {
			String shortCode = (String) content.get("short_code");
			Map<String, Object> row = new LinkedHashMap<>(content);
			// 구 contents 조회 결과에 없던 키 - 프롬프트/사이드카 입력 계약을 그대로 보존한다.
			row.remove("short_code");
			row.remove("thumbnail_url");
			if (phase == Phase.FACTS) {
				// 파트 A는 기준선·지표·댓글 분포를 안 싣는다. 사이드카 키 계약(SIDECAR_KEYS)만
				// 채우면 되므로 timely는 false 고정으로 넣고 수거가 읽지 않는다.
				row.put("timely", false);
				jsonl.append(json.writeValueAsString(
								GeminiBatchLines.factsRequestLine(json, shortCode, row, system)))
						.append('\n');
			} else {
				Baseline b = baselines.withBaseline().get(shortCode);
				if (b == null) {
					Baseline accountAvg = baselines.accountBaseline().get((String) content.get("account_handle"));
					b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
				}
				Map<String, Long> categoryCounts = commentCategoryCounts(shortCode);
				putBaseline(row, b);
				row.put("timely", timely);
				jsonl.append(json.writeValueAsString(phase == Phase.UNIFIED
								? GeminiBatchLines.requestLine(json, shortCode, row, categoryCounts, system)
								: GeminiBatchLines.synthesisRequestLine(json, shortCode, row, categoryCounts,
										storedFacts.getOrDefault(shortCode, Map.of()), system)))
						.append('\n');
			}
			sidecar.append(json.writeValueAsString(GeminiBatchLines.sidecarLine(json, shortCode, row)))
					.append('\n');
		}
		String fileName = batchApi.uploadFile(jsonl.toString().getBytes(StandardCharsets.UTF_8), uploadName);
		String batchName = batchApi.createBatch(model, fileName, uploadName);
		// 사이드카는 로컬 파일이 아니라 DB 컬럼에 보관한다 - analytics 컨테이너에는 쓰기 가능한
		// 볼륨이 없어(deploy/compose.yaml), 제출~수거 사이에 배포·컨테이너 교체가 끼면 로컬 파일은
		// 유실되고 pending 행이 영원히 pending으로 남는 좀비가 된다(리뷰 지적, 08-11).
		analysis.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, status, sidecar_jsonl, kind)
				VALUES (?, ?, ?, 'pending', ?, ?)""",
				batchName, phase == Phase.FACTS ? false : timely, targets.size(),
				sidecar.toString(), kindOf(phase));
		log.info("분석 배치 청크 제출 - batch={}, kind={}, {}건, timely={}",
				batchName, kindOf(phase), targets.size(), timely);
	}

	/** 기준선 10키를 프롬프트/사이드카 입력 맵에 싣는다 - 배치 제출 경로 공용. */
	private static void putBaseline(Map<String, Object> row, Baseline b) {
		row.put("recent_reels_avg_views", b.recentReelsAvgViews());
		row.put("rank_in_recent_reels", b.rankInRecentReels());
		row.put("recent_reels_count", b.recentReelsCount());
		row.put("recent_contents_count", b.recentContentsCount());
		row.put("recent12_avg_engagement_rate", b.recent12AvgEngagementRate());
		row.put("recent12_avg_like_count", b.recent12AvgLikeCount());
		row.put("recent12_avg_comment_count", b.recent12AvgCommentCount());
		row.put("category_top_percentile", b.categoryTopPercentile());
		row.put("category_avg_views", b.categoryAvgViews());
		row.put("category_sample_size", b.categorySampleSize());
	}

	/** 댓글 분류 분포 - 온라인·배치 경로가 같은 쿼리를 쓴다(프롬프트 근거가 갈리지 않게). */
	private Map<String, Long> commentCategoryCounts(String shortCode) {
		Map<String, Long> counts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					counts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);
		return counts;
	}
```

기존 `submitOneChunk` 안의 기준선 put 10줄과 댓글 분포 조회는 위 두 헬퍼로 대체된다. `analyzeOne`(403행)의 댓글 분포 조회도 `commentCategoryCounts(shortCode)` 호출로 바꾼다.

- [ ] 온라인 루프를 phase 축으로 바꾼다. 병렬·429 이월·실패 격리 골격은 그대로 두고 콘텐츠 1건 처리만 분기한다.

`runOnline` 시그니처와 태스크 본문을 아래로 바꾼다(322~344행 구간).

```java
	private JobResult runOnline(Phase phase, boolean timely, List<Map<String, Object>> targets,
			Baselines baselines, ProgressReporter progress) {
		String model = settings.activeLlmModel();
		// 파트 B 온라인 경로도 저장된 사실이 필요하다 - 배치와 같은 이유로 1회 조회.
		Map<String, Map<String, Object>> storedFacts = phase == Phase.SYNTHESIS
				? StoredFacts.loadPending(analysis) : Map.of();
		AtomicInteger processedCount = new AtomicInteger();
		AtomicInteger failedCount = new AtomicInteger();
		AtomicBoolean quotaExhausted = new AtomicBoolean();
		progress.report(0, 0, targets.size());

		List<Callable<Void>> tasks = new ArrayList<>();
		for (Map<String, Object> content : targets) {
			String shortCode = (String) content.get("short_code");
			tasks.add(() -> {
				if (quotaExhausted.get()) {
					return null;
				}
				try {
					switch (phase) {
						case UNIFIED -> analyzeOne(content, model, baselines.withBaseline(),
								baselines.accountBaseline(), timely);
						case FACTS -> analyzeFactsOne(content, model);
						case SYNTHESIS -> synthesizeOne(content, model, baselines.withBaseline(),
								baselines.accountBaseline(), storedFacts, timely);
					}
					int p = processedCount.incrementAndGet();
					progress.report(p, failedCount.get(), targets.size());
				} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
					quotaExhausted.set(true);
					log.warn("LLM 일 한도 소진 감지 - {} 스킵(이월), 이후 미착수 대상도 스킵됨", shortCode);
				} catch (Exception e) {
					int f = failedCount.incrementAndGet();
					log.error("analysis failed for {} - 다음 실행에서 재대상", shortCode, e);
					progress.report(processedCount.get(), f, targets.size());
				}
				return null;
			});
		}
		// (이하 풀 실행·최종 보고는 현행 그대로)
```

`analyzeOne` 바로 뒤에 phase별 1건 처리 두 개를 추가한다.

```java
	/**
	 * 파트 A 온라인 1건 - 캡션(+썸네일 게이트 on이면 생존 썸네일)만 보고 사실을 추출해 pending으로 저장한다.
	 * 캡션도 썸네일도 없으면 속성 근거가 없으므로 폐기하고 컬럼 NULL로 행만 만든다(통합 경로와 같은 규칙).
	 */
	private void analyzeFactsOne(Map<String, Object> content, String model) {
		String shortCode = (String) content.get("short_code");
		String caption = (String) content.get("caption");
		String thumbnailUrl = (String) content.get("thumbnail_url");
		boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
		if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
			log.info("썸네일 만료/접근 불가 - 캡션만으로 사실 추출: {}", shortCode);
		}
		boolean hasCaption = caption != null && !caption.isBlank();
		ContentAttributes attrs = factsPort.extractFacts(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), caption, (String) content.get("content_type"),
				null, null, null, Map.of(), Map.of(), (Boolean) content.get("ad_marked")),
				attachThumbnail ? thumbnailUrl : null);
		if (!hasCaption && !attachThumbnail) {
			attrs = null;
		} else if (Boolean.TRUE.equals(attrs.isRelevant()) && attrs.mainCategory() == null) {
			// 통합 경로와 같은 처방 - temperature 0 결정론이라 재대상해도 결과가 같다(무한 루프 방지).
			log.info("분류 대상이나 대분류 미도출 - 미분류로 종결 저장(재시도 루프 방지): {}", shortCode);
			attrs = attrs.asUnclassified();
		}
		ContentAnalysisWriter.insertFacts(analysis, json, shortCode, model, attrs);
	}

	/**
	 * 파트 B 온라인 1건 - 저장된 사실 + 핀 지표 + 기준선으로 해석 5필드를 만들고 시점을 확정한다.
	 * 빈 종합은 저장하지 않는다 - 저장하면 pending이 풀려 다시 대상이 되지 않는다.
	 */
	private void synthesizeOne(Map<String, Object> content, String model,
			Map<String, Baseline> withBaseline, Map<String, Baseline> accountBaseline,
			Map<String, Map<String, Object>> storedFacts, boolean timely) {
		String shortCode = (String) content.get("short_code");
		Baseline b = withBaseline.get(shortCode);
		if (b == null) {
			Baseline accountAvg = accountBaseline.get((String) content.get("account_handle"));
			b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
		}
		Synthesis s = synthesisPort.synthesize(new ContentToSynthesize(shortCode,
				(String) content.get("account_handle"), (String) content.get("content_type"),
				(Long) content.get("views"), (Long) content.get("likes"), (Long) content.get("comments"),
				PromptBaseline.of(b), commentCategoryCounts(shortCode),
				storedFacts.getOrDefault(shortCode, Map.of())));
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("해석 문구가 비어 있음: " + shortCode);
		}
		int updated = ContentAnalysisWriter.updateSynthesis(analysis, shortCode, model, b, s,
				timely ? "timely" : "late_backfill");
		if (updated == 0) {
			throw new IllegalStateException("해석 UPDATE 0행 - 그 사이 행이 사라짐: " + shortCode);
		}
	}
```

import에 `com.celfit.analytics.llm.ContentFactsPort`·`ContentSynthesisPort`·`ContentToSynthesize`·`GeminiContentSynthesizer`를 추가한다.

- [ ] `JobConfig.contentAnalysisJob`을 새 생성자로 배선한다.

```java
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			// vlm-enabled = 썸네일 첨부 게이트 (기본 off - 캡션 기반 5종은 항상 산출)
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled,
			ObjectProvider<JobProgressRegistry> progressRegistry,
			ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini,
			com.celfit.analytics.llm.BeautyTaxonomyLoader taxonomyLoader,
			ObjectProvider<com.celfit.analytics.llm.ContentFactsPort> factsPort,
			ObjectProvider<ContentSynthesisPort> synthesisPort) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null ? registry.reporter(JobName.ANALYZE) : ProgressReporter.NOOP;
		ProgressReporter backfillReporter = registry != null
				? registry.reporter(JobName.LATE_BACKFILL_ANALYZE) : ProgressReporter.NOOP;
		ProgressReporter factsReporter = registry != null
				? registry.reporter(JobName.FACT_ANALYZE) : ProgressReporter.NOOP;
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, insight,
				settings, thumbnailEnabled, headPrecheck(), reporter, backfillReporter,
				batchApiOrNull(settings, gemini), taxonomyLoader,
				factsReporter, splitPortOrNull(settings, factsPort), splitPortOrNull(settings, synthesisPort));
	}

	/**
	 * 2단계 분리(split) 전용 포트 - provider=anthropic이면 조회 자체를 하지 않는다.
	 * batchApiOrNull과 같은 관용구: @Lazy 빈이라도 getIfAvailable()은 생성을 강제해,
	 * GEMINI_API_KEY 없이 anthropic만으로 운영 중인 환경에서 불필요한 키 부재 예외를 낸다.
	 * split은 gemini/vertex 전용이며, anthropic 경로는 unified 모드로 남는다.
	 */
	private static <T> T splitPortOrNull(AnalyticsSettings settings, ObjectProvider<T> port) {
		return "anthropic".equals(settings.llmProvider()) ? null : port.getIfAvailable();
	}
```

import에 `com.celfit.analytics.llm.ContentSynthesisPort`가 이미 있으므로 추가는 `ContentFactsPort`뿐이며, 위 스니펫은 FQN으로 썼으니 그대로 둬도 된다.

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"
```

기대: `BUILD SUCCESSFUL`. 기존 케이스(통합 경로 20여 개)가 전부 통과해야 "토글 전 행동 불변"이 확인된다.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): ContentAnalysisJob에 Phase 축(UNIFIED/FACTS/SYNTHESIS) 추가

새 클래스 대신 phase를 더한 이유: 후보 조회·제외 게이트·기준선 로딩·청크 분할·
배치 제출·429 이월 배관이 이미 여기 있고 파트 B 배치도 같은 배관을 탄다.
FACTS 제외는 '행 존재' 하나(댓글 게이트 없음 - 파트 A는 댓글을 안 본다),
SYNTHESIS는 '후보 ∩ pending' 포함 집합으로 A 부재·B 완료를 한 번에 처리한다.
mode=unified면 runFacts는 no-op이라 토글 전 행동 변화 0.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `ContentBatchCollectJob`의 kind 분기

**Files:**
- `analytics/src/main/java/com/celfit/analytics/analyze/ContentBatchCollectJob.java` (52~72행 pending 조회·순회, 75~132행 `collectOne`)
- `analytics/src/test/java/com/celfit/analytics/analyze/ContentBatchCollectJobTest.java` (65~69행 헬퍼, 파일 끝에 케이스 추가)

### 실패하는 테스트 먼저

- [ ] 헬퍼에 kind 인자를 받는 오버로드를 추가한다(기존 2개는 그대로 두어 기존 케이스가 `analyze` 기본값을 계속 검증하게 한다).

```java
	void insertPendingBatchJob(String batchName, boolean timely, int submittedCount,
			String sidecarJsonl, String kind) {
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, status, sidecar_jsonl, kind)
				VALUES (?, ?, ?, 'pending', ?, ?)""", batchName, timely, submittedCount, sidecarJsonl, kind);
	}
```

- [ ] 파일 끝에 kind 3종 케이스를 추가한다.

```java
	static final String FACTS_JSON = """
			{"detectedBrands":null,"sponsoredSignalLevel":"low","sponsoredSignalReasons":null,
			 "adDisclosure":"표기 없음","detectedProductCategories":["클렌징폼"],"detectedProducts":null,
			 "vlmAttributes":null,"isRelevant":true,"mainCategory":"cleansing","subCategories":["클렌징폼"],
			 "detectedDistributors":null,"adType":"organic"}"""
			.replace("\n", "");

	static final String SYNTHESIS_JSON = """
			{"aiContentSummary":"평균 수준","contentsPattern":"루틴형","aiCommentInsight":"표본 부족",
			 "commentAuthenticityGrade":"normal","commentAuthenticityNote":"근거"}"""
			.replace("\n", "");

	@Test
	void kind_facts_배치는_사실만_저장하고_pending으로_남긴다() {
		insertPendingBatchJob("batches/f1", false, 1, sidecarLine("cc_f", false), "facts");
		String resultJsonl = """
				{"key":"cc_f","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(FACTS_JSON));

		JobResult result = collectJob(succeededApi("files/f1", resultJsonl)).run();

		assertEquals(1, result.processed());
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'cc_f'", String.class));
		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'cc_f'", String.class));
		assertNull(db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'cc_f'", String.class));
		// 사이드카의 timely=false는 facts kind에서 무시된다(파트 B가 확정한다)
		assertEquals("collected", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/f1'", String.class));
	}

	@Test
	void kind_synthesis_배치는_해석을_UPDATE하고_시점을_확정한다() {
		// 파트 A 행이 먼저 있어야 한다
		db.update("""
				INSERT INTO content_analyses (short_code, model, main_category, ad_type, is_beauty,
				  metric_timeliness) VALUES ('cc_s', 'facts-model', 'cleansing', 'organic', true, 'pending')""");
		insertPendingBatchJob("batches/s1", true, 1, sidecarLine("cc_s", true), "synthesis");
		String resultJsonl = """
				{"key":"cc_s","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(SYNTHESIS_JSON));

		JobResult result = collectJob(succeededApi("files/s1", resultJsonl)).run();

		assertEquals(1, result.processed());
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'cc_s'", String.class));
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'cc_s'", String.class));
		// 사이드카의 기준선 스냅샷이 그대로 복원된다
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'cc_s'", Long.class));
		// 파트 A 컬럼은 보존
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'cc_s'", String.class));
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void kind_synthesis에서_대상_행이_없으면_저장_실패로_센다() {
		// 제출~수거 사이에 행이 사라진 경우 - 0행 갱신은 성공이 아니다
		insertPendingBatchJob("batches/s2", true, 1, sidecarLine("cc_gone", true), "synthesis");
		String resultJsonl = """
				{"key":"cc_gone","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(SYNTHESIS_JSON));

		JobResult result = collectJob(succeededApi("files/s2", resultJsonl)).run();

		assertEquals(0, result.processed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		// 배치 자체는 수거 완료로 전이한다(라인 실패는 다음 후보 diff가 흡수)
		assertEquals("collected", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/s2'", String.class));
	}

	@Test
	void kind_기본값_analyze는_통합_파서로_처리된다() {
		// 롤링 창·롤백 직후 구 코드가 남긴 pending 행 - kind 컬럼을 모르고 INSERT한다
		insertPendingBatchJob("batches/legacy", true, 1, sidecarLine("cc_legacy", true));
		String resultJsonl = """
				{"key":"cc_legacy","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));

		JobResult result = collectJob(succeededApi("files/l1", resultJsonl)).run();

		assertEquals(1, result.processed());
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'cc_legacy'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'cc_legacy'", String.class));
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'cc_legacy'", String.class));
	}
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentBatchCollectJobTest"
```

기대 실패: `kind_facts_*`는 통합 파서가 `aiContentSummary` 부재로 false를 돌려 `processed=0`, `kind_synthesis_*`는 파싱 실패 또는 INSERT 충돌로 실패한다.

### 구현

- [ ] pending 조회에 `kind`를 더하고 `collectOne`에 넘긴다.

```java
		List<Map<String, Object>> pending = analysis.queryForList("""
				SELECT id, batch_name, sidecar_jsonl, kind FROM content_batch_jobs
				WHERE status = 'pending' ORDER BY submitted_at""");
		int collected = 0;
		int failed = 0;
		for (Map<String, Object> row : pending) {
			long id = ((Number) row.get("id")).longValue();
			String batchName = (String) row.get("batch_name");
			String sidecarJsonl = (String) row.get("sidecar_jsonl");
			// kind는 NOT NULL DEFAULT 'analyze'지만, 컬럼 추가 직전에 제출된 행을 방어적으로 흡수한다.
			String kind = row.get("kind") == null ? "analyze" : (String) row.get("kind");
			try {
				collected += collectOne(id, batchName, sidecarJsonl, kind);
			} catch (Exception e) {
				failed++;
				log.error("배치 수거 실패 - batch_name={}", batchName, e);
			}
		}
```

- [ ] `collectOne` 시그니처에 `kind` 파라미터를 더한다(75행). 76~113행(배치 상태 판정, 결과 파일 이름 판독, 사이드카 유실·파싱 실패 처리)은 **한 줄도 바꾸지 않는다** - kind와 무관한 공통 전처리다. 114행 이후만 아래로 바꾼다.

```java
	private int collectOne(long id, String batchName, String sidecarJsonl, String kind) {
		// (76~113행: 상태 판정·결과 파일 이름·사이드카 복원은 현행 그대로 둔다)
		String model = settings.activeLlmModel();
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		// 결과(운영 실측 119MB+)는 스트리밍으로 한 줄씩 받아 즉시 파싱·저장 - 전체 적재 금지(07-20 OOM)
		AtomicInteger saved = new AtomicInteger();
		AtomicInteger lineFailed = new AtomicInteger();
		batchApi.downloadResults(resultFile, line -> {
			// 응답 스키마가 kind마다 다르므로 파서를 여기서 고른다(2026-09-03 2단계 분리 §5).
			//   analyze   : 통합 1콜(레거시·롤백 경로) - 사실 + 해석 한 번에 INSERT
			//   facts     : 파트 A - insertFacts(metric_timeliness='pending', ON CONFLICT DO NOTHING)
			//   synthesis : 파트 B - updateSynthesis(사이드카 timely로 시점 확정, 0행이면 warn)
			boolean ok = switch (kind) {
				case "facts" -> GeminiBatchLines.processFactsResultLine(
						analysis, om, line, sidecar, model, taxonomy);
				case "synthesis" -> GeminiBatchLines.processSynthesisResultLine(
						analysis, om, line, sidecar, model);
				default -> GeminiBatchLines.processResultLine(analysis, om, line, sidecar, model, taxonomy);
			};
			if (ok) {
				saved.incrementAndGet();
			} else {
				lineFailed.incrementAndGet();
			}
		});
		analysis.update("""
				UPDATE content_batch_jobs SET status = 'collected', collected_at = now(), sidecar_jsonl = NULL
				WHERE id = ?""", id);
		log.info("배치 수거 완료 - batch_name={}, kind={}, {}건 저장, {}건 실패",
				batchName, kind, saved.get(), lineFailed.get());
		return saved.get();
	}
```

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentBatchCollectJobTest"
```

기대: `BUILD SUCCESSFUL`. 기존 7개 케이스(멱등·FAILED 전이·사이드카 유실 등)가 그대로 통과해야 한다.

- [ ] analytics 모듈 전체를 한 번 돌려 여기까지의 회귀를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test
```

기대: `BUILD SUCCESSFUL`.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics/analyze/ContentBatchCollectJob.java analytics/src/test/java/com/celfit/analytics/analyze/ContentBatchCollectJobTest.java
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): 배치 수거를 content_batch_jobs.kind로 분기

analyze(통합·레거시)·facts(파트 A)·synthesis(파트 B) 3종. kind가 NULL인 행도
analyze로 흡수해 컬럼 추가 직전 제출분을 방어한다. synthesis는 0행 갱신을
성공으로 세지 않는다 - 제출~수거 사이에 행이 사라진 경우다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```
---

## Task 9: `JobName.FACT_ANALYZE` 배선 (잡 서비스 · 스케줄 · 어드민 카드 · compose)

파트 A와 파트 B는 `JobName`이 달라 `JobLock`이 잡별로 독립이므로 같은 시각에 걸어도 서로 막지 않는다. 다만 배치 제출 직전 pending 수거(`collectJob.run()`) 호출이 두 잡에서 겹치면 BATCH_COLLECT 락에서 한쪽이 BUSY로 건너뛰는데, 30분 뒤 정기 수거가 받쳐 주므로 무해하다. 크론은 KST 기준 A 05:00 · B 05:30 · 늦크롤 B 06:00으로 15~30분 간격을 둔다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/admin/JobName.java` (7행 `ANALYZE` 뒤)
- `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java` (49~50행 `DERIVED_INPUT_JOBS`, 158행 `case ANALYZE` 옆)
- `analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java` (41행 `analyze()` 뒤)
- `analytics/src/main/java/com/celfit/analytics/admin/ScheduleInfo.java` (19~31행 생성자)
- `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java` (80~89행 `scheduleInfo` 빈)
- `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java` (36~39행 `DASHBOARD_JOBS`만. `scopeLine`·`scopeSubLine`은 Task 11에서)
- `deploy/compose.yaml` (132~139행 analytics 스케줄 env)
- `analytics/src/test/java/com/celfit/analytics/admin/JobNameTest.java`, `ScheduleInfoTest.java`, `AnalyticsJobServiceTest.java`

### 실패하는 테스트 먼저

- [ ] `JobNameTest`에 슬러그 케이스를 추가한다.

```java
	@Test
	void fact_analyze_슬러그() {
		assertThat(JobName.FACT_ANALYZE.slug()).isEqualTo("fact-analyze");
		assertThat(JobName.fromSlug("fact-analyze")).isEqualTo(JobName.FACT_ANALYZE);
	}
```

- [ ] `ScheduleInfoTest`의 기존 두 케이스는 `new ScheduleInfo(...)`를 5개 크론 인자로 호출한다. 인자가 하나 늘므로 두 호출 모두 `"-"`를 하나씩 더하고, fact-analyze 케이스를 추가한다.

```java
	@Test
	void 크론_다음_발화를_KST로() {
		ScheduleInfo info = new ScheduleInfo(true, "0 30 19 * * *", "-", "-", "-", "-", "-");
		// (이하 기존 그대로)
	}

	@Test
	void 비활성이면_전부_빈값() {
		ScheduleInfo info = new ScheduleInfo(false, "0 30 19 * * *", "-", "-", "-", "-", "-");
		// (이하 기존 그대로)
	}

	@Test
	void fact_analyze_크론도_KST로_계산된다() {
		// 운영 기본: UTC 20:00 = KST 05:00 (파트 A). 파트 B는 30분 뒤다.
		ScheduleInfo info = new ScheduleInfo(true, "-", "-", "0 30 20 * * *", "-", "-", "0 0 20 * * *");
		ZonedDateTime base = ZonedDateTime.of(2026, 9, 3, 10, 0, 0, 0, ZoneId.of("UTC"));

		assertThat(info.next(JobName.FACT_ANALYZE, base))
				.hasValueSatisfying(t -> {
					assertThat(t.getHour()).isEqualTo(5);
					assertThat(t.getMinute()).isZero();
				});
		assertThat(info.next(JobName.ANALYZE, base))
				.hasValueSatisfying(t -> {
					assertThat(t.getHour()).isEqualTo(5);
					assertThat(t.getMinute()).isEqualTo(30);
				});
	}
```

- [ ] `AnalyticsJobServiceTest`에 디스패치·파생 뷰 갱신 케이스를 추가한다. 기존 케이스가 쓰는 스텁 관용구(`service()`·`org.mockito.Mockito.verify`)를 그대로 쓴다.

```java
	@Test
	void fact_analyze_잡을_트리거하면_runFacts가_호출된다() {
		var result = service().trigger(JobName.FACT_ANALYZE, TriggerType.MANUAL);

		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.FACT_ANALYZE);
	}

	@Test
	void fact_analyze도_파생_matview_갱신_대상이다() {
		// 파트 A가 채우는 사실 컬럼(is_beauty·main_category·ad_type)이 발굴 사전집계 MV의 입력이다 -
		// 온라인 폴백 경로에서 수거 잡을 안 타므로 이 잡 자체가 갱신 후크를 가져야 한다.
		service().trigger(JobName.FACT_ANALYZE, TriggerType.MANUAL);

		org.mockito.Mockito.verify(derivedViewRefresher).refresh();
	}
```

기존 테스트가 `analyzeJob` 스텁을 어떻게 만드는지 확인하고(`ObjectProvider<ContentAnalysisJob>` mock), `runFacts()`가 `JobResult`를 돌려주도록 스텁을 보강한다.

```bash
grep -n "analyzeJob\|ContentAnalysisJob" $REPO/analytics/src/test/java/com/celfit/analytics/admin/AnalyticsJobServiceTest.java
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.admin.*"
```

기대 실패: 컴파일 에러 `cannot find symbol: variable FACT_ANALYZE`, `constructor ScheduleInfo ... cannot be applied`.

### 구현

- [ ] `JobName`에 상수를 추가한다(`ANALYZE` 바로 뒤 - 어드민 목록 순서와 실행 순서를 맞춘다).

```java
	FACT_ANALYZE("콘텐츠 사실 분석 (LLM) - 파트 A, 캡션 전용"),
```

- [ ] `AnalyticsJobService`의 `DERIVED_INPUT_JOBS`에 추가하고 디스패치를 더한다.

```java
	/** 파생 matview 입력(account_content_series·content_analyses)을 쓰는 잡 - 완료 후 사전집계를 갱신한다.
	 *  FACT_ANALYZE(2026-09-03)는 사실 컬럼(is_beauty·main_category·ad_type)을 채우므로 같은 대상이다 -
	 *  배치 경로에서는 BATCH_COLLECT가 갱신하지만 온라인 폴백 경로에는 이 후크뿐이다. */
	private static final Set<JobName> DERIVED_INPUT_JOBS = EnumSet.of(
			JobName.MIRROR, JobName.ANALYZE, JobName.FACT_ANALYZE,
			JobName.LATE_BACKFILL_ANALYZE, JobName.BATCH_COLLECT);
```

```java
			case ANALYZE -> analyzeJob.getObject().run();
			// 파트 A(사실) - 같은 빈의 다른 진입점. analyze-mode=unified면 잡 안에서 no-op이다.
			case FACT_ANALYZE -> analyzeJob.getObject().runFacts();
			case LATE_BACKFILL_ANALYZE -> analyzeJob.getObject().runLateBackfill();
```

- [ ] `ScheduleRunner`에 훅을 추가한다(`analyze()` 바로 뒤 - 실행 순서대로).

```java
	/** 파트 A(사실) 배치 - 2026-09-03 2단계 분리. analytics.analyze-mode=unified면 잡이 no-op이라
	 * 크론이 돌아도 로그 한 줄만 남는다(토글 전 무해). */
	@Scheduled(cron = "${analytics.schedule.fact-analyze-cron:-}")
	void factAnalyze() {
		log.info("스케줄 fact-analyze: {}", jobService.trigger(JobName.FACT_ANALYZE, TriggerType.SCHEDULED));
	}
```

- [ ] `ScheduleInfo` 생성자에 크론을 추가한다.

```java
	public ScheduleInfo(@Value("${analytics.schedule.enabled:false}") boolean enabled,
			@Value("${analytics.schedule.mirror-cron:-}") String mirrorCron,
			@Value("${analytics.schedule.classify-cron:-}") String classifyCron,
			@Value("${analytics.schedule.analyze-cron:-}") String analyzeCron,
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron,
			@Value("${analytics.schedule.archive-cron:-}") String archiveCron,
			@Value("${analytics.schedule.fact-analyze-cron:-}") String factAnalyzeCron) {
		this.enabled = enabled;
		put(JobName.MIRROR, mirrorCron);
		put(JobName.CLASSIFY, classifyCron);
		put(JobName.ANALYZE, analyzeCron);
		put(JobName.FACT_ANALYZE, factAnalyzeCron);
		put(JobName.ACCOUNT_ANALYZE, accountCron);
		put(JobName.ARCHIVE, archiveCron);
	}
```

- [ ] `AdminConfig.scheduleInfo` 빈에 같은 프로퍼티를 추가한다.

```java
	@Bean
	public ScheduleInfo scheduleInfo(
			@Value("${analytics.schedule.enabled:false}") boolean enabled,
			@Value("${analytics.schedule.mirror-cron:-}") String mirrorCron,
			@Value("${analytics.schedule.classify-cron:-}") String classifyCron,
			@Value("${analytics.schedule.analyze-cron:-}") String analyzeCron,
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron,
			@Value("${analytics.schedule.archive-cron:-}") String archiveCron,
			@Value("${analytics.schedule.fact-analyze-cron:-}") String factAnalyzeCron) {
		return new ScheduleInfo(enabled, mirrorCron, classifyCron, analyzeCron, accountCron,
				archiveCron, factAnalyzeCron);
	}
```

- [ ] `AdminUiController`의 잡 카드 목록에 추가한다(`ANALYZE` 앞 - 실행 순서대로 보이게).

```java
	private static final List<JobName> DASHBOARD_JOBS =
			List.of(JobName.MIRROR, JobName.FACT_ANALYZE, JobName.ANALYZE,
					JobName.LATE_BACKFILL_ANALYZE, JobName.ACCOUNT_ANALYZE, JobName.ARCHIVE,
					JobName.TRAIT_CANON_DRY, JobName.TRAIT_CANON_APPLY);
```

`scopeLine`·`scopeSubLine`의 switch는 이 태스크에서 건드리지 않는다. 둘 다 `default -> null`이 있어 FACT_ANALYZE 카드가 대상 한 줄 없이 렌더될 뿐 컴파일·렌더가 깨지지 않는다. 대상 수치는 `Heavy`에 파트 A 축이 생긴 뒤(Task 11)에 붙인다.

- [ ] `deploy/compose.yaml`의 analytics 스케줄 블록을 고친다. 132행 주석과 136행을 아래로 바꾸고 138행 앞에 fact-analyze를 넣는다.

```yaml
      # 스케줄(KST=UTC+9): 미러 04:30 → 아카이브 04:50 → 사실 분석 05:00 → 해석 분석 05:30
      #                    → 늦크롤 백필 06:00 → 계정 카피 07:00 (백업 04:10 이후)
      ANALYTICS_SCHEDULE_ENABLED: "true"
      ANALYTICS_SCHEDULE_MIRROR_CRON: "0 30 19 * * *"
      ANALYTICS_SCHEDULE_ARCHIVE_CRON: "0 50 19 * * *"
      # 파트 A(사실) - 2026-09-03 2단계 분리. app_setting analytics.analyze-mode=unified면
      # 잡이 no-op 로그만 남긴다(토글을 켜기 전까지 이 크론은 무해).
      ANALYTICS_SCHEDULE_FACT_ANALYZE_CRON: "0 0 20 * * *"
      # 파트 B(해석). unified 모드에서는 현행 통합 1콜 그대로다. 파트 A 제출과 15~30분 띄운다 -
      # 제출 직전 pending 수거가 겹치면 BATCH_COLLECT 락에서 한쪽이 BUSY로 건너뛴다(30분 뒤
      # 정기 수거가 받쳐 주므로 무해하지만 로그가 어지럽다).
      ANALYTICS_SCHEDULE_ANALYZE_CRON: "0 30 20 * * *"
      # 늦크롤 백필 - timely와 예산 독립(2026-07-23 분리). 신규 계정 온보딩 백카탈로그가 주 대상
      ANALYTICS_SCHEDULE_LATE_BACKFILL_ANALYZE_CRON: "0 0 21 * * *"
      ANALYTICS_SCHEDULE_ACCOUNT_ANALYZE_CRON: "0 0 22 * * *"
```

- [ ] 테스트를 돌린다(Task 11 완료 후 최종 확인).

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.admin.*"
```

기대: `BUILD SUCCESSFUL`.

- [ ] compose YAML 문법을 확인한다.

```bash
cd $REPO/deploy && docker compose -f compose.yaml config >/dev/null && echo "compose OK"
```

기대: `compose OK`. `.env` 미설정 경고는 무시한다(값 치환 경고일 뿐 문법 검증은 통과).

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics/admin analytics/src/test/java/com/celfit/analytics/admin deploy/compose.yaml
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): FACT_ANALYZE 잡 배선 + 새벽 크론 05:00, 해석 분석 05:30으로 이동

파트 A와 파트 B는 JobName이 달라 JobLock이 독립이지만, 제출 직전 pending 수거가
겹치면 BATCH_COLLECT 락에서 한쪽이 BUSY로 건너뛴다 - 30분 간격을 둔다.
FACT_ANALYZE를 DERIVED_INPUT_JOBS에 넣는다: 파트 A가 채우는 사실 컬럼이 발굴
사전집계 MV의 입력이고, 온라인 폴백 경로에는 이 후크뿐이다.
analyze-mode=unified면 잡이 no-op이라 크론이 돌아도 로그 한 줄만 남는다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `ContentSynthesisRefreshJob`에 pending 제외 가드

재생성 잡의 대상 조건은 `synthesis_version IS DISTINCT FROM ?` 하나다. 파트 A 행은 `synthesis_version`이 NULL이므로 **그대로 두면 이 잡이 A만 행을 낚아채 온라인으로 파트 B를 돌려버린다** - 성숙 가드가 없어 D+1 미성숙 지표를 인용한 문구가 만들어지고, 일 3,500건이 동기 호출로 나간다. 이 잡의 역할을 "이미 해석이 있는 행의 재생성"으로 한정한다.

**NULL 처리 주의:** `metric_timeliness <> 'pending'`은 값이 NULL인 레거시 행(V33 이전 기분석분)을 조용히 제외해 버린다. `IS DISTINCT FROM 'pending'`을 써야 NULL 행이 계속 대상으로 남는다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/analyze/ContentSynthesisRefreshJob.java` (53~57행 대상 SQL)
- `analytics/src/test/java/com/celfit/analytics/analyze/ContentSynthesisRefreshJobTest.java` (86~99행 fixture, 파일 끝 케이스)

### 실패하는 테스트 먼저

- [ ] fixture에 파트 A만 있는 행(`facts_c`)을 추가한다. `INSERT INTO content_analyses ...` 값 목록의 `fresh_c` 앞에 넣는다.

```java
				  ('facts_c','facts-model',NULL,NULL,NULL,NULL,NULL,
				   NULL,'cleansing','sponsored','[{"name":"브랜드C"}]'::jsonb,true,'pending',NULL),
```

기준선 앵커가 있어야 "앵커 없어 보존"과 구분되므로 `baseline_fixture`에도 한 줄 넣는다.

```java
		db.update("INSERT INTO analytics.baseline_fixture VALUES ('facts_c', 7000, 1, 3, 3, 0.06, 800, 50, 60, 15000, 3)");
```

- [ ] 케이스를 추가한다.

```java
	/**
	 * 파트 A만 채워진 행(metric_timeliness='pending')은 재생성 대상이 아니다.
	 * synthesis_version이 NULL이라 가드가 없으면 이 잡이 낚아채 온라인으로 파트 B를 돌려버리는데,
	 * 이 잡에는 성숙 가드가 없어 D+1 미성숙 지표를 인용한 문구가 만들어지고 일 3,500건이
	 * 동기 호출로 나간다(2026-09-03 2단계 분리 §4-4).
	 */
	@Test
	void 파트A만_있는_pending_행은_재생성_대상이_아니다() {
		job.run();

		assertTrue(calls.stream().noneMatch(c -> c.shortCode().equals("facts_c")));
		assertNull(db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='facts_c'", String.class));
		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code='facts_c'", String.class));
	}

	/** 시점이 NULL인 레거시 행(V33 이전 기분석분)은 계속 재생성 대상이다 - IS DISTINCT FROM이 필요한 이유. */
	@Test
	void 시점_NULL_레거시_행은_여전히_재생성_대상이다() {
		db.update("UPDATE content_analyses SET metric_timeliness = NULL WHERE short_code = 'old_c'");

		job.run();

		assertEquals("새 요약: old_c", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='old_c'", String.class));
		assertNull(db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code='old_c'", String.class));
	}
```

기존 `낡은_행만_갱신하고_최신_행은_건드리지_않는다`의 기대값(`processed == 2`, `synthesis_version IS NULL`이 1건)은 `facts_c` 추가로 바뀐다. `facts_c`가 제외되므로 processed는 그대로 2지만 `synthesis_version IS NULL` 카운트는 2가 된다(orphan_c + facts_c). 그 단언을 2로 고친다.

```java
		assertEquals(2L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE synthesis_version IS NULL", Long.class));
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentSynthesisRefreshJobTest"
```

기대 실패: `파트A만_있는_pending_행은_재생성_대상이_아니다`에서 `calls`에 `facts_c`가 잡혀 실패한다.

### 구현

- [ ] 대상 SQL에 가드를 추가한다.

```java
		List<String> targets = analysis.queryForList("""
				SELECT short_code FROM content_analyses
				WHERE synthesis_version IS DISTINCT FROM ?
				  -- 파트 A만 채워진 행(2026-09-03 2단계 분리)은 재생성이 아니라 최초 생성 대상이다.
				  -- 이 잡은 성숙 가드가 없고 온라인 전용이라, 낚아채면 D+1 미성숙 지표를 인용한
				  -- 문구가 만들어지고 일 3,500건이 동기 호출로 나간다. 파트 B 배치(ContentAnalysisJob
				  -- Phase.SYNTHESIS)가 성숙 후에 채운다.
				  -- <> 가 아니라 IS DISTINCT FROM: 시점이 NULL인 레거시 행(V33 이전 기분석분)을
				  -- 조용히 제외하면 안 된다.
				  AND metric_timeliness IS DISTINCT FROM 'pending'
				ORDER BY short_code
				LIMIT ?""", String.class, Synthesis.VERSION, settings.analyzeBatchLimit());
```

클래스 javadoc의 "대상: synthesis_version이 VERSION과 다르거나 NULL인 행" 문단 뒤에 한 줄을 더한다.

```java
 * <p>2026-09-03(2단계 분리): {@code metric_timeliness = 'pending'} 행은 제외한다.
 * 그건 파트 A만 채워진 "최초 생성 대기"라 이 잡의 재생성 대상이 아니다.
```

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentSynthesisRefreshJobTest"
```

기대: `BUILD SUCCESSFUL`.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics/analyze/ContentSynthesisRefreshJob.java analytics/src/test/java/com/celfit/analytics/analyze/ContentSynthesisRefreshJobTest.java
git -C $REPO commit -m "$(cat <<'EOF'
fix(analytics): 해석 재생성 잡이 파트 A만 있는 pending 행을 낚아채지 않게

synthesis_version이 NULL이라 가드가 없으면 재생성 잡이 A만 행을 대상으로 잡는다.
이 잡은 성숙 가드가 없고 온라인 전용이라 D+1 미성숙 지표를 인용한 문구가 만들어지고
일 3,500건이 동기 호출로 나간다. <> 가 아니라 IS DISTINCT FROM을 쓴다 - 시점이
NULL인 레거시 행(V33 이전)을 조용히 제외하면 안 된다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: 어드민 퍼널 · `pending.sh` · 커버리지의 "사실만" 분리

`content_analyses`에 행이 존재한다는 사실이 지금까지 "기분석"과 동의어였다. 파트 A 행이 생기면 그 등식이 깨진다 - 세 소비처를 정정한다.

1. **어드민 퍼널(`PipelineStatsService`)**: 후보 스캔을 `v_fact_candidates` 1회로 통합하고(mature 행만 추려 기존 트랙 분할을 그대로 만든다 - 스캔 횟수는 현행과 동일), 트랙별 "사실만"을 분리한다. 트랙 done은 **파트 B 완료만** 센다.
2. **`analytics/check/pending.sql`**: `'pending'`을 별도 상태 코드로 분리한다(11 / 24 / 34 / 42).
3. **커버리지(`CoverageRepository`·`analytics/check/coverage.sql`)**: 드로어 카피 3종(18행)·댓글 신뢰도(22행)의 분모를 "해석 단계까지 온 행"으로 한정한다.

**Files:**
- `analytics/src/main/java/com/celfit/analytics/admin/PipelineStatsService.java` (44~66행 `Heavy`, 128~136행 `Funnel`, 191~214행 `funnel()`, 315~335행 `split`, 384~391행 후보 스캔, 436~444행 `Heavy` 생성)
- `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java` (63~82행 `FunnelView`, 198~242행 `scopeLine`, 243~268행 `scopeSubLine`, 270~318행 `funnelView`)
- `analytics/src/main/resources/templates/fragments/board.html` (134~171행 트랙 분할 블록)
- `analytics/check/pending.sql` (20~53행 분류 본체, 55~87행 표기·항등식)
- `analytics/src/main/java/com/celfit/analytics/coverage/CoverageRepository.java` (50~64행 `an` CTE, 162~165행·182~185행 행 정의)
- `analytics/check/coverage.sql` (33~42행 `an` CTE, 140~143행·160~163행)
- `analytics/src/test/java/com/celfit/analytics/admin/PipelineStatsServiceTest.java`, `AdminUiControllerTest.java`

### 실패하는 테스트 먼저

- [ ] `PipelineStatsServiceTest`의 `트랙별_대조는_기분석_셋과의_교집합으로_4분할`을 pending 셋까지 받는 형태로 고치고, "사실만은 기분석이 아니다"를 고정한다.

```java
	@Test
	void 트랙별_대조는_파트B_완료만_기분석으로_센다() {
		// 후보 5건: timely 2(a,b) + 윈도우 3(c,d,e). 행 보유 {a,c,d,x}, 그중 pending(A만) {c}.
		// 파트 A 행은 랭킹에 못 뜨므로 '기분석'이 아니라 '사실만'으로 따로 센다.
		Map<String, Boolean> candidates = new LinkedHashMap<>();
		candidates.put("a", true);
		candidates.put("b", true);
		candidates.put("c", false);
		candidates.put("d", false);
		candidates.put("e", false);

		PipelineStatsService.TrackSplit s = PipelineStatsService.split(
				candidates, Set.of("a", "c", "d", "x"), Set.of("c"));

		assertThat(s.timelyTotal()).isEqualTo(2);
		assertThat(s.timelyDone()).isEqualTo(1);
		assertThat(s.timelyFactsOnly()).isZero();
		assertThat(s.windowTotal()).isEqualTo(3);
		assertThat(s.windowDone()).isEqualTo(1);   // d만 (c는 사실만)
		assertThat(s.windowFactsOnly()).isEqualTo(1);
		// 항등식: 후보 = 트랙 합, 후보 밖 행 보유(x)는 어디에도 안 센다
		assertThat(s.timelyTotal() + s.windowTotal()).isEqualTo(candidates.size());
	}
```

- [ ] `heavy_스냅샷은_항등식_후보는_기분석더하기미분석`의 `new Heavy(...)` 호출을 새 인자 순서로 고치고 파트 A 수치를 더한다.

```java
	@Test
	void heavy_스냅샷은_항등식_후보는_기분석더하기미분석() {
		// v3 설계 문서 §1 실측(07-21) + 09-03 파트 A 축.
		PipelineStatsService.Heavy h = new PipelineStatsService.Heavy(
				7_402,
				1_435, 1_432, 2,
				5_967, 5_684, 100,
				9_000, 8_800,
				12_777, 11_072,
				4_000, 1_104,
				723, 700,
				new PipelineStatsService.ArchiveCoverage(107_886, 27_686, 5_699, 5_694),
				Instant.now());
		assertThat(h.timelyPending()).isEqualTo(3);
		assertThat(h.windowPending()).isEqualTo(283);
		assertThat(h.truePending()).isEqualTo(286);
		assertThat(h.factsOnlyTotal()).isEqualTo(102);
		assertThat(h.factPending()).isEqualTo(200);
		// 항등식: 후보 = (트랙별 파트 B 완료 + 미완)의 합. '사실만'은 미완에 포함된다 -
		// 파트 A만으로는 랭킹에 못 뜨므로 여전히 해야 할 일이다.
		assertThat(h.timelyDone() + h.timelyPending() + h.windowDone() + h.windowPending())
				.isEqualTo(h.candidates());
	}
```

- [ ] 실패를 확인한다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.admin.PipelineStatsServiceTest"
```

기대 실패: 컴파일 에러(`split` 인자 개수, `Heavy` 생성자, `timelyFactsOnly` 부재).

### 구현 (어드민 퍼널)

- [ ] `Heavy` record를 확장한다.

```java
	/**
	 * 무거운 집계 스냅샷 (G1·G2) - raw 쪽 수치는 REPEATABLE READ 한 트랜잭션에서 읽어 서로 정합.
	 *
	 * <p>트랙 구분: timely(제때 크롤 - 분석되면 랭킹 노출) vs 윈도우 전용(늦크롤 - 분석돼도
	 * 인플루언서 상세만). 항등식: candidates = timelyTotal + windowTotal,
	 * 각 트랙 total = 파트 B 완료(Done) + 미완(Pending).
	 *
	 * <p>2026-09-03 2단계 분리: 행 존재 = 기분석이라는 등식이 깨졌다. 파트 A만 채워진 행
	 * (metric_timeliness='pending')은 랭킹에 못 뜨므로 Done이 아니라 FactsOnly로 따로 세고,
	 * Pending(해야 할 일)에는 그대로 포함한다. factCandidates/factAnalyzed는 파트 A 잡의 대상·완료다.
	 */
	public record Heavy(
			long candidates,
			long timelyTotal, long timelyDone, long timelyFactsOnly,
			long windowTotal, long windowDone, long windowFactsOnly,
			long factCandidates, long factAnalyzed,
			long servingContents, long servingAnalyzed,
			long immaturePool, long lateExcluded,
			long beautyHandles, long beautyCopied,
			ArchiveCoverage archive,
			Instant computedAt) {

		public long timelyPending() {
			return timelyTotal - timelyDone;
		}

		public long windowPending() {
			return windowTotal - windowDone;
		}

		/** 진짜 잔여 - 후보 ∩ 파트 B 미완('사실만' 포함). */
		public long truePending() {
			return timelyPending() + windowPending();
		}

		/** 사실만 채워진 후보 - 화면에 광고 판정·카테고리는 이미 떠 있고 해석만 대기 중이다. */
		public long factsOnlyTotal() {
			return timelyFactsOnly + windowFactsOnly;
		}

		/** 파트 A 잡의 잔여 - 캡션은 있는데 아직 사실 추출이 안 된 것. */
		public long factPending() {
			return factCandidates - factAnalyzed;
		}
	}
```

- [ ] `split`을 pending 셋까지 받게 한다.

```java
	/** 트랙별 대조 (G1 핵심) - 후보(short_code→timely) × (파트 B 완료 / 사실만 / 미착수). */
	record TrackSplit(long timelyTotal, long timelyDone, long timelyFactsOnly,
			long windowTotal, long windowDone, long windowFactsOnly) {
	}

	/**
	 * @param analyzed content_analyses에 행이 있는 short_code 전량
	 * @param factsOnly 그중 파트 A만 채워진 것(metric_timeliness='pending').
	 *        행 존재만으로 Done을 세면 파트 A 행이 "랭킹 노출 가능"으로 잘못 잡힌다(2026-09-03).
	 */
	static TrackSplit split(Map<String, Boolean> candidates, Set<String> analyzed, Set<String> factsOnly) {
		long timelyTotal = 0;
		long timelyDone = 0;
		long timelyFacts = 0;
		long windowTotal = 0;
		long windowDone = 0;
		long windowFacts = 0;
		for (Map.Entry<String, Boolean> e : candidates.entrySet()) {
			boolean facts = factsOnly.contains(e.getKey());
			boolean done = analyzed.contains(e.getKey()) && !facts;
			if (Boolean.TRUE.equals(e.getValue())) {
				timelyTotal++;
				if (done) timelyDone++;
				if (facts) timelyFacts++;
			} else {
				windowTotal++;
				if (done) windowDone++;
				if (facts) windowFacts++;
			}
		}
		return new TrackSplit(timelyTotal, timelyDone, timelyFacts, windowTotal, windowDone, windowFacts);
	}
```

- [ ] `computeHeavy`의 셋 로딩과 후보 스캔을 바꾼다. **스캔 횟수는 늘리지 않는다** - `v_fact_candidates`를 한 번 읽어 mature 행만 추리면 기존 `v_analysis_candidates` 스캔과 같은 결과를 얻고, 파트 A 수치는 덤으로 나온다.

`analyzedCodes` 로딩 아래에 pending 셋을 더한다.

```java
		Set<String> analyzedCodes = new HashSet<>(
				analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
		// 파트 A만 채워진 행(2026-09-03) - 부분 인덱스로 좁혀진 집합이라 통짜 로드가 싸다.
		Set<String> factsOnlyCodes = new HashSet<>(analysis.queryForList(
				"SELECT short_code FROM content_analyses WHERE metric_timeliness = 'pending'", String.class));
```

후보 스캔 블록(①)을 바꾼다.

```java
					// ① 분석 자격 - 파트 A 입구 뷰를 1회 스캔해 파트 A 축과 파트 B 트랙을 함께 얻는다.
					//    v_analysis_candidates = v_fact_candidates WHERE mature 이므로 mature 행만
					//    추리면 구 스캔과 결과가 같다(스캔 횟수 불변 - 뷰 평가가 이 집계의 본체다).
					Map<String, Boolean> candidateRows = new java.util.LinkedHashMap<>();
					long factCandidates = 0;
					long factAnalyzed = 0;
					try (ResultSet rs = st.executeQuery(
							"SELECT short_code, timely, mature FROM v_fact_candidates")) {
						while (rs.next()) {
							String shortCode = rs.getString(1);
							factCandidates++;
							if (analyzedCodes.contains(shortCode)) {
								factAnalyzed++;
							}
							if (rs.getBoolean(3)) {
								candidateRows.put(shortCode, rs.getBoolean(2));
							}
						}
					}
					TrackSplit tracks = split(candidateRows, analyzedCodes, factsOnlyCodes);
```

`Heavy` 생성부를 새 순서로 바꾼다.

```java
					con.commit();
					long candidates = tracks.timelyTotal() + tracks.windowTotal();
					return new Heavy(candidates,
							tracks.timelyTotal(), tracks.timelyDone(), tracks.timelyFactsOnly(),
							tracks.windowTotal(), tracks.windowDone(), tracks.windowFactsOnly(),
							factCandidates, factAnalyzed,
							serving, servingAnalyzed,
							Math.max(0, captionPool - maturePool),
							Math.max(0, maturePool - candidates),
							beautyHandles, beautyCopied,
							archiveCoverage(thumbCodes, archivedThumbs, profileUrls, archivedProfiles),
							Instant.now());
```

- [ ] `Funnel`에 전체 pending 마킹 수를 더한다(누적 각주용).

```java
	public record Funnel(long rawContents,
			long analyzed, long timelyMarked, long backfillMarked, long pendingMarked,
			long served, long mirrorAccounts,
			long copiedAccounts, long accountTarget,
			Accounts accounts, Heavy heavy,
			int todayPlanned, int daysToFull,
			int pinDays, int slackDays,
			String candidatesError) {
	}
```

`funnel()`의 1패스 집계와 생성부를 고친다.

```java
		Map<String, Object> ca = analysis.queryForMap("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE metric_timeliness = 'timely')        AS timely,
				       count(*) FILTER (WHERE metric_timeliness = 'late_backfill') AS backfill,
				       count(*) FILTER (WHERE metric_timeliness = 'pending')       AS pending
				FROM content_analyses""");
```

```java
		return new Funnel(rawContents,
				num(ca.get("total")), num(ca.get("timely")), num(ca.get("backfill")), num(ca.get("pending")),
				served, mirrorAccounts, copied, accountTarget(), accounts, heavy,
				remaining < 0 ? 0 : todayPlanned(remaining),
				remaining < 0 ? 0 : daysToFull(remaining),
				settings.metricPinDays(), settings.analyzeTimelySlackDays(),
				heavyError);
```

- [ ] `AdminUiController.FunnelView`에 세 필드를 더하고 `funnelView`를 고친다.

`String timelyTotal, String timelyDone, String timelyPending,` 뒤에 `String timelyFactsOnly,`를, `String windowTotal, String windowDone, String windowPending,` 뒤에 `String windowFactsOnly,`를, `String truePending` 뒤에 `String factsOnlyTotal,`을 추가한다. `funnelView` 본문의 대응 위치에 값을 넣는다.

```java
				h == null ? null : comma(h.timelyTotal()),
				h == null ? null : comma(h.timelyDone()),
				h == null ? null : comma(h.timelyPending()),
				h == null ? null : comma(h.timelyFactsOnly()),
				h == null ? null : comma(h.windowTotal()),
				h == null ? null : comma(h.windowDone()),
				h == null ? null : comma(h.windowPending()),
				h == null ? null : comma(h.windowFactsOnly()),
				h == null ? null : comma(h.truePending()),
				h == null ? null : comma(h.factsOnlyTotal()),
```

"기타 마킹" 계산에서 pending을 빼야 한다(안 빼면 파트 A 행이 전부 "레거시 미분류"로 잡힌다).

```java
		// immature·마킹 전(NULL) 레거시 - timely/backfill/pending 어느 쪽도 아닌 기분석분.
		long other = Math.max(0,
				f.analyzed() - f.timelyMarked() - f.backfillMarked() - f.pendingMarked());
```

- [ ] `AdminUiController.scopeLine`·`scopeSubLine`의 switch에 FACT_ANALYZE 케이스를 더한다(Task 9에서 카드만 추가하고 미룬 부분 - 이제 `Heavy`에 파트 A 축이 있다).

```java
			case FACT_ANALYZE -> {
				if (h == null) {
					yield f.candidatesError() != null ? "대상 집계 실패 - 분석 뷰 확인 필요" : "대상 집계 중…";
				}
				yield "후보 %s · 사실 보유 %s · 미추출 %s".formatted(
						comma(h.factCandidates()), comma(h.factAnalyzed()), comma(h.factPending()));
			}
```

```java
			case FACT_ANALYZE -> "성숙 무관 - 업로드 다음 날 광고 판정·카테고리를 먼저 채운다";
```

- [ ] `board.html`의 트랙 분할 블록에 칩을 추가한다. 랭킹 트랙 `미분석` 칩 뒤와 상세 트랙 `미분석` 칩 뒤에 각각 넣고, "진짜 잔여" 줄에 합계를 붙인다.

```html
                <span class="chip alt">사실만 <b th:text="${funnel.timelyFactsOnly()}">0</b>
                    <span class="off">광고·카테고리는 표시됨, 해석 대기</span></span>
```

```html
                <span class="chip alt">사실만 <b th:text="${funnel.windowFactsOnly()}">0</b>
                    <span class="off">광고·카테고리는 표시됨, 해석 대기</span></span>
```

```html
                <span class="chip mute">그중 사실만 <b th:text="${funnel.factsOnlyTotal()}">0</b>
                    <span class="off">파트 A 완료 · 파트 B 대기</span></span>
```

- [ ] `AdminUiControllerTest`에서 `Funnel`·`Heavy`를 직접 만드는 지점을 새 시그니처로 고치고, "사실만" 칩 노출 케이스를 하나 더한다.

```bash
grep -n "new PipelineStatsService.Heavy\|new PipelineStatsService.Funnel" $REPO/analytics/src/test/java/com/celfit/analytics/admin/AdminUiControllerTest.java
```

```java
	@Test
	void 콘텐츠_보드는_사실만_칩으로_파트A_완료분을_따로_보여준다() {
		// 파트 A만 채워진 행은 랭킹에 못 뜨므로 '기분석'이 아니라 '사실만'으로 센다 -
		// 이걸 뭉개면 "분석이 다 됐는데 왜 랭킹에 없나"라는 오독이 생긴다.
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("사실만")));
	}
```

### 구현 (`pending.sql`)

- [ ] 분류 본체의 `s` LATERAL에 `facts_only`를 더하고 CASE에 상태 코드를 추가한다.

```sql
CREATE TEMP TABLE _cls AS
SELECT
  p.short_code,
  CASE
    WHEN NOT p.has_caption THEN '50'
    WHEN NOT p.mature THEN CASE WHEN s.facts_only THEN '11' ELSE '10' END
    WHEN c.timely IS TRUE THEN
      CASE WHEN s.facts_only THEN '24'
           WHEN s.analyzed THEN '20'
           WHEN s.gated THEN '22'
           WHEN NOT s.mirrored THEN '23'
           ELSE '21' END
    WHEN c.timely IS FALSE THEN
      CASE WHEN s.facts_only THEN '34'
           WHEN s.analyzed THEN '30'
           WHEN s.gated THEN '32'
           WHEN NOT s.mirrored THEN '33'
           ELSE '31' END
    WHEN s.facts_only THEN '42'
    WHEN s.analyzed THEN '41'
    ELSE '40'
  END AS k
FROM (
  SELECT v.short_code,
         (v.caption IS NOT NULL AND btrim(v.caption) <> '') AS has_caption,
         (v.posted_at AT TIME ZONE 'Asia/Seoul')::date
           + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
           + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
           <= (now() AT TIME ZONE 'Asia/Seoul')::date AS mature
  FROM analytics.v_contents v
) p
LEFT JOIN (SELECT short_code, timely FROM analytics.v_analysis_candidates) c USING (short_code)
CROSS JOIN LATERAL (
  SELECT EXISTS (SELECT 1 FROM _analyzed a WHERE a.short_code = p.short_code) AS analyzed,
         -- 2026-09-03 2단계 분리: 행이 있어도 파트 A만이면 '기분석'이 아니다(해석·기준선이 없어
         -- 랭킹·드로어 비교 블록에 못 뜬다). '사실만'으로 따로 센다.
         EXISTS (SELECT 1 FROM _analyzed a WHERE a.short_code = p.short_code
                 AND a.metric_timeliness = 'pending') AS facts_only,
         EXISTS (SELECT 1 FROM _comment_gate g WHERE g.short_code = p.short_code) AS gated,
         EXISTS (SELECT 1 FROM _mirrored m WHERE m.short_code = p.short_code) AS mirrored
) s;
```

- [ ] 상태표에 4행을 추가한다(`VALUES` 목록의 해당 위치).

기존 12행은 손대지 않고 네 줄만 끼워 넣는다. 삽입 위치는 각 상태의 바로 뒤다: `'10'`(미성숙) 뒤에 `'11'`, `'23'`(랭킹 트랙 마지막) 뒤에 `'24'`, `'33'`(상세 트랙 마지막) 뒤에 `'34'`, `'41'`(영구 제외 마지막) 뒤에 `'42'`.

```sql
  ('11', '미성숙 · 사실만',             '파트 A 완료(광고 판정·카테고리 표시됨) - 해석은 창 닫힌 뒤'),
  ('24', '랭킹 트랙 · 사실만',          '파트 A 완료 · 파트 B 대기 - 광고/카테고리는 뜨지만 랭킹엔 아직 안 나온다'),
  ('34', '상세 트랙 · 사실만',          '파트 A 완료 · 파트 B 대기 - 인플루언서 상세엔 이미 반영'),
  ('42', '영구 제외 · 사실만',          '제때창 놓침 ∧ 윈도우 밖이지만 파트 A는 채워짐 - 해석은 영영 안 만든다'),
```

- [ ] 항등식 대조 블록의 FILTER 목록에 새 코드를 넣는다.

```sql
SELECT
  count(*) FILTER (WHERE k IN ('20','21','22','23','24','30','31','32','33','34')) AS "분석 자격",
  count(*) FILTER (WHERE k IN ('20','21','22','23','24'))                          AS "랭킹 트랙",
  count(*) FILTER (WHERE k = '20')                                                 AS "랭킹 기분석",
  count(*) FILTER (WHERE k IN ('21','22','23','24'))                               AS "랭킹 미분석",
  count(*) FILTER (WHERE k = '24')                                                 AS "랭킹 사실만",
  count(*) FILTER (WHERE k IN ('30','31','32','33','34'))                          AS "상세 트랙",
  count(*) FILTER (WHERE k = '30')                                                 AS "상세 기분석",
  count(*) FILTER (WHERE k IN ('31','32','33','34'))                               AS "상세 미분석",
  count(*) FILTER (WHERE k IN ('10','11'))                                         AS "미성숙",
  count(*) FILTER (WHERE k IN ('40','41','42'))                                    AS "영구 제외"
FROM _cls;
```

파일 상단 주석의 `_analyzed(short_code, metric_timeliness)` 설명 뒤에 한 줄을 더한다.

```sql
--   ※ 2026-09-03 2단계 분리: metric_timeliness='pending'은 "파트 A(사실)만 채워짐"이다.
--     행 존재 = 기분석이 아니므로 상태 11 / 24 / 34 / 42로 따로 센다.
```

### 구현 (커버리지)

드로어 카피 3종·댓글 신뢰도의 분모를 `content_analyses` 전체로 두면 파트 A 행이 쌓이는 만큼 상시 "부분"으로 보인다. 분모를 **해석 단계까지 온 행**(`metric_timeliness IS DISTINCT FROM 'pending'`)으로 한정한다.

- [ ] `CoverageRepository.MATRIX_SQL`의 `an` CTE에서 copy3·cauth를 떼어내 별도 CTE로 옮긴다.

```sql
				  an AS (SELECT count(main_category)          AS category,
				                count(sub_categories)         AS subcats,
				                count(ad_type)                AS ad_type,
				                least(count(detected_brands), count(detected_products)) AS tags,
				                count(detected_distributors)  AS distributors,
				                least(count(recent_reels_avg_views), count(recent12_avg_engagement_rate)) AS baseline,
				                least(count(sponsored_signal_level), count(detected_product_categories), count(vlm_attributes)) AS vlm
				         FROM content_analyses),
				  -- 해석(파트 B) 채움율은 "해석 단계까지 온 행"만 분모로 삼는다(2026-09-03 2단계 분리).
				  -- 파트 A만 채워진 행(metric_timeliness='pending')은 아직 해석을 만들 차례가 아니라
				  -- 결손이 아니다 - 전체를 분모로 두면 파트 A가 쌓이는 만큼 상시 '부분'으로 보인다.
				  -- <> 가 아니라 IS DISTINCT FROM: 시점이 NULL인 레거시 기분석분을 빠뜨리면 안 된다.
				  anb AS (SELECT count(*) AS total,
				                 least(count(ai_content_summary), count(contents_pattern),
				                       count(ai_comment_insight)) AS copy3,
				                 count(comment_authenticity_grade) AS cauth
				          FROM content_analyses WHERE metric_timeliness IS DISTINCT FROM 'pending'),
```

- [ ] 18행·22행을 새 CTE로 바꾼다.

```sql
				  SELECT 18, '드로어 AI 카피 3종 (댓글 인사이트는 임시 숨김)', 'content_analyses.ai_* / contents_pattern',
				         format('%s / %s', anb.copy3, anb.total),
				         CASE WHEN anb.copy3 = 0 THEN '없음' WHEN anb.copy3 < anb.total THEN '부분' ELSE '준비됨' END
				  FROM anb
```

```sql
				  SELECT 22, '드로어 댓글 신뢰도 판정 (배포본 임시 숨김)', 'content_analyses.comment_authenticity_grade',
				         format('%s / %s', anb.cauth, anb.total),
				         CASE WHEN anb.cauth = 0 THEN '없음' WHEN anb.cauth < anb.total THEN '부분' ELSE '준비됨' END
				  FROM anb
```

- [ ] `analytics/check/coverage.sql`에 같은 변경을 그대로 적용한다(매트릭스 정의는 두 파일이 쌍이다 - 클래스 javadoc에 명시돼 있다).

- [ ] 테스트를 돌린다.

```bash
cd $REPO && ./gradlew :analytics:test --tests "com.celfit.analytics.admin.*" --tests "com.celfit.analytics.coverage.*"
```

기대: `BUILD SUCCESSFUL`.

- [ ] 실데이터에서 `pending.sh`·`coverage.sh`가 도는지 확인한다(SQL 문법·임시 테이블 계약 검증).

```bash
cd $REPO/analytics && PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1} ./check/pending.sh | tail -30
cd $REPO/analytics && PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1} ./check/coverage.sh | tail -20
```

기대: 상태 분해표에 `사실만` 행 4개가 나오고(전량 0건 - 아직 split을 안 켰다), 항등식 블록이 에러 없이 출력된다.

- [ ] 커밋한다.

```bash
git -C $REPO add analytics/src/main/java/com/celfit/analytics analytics/src/main/resources/templates analytics/src/test/java/com/celfit/analytics/admin analytics/check
git -C $REPO commit -m "$(cat <<'EOF'
feat(analytics): 어드민 퍼널·pending.sh·커버리지에서 '사실만' 상태 분리

행 존재 = 기분석이라는 등식이 파트 A 행 때문에 깨진다. 트랙 done은 파트 B 완료만
세고, 파트 A 행은 '사실만'으로 따로 노출한다(잔여에는 그대로 포함 - 랭킹에 뜨려면
아직 해야 할 일이다). 후보 스캔은 v_fact_candidates 1회로 통합해 스캔 횟수를
늘리지 않으면서 파트 A 축까지 얻는다. 커버리지의 해석 채움율 분모는
metric_timeliness IS DISTINCT FROM 'pending'으로 한정한다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: was 노출 규칙 회귀 고정 + 6.3 계약 문서

was 코드는 **한 줄도 바꾸지 않는다.** `'pending'`은 timely도 NULL도 아니라 6.1 랭킹·6.3 카테고리 벤치마크·assembler 비교 게이트에서 자동으로 제외된다. 그 자동 제외가 우연이 아니라 계약임을 테스트로 고정하는 것이 이 태스크다.

| 소비처 | 현행 필터 | `'pending'` 행 | 코드 변경 |
|---|---|---|---|
| 6.1 랭킹 `V1ContentRepository` | `= 'timely' OR IS NULL` | 제외 | 없음 |
| 6.3 카테고리 벤치마크 `V1ContentReportRepository` | 동일 | 표본에서 제외 | 없음 |
| 6.3 드로어 조회 `findReport` | 필터 없음(INNER JOIN) | **200으로 내려간다**(목표) | 없음 |
| `V1ContentReportAssembler.comparableMetric` | timely 또는 NULL만 | 백분위 억제 | 없음 |
| 6.4 / v2 인플루언서 상세 `findSeries` | 필터 없음, ad_type만 | **D+1부터 ad_type 표시**(목표) | 없음 |

**Files:**
- `was/src/test/java/com/celfit/was/v1/content/V1ContentRepositoryTest.java` (142~156행 contents 시드, 163~178행 분석 시드, 217~231행 케이스 옆)
- `was/src/test/java/com/celfit/was/v1/content/V1ContentReportRepositoryTest.java` (87~108행 시드, 파일 끝)
- `was/src/test/java/com/celfit/was/v1/content/V1ContentReportAssemblerTest.java` (164~176행 케이스 옆)
- `docs/contracts/v1-content-report-nullable-fields.md` (신규)

### 6.1 랭킹 제외

- [ ] `V1ContentRepositoryTest`의 contents 시드에 `pd1`을 더한다(시점 마킹 대조군 `lg1` 뒤).

```java
				 ('pd1', 'alpha', 'https://thumb/pd1.jpg', '사실만 채워진 릴스', '2026-07-18T03:00:00Z', 'reels',
				  20, 'https://ig/pd1', 8888, 888, 88, 888, '2026-07-19T03:00:00Z', 888),
```

분석 시드에도 한 줄 더한다(`lg1` 뒤).

```java
				 ('pd1', 'makeup', '["아이라이너"]'::jsonb, 'sponsored', NULL, NULL, NULL, true, 'pending'),
```

- [ ] 케이스를 추가한다(`late_backfill은_랭킹에서_제외되고...` 바로 뒤).

```java
	@Test
	void 사실만_채워진_pending은_랭킹에서_제외된다() {
		// 2026-09-03 2단계 분리: 파트 A만 채워진 행은 지표 시점이 미확정('pending')이라
		// 랭킹에 뜨면 안 된다. NULL로 뒀다면 `= 'timely' OR IS NULL` 필터가 레거시 timely로
		// 취급해 미성숙 지표가 노출됐을 것이다 - 신규 어휘를 쓴 이유가 이 케이스다.
		V1ContentQuery q = V1ContentQuery.of(LocalDate.parse("2026-07-11"), LocalDate.parse("2026-07-20"),
				null, null, null, null, null, null, null, null, null, null, null, null);

		List<ContentCardRow> rows = repository.findCards(q);

		// pd1은 hype 888로 필터 없으면 1위인데 빠진다
		assertThat(rows).extracting(ContentCardRow::shortCode).doesNotContain("pd1");
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("tl1", "lg1");
		assertThat(repository.countCards(q)).isEqualTo(2);
	}
```

- [ ] 돌린다.

```bash
cd $REPO && ./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentRepositoryTest"
```

기대: `BUILD SUCCESSFUL`. 기존 `late_backfill은_랭킹에서...` 케이스도 그대로 통과해야 한다(pd1을 추가하면서 `containsExactly` 기대값이 안 바뀌는지 함께 확인된다).

### 6.3 조회·표본

- [ ] `V1ContentReportRepositoryTest` 시드에 pending 행을 더한다. contents에 한 줄, content_analyses에 한 줄.

```java
				 ('mpend',   'alpha', 'reels', 50000, 5000, 500),
```

```java
				 ('mpend',   'makeup',   true,  'pending',       NULL,   12),
```

`ai_content_summary`가 NULL인 것이 파트 A 행의 핵심이다(해석 5필드 미생성).

- [ ] 케이스를 추가한다.

```java
	@Test
	void 사실만_채워진_pending은_카테고리_표본에서_빠진다() {
		// mpend는 views 50000으로 표본에 끼면 평균을 크게 왜곡한다.
		// 지표 시점이 미확정이라 표본(timely)과 잣대가 다르다 - late_backfill과 같은 이유로 제외한다.
		var ctx = repository.findCategoryContext("makeup", 1000L);

		assertThat(ctx.sampleSize()).isEqualTo(4L);
		assertThat(ctx.avgViews()).isEqualTo(1325L);
	}

	@Test
	void 사실만_채워진_pending도_6_3_조회에는_잡힌다_해석은_null() {
		// 이 트랙의 목표: D+1부터 드로어가 404가 아니라 200이 되고, 광고 판정·카테고리가 먼저 뜬다.
		// 해석 5필드·기준선은 파트 B가 채우기 전이라 null이다(계약 문서 docs/contracts 참조).
		var row = repository.findReport("mpend").orElseThrow();

		assertThat(row.metricTimeliness()).isEqualTo("pending");
		assertThat(row.mainCategory()).isEqualTo("makeup");
		assertThat(row.categoryLabel()).isEqualTo("메이크업");
		assertThat(row.aiContentSummary()).isNull();
		assertThat(row.contentsPattern()).isNull();
		assertThat(row.aiCommentInsight()).isNull();
		assertThat(row.commentAuthenticityGrade()).isNull();
		assertThat(row.recent12AvgLikeCount()).isNull();
		assertThat(row.recent12AvgEngagementRate()).isNull();
	}
```

- [ ] 돌린다.

```bash
cd $REPO && ./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentReportRepositoryTest"
```

기대: `BUILD SUCCESSFUL`.

### assembler 비교 억제

- [ ] `V1ContentReportAssemblerTest`에 케이스를 추가한다(`카테고리맥락_늦크롤_백필은_백분위_null` 뒤). 기존 `categoryRow` 헬퍼를 그대로 쓴다.

```java
	@Test
	void 카테고리맥락_사실만_pending은_백분위_null() {
		// 2026-09-03 2단계 분리: 파트 A만 채워진 행은 지표 시점이 미확정이라 표본(timely)과
		// 잣대가 다르다. comparableMetric이 timely·NULL만 통과시키므로 was 코드 변경 없이 억제된다.
		var row = categoryRow(50000L, "pending");

		var ctx = assembler.toReport(row, List.of(),
				new V1ContentReportRepository.CategoryContextRow(200L, 41713L, 5L),
				Map.of(), List.of()).categoryContext();

		assertThat(ctx.percentile()).isNull();
		assertThat(ctx.sampleSize()).isEqualTo(200L);
		assertThat(ctx.categoryAvgViews()).isEqualTo(41713L);
	}

	@Test
	void 사실만_pending은_기준선_인용_필드가_null이다() {
		// 드로어 비교 블록의 baseline 계열(참여율·좋아요·댓글 평균)은 content_analyses의
		// recent12_* 컬럼에서 온다. 파트 A 행에는 없으므로 null이다 - FE는 이 상태를
		// "해석 준비 중"으로 렌더링해야 한다(docs/contracts/v1-content-report-nullable-fields.md).
		var report = assembler.toReport(categoryRow(1000L, "pending"), List.of(), null,
				Map.of(), List.of());

		assertThat(report.aiContentSummary()).isNull();
		assertThat(report.comparison().engagementRate().baseline()).isNull();
		assertThat(report.comparison().engagementQuality().likes().baseline()).isNull();
		assertThat(report.comparison().narrative()).isNull();
	}
```

`comparison()`의 접근자 이름(`engagementRate`·`engagementQuality`·`narrative`)은 실제 record 정의를 보고 맞춘다.

```bash
grep -n "record Comparison" -A 25 $REPO/was/src/main/java/com/celfit/was/v1/content/ContentAiReport.java
```

- [ ] 돌린다.

```bash
cd $REPO && ./gradlew :was:test --tests "com.celfit.was.v1.content.*"
```

기대: `BUILD SUCCESSFUL`.

### 계약 문서

`docs/contracts/`에는 monitoring·brand-ai 계약만 있고 6.x 계약 문서가 없다. 6.3의 null 가능 필드만 담은 짧은 문서를 새로 만든다(09-02 지침: FE 노출 변경은 계약 문서를 같은 커밋에).

- [ ] `docs/contracts/v1-content-report-nullable-fields.md`를 만든다.

```markdown
# `GET /v1/contents/{shortCode}` (스펙 6.3) - null 가능 필드 계약

> 상태: ✅ 실행됨 (2026-09-03)

## 무엇이 바뀌었나

콘텐츠 AI 분석이 2단계로 갈렸다([설계](../superpowers/specs/2026-09-03-content-analysis-two-phase-split-design.md)).

- **파트 A(사실)**: 광고 판정·카테고리·브랜드·제품·유통사·협찬 신호. 캡션만 보고 만들며
  **업로드 다음 날(D+1)** 채워진다.
- **파트 B(해석)**: AI 요약·패턴 해설·댓글 인사이트·댓글 신뢰도 + 계정 기준선 스냅샷.
  3일 고정 지표와 계정 기준선을 인용하므로 **업로드 나흘 뒤(D+4)** 채워진다.

그래서 D+1 ~ D+3 사이 이 API는 **404가 아니라 200**을 돌려주고, 파트 B 산출물만 null이다.
이전에는 분석 행이 아예 없어 404였다.

## D+1 ~ D+3 응답에서 null인 필드

| 경로 | 의미 | 언제 채워지나 |
|---|---|---|
| `aiContentSummary` | AI 요약 | 파트 B(D+4) |
| `comparison.narrative` | 패턴 해설 | 파트 B(D+4) |
| `comparison.engagementRate.baseline` | 계정 최근 12개 평균 참여율 | 파트 B(D+4) |
| `comparison.engagementQuality.likes.baseline` | 최근 12개 평균 좋아요 | 파트 B(D+4) |
| `comparison.engagementQuality.comments.baseline` | 최근 12개 평균 댓글 | 파트 B(D+4) |
| `commentAnalysis.insight` | 댓글 인사이트 | 파트 B(D+4) |
| `commentAnalysis.signals.authenticity.grade` / `.note` | 댓글 신뢰도 판정 | 파트 B(D+4) |
| `categoryContext.percentile` | 카테고리 상위 백분위 | 파트 B(D+4) |

## D+1부터 이미 채워지는 필드

`vlmAnalysis`(브랜드·협찬 신호·광고 고지·제품 카테고리·속성), `categoryContext.categoryLabel`,
`categoryContext.categoryAvgViews`, `categoryContext.sampleSize`,
`comparison.views`(조회수·라이브 재계산 기준선·순위·최근 릴스 차트),
`comparison.engagementRate.value`, `comparison.engagementQuality.*.value`.

인플루언서 상세(6.4 / v2)의 최근 콘텐츠 `adType`·카테고리도 D+1부터 값이 있다.

## 화면 요청

D+1 ~ D+3 구간은 "분석 실패"가 아니라 "해석 준비 중"이다. 위 null 필드는 빈 문자열이나 0이
아니라 **자리표시(예: 준비 중)** 로 그려 주기 바란다. 파트 A 값(광고 배지·카테고리·브랜드)은
그대로 노출하면 된다.

## 랭킹(6.1)은 무엇이 바뀌나

바뀌지 않는다. 랭킹 노출 시점은 현행과 같은 D+4다 - 파트 B가 채워지기 전에는 지표 시점이
미확정(`pending`)이라 랭킹 쿼리가 제외한다.

## 롤백

백엔드 `app_setting`의 `analytics.analyze-mode`를 `unified`로 되돌리면 이 계약도 이전 상태
(D+1 ~ D+3은 404)로 돌아간다. 되돌릴 때는 FE에 별도로 알린다.
```

- [ ] 커밋한다.

```bash
git -C $REPO add was/src/test/java/com/celfit/was/v1/content docs/contracts/v1-content-report-nullable-fields.md
git -C $REPO commit -m "$(cat <<'EOF'
test(was): 사실만(pending) 행의 6.1 제외·6.3 200 노출을 회귀로 고정 + 계약 문서

was 코드는 한 줄도 바꾸지 않는다 - 'pending'은 timely도 NULL도 아니라 랭킹·카테고리
표본·비교 백분위에서 자동으로 빠진다. 그 자동 제외가 우연이 아니라 계약임을 고정한다.
6.3은 D+1부터 404가 아니라 200이 되고 해석 5필드·기준선만 null이므로, FE가 렌더링을
맞출 수 있게 null 가능 필드 목록을 docs/contracts에 남긴다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: 문서 마무리 · 운영 런북 · PR 전 전체 검증

**Files:**
- `deploy/README.md` (파일 끝에 §17 추가)
- `docs/superpowers/specs/2026-09-03-content-analysis-two-phase-split-design.md` (3행 상태 헤더)
- `docs/superpowers/plans/2026-09-03-content-analysis-two-phase-split.md` → `docs/superpowers/plans/archive/`로 이동
- `docs/tracks/` (해당 트랙 파일이 있으면 상태 갱신)

### 운영 런북

- [ ] `deploy/README.md` 끝에 아래를 추가한다.

```markdown
## 17. 콘텐츠 분석 2단계 분리 (파트 A 사실 D+1 · 파트 B 해석 D+4)

설계: [specs/2026-09-03-content-analysis-two-phase-split-design.md](../docs/superpowers/specs/2026-09-03-content-analysis-two-phase-split-design.md)

코드 배포만으로는 아무 것도 바뀌지 않는다(`analytics.analyze-mode` 기본값 `unified`).
아래 3단계를 거쳐 켠다.

### 17-1. 뷰 적용 (배포 후, 1회)

분석 뷰는 Flyway가 아니라 수동 적용이다(런북은 메모리 `analytics-prod-view-apply-mirror`).
raw DB 컨테이너에 04를 다시 적용한다.

```bash
# 서버에서
docker exec -i deploy-postgres-raw-1 psql -U "$RAW_DB_USER" -d crawler < analytics/views/04_analysis_candidates.sql
```

적용 직후 플랜을 확인한다. 07-20에 배리어가 무력화돼 152ms에서 9.5초대로 폭주한 전례가 있다.

```bash
docker exec -i deploy-postgres-raw-1 psql -U "$RAW_DB_USER" -d crawler -c \
  "EXPLAIN ANALYZE SELECT count(*) FROM analytics.v_analysis_candidates;"
docker exec -i deploy-postgres-raw-1 psql -U "$RAW_DB_USER" -d crawler -c \
  "EXPLAIN ANALYZE SELECT count(*) FROM analytics.v_fact_candidates;"
```

기준: 수백 ms 이내. 초 단위로 튀면 켜지 말고 배리어부터 확인한다.

### 17-2. 파트 A 정확도 대조 (켜기 전 필수)

통합 프롬프트에서 지표·기준선·댓글 분포 줄이 빠진 입력으로 사실 판정이 달라지는지 확인한다.
광고 표기 트랙 골드셋 20~30건으로 통합 출력과 파트 A 출력을 대조하고, adType·mainCategory·
adDisclosure가 어긋나는 건이 없어야 켠다.

### 17-3. 켜기

```sql
-- raw DB (crawler)
UPDATE app_setting SET value = 'split' WHERE key = 'analytics.analyze-mode';
```

재기동 불필요하다(잡 시작마다 읽는다). 다음 새벽부터 KST 05:00 파트 A, 05:30 파트 B가 돈다.

**첫날은 미성숙 3일치(D+1 ~ D+3, 약 1만 건)가 한꺼번에 파트 A 후보가 된다.**
배치 청크 상한(`analytics.batch-chunk-size`, 기본 3,000)으로 자동 분할되며 1회성이다.
1회성 비용은 파트 A 약 $5 수준이다.

### 17-4. 다음 날 아침 확인

```bash
# ① 사실만(파트 A 완료·파트 B 대기) 건수 - 전날 크롤 신규분과 같은 자리수여야 한다
docker exec -i deploy-postgres-1 psql -U "$DB_USER" -d analysis -c \
  "SELECT count(*) FROM content_analyses WHERE metric_timeliness = 'pending';"

# ② 배치 kind별 수거 결과 - facts·synthesis가 각각 collected로 끝났는지
docker exec -i deploy-postgres-1 psql -U "$DB_USER" -d analysis -c \
  "SELECT kind, status, count(*), sum(submitted_count) FROM content_batch_jobs
   WHERE submitted_at > now() - interval '1 day' GROUP BY 1, 2 ORDER BY 1, 2;"

# ③ 상태 분해 정본 - '랭킹 트랙 · 사실만'(24) / '미성숙 · 사실만'(11)이 채워지는지
PG_CONTAINER=deploy-postgres-raw-1 analytics/check/pending.sh

# ④ 목표 증상 해소 확인 - 어제 업로드분에 ad_type이 붙었는지
docker exec -i deploy-postgres-1 psql -U "$DB_USER" -d analysis -c \
  "SELECT count(*) FILTER (WHERE a.ad_type IS NOT NULL) AS ad_type_있음, count(*) AS 어제분
   FROM contents c LEFT JOIN content_analyses a ON a.short_code = c.short_code
   WHERE c.posted_at >= (now() AT TIME ZONE 'Asia/Seoul')::date - 2;"
```

`pending` 건수 추이는 3일 후 정체해야 한다(D+4마다 파트 B로 빠지므로 유입과 유출이 균형).
계속 단조 증가하면 파트 B가 안 돌고 있다는 뜻이니 ②의 `synthesis` kind 수거를 본다.

### 17-5. 롤백

```sql
UPDATE app_setting SET value = 'unified' WHERE key = 'analytics.analyze-mode';
```

한 줄이고 재기동 불필요하다. 다음 잡부터 FACT_ANALYZE는 no-op, ANALYZE / LATE_BACKFILL은
통합 콜로 복귀한다. 뷰·마이그레이션은 롤백 불요다(추가만이라 구 코드와 호환).

**롤백이 되돌리지 않는 것: 이미 만들어진 파트 A만(`pending`) 행.** 통합 잡은 "행 존재"를
제외로 보므로 이 행들은 파트 B를 영영 못 받는다. 두 갈래로 처리한다.

- (a) split 재전환 예정이면 그냥 둔다 - SYNTHESIS가 자연 재대상한다.
- (b) 통합으로 영구 복귀면 아래를 **사용자 확인 후** 실행한다. 파트 A는 다음 통합 잡이 다시 만든다.

```sql
DELETE FROM content_analyses WHERE metric_timeliness = 'pending';
```
```

- [ ] 스펙 상태 헤더를 갱신한다(3행). 혼합 표기(활성 + 구현됨)는 금지다 - 단일 상태로 둔다.

```markdown
> 상태: ✅ 구현됨 · 2026-09-03 · 구현 계획은 plans/archive/2026-09-03-content-analysis-two-phase-split.md
```

- [ ] 트랙 문서가 있으면 상태를 갱신한다. 없으면 만들지 않는다.

```bash
ls $REPO/docs/tracks/ | grep -i "analysis\|content" || echo "해당 트랙 파일 없음"
```

- [ ] 이 계획 문서를 아카이브로 옮기고 참조 링크를 고친다.

```bash
git -C $REPO mv docs/superpowers/plans/2026-09-03-content-analysis-two-phase-split.md \
                docs/superpowers/plans/archive/2026-09-03-content-analysis-two-phase-split.md
grep -rn "plans/2026-09-03-content-analysis-two-phase-split" $REPO --exclude-dir=.git || echo "옛 경로 참조 없음"
```

아카이브 문서 상태 헤더도 `> 상태: ✅ 실행됨 · 2026-09-03`으로 바꾼다.

### PR 전 전체 검증

- [ ] 전체 SQL 하니스.

```bash
cd $REPO/analytics && PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1} ./test/run.sh
```

기대: `ALL GREEN`.

- [ ] 마이그레이션 가드.

```bash
cd $REPO && bash .github/scripts/check-migration-safety.sh
```

기대: 에러 없음.

- [ ] 전체 모듈 테스트(이 한 번만 돌린다 - 모듈 4개가 각자 Testcontainers Postgres를 띄운다).

```bash
cd $REPO && ./gradlew test
```

기대: `BUILD SUCCESSFUL`. 대량 실패가 나면 먼저 `DOCKER_HOST`와 colima 자원(8 CPU / 12 GiB)을 확인한다.

- [ ] 브랜치를 push한다. **PR은 열지 않는다** - 사용자에게 PR 개설 여부를 물어본다.

```bash
git -C $REPO status --short
git -C $REPO log --oneline develop..HEAD
git -C $REPO push -u origin HEAD
```

- [ ] 커밋한다(문서 마무리분).

```bash
git -C $REPO add deploy/README.md docs/superpowers
git -C $REPO commit -m "$(cat <<'EOF'
docs: 콘텐츠 분석 2단계 분리 운영 런북 추가 + 스펙 상태·계획 아카이브

deploy/README §17에 뷰 적용·골드셋 대조·토글·첫날 관찰 쿼리·롤백을 정리했다.
롤백이 되돌리지 않는 것(이미 만들어진 pending 행)의 두 갈래 처방도 함께 적었다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## 스펙 ↔ 태스크 매핑

| 스펙 절 | 내용 | 태스크 |
|---|---|---|
| §1-1 | 파트 A를 D+1 새벽 배치로 먼저 실행·저장 | 3, 4, 5, 6, 7, 9 |
| §1-2 | 파트 B는 성숙 시 별도 배치, `ContentAnalysisJob`에 phase로 추가 | 6, 7, 8 |
| §1-3 | `metric_timeliness` 기록 시점을 파트 B로, 파트 A는 `'pending'` | 1, 5, 8 |
| §1-4 | 제외 게이트 재정의(A 잡=행 존재, B 잡=A 있음 ∧ B 미완, 댓글 게이트는 B만) | 7 |
| §1-5 | 하지 않는 것(D+0 무LLM 라벨·파트 B 템플릿화) | 전 태스크에서 미구현 |
| §1-6 | 롤백은 `analytics.analyze-mode` 한 줄 | 1, 2, 7, 13 |
| §2-2 | 신 흐름 타임라인(A 05:00 · B 05:30 · 늦크롤 B 06:00 KST) | 9 |
| §3 | 행 상태 전이(A만 / A+B), 판별식은 `metric_timeliness='pending'` | 5 |
| §3 | `analyzed_at`은 파트 A 시각 고정, `model`은 파트 B가 덮음 | 5 |
| §3 | 파트 A 행에 기준선 스냅샷 미기록 → assembler 비교 자동 억제 | 5, 12 |
| §4-1 | 04 뷰 분할, `NOT mature OR timely OR in_window`, 배리어 유지 | 3 |
| §4-1 | 옛 백로그(성숙 ∧ 늦크롤 ∧ 윈도우 밖)는 열지 않음 | 3 |
| §4-1 | 첫 배포일 미성숙 3일치 청크 분할 | 13 (런북) |
| §4-2 | `'pending'` 어휘 신설 + CHECK 확장 + 부분 인덱스 | 1 |
| §4-2 | 소비처 계약표 전수(was 3곳 · 파생 MV · 재생성 잡 · 퍼널 · pending.sh · 커버리지) | 9, 10, 11, 12 |
| §4-3 | was 노출 규칙(6.1 무변경, 6.3 D+1부터 200, 계약 문서) | 12 |
| §4-4 | `Phase` enum · `resolveTargets(phase, timely)` · 진입점 매핑 | 7 |
| §4-4 | 파트 A 프롬프트·스키마·`parseFacts`, MAX_OUTPUT 8192 유지 | 4 |
| §4-4 | 파트 B 프롬프트를 배치 JSONL로, 사실은 9키로 조립 | 5(StoredFacts), 6 |
| §4-4 | `GeminiBatchLines` phase별 라인 + 사이드카 | 6 |
| §4-4 | `insertFacts` · `updateSynthesis`에 `metric_timeliness` | 5 |
| §4-4 | `ContentBatchCollectJob` kind 분기 · `DERIVED_INPUT_JOBS` | 8, 9 |
| §4-4 | 온라인 폴백(FACTS·SYNTHESIS 루프), 썸네일은 파트 A에만 | 7 |
| §4-4 | `ContentSynthesisRefreshJob`에 pending 제외 가드 | 10 |
| §4-5 | `content_batch_jobs.kind` + `batch_name` 접두사 | 1, 7 |
| §4-6 | 마이그레이션 3종(UTC 채번) · 뷰 · compose | 1, 3, 9 |
| §4-7 | 롤백 절차와 "되돌리지 않는 것" | 13 |
| §5 | 수거 분기 의사코드(0행 갱신 warn 포함) | 6, 8 |
| §6 | 뷰 하니스 케이스 | 3 |
| §6 | `ContentAnalysisWriterTest` 상태 전이 | 5 |
| §6 | `ContentAnalysisJobTest` phase별 resolveTargets · unified no-op | 7 |
| §6 | `ContentBatchCollectJobTest` kind 3종 · 기본값 호환 | 8 |
| §6 | `ContentSynthesisRefreshJobTest` pending 미대상 | 10 |
| §6 | `GeminiContentAnalyzerTest` 스키마·유저텍스트 | 4 |
| §6 | 마이그레이션 CHECK 테스트 | 1 |
| §6 | was 랭킹 제외 · 6.3 200 + null | 12 |
| §6 | 운영 검증(다음 날 아침 쿼리) | 13 |
| §7 | 부수 효과(커버리지 잔여·계정 카피·발굴 MV 가속) | 9(DERIVED_INPUT_JOBS), 13(관찰) |
| §9-1 | FE 6.3 null 렌더링 | 12 (계약 문서) |
| §9-2 | 옛 백로그 개방 여부 → 안 연다 | 3 |
| §9-3 | 파트 A 정확도 회귀 → 골드셋 대조 후 전환 | 13 (런북 17-2) |
| §9-5 | 크론 간격 A 05:00 · B 05:30 | 9 |
| §9-6 | 어드민 잡 카드 · 퍼널 "사실만" 칩 | 9, 11 |
| §9-7 | contract 단계 시점 | 이 계획 범위 밖(split 2주 안정 후 별도 릴리스) |
| §10 | 검토했다 접은 대안 | 뷰 주석·마이그레이션 주석에 근거로 기록(3, 1) |

## 해결한 스펙 모호성 (구현 시 이 선택을 따른다)

1. **파트 B 온라인 폴백의 포트 배선.** 스펙은 "SYNTHESIS는 `ContentSynthesisPort.synthesize`"라고만 적고 파트 A 온라인 경로의 포트를 명시하지 않았다. 기존 `ContentAttributePort`는 `(caption, thumbnailUrl)` 2인자라 유료 파트너십 태그를 못 싣는다(안 실으면 태그 붙은 게시물을 LLM이 organic으로 뒤집는다, 운영 실측 87건). **`ContentFactsPort`를 신설**했고, `ContentSynthesisPort`와 같은 이유로 Gemini/Vertex 전용이다. provider=anthropic이면 `JobConfig`가 null을 주입하고, split 모드로 돌리면 명시적으로 실패한다(조용한 no-op보다 낫다).

2. **사이드카 키 집합.** 스펙 §5는 "facts = short_code + is_beauty 판정에 필요한 어휘 버전"이라고 적었지만, 어휘(`BeautyTaxonomy`)는 수거 시점에 `taxonomyLoader`가 로드하므로 사이드카에 실을 필요가 없다. **kind와 무관하게 기존 `SIDECAR_KEYS` 한 벌**을 쓴다(파트 A는 기준선 키가 null, timely는 false 고정). 키를 kind마다 나누면 `parseSidecar`가 kind를 알아야 해 수거 경로가 두 겹으로 갈린다.

3. **커버리지 분모.** 스펙 §4-2는 "`synthesized_at IS NOT NULL`로 하거나 A/B 행 분리 표기" 중 택일을 남겼다. `synthesized_at`은 V38에서 생긴 컬럼이라 그 이전 기분석분이 해석 문구를 가지고 있는데도 분모에서 빠진다. **`metric_timeliness IS DISTINCT FROM 'pending'`** 을 쓴다 - 의미가 같으면서 레거시 행을 보존한다. `<>` 가 아니라 `IS DISTINCT FROM`인 것도 같은 이유(시점 NULL 레거시 행 보존)다. 같은 판단을 `ContentSynthesisRefreshJob` 가드에도 적용했다.

4. **어드민 퍼널의 "기분석" 정의.** 스펙은 칩 분리만 요구했으나, 트랙 `Done`을 행 존재로 두면 파트 A 행이 "랭킹 노출 가능"으로 잡혀 잔여가 실제보다 작게 보인다. **`Done` = 파트 B 완료로 좁히고 `FactsOnly`를 별도 축으로** 뒀다. 잔여(`truePending`)에는 사실만 행이 포함된다 - 랭킹에 뜨려면 아직 해야 할 일이기 때문이다. 후보 스캔은 `v_fact_candidates` 1회로 통합해 스캔 횟수를 늘리지 않았다(뷰 평가가 이 집계 비용의 본체다).

5. **6.3의 "비교 블록 없음".** 실제 `V1ContentReportAssembler.comparison()`은 항상 블록을 만든다. `'pending'` 행에서 null이 되는 것은 기준선 인용 필드(`engagementRate.baseline`, `engagementQuality.*.baseline`, `narrative`)와 `categoryContext.percentile`이고, `comparison.views`는 라이브 재계산이라 값이 있다. 계약 문서와 테스트를 **필드 단위로** 적었다.

6. **`pending.sql` 상태 코드.** 스펙은 "예: 24/34"만 제시했다. 파트 A는 미성숙 행과 영구 제외 행에도 붙으므로 **11(미성숙 · 사실만)·42(영구 제외 · 사실만)까지 4개**를 추가하고 항등식 블록도 함께 고쳤다. 안 하면 미성숙 칸에 파트 A 완료분이 숨는다.

7. **`ScheduleInfo` 인자 증가.** 기존 생성자는 크론 5개를 위치 인자로 받고 `AdminConfig`·테스트 두 곳이 호출한다. 가변 인자나 Map으로 리팩터링하지 않고 **인자 하나만 늘렸다** - 이 트랙 범위 밖의 변경을 만들지 않기 위해서다(호출부 2곳 + 테스트 2건 수정으로 끝난다).

8. **통합 프롬프트 바이트 동일성.** 파트 A 블록을 상수로 뽑을 때 텍스트를 재타이핑하면 검증 통과본(07-18 11건 검증)이 조용히 달라질 수 있다. **기존 줄을 잘라 옮기고**, `instructions()`의 결과 문자열이 이전과 바이트 단위로 같도록 포맷 자리만 재배치했다(Task 4에 명시).
