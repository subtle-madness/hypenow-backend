# 태스크 C2 — 인플루언서 계정 LLM 카피 Implementation Plan

> 상태: ✅ 실행됨
> ※ 플랜 본문의 V11 표기는 실행 중 V20으로 renumber됨 (공유 DB 선점 충돌 — 스펙 §3 참조)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계정마다 LLM 1콜로 AccountReport 카피 7종을 생성해 `account_analyses`(V11)에 이력 INSERT하는 배치를 만든다.

**Architecture:** [스펙](../../specs/2026-07-14-c2-account-llm-design.md) 그대로 — B2·B3 LLM 골격(포트/어댑터·structured output·게이트·상한) 재사용. 대상 선별 = 신규 즉시 / stale(새 게시물)+쿨다운 7일. 계약 record `AccountAnalysis`를 생산자(잡)와 소비자(was/E)가 공유. 프롬프트 입력은 전부 C1 미러(`account_summaries`·`account_category_stats`·`account_content_series`+`contents.caption`).

**Tech Stack:** Java 21, Spring Boot 4.1(analytics 평탄 패키지), Anthropic SDK structured output, Flyway, Testcontainers(`postgres:16-alpine`) + `TestDb.resetAndMigrate`, Jackson 3(`tools.jackson.*`).

**작업 위치:** 워크트리 `.worktrees/c2` (브랜치 `feat/task-c2-account-llm`). 모든 명령은 워크트리 루트에서.

**사전 조건:** Docker 데몬(Testcontainers). Task 5의 실 실행만 `ANTHROPIC_AUTH_TOKEN`(또는 `ANTHROPIC_API_KEY`) 셸 export 필요 — `.env`는 JVM에 자동 로드되지 않는다(CLAUDE.md 함정).

**참고 파일 (패턴 출처):**
- 잡: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`
- 어댑터: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicSynthesizer.java`
- 배선: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`, `.../analyze/AnalyzeRunner.java`
- 테스트: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (fake 포트 + `TestDb.resetAndMigrate`)
- DDL·계약: `V3__content_analyses.sql`, `contract-analysis/.../AccountSummary.java`

---

### Task 1: 계약 record `AccountAnalysis` + Flyway V11 + FlywaySchemaTest

스펙 §3의 `id bigserial`은 자연키 `(handle, analyzed_at)` PK로 바꾼다 — 미러 계열의 자연키 관례와
FlywaySchemaTest의 전체 컬럼 순서 대조를 그대로 쓰기 위함(serial id는 record에 없는 컬럼이라 대조 불가).
스펙도 같은 커밋에서 정정한다.

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountAnalysis.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V11__account_analyses.sql`
- Modify: `analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java`
- Modify: `docs/superpowers/specs/2026-07-14-c2-account-llm-design.md` (§3 PK 정정)

- [ ] **Step 1: record 작성** (테스트가 record 없이는 컴파일 안 되므로 먼저)

`contract-analysis/src/main/java/com/celfit/contract/analysis/AccountAnalysis.java`:

```java
package com.celfit.contract.analysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 계정 LLM 카피 1행 (analytics 잡이 조립·INSERT, was/E가 계정별 최신 1행 SELECT — analysis DB account_analyses).
 * 이력 테이블: 행은 INSERT로만 쌓인다. inputLastPostedAt = 분석 당시 미러의 last_posted_at(stale 판정 기준).
 * adHeadline: 광고 비교 데이터(organic_avg·ad_avg 모두 존재)가 있을 때만 값, 아니면 null (프론트 계약).
 * traits: 성향 태그 3~5개 — DB엔 jsonb 배열, 직렬화는 생산자/소비자 각자의 매핑 계층에서.
 */
public record AccountAnalysis(String handle, OffsetDateTime analyzedAt, String model,
		OffsetDateTime inputLastPostedAt, Long inputAnalyzedCount, String tagline, String summary,
		String trendNote, String chartNote, List<String> traits, String adHeadline, String paceNote) {
}
```

