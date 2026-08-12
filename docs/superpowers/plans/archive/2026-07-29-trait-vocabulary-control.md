# trait 어휘 통제 구현 계획 (유사도 v2 2단계)

> 상태: ✅ 구현/실행/반영됨 (2026-07-30 운영 매핑 잡 DRY→APPLY 실행 완료 · 트랙 T)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** traits를 172개 고정 어휘로 통제 — 신규 산출은 프롬프트 주입+저장 sanitize, 기존 데이터는 LLM 배치 매핑(1:N 분해)으로 이행.

**Architecture:** 스펙 [2026-07-29-trait-vocabulary-control-design.md](../../specs/2026-07-29-trait-vocabulary-control-design.md).
어휘는 analysis DB `trait_taxonomy`(V41 시드) 단일 원천 — `TraitTaxonomyLoader`(BeautyTaxonomyLoader 패턴) 스냅샷을
프롬프트 주입과 저장 sanitize가 공유한다. 기존 데이터 이행은 어드민 원샷 잡 2모드(TRAIT_CANON_DRY/TRAIT_CANON_APPLY):
매핑(LLM→`trait_canon_log`)은 두 모드 공통, `account_analyses.traits` UPDATE는 APPLY만.

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway(analytics `db/migration/analysis`), Testcontainers(PostgreSQL), GeminiApi.generateJson.

**모듈 경계:** analytics만 변경(분석 결과 쓰기). was 컷 재조정은 매핑 후 별도 PR(스펙 §3-4-3).

---

### Task 1: V41 마이그레이션 — trait_taxonomy·trait_canon_log·시드

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V41__trait_taxonomy.sql`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/TraitTaxonomySeedTest.java`

- [ ] **Step 1: 실패하는 시드 테스트 작성** (BeautyTaxonomySeedTest 패턴 — TestDb.resetAndMigrate)

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.testsupport.TestDb;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V41 시드 ↔ 스펙 부록 A(2026-07-29, 사용자 확정 172개) 계약 검증. */
@Testcontainers
class TraitTaxonomySeedTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;
	static TraitTaxonomy taxonomy;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		taxonomy = new TraitTaxonomyLoader(ds).get();
	}

	@Test
	void 시드는_172개_13축이다() {
		assertEquals(172L, db.queryForObject("SELECT count(*) FROM trait_taxonomy", Long.class));
		assertEquals(13L, db.queryForObject("SELECT count(DISTINCT facet) FROM trait_taxonomy", Long.class));
	}

	@Test
	void 캐노니컬_집합_스팟체크() {
		assertTrue(taxonomy.names().contains("솔직 리뷰"));
		assertTrue(taxonomy.names().contains("릴스 중심"));
		assertTrue(taxonomy.names().contains("여름쿨톤"));
		assertTrue(taxonomy.names().contains("무쌍 메이크업"));
		assertTrue(taxonomy.names().contains("50대 이상"));
	}

	@Test
	void 프롬프트_블록은_축별_구획으로_전_어휘를_담는다() {
		String block = taxonomy.promptBlock();
		assertTrue(block.contains("콘텐츠 형식"));
		assertTrue(block.contains("퍼스널컬러"));
		assertTrue(block.contains("솔직 리뷰"));
		assertTrue(block.contains("키작녀 코디"));
	}

	@Test
	void canon_log_테이블이_존재한다() {
		db.update("INSERT INTO trait_canon_log (raw_value, canon_value, mapped_at) VALUES ('x','솔직 리뷰', now())");
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM trait_canon_log", Long.class));
	}
}
```

- [ ] **Step 2: 실패 확인** — `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :analytics:test --tests "com.celfit.analytics.llm.TraitTaxonomySeedTest"` → 컴파일 실패(TraitTaxonomy 없음)여도 "테이블·클래스 부재로 실패" 확인이면 충분. Task 2와 함께 그린으로.

- [ ] **Step 3: V41 작성**

```sql
-- trait 어휘 통제 (2026-07-29 스펙): traits 고정 어휘 + 배치 매핑 감사 로그.
-- 어휘 정본은 스펙 부록 A(사용자 확정 172개·13축). 수정은 후속 마이그레이션으로,
-- CHECK 없음 — 방어는 Java sanitize(프롬프트와 같은 로더 스냅샷). V30 beauty_taxonomy와 동일 원칙.
CREATE TABLE trait_taxonomy (
    name        text PRIMARY KEY,
    facet       text NOT NULL,
    facet_order int  NOT NULL,
    sort        int  NOT NULL
);