- [ ] **Step 2: 실패하는 테스트 추가**

`FlywaySchemaTest.java` — import에 `com.celfit.contract.analysis.AccountAnalysis` 추가, 기존 테스트 뒤에:

```java
	@Test
	void account_analyses_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_analyses", AccountAnalysis.class);
	}
```

- [ ] **Step 3: 실행 — 실패 확인**

Run: `./gradlew :analytics:test --tests '*FlywaySchemaTest*'`
Expected: FAIL — 신규 테스트가 `account_analyses` 테이블 부재로 실패 (기존 7개는 통과)

- [ ] **Step 4: Flyway DDL 작성**

`analytics/src/main/resources/db/migration/analysis/V11__account_analyses.sql`:

```sql
-- 계정 LLM 카피 이력 (스펙 §3). content_analyses(불변 1회)와 달리 stale 재분석 — INSERT로만 쌓고
-- was/E는 계정별 최신 1행(analyzed_at DESC)을 읽는다. 미러 테이블과 FK 없음(논리 참조).
-- 컬럼 이름·순서 = AccountAnalysis record (FlywaySchemaTest 대조). PK가 최신 조회 인덱스를 겸한다.
CREATE TABLE account_analyses (
    handle               text NOT NULL,
    analyzed_at          timestamptz NOT NULL,
    model                text NOT NULL,
    input_last_posted_at timestamptz,
    input_analyzed_count bigint,
    tagline              text,
    summary              text,
    trend_note           text,
    chart_note           text,
    traits               jsonb,
    ad_headline          text,
    pace_note            text,
    PRIMARY KEY (handle, analyzed_at)
);
```

- [ ] **Step 5: 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests '*FlywaySchemaTest*'`
Expected: PASS (8개)

주의: `ContentAnalysisJobTest`·`CommentClassificationJobTest`는 `TestDb.resetAndMigrate`(스키마 통째
재생성)를 쓰므로 V11 추가로 DROP 목록 갱신이 필요 없다. `./gradlew :analytics:test` 전체도 그린 확인.

- [ ] **Step 6: 스펙 §3 정정**

`docs/superpowers/specs/2026-07-14-c2-account-llm-design.md` §3 코드 블록의
`id                     bigserial PK` 줄을 삭제하고, 블록 아래 문장
"인덱스 `(handle, analyzed_at DESC)`. 미러 테이블과 FK 없음(논리 참조)."을
"PK `(handle, analyzed_at)` — 자연키가 최신 조회 인덱스를 겸한다. 미러 테이블과 FK 없음(논리 참조)."으로 교체.

- [ ] **Step 7: Commit**

```bash
git add contract-analysis/src/main/java/com/celfit/contract/analysis/AccountAnalysis.java \
        analytics/src/main/resources/db/migration/analysis/V11__account_analyses.sql \
        analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java \
        docs/superpowers/specs/2026-07-14-c2-account-llm-design.md
git commit -m "feat(analytics): 계정 카피 계약 record + V11 account_analyses (자연키 이력 테이블)"
```

---

### Task 2: LLM 포트·입출력 record + Anthropic 어댑터

어댑터는 실 API 경계라 전용 테스트 없음(기존 `AnthropicSynthesizer`와 동일 취급) — 검증 로직은
전부 잡(Task 3)에 있고 잡 테스트가 fake 포트로 커버한다.

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AccountToAnalyze.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AccountCopy.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AccountSynthesisPort.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicAccountSynthesizer.java`

- [ ] **Step 1: 입출력 record + 포트**

`analytics/src/main/java/com/celfit/analytics/llm/AccountToAnalyze.java`:

```java
package com.celfit.analytics.llm;

import java.util.List;
import java.util.Map;

/**
 * 계정 카피 입력 — 잡이 조립한 계정 1건의 전체 맥락 (전부 C1 미러 산출물).
 * posts는 올린 순, 캡션은 앞 300자 절단. hasAdComparison=false면 어댑터가 adHeadline 생성을 지시하지 않는다.
 */
public record AccountToAnalyze(String handle, Map<String, Object> summary,
		List<Map<String, Object>> categoryStats, List<Map<String, Object>> posts,
		boolean hasAdComparison) {
}
```

`analytics/src/main/java/com/celfit/analytics/llm/AccountCopy.java`:

```java
package com.celfit.analytics.llm;

import java.util.List;

/** LLM 계정 카피 산출 — AccountReport의 문구 7종 (스펙 §1 표). */
public record AccountCopy(String tagline, String summary, String trendNote, String chartNote,
		List<String> traits, String adHeadline, String paceNote) {
}
```

`analytics/src/main/java/com/celfit/analytics/llm/AccountSynthesisPort.java`:

```java
package com.celfit.analytics.llm;

/** 계정 카피 포트 — 테스트는 fake (실 API 금지). */
public interface AccountSynthesisPort {

	AccountCopy synthesize(AccountToAnalyze account);
}
```

- [ ] **Step 2: Anthropic 어댑터**

`analytics/src/main/java/com/celfit/analytics/llm/AnthropicAccountSynthesizer.java`:

```java
package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;

/** 계정 카피 Anthropic 구현 — C1 미러 수치·캡션만 근거로 인플루언서 패널 문구 7종을 생성한다. */
public final class AnthropicAccountSynthesizer implements AccountSynthesisPort {

	private static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: 프로필 헤더 한 줄 소개 — 콘텐츠 성격·톤 (예: "저자극 스킨케어 중심 · 성분과 사용감을 짚는 정보형 리뷰 톤"). 40자 이내
			- summary: 마케터 관점의 계정 분석 요약, 3~4문장
			- trendNote: 최근 흐름 한 문장 (trend_direction·trend_change_pct 근거)
			- chartNote: 게시물별 성과 분포의 특징 한 문장 (예: "잘 터진 3개가 평균을 끌어올림")
			- traits: 콘텐츠 성향 태그 3~5개, 각 2~6자 명사구
			- adHeadline: 광고 비교 수치(organic_avg·ad_avg·ad_drop_pct) 근거 헤드라인 한 문장.
			  입력에 "광고 비교 데이터: 없음"이면 빈 문자열
			- paceNote: 업로드 페이스 한 문장 (avg_interval_days 근거, 예: "주 2~3회 올리는 페이스")
			""";

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicAccountSynthesizer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public AccountCopy synthesize(AccountToAnalyze account) {
		String input = """
				계정: @%s (광고 비교 데이터: %s)
				계정 지표: %s
				카테고리 믹스: %s
				게시물(올린 순, 캡션은 앞부분만): %s
				""".formatted(account.handle(), account.hasAdComparison() ? "있음" : "없음",
				account.summary(), account.categoryStats(), account.posts());
		StructuredMessageCreateParams<AccountCopy> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(AccountCopy.class)
				.addUserMessage(input)
				.build();
		return client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("계정 카피 응답에 본문 없음"))
				.text();
	}
}
```

- [ ] **Step 3: 컴파일 확인 + Commit**

Run: `./gradlew :analytics:compileJava`
Expected: BUILD SUCCESSFUL

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/AccountToAnalyze.java \
        analytics/src/main/java/com/celfit/analytics/llm/AccountCopy.java \
        analytics/src/main/java/com/celfit/analytics/llm/AccountSynthesisPort.java \
        analytics/src/main/java/com/celfit/analytics/llm/AnthropicAccountSynthesizer.java
git commit -m "feat(analytics): 계정 카피 포트·입출력 record + Anthropic 어댑터"
```

---

### Task 3: `AccountAnalysisJob` + 설정 키 2종 + 잡 테스트 (TDD)

**Files:**
- Create: `analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java`
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java`:

```java
package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 계정 카피 배치 계약 (스펙 §2·§4):
 * ① 신규 즉시 분석·저장(adHeadline 조건부·traits jsonb 포함) ② 입력 동일 스킵
 * ③ stale인데 쿨다운 미경과 제외 ④ stale+쿨다운 경과 재분석 — 이력 2행
 * ⑤ 배치 상한 ⑥ 빈 카피 실패 격리 ⑦ traits 5개 절단.
 */
@Testcontainers
class AccountAnalysisJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	AccountAnalysisJob job;
	List<AccountToAnalyze> calls;

	/** fake 포트: 호출 기록 + 고정 응답. adHeadline은 항상 채워 반환 — 조건부 NULL은 잡의 책임임을 검증. */
	AccountSynthesisPort fakePort() {
		return account -> {
			calls.add(account);
			return new AccountCopy("태그라인: " + account.handle(), "요약 문단", "흐름 문구", "차트 캡션",
					List.of("저자극", "성분리뷰", "정보형"), "광고 헤드라인", "페이스 문구");
		};
	}

	void rewireJob(AccountSynthesisPort port) {
		job = new AccountAnalysisJob(ds, port, new AnalyticsSettings(db));
	}

	@BeforeEach
	void setUp() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		calls = new ArrayList<>();
		TestDb.resetAndMigrate(db, ds);
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");

		// C1 미러 시드: acct_ad(광고 비교 있음), acct_noad(광고 없음 — ad_avg NULL)
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  organic_avg, ad_avg, last_posted_at) VALUES
				  ('acct_ad',   10000, 6, 6, 'views', 13500, 15000, timestamptz '2026-07-01 09:00:00+09'),
				  ('acct_noad',  8000, 4, 4, 'views', 10375, NULL,  timestamptz '2026-07-02 09:00:00+09')""");
		db.update("""
				INSERT INTO account_category_stats (account_handle, main_group, content_count) VALUES
				  ('acct_ad', 'B', 6), ('acct_noad', 'B', 4)""");
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('p1', 'acct_ad',   timestamptz '2026-06-01 09:00:00+09', 'reels', 20000, 400, 40, false),
				  ('p2', 'acct_ad',   timestamptz '2026-07-01 09:00:00+09', 'reels', 22000, 500, 50, true),
				  ('p3', 'acct_noad', timestamptz '2026-07-02 09:00:00+09', 'feed',  NULL,  200, 20, false)""");
		db.update("""
				INSERT INTO contents (short_code, account_handle, caption, content_type) VALUES
				  ('p1', 'acct_ad', '캡션1', 'reels'), ('p2', 'acct_ad', '캡션2', 'reels'),
				  ('p3', 'acct_noad', '캡션3', 'feed')""");

		rewireJob(fakePort());
	}

	@Test
	void 신규_계정은_즉시_분석되고_카피가_저장된다() {
		int processed = job.run();

		assertEquals(2, processed);
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
		assertEquals("태그라인: acct_ad", db.queryForObject(
				"SELECT tagline FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// traits는 jsonb 배열로 저장된다
		assertEquals("저자극", db.queryForObject(
				"SELECT traits->>0 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// input 스냅샷 = 분석 당시 미러 값
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad' AND input_last_posted_at = timestamptz '2026-07-01 09:00:00+09'",
				Long.class));
	}

	@Test
	void adHeadline은_광고_비교가_있는_계정에만_저장된다() {
		job.run();

		// fake 포트는 둘 다 헤드라인을 반환하지만, 비교 없는 계정은 잡이 NULL로 저장한다
		assertEquals("광고 헤드라인", db.queryForObject(
				"SELECT ad_headline FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		assertNull(db.queryForObject(
				"SELECT ad_headline FROM account_analyses WHERE handle = 'acct_noad'", String.class));
		// 포트 입력의 hasAdComparison 플래그도 정확해야 한다 (어댑터가 지시문에서 분기)
		assertTrue(calls.stream().filter(c -> c.handle().equals("acct_ad")).findFirst().orElseThrow().hasAdComparison());
		assertFalse(calls.stream().filter(c -> c.handle().equals("acct_noad")).findFirst().orElseThrow().hasAdComparison());
	}

	@Test
	void 입력이_같으면_재분석하지_않는다() {
		job.run();
		calls.clear();

		int processed = job.run();

		assertEquals(0, processed);
		assertTrue(calls.isEmpty());
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	@Test
	void stale여도_쿨다운_미경과면_재분석하지_않는다() {
		job.run(); // 최초 분석 (analyzed_at = now)
		calls.clear();
		// 새 게시물 유입으로 stale
		db.update("UPDATE account_summaries SET last_posted_at = timestamptz '2026-07-10 09:00:00+09' WHERE handle = 'acct_ad'");

		int processed = job.run(); // 쿨다운 기본 7일 — 방금 분석했으므로 미경과

		assertEquals(0, processed);
		assertTrue(calls.isEmpty());
	}

	@Test
	void stale이고_쿨다운이_지나면_재분석되어_이력이_쌓인다() {
		job.run();
		calls.clear();
		db.update("UPDATE account_summaries SET last_posted_at = timestamptz '2026-07-10 09:00:00+09' WHERE handle = 'acct_ad'");
		// 기존 분석을 8일 전으로 백데이트 — 쿨다운(7일) 경과 재현
		db.update("UPDATE account_analyses SET analyzed_at = now() - interval '8 days' WHERE handle = 'acct_ad'");

		int processed = job.run();

		assertEquals(1, processed); // acct_ad만 (acct_noad는 입력 동일)
		assertEquals(2L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Long.class)); // 이력 2행
		// 최신 행의 input 스냅샷이 갱신된 last_posted_at
		assertEquals(1, db.queryForObject("""
				SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'
				  AND input_last_posted_at = timestamptz '2026-07-10 09:00:00+09'
				  AND analyzed_at = (SELECT max(analyzed_at) FROM account_analyses WHERE handle = 'acct_ad')""",
				Integer.class));
	}

	@Test
	void 배치_상한을_지킨다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-batch-limit', '1')");

		int processed = job.run(); // 신규 2계정 중 1건만

		assertEquals(1, processed);
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	@Test
	void 빈_카피는_저장하지_않고_다른_계정은_처리된다() {
		rewireJob(account -> {
			calls.add(account);
			if (account.handle().equals("acct_ad")) {
				return new AccountCopy("", "", "흐름", "차트", List.of("태그"), "", "페이스");
			}
			return new AccountCopy("태그라인", "요약", "흐름", "차트", List.of("태그", "태그2", "태그3"), "", "페이스");
		});

		int processed = job.run(); // 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // acct_noad만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_noad'", Long.class));
	}

	@Test
	void traits가_5개를_넘으면_앞_5개만_저장한다() {
		rewireJob(account -> {
			calls.add(account);
			return new AccountCopy("태그라인", "요약", "흐름", "차트",
					List.of("t1", "t2", "t3", "t4", "t5", "t6"), "", "페이스");
		});

		job.run();

		assertEquals(5, db.queryForObject(
				"SELECT jsonb_array_length(traits) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
		assertEquals("t5", db.queryForObject(
				"SELECT traits->>4 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
	}
}
```

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew :analytics:test --tests '*AccountAnalysisJobTest*'`
Expected: 컴파일 FAIL — `AccountAnalysisJob` 미존재

- [ ] **Step 3: 설정 키 2종 추가**

`analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java` — 기존 키 상수 아래에 추가:

```java
	/** 1회 실행당 계정 카피(LLM 호출) 계정 수 상한 — 비용 가드. */
	public static final String KEY_ACCOUNT_ANALYZE_BATCH_LIMIT = "analytics.account-analyze-batch-limit";
	/** stale 계정 재분석 최소 간격(일) — 매일 크롤 구조에서 계정당 매일 호출 방지 (스펙 §2-2). */
	public static final String KEY_ACCOUNT_ANALYZE_COOLDOWN_DAYS = "analytics.account-analyze-cooldown-days";

	static final int DEFAULT_ACCOUNT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_COOLDOWN_DAYS = 7;
```

기존 메서드 아래에 추가:

```java
	public int accountAnalyzeBatchLimit() {
		return read(KEY_ACCOUNT_ANALYZE_BATCH_LIMIT).map(Integer::parseInt)
				.orElse(DEFAULT_ACCOUNT_ANALYZE_BATCH_LIMIT);
	}

	public int accountAnalyzeCooldownDays() {
		return read(KEY_ACCOUNT_ANALYZE_COOLDOWN_DAYS).map(Integer::parseInt)
				.orElse(DEFAULT_ACCOUNT_ANALYZE_COOLDOWN_DAYS);
	}
```

- [ ] **Step 4: 잡 구현**

`analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java`:

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.contract.analysis.AccountAnalysis;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 계정 카피 배치 (스펙 §4). content_analyses(불변 1회)와 달리 stale 재분석 —
 * 행은 INSERT로만 쌓고 was/E는 계정별 최신 1행을 읽는다.
 * 대상: ① 분석 없음 → 즉시 ② input_last_posted_at ≠ 미러 last_posted_at(새 게시물, stale)
 *       AND 마지막 분석 후 쿨다운(일) 경과. 계정 단위 실패 격리.
 */
public class AccountAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(AccountAnalysisJob.class);
	private static final int MAX_TRAITS = 5;
	private static final int CAPTION_CHARS = 300;

	private final JdbcTemplate analysis;
	private final AccountSynthesisPort port;
	private final AnalyticsSettings settings;
	private final ObjectMapper json = new ObjectMapper();

	public AccountAnalysisJob(DataSource analysisDataSource, AccountSynthesisPort port,
			AnalyticsSettings settings) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.port = port;
		this.settings = settings;
	}

	/** @return 카피 생성 완료 계정 수 */
	public int run() {
		List<String> targets = analysis.queryForList("""
				SELECT s.handle
				FROM account_summaries s
				LEFT JOIN LATERAL (
				  SELECT a.input_last_posted_at, a.analyzed_at
				  FROM account_analyses a WHERE a.handle = s.handle
				  ORDER BY a.analyzed_at DESC LIMIT 1
				) latest ON true
				WHERE latest.analyzed_at IS NULL
				   OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
				       AND latest.analyzed_at < now() - make_interval(days => ?))
				ORDER BY s.handle
				LIMIT ?""", String.class,
				settings.accountAnalyzeCooldownDays(), settings.accountAnalyzeBatchLimit());
		String model = settings.llmModel();
		int processed = 0;
		int failed = 0;
		for (String handle : targets) {
			try {
				analyzeOne(handle, model);
				processed++;
			} catch (Exception e) {
				failed++;
				log.error("account copy failed for {} — 다음 실행에서 재대상", handle, e);
			}
		}
		log.info("account copy complete ({} accounts, {} failed)", processed, failed);
		return processed;
	}

	private void analyzeOne(String handle, String model) {
		Map<String, Object> summary = analysis.queryForMap(
				"SELECT * FROM account_summaries WHERE handle = ?", handle);
		// input 스냅샷은 JDBC 타입 지정 조회 (queryForMap의 timestamptz는 Timestamp라 record 타입과 어긋남)
		OffsetDateTime lastPostedAt = analysis.queryForObject(
				"SELECT last_posted_at FROM account_summaries WHERE handle = ?", OffsetDateTime.class, handle);
		Long analyzedCount = analysis.queryForObject(
				"SELECT analyzed_count FROM account_summaries WHERE handle = ?", Long.class, handle);
		List<Map<String, Object>> categories = analysis.queryForList("""
				SELECT main_group, content_count FROM account_category_stats
				WHERE account_handle = ? ORDER BY content_count DESC, main_group ASC""", handle);
		List<Map<String, Object>> posts = analysis.queryForList("""
				SELECT p.posted_at, p.content_type, p.views, p.likes, p.comments, p.sponsored,
				       left(c.caption, %d) AS caption
				FROM account_content_series p
				LEFT JOIN contents c ON c.short_code = p.short_code
				WHERE p.account_handle = ?
				ORDER BY p.posted_at ASC, p.short_code ASC""".formatted(CAPTION_CHARS), handle);
		boolean hasAdComparison = summary.get("organic_avg") != null && summary.get("ad_avg") != null;

		AccountCopy copy = port.synthesize(
				new AccountToAnalyze(handle, summary, categories, posts, hasAdComparison));

		// 이력 INSERT 전 가드 — 빈 카피가 "최신 행"으로 서빙되는 것을 차단 (B3의 빈 종합 가드와 동일 취지)
		if (isBlank(copy.tagline()) || isBlank(copy.summary())) {
			throw new IllegalStateException("계정 카피가 비어 있음: " + handle);
		}
		if (copy.traits() == null || copy.traits().isEmpty()) {
			throw new IllegalStateException("traits가 비어 있음: " + handle);
		}
		List<String> traits = copy.traits().size() > MAX_TRAITS
				? copy.traits().subList(0, MAX_TRAITS) : copy.traits();

		AccountAnalysis row = new AccountAnalysis(handle, OffsetDateTime.now(), model,
				lastPostedAt, analyzedCount, copy.tagline(), copy.summary(), copy.trendNote(),
				copy.chartNote(), traits,
				hasAdComparison ? blankToNull(copy.adHeadline()) : null, copy.paceNote());
		analysis.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, summary, trend_note, chart_note, traits,
				  ad_headline, pace_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
				row.handle(), row.analyzedAt(), row.model(), row.inputLastPostedAt(),
				row.inputAnalyzedCount(), row.tagline(), row.summary(), row.trendNote(),
				row.chartNote(), json.writeValueAsString(row.traits()), row.adHeadline(),
				row.paceNote());
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String blankToNull(String s) {
		return isBlank(s) ? null : s;
	}
}
```