-- 배치 매핑 감사 — raw당 복수 행(1:N 분해 매핑). 매핑 불가는 canon_value='' 센티널 1행.
CREATE TABLE trait_canon_log (
    raw_value   text NOT NULL,
    canon_value text NOT NULL,
    mapped_at   timestamptz NOT NULL,
    PRIMARY KEY (raw_value, canon_value)
);

INSERT INTO trait_taxonomy (name, facet, facet_order, sort) VALUES
  ('릴스 중심','콘텐츠 형식',1,1),
  ('브이로그','콘텐츠 형식',1,2),
  -- … 스펙 부록 A의 13축 표를 축 순서(A=1…M=13)·표 행 순서 그대로 172행 전사한다.
  ('50대 이상','타겟·연령',13,4);
```

시드 행은 **스펙 부록 A를 축 순서(A:콘텐츠 형식=1 … M:타겟·연령=13)·표 내 순서 그대로 전사**한다.
facet 라벨은 부록 A 절 제목에서 개수 괄호를 뺀 것("콘텐츠 형식", "리뷰 방식", "정보·큐레이션·추천",
"뷰티 주제", "피부 타입·고민", "퍼스널컬러", "패션", "라이프스타일", "톤·무드", "소통·운영",
"커머스·활동", "정체성·전문성", "타겟·연령"). 캐노니컬의 빈도 괄호는 떼고 이름만.
전사 후 행 수를 세서 172 확인(테스트가 재검증).

- [ ] **Step 4: 커밋** — Task 2와 묶어 커밋(테스트가 로더까지 요구).

---

### Task 2: TraitTaxonomy + TraitTaxonomyLoader

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/TraitTaxonomy.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/TraitTaxonomyLoader.java`
- Test: Task 1의 `TraitTaxonomySeedTest` (통합) — 단위는 promptBlock 구획만 seed 테스트로 커버되므로 별도 파일 없음

- [ ] **Step 1: TraitTaxonomy record**

```java
package com.celfit.analytics.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * trait 고정 어휘 스냅샷 (trait_taxonomy, V41 시드 — 정본은 2026-07-29 스펙 부록 A).
 * 프롬프트 주입({@link #promptBlock()})과 저장 sanitize({@link #names()})가 같은 스냅샷을 본다.
 */
public record TraitTaxonomy(List<Entry> entries) {

	public record Entry(String name, String facet) {}

	/** 캐노니컬 전체 집합 — AccountAnalysisWriter sanitize·배치 매핑 검증용. */
	public Set<String> names() {
		return entries.stream().map(Entry::name).collect(Collectors.toUnmodifiableSet());
	}

	/** 축별 구획 어휘 블록 — 합성 프롬프트·배치 매핑 프롬프트 공용. */
	public String promptBlock() {
		Map<String, List<String>> byFacet = new LinkedHashMap<>();
		for (Entry e : entries) {
			byFacet.computeIfAbsent(e.facet(), k -> new java.util.ArrayList<>()).add(e.name());
		}
		return byFacet.entrySet().stream()
				.map(f -> "[" + f.getKey() + "] " + String.join(", ", f.getValue()))
				.collect(Collectors.joining("\n"));
	}
}
```

- [ ] **Step 2: TraitTaxonomyLoader** (BeautyTaxonomyLoader 사본 패턴 — 메모이즈·fail-fast)

```java
package com.celfit.analytics.llm;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * trait_taxonomy(analysis DB, V41 시드)에서 {@link TraitTaxonomy} 스냅샷을 조립한다.
 * 배치 프로세스 수명 동안 어휘는 불변 — 첫 로드 후 메모이즈 (BeautyTaxonomyLoader와 동일 취지:
 * 프롬프트와 sanitize가 항상 같은 스냅샷을 본다).
 */
public final class TraitTaxonomyLoader {

	private final JdbcTemplate analysis;
	private volatile TraitTaxonomy cached;

	public TraitTaxonomyLoader(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public TraitTaxonomy get() {
		TraitTaxonomy t = cached;
		if (t == null) {
			t = load();
			cached = t;
		}
		return t;
	}

	private TraitTaxonomy load() {
		List<TraitTaxonomy.Entry> entries = analysis.query("""
				SELECT name, facet FROM trait_taxonomy ORDER BY facet_order, sort""",
				(rs, i) -> new TraitTaxonomy.Entry(rs.getString(1), rs.getString(2)));
		if (entries.isEmpty()) {
			throw new IllegalStateException("trait 어휘 테이블이 비어 있음 — V41 시드 확인");
		}
		return new TraitTaxonomy(entries);
	}
}
```

- [ ] **Step 3: 테스트 그린 확인** — Task 1 Step 2 명령 재실행 → PASS
- [ ] **Step 4: 커밋** — `feat(analytics): trait 고정 어휘 V41 시드 + 로더 (유사도 v2 2단계)`

---

### Task 3: 합성 프롬프트 어휘 주입 (Gemini·Anthropic 공유)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicAccountSynthesizer.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java:87-95`
- Modify(호출부): `analytics/src/main/java/com/celfit/analytics/analyze/ClaudeBurstRunner.java`·`GeminiBackfillRunner.java` 중 `GeminiAccountSynthesizer.instructions()`를 쓰는 곳 전부 (grep으로 확정)
- Test: `analytics/src/test/java/com/celfit/analytics/llm/GeminiAccountSynthesizerTest.java` (기존 파일에 추가)

- [ ] **Step 1: 실패하는 테스트 추가** (기존 GeminiAccountSynthesizerTest에)

```java
private static TraitTaxonomy vocab() {
	return new TraitTaxonomy(List.of(
			new TraitTaxonomy.Entry("솔직 리뷰", "리뷰 방식"),
			new TraitTaxonomy.Entry("릴스 중심", "콘텐츠 형식")));
}

@Test
void 프롬프트는_어휘_내_선택을_지시하고_어휘_블록을_담는다() {
	String instructions = GeminiAccountSynthesizer.instructions(vocab());

	assertTrue(instructions.contains("아래 어휘에서만"));
	assertTrue(instructions.contains("[리뷰 방식] 솔직 리뷰"));
	assertTrue(instructions.contains("[콘텐츠 형식] 릴스 중심"));
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :analytics:test --tests "com.celfit.analytics.llm.GeminiAccountSynthesizerTest"` → 컴파일 실패(시그니처 없음)

- [ ] **Step 3: GeminiAccountSynthesizer 수정** — INSTRUCTIONS를 템플릿으로, traits 항목 교체:

```java
	static final String INSTRUCTIONS_TEMPLATE = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: (기존 그대로)
			- traits: 콘텐츠 성향 태그 3~5개 — **아래 어휘에서만 고른다(임의 조어·변형 금지, 표기 그대로).**
			  계정을 가장 잘 설명하는 원자 태그를 조합한다(예: 감성 브이로그 계정 → "브이로그"와 "감성 무드" 2개).
			  어휘:
			%s
			- perfSummary: (기존 그대로)
			…
			%s""";

	/** 시스템 프롬프트 — 구독 버스트 러너(ClaudeBurstRunner)도 같은 검증 통과본을 쓴다. */
	public static String instructions(TraitTaxonomy vocab) {
		return INSTRUCTIONS_TEMPLATE.formatted(vocab.promptBlock().indent(2), LlmGuard.RULES);
	}
```