- [ ] **Step 5: 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests '*AccountAnalysisJobTest*'`
Expected: PASS (8개)

- [ ] **Step 6: 모듈 전체 회귀**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java \
        analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java \
        analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java
git commit -m "feat(analytics): 계정 카피 배치 — 신규 즉시·stale+쿨다운 재분석, 이력 INSERT"
```

---

### Task 4: 배선 — LlmConfig 게이트 + AccountAnalyzeRunner

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalyzeRunner.java`

- [ ] **Step 1: LlmConfig 게이트 확장 + 포트 빈**

`LlmConfig.java`의 클래스 어노테이션을 다음으로 교체 (account 게이트 추가):

```java
@Configuration
@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.analyze-on-startup:false}"
		+ " or ${analytics.account-analyze-on-startup:false}")
public class LlmConfig {
```

기존 빈들 아래에 추가:

```java
	@Bean
	public AccountSynthesisPort accountSynthesisPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicAccountSynthesizer(client, settings);
	}
```

- [ ] **Step 2: 러너 작성**

`analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalyzeRunner.java`:

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountSynthesisPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 계정 카피 배치 배선 — analytics.account-analyze-on-startup=true일 때만 (실 API 비용). */
@Configuration
@ConditionalOnProperty(name = "analytics.account-analyze-on-startup", havingValue = "true")
public class AccountAnalyzeRunner {

	@Bean
	public AccountAnalysisJob accountAnalysisJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AccountSynthesisPort port, AnalyticsSettings settings) {
		return new AccountAnalysisJob(analysisDataSource, port, settings);
	}

	@Bean
	public CommandLineRunner accountAnalyzeOnStartup(AccountAnalysisJob job) {
		return args -> job.run();
	}
}
```