기존 문구는 traits 항목 외 **한 글자도 바꾸지 않는다**(검증 통과본). 생성자는
`Supplier<TraitTaxonomy> vocab`을 추가로 받고 `synthesize`에서 `instructions(vocab.get())` 사용
(GeminiContentAnalyzer의 `Supplier<BeautyTaxonomy>` 패턴 그대로).

- [ ] **Step 4: AnthropicAccountSynthesizer 수정** — static final 캡처 제거, 생성자에 `TraitTaxonomyLoader` 추가, `synthesize`에서 `GeminiAccountSynthesizer.instructions(loader.get())` 호출(원천은 여전히 Gemini 어댑터 한 곳 — 기존 주석 취지 유지).

- [ ] **Step 5: LlmConfig 배선** — `accountSynthesisPort`가 `TraitTaxonomyLoader` 빈을 받아 두 구현에 주입:

```java
	@Bean
	@Lazy
	public TraitTaxonomyLoader traitTaxonomyLoader(
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new TraitTaxonomyLoader(analysisDataSource);
	}

	@Bean
	@Lazy
	public AccountSynthesisPort accountSynthesisPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini,
			TraitTaxonomyLoader traitLoader) {
		if ("anthropic".equals(settings.llmProvider())) {
			return new AnthropicAccountSynthesizer(anthropic.getObject(), settings, traitLoader);
		}
		return new GeminiAccountSynthesizer(gemini.getObject(), settings::geminiModel, traitLoader::get);
	}
```

- [ ] **Step 6: instructions() 무인자 호출부 정리** — `grep -rn "GeminiAccountSynthesizer.instructions()" analytics/` 로 찾은 곳(ClaudeBurstRunner export 등) 전부 로더 스냅샷을 넘기도록 수정.
- [ ] **Step 7: 전체 컴파일·해당 테스트 그린** — `./gradlew :analytics:compileJava :analytics:test --tests "*AccountSynthesizer*" --tests "*ClaudeBurstRunner*"` → PASS
- [ ] **Step 8: 커밋** — `feat(analytics): 계정 합성 프롬프트에 trait 어휘 주입 — 어휘 내 선택 강제`

---

### Task 4: AccountAnalysisWriter sanitize — 어휘 밖 드롭·중복 제거

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisWriter.java`
- Modify(호출부): `AccountAnalysisJob.java`·`ClaudeBurstRunner.java` — insert 시그니처에 어휘 Set 전달
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisWriterTest.java` (신규 — sanitize는 순수 함수라 단위)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 어휘 통제(2026-07-29 스펙 §3-2): 어휘 밖 드롭 + 중복 제거 + 5개 상한. 빈 결과는 허용(빈 배열 저장). */
class AccountAnalysisWriterTest {

	static final Set<String> VOCAB = Set.of("솔직 리뷰", "릴스 중심", "브이로그", "감성 무드", "코덕", "데일리룩");

	@Test
	void 어휘_밖_값은_드롭하고_중복은_접는다() {
		List<String> out = AccountAnalysisWriter.sanitize(
				List.of("솔직 리뷰", "솔직한 후기", "릴스 중심", "솔직 리뷰"), VOCAB);
		assertEquals(List.of("솔직 리뷰", "릴스 중심"), out);
	}

	@Test
	void 전부_어휘_밖이면_빈_배열이_된다() {
		assertEquals(List.of(), AccountAnalysisWriter.sanitize(List.of("아무말", "조어"), VOCAB));
	}

	@Test
	void 상한_5개는_sanitize_후에_적용된다() {
		List<String> out = AccountAnalysisWriter.sanitize(
				List.of("솔직 리뷰", "릴스 중심", "브이로그", "감성 무드", "코덕", "데일리룩"), VOCAB);
		assertEquals(5, out.size());
	}
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountAnalysisWriterTest"` → 컴파일 실패
- [ ] **Step 3: 구현** — Writer에 sanitize 추가, insert가 사용:

```java
	/** 어휘 통제(2026-07-29 스펙): 어휘 밖 드롭 → 중복 제거(입력 순서 유지) → MAX_TRAITS 절단. */
	static List<String> sanitize(List<String> raw, Set<String> vocabulary) {
		return raw.stream().filter(vocabulary::contains).distinct().limit(MAX_TRAITS).toList();
	}
```

`insert(...)` 시그니처에 `Set<String> vocabulary` 파라미터 추가, 기존 절단 로직을
`List<String> traits = sanitize(copy.traits(), vocabulary);`로 교체. `isValid`는 그대로
(LLM raw 빈 traits는 여전히 빈 카피 신호) — sanitize 후 0개는 빈 배열로 저장 허용(스펙 §3-2).

- [ ] **Step 4: 호출부 수정** — AccountAnalysisJob·ClaudeBurstRunner가 `TraitTaxonomyLoader`를 받아 `insert(..., loader.get().names())` 전달. Job 생성자를 만드는 config(어드민 쪽 `AdminConfig` 또는 잡 배선 위치를 grep으로 확정)도 같이.
- [ ] **Step 5: 그린 확인** — `./gradlew :analytics:test --tests "*AccountAnalysisWriter*" --tests "*AccountAnalysisJob*" --tests "*ClaudeBurstRunner*"` → PASS
- [ ] **Step 6: 커밋** — `feat(analytics): traits 저장 sanitize — 어휘 밖 드롭·중복 제거`

---

### Task 5: 배치 매핑 잡 (TRAIT_CANON_DRY / TRAIT_CANON_APPLY)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/TraitCanonJob.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/TraitMappingPort.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/GeminiTraitMapper.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/JobName.java`, `AnalyticsJobService.java`, 잡 빈 배선 config, `ScheduleRunner.java`(스케줄 제외 확인)
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/TraitCanonJobTest.java` (Testcontainers + fake port)

- [ ] **Step 1: 포트 정의**

```java
package com.celfit.analytics.llm;

import java.util.List;
import java.util.Map;

/** 고유 trait 값 → 캐노니컬(1:N, 최대 2) 매핑. 매핑 불가는 빈 리스트. */
public interface TraitMappingPort {
	Map<String, List<String>> map(List<String> rawValues);
}
```

- [ ] **Step 2: 실패하는 잡 테스트 작성** — Testcontainers로 V41까지 마이그레이션된 DB에 `account_analyses` 3행 시드(어휘 값·변형 값·매핑 불가 값 혼합), fake `TraitMappingPort`(고정 Map 반환)로:

```java
package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 배치 매핑(2026-07-29 스펙 §3-3): DRY=canon_log만, APPLY=traits in-place UPDATE. 1:N 분해·드롭·빈 배열 허용. */
@Testcontainers
class TraitCanonJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static DataSource ds;
	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
	}

	@BeforeEach
	void seed() {
		db.update("TRUNCATE account_analyses, trait_canon_log");
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, tagline, traits, perf_summary, content_summary)
				VALUES ('a', now(), 'test', 't', '["감성 브이로그","솔직한 후기","솔직 리뷰"]'::jsonb, 'p', 'c'),
				       ('b', now(), 'test', 't', '["조어불가값"]'::jsonb, 'p', 'c')""");
	}

	static TraitCanonJob job(boolean dryRun) {
		// 어휘 밖 캐노니컬("어휘밖")을 돌려주는 배신 케이스 포함 — 잡이 걸러야 한다
		var port = (com.celfit.analytics.llm.TraitMappingPort) raws -> Map.of(
				"감성 브이로그", List.of("브이로그", "감성 무드"),
				"솔직한 후기", List.of("솔직 리뷰"),
				"조어불가값", List.of(),
				"배신값", List.of("어휘밖"));
		return new TraitCanonJob(ds, port, dryRun, new ProgressReporter() {
			public void report(int done, int failed, int total) {}
		});
	}

	@Test
	void DRY는_canon_log만_쓰고_traits는_그대로다() {
		job(true).run();
		// 어휘 값("솔직 리뷰")은 LLM에 안 보내고 항등 처리 — canon_log 대상은 어휘 밖 raw만
		assertEquals(3L, db.queryForObject(
				"SELECT count(DISTINCT raw_value) FROM trait_canon_log", Long.class)); // 감성 브이로그·솔직한 후기·조어불가값
		assertEquals("[\"감성 브이로그\", \"솔직한 후기\", \"솔직 리뷰\"]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='a'", String.class));
	}

	@Test
	void APPLY는_분해·치환·드롭·중복제거로_UPDATE한다() {
		job(false).run();
		assertEquals("[\"브이로그\", \"감성 무드\", \"솔직 리뷰\"]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='a'", String.class));
		assertEquals("[]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='b'", String.class));
	}

	@Test
	void 재실행은_canon_log를_재사용해_LLM을_다시_부르지_않는다() {
		job(true).run();
		var counting = new java.util.concurrent.atomic.AtomicInteger();
		var port = (com.celfit.analytics.llm.TraitMappingPort) raws -> {
			counting.incrementAndGet();
			return Map.of();
		};
		new TraitCanonJob(ds, port, false, (d, f, t) -> {}).run();
		assertEquals(0, counting.get());
	}
}
```

(ProgressReporter가 함수형이 아니면 테스트의 무연산 구현을 기존 관용구에 맞춘다 — 구현 시 확인.)

- [ ] **Step 3: 실패 확인** — 컴파일 실패
- [ ] **Step 4: TraitCanonJob 구현** — 절차(스펙 §3-3):

```java
package com.celfit.analytics.analyze;

// (import 생략 — 구현 시 채움)

/**
 * trait 배치 매핑 원샷 잡 (2026-07-29 스펙 §3-3). DRY: LLM 매핑→trait_canon_log 기록·통계만.
 * APPLY: canon_log 기반으로 account_analyses.traits(전 이력 행) in-place UPDATE.
 * canon_log가 이미 있는 raw는 LLM 재호출 없이 재사용(재실행 안전·저렴).
 * 매핑 불가는 canon_value='' 센티널 1행 — UPDATE 시 드롭된다.
 */
public class TraitCanonJob {

	static final int BATCH = 100;

	public JobResult run() {
		TraitTaxonomy vocab = loader.get();
		Set<String> names = vocab.names();
		// 1) 고유 raw 수집 — 어휘 값은 항등이라 제외
		List<String> raws = analysis.queryForList("""
				SELECT DISTINCT t FROM account_analyses, jsonb_array_elements_text(traits) AS t
				WHERE t NOT IN (SELECT name FROM trait_taxonomy)
				  AND t NOT IN (SELECT DISTINCT raw_value FROM trait_canon_log)
				ORDER BY t""", String.class);
		// 2) BATCH개씩 port.map → 검증(캐노니컬이 어휘 밖이면 드롭, raw당 최대 2개) → canon_log INSERT
		//    (빈 리스트면 ('' 센티널) 1행). 진행은 reporter.report(처리 raw 수, 0, 전체).
		// 3) dryRun이면 통계 로그(매핑/드롭 raw 수) 남기고 종료.
		// 4) APPLY: 전 이력 행 순회 — 행별 raw traits를 canon_log(+어휘 항등)로 치환·평탄화·
		//    distinct·5개 절단 후 UPDATE account_analyses SET traits=?::jsonb WHERE ctid 기준이 아닌
		//    (handle, analyzed_at) PK 기준. 변경 없는 행은 스킵.
		// 반환: new JobResult(변경 행 수(DRY는 매핑 raw 수), 0, false)
	}
}
```

핵심 규칙(테스트가 고정): 검증 시 어휘 밖 캐노니컬 드롭 → 남은 게 없으면 매핑 불가 처리,
치환 순서는 원 traits 순서에서 각 raw의 캐노니컬 전개 순서, distinct는 첫 등장 유지,
`AccountAnalysisWriter.MAX_TRAITS` 재사용해 5개 절단.

- [ ] **Step 5: GeminiTraitMapper 구현** — `GeminiApi.generateJson` 사용, 시스템 프롬프트에
`vocab.promptBlock()` + 분해 규칙(최대 2개, 의미 없으면 빈 배열) 제시, 응답 스키마
`{"mappings":[{"raw":"…","canon":["…"]}]}`. userText는 raw 100개 개행 목록.
파싱은 ObjectMapper record 바인딩(GeminiContentAnalyzer.parse 관용구).

- [ ] **Step 6: JobName·서비스·배선** — `TRAIT_CANON_DRY("trait 어휘 매핑 dry-run (LLM)")`,
`TRAIT_CANON_APPLY("trait 어휘 매핑 실행 (LLM) — traits UPDATE")` 추가, AnalyticsJobService
switch에 두 케이스(ObjectProvider<TraitCanonJob> 2개가 아니라 dryRun 플래그 분기),
잡 빈 배선은 기존 AccountAnalysisJob 배선 위치를 따라 추가. **ScheduleRunner 크론 목록에
넣지 않는 것을 확인**(원샷 수동 전용).
- [ ] **Step 7: 그린 확인** — `./gradlew :analytics:test --tests "*TraitCanon*"` → PASS
- [ ] **Step 8: 커밋** — `feat(analytics): trait 배치 매핑 원샷 잡 — dry-run/apply 2모드`

---

### Task 6: 문서·마무리

**Files:**
- Modify: `docs/superpowers/specs/2026-07-28-similar-influencer-similarity-v2-design.md` (추기 1줄 — §5 보류 해제, 2026-07-29 스펙 링크)
- Modify: `ARCHITECTURE.md` §5(트랙 표)·§7(결정 기록)
- Modify: 본 계획 상태 헤더 → ✅, `plans/archive/` 이동은 머지 후

- [ ] **Step 1: 07-28 스펙 추기** — §5 앞에 `> 추기(2026-07-29): 2단계 보류 해제 — 어휘 172개·1:N 분해 매핑 확정, 정본은 2026-07-29-trait-vocabulary-control-design.md.`
- [ ] **Step 2: ARCHITECTURE 갱신** — 트랙 표에 2단계 상태, 결정 기록에 07-29 어휘 통제 확정 1줄.
- [ ] **Step 3: 전체 테스트** — `DOCKER_HOST=… ./gradlew :analytics:test` → 전체 PASS (was·crawler 영향 없음 확인은 `./gradlew test`)
- [ ] **Step 4: 커밋·PR** — `docs: trait 어휘 통제 스펙·계획 + ARCHITECTURE 갱신`, PR은 develop 대상 1건(전 커밋 포함).

---

## 배포 런북 (PR 머지 후 — 스펙 §3-4)

1. develop→main 머지(CD)로 analytics 배포 → 운영 Flyway V41 적용 확인(`flyway_schema_history`).
2. 어드민 /ui에서 `trait-canon-dry` 트리거 → 로그의 매핑/드롭 통계 확인 → **사용자 승인**.
3. `trait-canon-apply` 트리거 → 완료 후 검증 쿼리:
   `SELECT count(DISTINCT t) FROM account_analyses, jsonb_array_elements_text(traits) t` ≈ 172.
4. 유사도 점수 분포 재실측(07-28 dry-run 스크립트 재사용) → 컷 0.30 재점검, 필요시 was 상수 PR.

## Self-Review 결과

- 스펙 §2 ①~⑧ ↔ Task 1(①), Task 3·4(⑤), Task 5(②③④⑥), ⑦은 변경 없음(확인만), ⑧은 런북 4.
- 시드 172행 전사는 스펙 부록 A가 정본이라 계획에 중복 전사하지 않음(테스트 172 카운트가 방어).
- 타입 일치: `TraitTaxonomy.names()/promptBlock()`, `sanitize(List, Set)`, `TraitMappingPort.map` — Task 간 시그니처 상호 참조 확인 완료.