- [ ] **Step 3: 전체 테스트 + Commit**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java \
        analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalyzeRunner.java
git commit -m "feat(analytics): 계정 카피 배치 배선 — 독립 게이트 account-analyze-on-startup"
```

---

### Task 5: 소량 실 실행 확인 + 문서 갱신

- [ ] **Step 1: 상한 2로 소량 실 실행** (LLM 비용 발생 — 계정 2건 × opus 1콜)

```bash
docker start crawler-postgres-1
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -c \
  "INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-batch-limit', '2')
   ON CONFLICT (key) DO UPDATE SET value = '2';"
export ANTHROPIC_AUTH_TOKEN=...   # 또는 ANTHROPIC_API_KEY (.env는 자동 로드 안 됨)
./gradlew :analytics:bootRun --args='--analytics.account-analyze-on-startup=true'
```

Expected: 미러 실행 후 `account copy complete (2 accounts, 0 failed)` 로그 → 정상 종료.
확인:

```bash
docker exec -i crawler-postgres-1 psql -U crawler -d analysis -c \
  "SELECT handle, model, tagline, traits, ad_headline IS NOT NULL AS has_ad_headline, pace_note
   FROM account_analyses ORDER BY analyzed_at DESC LIMIT 2;"
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -c \
  "DELETE FROM app_setting WHERE key = 'analytics.account-analyze-batch-limit';"
```

Expected: 2행 — tagline·traits(3~5개)·pace_note가 자연스러운 한국어 카피, 광고 비교 없는 계정은 has_ad_headline=f.
카피 품질이 어색하면(수치 지어냄·분량 초과) INSTRUCTIONS를 다듬고 재실행 — 코드 계약은 불변.

- [ ] **Step 2: ARCHITECTURE.md 갱신 + 플랜 아카이브**

§5의 C2 행 상태를 `🔨` → `✅`로. (§7 결정 기록은 07-14에 이미 기록됨 — 중복 추가 금지.)
플랜 첫머리 `> 상태: 🟢 활성`을 `> 상태: ✅ 실행됨`으로 바꾼 뒤:

```bash
git mv docs/superpowers/plans/2026-07-14-c2-account-llm.md docs/superpowers/plans/archive/
git add ARCHITECTURE.md
git commit -m "docs: C2 완료 반영 — 계정 카피 배치 개통 + 계획 아카이브"
```

---

## Self-Review 결과

- **스펙 커버리지**: §1 카피 7종 → Task 2·3, §2-1 별도 잡 → Task 3, §2-2 신규 즉시/stale+쿨다운·이력 INSERT → Task 3(선별 쿼리·테스트 ③④), §2-3 계약 record → Task 1, §2-4 adHeadline 조건부 → Task 3(잡이 NULL 강제 + 테스트), §2-5 키·게이트 → Task 3(Settings)·Task 4(배선), §3 저장 → Task 1(PK만 자연키로 정정 — 스펙 동반 수정), §4 잡 흐름 → Task 3, §5 검증 → Task 1(스키마 대조)·Task 3(포트 fake 8케이스)·Task 5(소량 실 실행), §6·§7 → Task 5 문서.
- **플레이스홀더**: 없음 — 전 스텝 실코드·실명령.
- **타입 일관성**: `AccountAnalysis` 12컴포넌트 = V11 12컬럼 순서 대조 완료(`analyzedAt`→`analyzed_at` 등 snake 변환 확인). `AccountCopy`/`AccountToAnalyze`/`AccountSynthesisPort` 시그니처가 Task 2·3·4에서 동일. `AnalyticsSettings` 메서드명(`accountAnalyzeBatchLimit`·`accountAnalyzeCooldownDays`)이 잡·테스트에서 일치.
- **주의 지점 재확인**: queryForMap의 timestamptz→Timestamp 함정은 input 스냅샷만 타입 지정 조회로 회피(잡 코드 주석 명시). 테스트 fake 포트가 adHeadline을 항상 채워 반환하는 것은 의도 — 조건부 NULL이 잡의 책임임을 검증하기 위함.
