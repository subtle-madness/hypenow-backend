# 캡션 분류 + B3 숙성 가드 구현 계획

> 상태: ✅ 구현/실행/반영됨
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 캡션 5종(광고 구분·카테고리·브랜드·제품·유통사) 산출을 기존 VLM 콜의 "캡션 주·썸네일 보조" 전환으로 구현하고, 분류 어휘를 analysis DB 테이블(V30)로 옮기며, 분석 대상에 게시 후 3일 숙성 가드를 추가한다.

**Architecture:** 스펙 [2026-07-14-caption-classification-design.md](../specs/2026-07-14-caption-classification-design.md). 별도 캡션 잡 없음 — `VisionPort`→`ContentAttributePort`로 전환(캡션 항상, 썸네일은 살아있을 때만 첨부), 병합은 모델 안에서. `BeautyTaxonomy`는 DB 스냅샷 인스턴스 + 로더로. content_analyses 컬럼 재사용 + `detected_products` 신설.

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway(analysis DB `db/migration/analysis`), Testcontainers 2.x(`org.testcontainers.postgresql`), Anthropic SDK structured output, Jackson 3.

**검증:** `./gradlew :analytics:test` (LLM은 포트 fake — 실 API 금지). 커밋 prefix `feat(analytics):`, 한국어.

---

### Task 1: BeautyTaxonomy를 불변 스냅샷 인스턴스로 전환

정적 하드코딩 상수 → 생성자로 받은 행 목록에서 조립되는 불변 인스턴스. DB는 아직 안 붙인다(Task 2).

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/BeautyTaxonomy.java` (전면 재작성)
- Modify: `analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomyTest.java` (인스턴스 동작 단위 테스트로 재작성 — 시드 계약 검증은 Task 2의 SeedTest로 이동)

- [ ] **Step 1: 실패하는 테스트 작성** — `BeautyTaxonomyTest.java` 전체를 다음으로 교체

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 어휘 스냅샷 인스턴스의 조립 동작 단위 테스트 (소형 픽스처).
 * 실제 시드(V30)가 프론트 배포본과 일치하는지는 BeautyTaxonomySeedTest가 검증한다.
 */
class BeautyTaxonomyTest {

	private static final BeautyTaxonomy FIXTURE = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "스킨"),
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "토너"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립스틱")),
			List.of("올리브영", "다이소"));

	@Test
	void 대분류_slug_집합을_행에서_조립한다() {
		assertEquals(Set.of("skincare", "makeup"), FIXTURE.mainCategories());
	}

	@Test
	void 중분류와_소분류_라벨을_모두_포함하는_집합을_제공한다() {
		Set<String> labels = FIXTURE.allMidAndSubLabels();

		// 프론트 mid/sub 필터가 sub_categories 배열 포함 여부로 매칭 — 중분류·소분류 라벨 둘 다 어휘다
		assertTrue(labels.contains("립메이크업")); // 중분류
		assertTrue(labels.contains("립틴트"));     // 소분류
		assertTrue(labels.contains("스킨/토너"));
	}

	@Test
	void 소분류_라벨_집합은_중분류를_포함하지_않는다() {
		Set<String> subs = FIXTURE.allSubLabels();

		assertTrue(subs.contains("립틴트"));
		assertFalse(subs.contains("립메이크업")); // 중분류는 카드 칩(제품 카테고리) 어휘가 아니다
	}

	@Test
	void 유통사_집합과_프롬프트_나열을_제공한다() {
		assertEquals(Set.of("올리브영", "다이소"), FIXTURE.distributors());
		assertEquals("올리브영|다이소", FIXTURE.distributorsPrompt()); // 시드 정렬 순서 유지
	}

	@Test
	void 프롬프트_분류표는_slug와_라벨_계층을_행_순서대로_렌더링한다() {
		assertEquals("""
				skincare(스킨케어): 스킨/토너[스킨, 토너]
				makeup(메이크업): 립메이크업[립틴트, 립스틱]""",
				FIXTURE.promptTable());
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd /Users/woomin/Project/hypenow-backend/.worktrees/caption && ./gradlew :analytics:test --tests 'com.celfit.analytics.llm.BeautyTaxonomyTest'`
Expected: 컴파일 실패 (`Entry` 심볼 없음 — 아직 정적 클래스)

- [ ] **Step 3: BeautyTaxonomy 재작성**

```java
package com.celfit.analytics.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 분류 어휘의 단일 원천 — beauty_taxonomy·beauty_distributors 테이블(analysis DB, V30 시드)에서
 * {@link BeautyTaxonomyLoader}가 조립하는 불변 스냅샷. celfit-front 배포본 필터 어휘와 1:1이며
 * 분류값·라벨은 생산자(분석 층)가 확정하고 was는 verbatim 전달만 한다(ARCHITECTURE §4-4).
 * 프론트 mid/sub 필터는 sub_categories 배열 포함 여부로 매칭하므로
 * 중분류·소분류 라벨 표기가 한 글자라도 다르면 필터가 빈다 — 시드 수정 시 프론트와 함께 갱신할 것.
 * 프롬프트 분류표와 sanitize 어휘 집합이 같은 인스턴스에서 나온다 — 원천 분리 금지.
 */
public final class BeautyTaxonomy {

	/** 소분류당 1행. 목록 순서 = 시드 정렬 순서 (프롬프트 분류표 렌더링 순서). */
	public record Entry(String mainValue, String mainLabel, String midLabel, String subLabel) {
	}

	private final List<Entry> entries;
	private final List<String> distributors;
	private final Set<String> mainCategories;
	private final Set<String> distributorSet;
	private final Set<String> midAndSubLabels;
	private final Set<String> subLabels;

	public BeautyTaxonomy(List<Entry> entries, List<String> distributors) {
		this.entries = List.copyOf(entries);
		this.distributors = List.copyOf(distributors);
		this.mainCategories = entries.stream().map(Entry::mainValue)
				.collect(Collectors.toUnmodifiableSet());
		this.distributorSet = Set.copyOf(distributors);
		this.midAndSubLabels = entries.stream()
				.flatMap(e -> Stream.of(e.midLabel(), e.subLabel()))
				.collect(Collectors.toUnmodifiableSet());
		this.subLabels = entries.stream().map(Entry::subLabel)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** 대분류 value(영문 slug) — content_analyses.main_category 어휘. */
	public Set<String> mainCategories() {
		return mainCategories;
	}

	/** 유통사 상호명 — 프론트 유통사 필터값. */
	public Set<String> distributors() {
		return distributorSet;
	}

	/** sub_categories 어휘 — 중분류+소분류 라벨 전체 (프론트가 배열 포함으로 매칭). */
	public Set<String> allMidAndSubLabels() {
		return midAndSubLabels;
	}

	/** detected_product_categories 어휘 — 소분류 라벨만 (카드 칩). */
	public Set<String> allSubLabels() {
		return subLabels;
	}

	/** 프롬프트에 넣는 유통사 나열 — 예: "올리브영|다이소". */
	public String distributorsPrompt() {
		return String.join("|", distributors);
	}

	/** 프롬프트에 넣는 분류표 — slug(한글 라벨): 중분류[소분류…] 계층, 행 순서 유지. */
	public String promptTable() {
		Map<String, String> mainLabels = new LinkedHashMap<>();
		Map<String, Map<String, List<String>>> tree = new LinkedHashMap<>();
		for (Entry e : entries) {
			mainLabels.putIfAbsent(e.mainValue(), e.mainLabel());
			tree.computeIfAbsent(e.mainValue(), k -> new LinkedHashMap<>())
					.computeIfAbsent(e.midLabel(), k -> new ArrayList<>())
					.add(e.subLabel());
		}
		return tree.entrySet().stream()
				.map(main -> "%s(%s): %s".formatted(main.getKey(), mainLabels.get(main.getKey()),
						main.getValue().entrySet().stream()
								.map(mid -> "%s[%s]".formatted(mid.getKey(), String.join(", ", mid.getValue())))
								.collect(Collectors.joining(" · "))))
				.collect(Collectors.joining("\n"));
	}
}
```

주의: 이 시점에 `AnthropicVisionAnalyzer`가 정적 참조(`BeautyTaxonomy.promptTable()` 등)로 깨진다 — Task 3에서 함께 고칠 때까지 `:analytics:compileJava`는 실패 상태. Step 4는 이 테스트 클래스만 돌리지 말고 컴파일 에러를 확인만 하고 넘어가면 안 되므로, **Task 1~3은 커밋을 Task 3 끝으로 미룬다** (아래 Step 5 참고). 단 BeautyTaxonomyTest 자체는 Task 3 완료 후 그린이어야 한다.

- [ ] **Step 4: (컴파일 확인만)**

Run: `./gradlew :analytics:compileJava || true`
Expected: `AnthropicVisionAnalyzer`에서 컴파일 에러 — Task 3에서 해소. Task 1은 커밋하지 않고 진행.

### Task 2: V30 마이그레이션 + BeautyTaxonomyLoader + 시드 계약 테스트

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V30__caption_classification.sql`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/BeautyTaxonomyLoader.java`
- Create: `analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomySeedTest.java`

- [ ] **Step 1: V30 마이그레이션 작성** (V30번대 예약 — §4-5. 착수 시 `docker exec crawler-postgres-1 psql -U crawler -d analysis -tc "SELECT version FROM flyway_schema_history"`로 V30 미사용 재확인)

```sql
-- 캡션 분류 태스크 (2026-07-14 스펙): 분류 어휘 DB화 + 제품명 산출 + CHECK 이관.
-- 어휘는 celfit-front 배포본 필터와 1:1 (분석 층이 확정, was는 verbatim 전달 — ARCHITECTURE §4-4).
-- 어휘 수정은 이 테이블 행 수정(후속 마이그레이션)으로 — 프론트 필터 어휘와 함께 갱신할 것.
CREATE TABLE beauty_taxonomy (
    main_value text NOT NULL,  -- 대분류 영문 slug (main_category 어휘)
    main_label text NOT NULL,
    mid_label  text NOT NULL,
    sub_label  text NOT NULL,
    main_order int  NOT NULL,
    mid_order  int  NOT NULL,
    sub_order  int  NOT NULL,
    PRIMARY KEY (main_value, mid_label, sub_label)
);

CREATE TABLE beauty_distributors (
    name text PRIMARY KEY,
    sort int NOT NULL
);

INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label, main_order, mid_order, sub_order) VALUES
  ('skincare','스킨케어','스킨/토너','스킨',1,1,1),
  ('skincare','스킨케어','스킨/토너','토너',1,1,2),
  ('skincare','스킨케어','에센스/세럼/앰플','에센스',1,2,1),
  ('skincare','스킨케어','에센스/세럼/앰플','세럼',1,2,2),
  ('skincare','스킨케어','에센스/세럼/앰플','앰플',1,2,3),
  ('skincare','스킨케어','크림','크림',1,3,1),
  ('skincare','스킨케어','크림','아이크림',1,3,2),
  ('skincare','스킨케어','로션','로션',1,4,1),
  ('skincare','스킨케어','로션','올인원',1,4,2),
  ('skincare','스킨케어','미스트/오일','미스트',1,5,1),
  ('skincare','스킨케어','미스트/오일','페이스오일',1,5,2),
  ('suncare','선케어','선크림','선크림',2,1,1),
  ('suncare','선케어','선스틱','선스틱',2,2,1),
  ('suncare','선케어','선쿠션','선쿠션',2,3,1),
  ('suncare','선케어','선스프레이/선패치','선스프레이',2,4,1),
  ('suncare','선케어','선스프레이/선패치','선패치',2,4,2),
  ('suncare','선케어','태닝/애프터선','태닝',2,5,1),
  ('suncare','선케어','태닝/애프터선','애프터선',2,5,2),
  ('makeup','메이크업','립메이크업','립틴트',3,1,1),
  ('makeup','메이크업','립메이크업','립스틱',3,1,2),
  ('makeup','메이크업','립메이크업','립라이너',3,1,3),
  ('makeup','메이크업','립메이크업','립케어',3,1,4),
  ('makeup','메이크업','립메이크업','컬러립밤',3,1,5),
  ('makeup','메이크업','립메이크업','립글로스',3,1,6),
  ('makeup','메이크업','베이스메이크업','쿠션',3,2,1),
  ('makeup','메이크업','베이스메이크업','파운데이션',3,2,2),
  ('makeup','메이크업','베이스메이크업','블러셔',3,2,3),
  ('makeup','메이크업','베이스메이크업','파우더',3,2,4),
  ('makeup','메이크업','베이스메이크업','팩트',3,2,5),
  ('makeup','메이크업','베이스메이크업','컨실러',3,2,6),
  ('makeup','메이크업','베이스메이크업','프라이머',3,2,7),
  ('makeup','메이크업','베이스메이크업','쉐딩',3,2,8),
  ('makeup','메이크업','베이스메이크업','하이라이터',3,2,9),
  ('makeup','메이크업','베이스메이크업','메이크업 픽서',3,2,10),
  ('makeup','메이크업','아이메이크업','아이라이너',3,3,1),
  ('makeup','메이크업','아이메이크업','마스카라',3,3,2),
  ('makeup','메이크업','아이메이크업','아이브로우',3,3,3),
  ('makeup','메이크업','아이메이크업','아이섀도우',3,3,4),
  ('makeup','메이크업','아이메이크업','아이래쉬 케어',3,3,5),
  ('cleansing','클렌징','클렌징폼/젤','클렌징폼',4,1,1),
  ('cleansing','클렌징','클렌징폼/젤','클렌징젤',4,1,2),
  ('cleansing','클렌징','클렌징폼/젤','팩클렌저',4,1,3),
  ('cleansing','클렌징','클렌징폼/젤','클렌징 비누',4,1,4),
  ('cleansing','클렌징','오일/밤','클렌징오일',4,2,1),
  ('cleansing','클렌징','오일/밤','클렌징밤',4,2,2),
  ('cleansing','클렌징','워터/밀크','클렌징워터',4,3,1),
  ('cleansing','클렌징','워터/밀크','클렌징밀크',4,3,2),
  ('cleansing','클렌징','워터/밀크','클렌징크림',4,3,3),
  ('cleansing','클렌징','필링&스크럽','스크럽',4,4,1),
  ('cleansing','클렌징','필링&스크럽','필링',4,4,2),
  ('cleansing','클렌징','필링&스크럽','파우더워시',4,4,3),
  ('cleansing','클렌징','티슈/패드','클렌징티슈',4,5,1),
  ('cleansing','클렌징','티슈/패드','클렌징패드',4,5,2),
  ('cleansing','클렌징','립&아이리무버','립&아이리무버',4,6,1),
  ('haircare','헤어케어','샴푸/스케일러','샴푸',5,1,1),
  ('haircare','헤어케어','트리트먼트/팩','린스',5,2,1),
  ('haircare','헤어케어','트리트먼트/팩','컨디셔너',5,2,2),
  ('haircare','헤어케어','트리트먼트/팩','헤어 트리트먼트',5,2,3),
  ('haircare','헤어케어','트리트먼트/팩','헤어팩',5,2,4),
  ('haircare','헤어케어','트리트먼트/팩','노워시 트리트먼트',5,2,5),
  ('haircare','헤어케어','두피에센스','두피토닉',5,3,1),
  ('haircare','헤어케어','두피에센스','두피앰플',5,3,2),
  ('haircare','헤어케어','헤어에센스','헤어세럼',5,4,1),
  ('haircare','헤어케어','헤어에센스','헤어오일',5,4,2),
  ('fragrance','향수/디퓨저','향수','향수',6,1,1),
  ('fragrance','향수/디퓨저','향수','헤어퍼퓸',6,1,2),
  ('fragrance','향수/디퓨저','홈프래그런스','디퓨저',6,2,1),
  ('fragrance','향수/디퓨저','홈프래그런스','캔들',6,2,2),
  ('fragrance','향수/디퓨저','홈프래그런스','인센스',6,2,3),
  ('fragrance','향수/디퓨저','홈프래그런스','룸스프레이',6,2,4),
  ('fragrance','향수/디퓨저','홈프래그런스','탈취제',6,2,5),
  ('fragrance','향수/디퓨저','홈프래그런스','차량용방향제',6,2,6);

INSERT INTO beauty_distributors (name, sort) VALUES ('올리브영', 1), ('다이소', 2);

-- 캡션 5종 중 "제품명" 산출 — [{name, brand}] (자유 텍스트, 어휘 없음).
ALTER TABLE content_analyses ADD COLUMN detected_products jsonb;

-- 어휘가 DB 데이터가 되면 CHECK는 어휘 수정 때마다 마이그레이션을 강제해 '수정 용이' 목적과 상충 —
-- 어휘 방어는 Java sanitize(같은 원천을 읽음)가 담당한다. 쓰는 쪽은 analytics뿐이라 안전.
ALTER TABLE content_analyses DROP CONSTRAINT content_analyses_main_category_check;
```

- [ ] **Step 2: 로더 작성** — `BeautyTaxonomyLoader.java`

```java
package com.celfit.analytics.llm;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * beauty_taxonomy·beauty_distributors(analysis DB, V30 시드)에서 {@link BeautyTaxonomy}
 * 스냅샷을 조립한다. 배치 프로세스 수명 동안 어휘는 불변 — 첫 로드 후 메모이즈
 * (어휘 수정은 다음 실행부터 반영, 프롬프트와 sanitize가 항상 같은 스냅샷을 본다).
 */
public final class BeautyTaxonomyLoader {

	private final JdbcTemplate analysis;
	private volatile BeautyTaxonomy cached;

	public BeautyTaxonomyLoader(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public BeautyTaxonomy get() {
		BeautyTaxonomy t = cached;
		if (t == null) {
			t = load();
			cached = t;
		}
		return t;
	}

	private BeautyTaxonomy load() {
		List<BeautyTaxonomy.Entry> entries = analysis.query("""
				SELECT main_value, main_label, mid_label, sub_label
				FROM beauty_taxonomy ORDER BY main_order, mid_order, sub_order""",
				(rs, i) -> new BeautyTaxonomy.Entry(
						rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
		List<String> distributors = analysis.queryForList(
				"SELECT name FROM beauty_distributors ORDER BY sort", String.class);
		if (entries.isEmpty() || distributors.isEmpty()) {
			throw new IllegalStateException("분류 어휘 테이블이 비어 있음 — V30 시드 확인");
		}
		return new BeautyTaxonomy(entries, distributors);
	}
}
```

- [ ] **Step 3: 시드 계약 테스트 작성** — `BeautyTaxonomySeedTest.java` (기존 BeautyTaxonomyTest의 프론트 계약 검증을 계승)

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.testsupport.TestDb;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V30 시드 ↔ celfit-front 배포본(2026-07-14) 필터 어휘 계약 검증.
 * was는 verbatim 매칭만 하므로(§4-4) 이 시드가 곧 목록 API 필터의 어휘다.
 */
@Testcontainers
class BeautyTaxonomySeedTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static BeautyTaxonomy taxonomy;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		TestDb.resetAndMigrate(new JdbcTemplate(ds), ds);
		taxonomy = new BeautyTaxonomyLoader(ds).get();
	}

	@Test
	void 대분류_slug는_프론트_배포본_6종이다() {
		assertEquals(Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance"),
				taxonomy.mainCategories());
	}

	@Test
	void 중분류와_소분류_라벨을_모두_포함하는_집합을_제공한다() {
		Set<String> labels = taxonomy.allMidAndSubLabels();

		assertTrue(labels.contains("립메이크업")); // 중분류
		assertTrue(labels.contains("립틴트"));     // 소분류
		assertTrue(labels.contains("홈프래그런스"));
		assertTrue(labels.contains("차량용방향제"));
	}

	@Test
	void 소분류_라벨_집합은_중분류를_포함하지_않는다() {
		Set<String> subs = taxonomy.allSubLabels();

		assertTrue(subs.contains("립틴트"));
		assertTrue(subs.contains("아이래쉬 케어"));
		assertFalse(subs.contains("립메이크업"));
	}

	@Test
	void 소분류는_72행이다() {
		// 시드 행 누락·중복을 총량으로 방어 (프론트 배포본 소분류 수)
		assertEquals(72, taxonomy.allSubLabels().stream()
				.mapToInt(s -> 1).sum() <= 72 ? countEntries() : -1);
	}

	private static int countEntries() {
		return 72;
	}

	@Test
	void 유통사_어휘는_프론트_필터값_고정이다() {
		assertEquals(Set.of("올리브영", "다이소"), taxonomy.distributors());
		assertEquals("올리브영|다이소", taxonomy.distributorsPrompt());
	}

	@Test
	void 프롬프트_분류표는_slug와_라벨_계층을_담는다() {
		String table = taxonomy.promptTable();

		assertTrue(table.contains("skincare(스킨케어)"));
		assertTrue(table.contains("fragrance(향수/디퓨저)"));
		assertTrue(table.contains("립메이크업"));
		assertTrue(table.contains("립틴트"));
	}
}
```

※ `소분류는_72행이다` 테스트는 위 형태가 어색하다 — 구현 시 `allSubLabels()`는 Set이라 중복 소분류 라벨(스킨/토너의 '스킨' 등)이 접히므로, 행 수 검증은 로더가 아니라 SQL로 직접:
`assertEquals(72L, new JdbcTemplate(ds).queryForObject("SELECT count(*) FROM beauty_taxonomy", Long.class));`
로 작성할 것 (필드로 ds 보관). 위 코드 블록의 해당 테스트는 이 SQL 버전으로 대체한다.

- [ ] **Step 4: 실행 — 아직 컴파일 에러 (Task 3까지 미룸)**

Run: `./gradlew :analytics:compileJava || true`
Expected: AnthropicVisionAnalyzer의 정적 참조 에러만 남음.

### Task 3: ContentAttributePort 전환 (캡션 주·썸네일 보조)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/ContentAttributes.java` (VlmResult 대체 + detectedProducts)
- Create: `analytics/src/main/java/com/celfit/analytics/llm/ContentAttributePort.java` (VisionPort 대체)
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java` (AnthropicVisionAnalyzer 대체)
- Delete: `VisionPort.java`, `VlmResult.java`, `AnthropicVisionAnalyzer.java`
- Rename+Modify: `AnthropicVisionAnalyzerTest.java` → `AnthropicContentAttributeAnalyzerTest.java`

- [ ] **Step 1: record와 포트 작성**

`ContentAttributes.java`:

```java
package com.celfit.analytics.llm;

import java.util.List;

/**
 * 콘텐츠 속성 분석 산출물 — 캡션 5종(광고 구분·카테고리·브랜드·제품·유통사) + 속성.
 * content_analyses의 NULL 허용 컬럼에 대응 (vlm_attributes 등 컬럼명은 서빙 계약이라 유지).
 * 분류 어휘는 {@link BeautyTaxonomy}(analysis DB 시드) — 어댑터 sanitize가 어휘 밖 값을 걸러낸다.
 * detectedProducts는 자유 텍스트(어휘 없음) — sanitize 대상 아님.
 */
public record ContentAttributes(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Product> detectedProducts,
		List<Attribute> vlmAttributes, String mainCategory, List<String> subCategories,
		List<String> detectedDistributors, String adType) {

	public record Brand(String name, String evidence) {
	}

	public record Product(String name, String brand) {
	}

	public record Attribute(String label, String value) {
	}
}
```

`ContentAttributePort.java`:

```java
package com.celfit.analytics.llm;

/** 콘텐츠 속성 분석 포트 — 캡션 주, 썸네일 보조. */
public interface ContentAttributePort {

	/** @param thumbnailUrl null이면 캡션만으로 분석한다 (썸네일 만료/게이트 off). */
	ContentAttributes analyze(String caption, String thumbnailUrl);
}
```

- [ ] **Step 2: 어댑터 작성** — `AnthropicContentAttributeAnalyzer.java` (AnthropicVisionAnalyzer 기반, 변경점: 생성자에 로더, INSTRUCTIONS를 taxonomy 기반 인스턴스 조립으로, 이미지 블록 조건부, sanitize에 taxonomy 파라미터, detectedProducts 프롬프트 추가)

```java
package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.celfit.analytics.config.AnalyticsSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 콘텐츠 속성 분석 Anthropic 구현 — 캡션 주, 썸네일은 살아있을 때만 보조 입력 (2026-07-14 캡션 분류 스펙).
 * 분류 어휘는 {@link BeautyTaxonomyLoader}가 주는 스냅샷을 프롬프트와 sanitize가 공유한다.
 *
 * <p>이미지는 직접 내려받아 base64로 넣는다 — URL 입력은 Anthropic이 인스타 CDN을
 * robots.txt 사유로 전면 거부(400)해 불가 (F-2 실측 2026-07-14).
 */
public final class AnthropicContentAttributeAnalyzer implements ContentAttributePort {

	private static final Logger log = LoggerFactory.getLogger(AnthropicContentAttributeAnalyzer.class);

	private static final Set<String> SIGNAL_LEVELS = Set.of("high", "mid", "low");
	private static final Set<String> AD_TYPES = Set.of("organic", "sponsored");

	private final AnthropicClient client;
	private final AnalyticsSettings settings;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public AnthropicContentAttributeAnalyzer(AnthropicClient client, AnalyticsSettings settings,
			BeautyTaxonomyLoader taxonomyLoader) {
		this.client = client;
		this.settings = settings;
		this.taxonomyLoader = taxonomyLoader;
	}

	static String instructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 뷰티 콘텐츠 분석가다. 캡션(과 썸네일이 주어지면 썸네일)을 보고 다음을 추출하라.
				확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라. 한국어로.

				- detectedBrands: 캡션·화면에서 확인되는 브랜드 {name, evidence(근거)} —
				  브랜드를 특정할 수 없는 제품은 목록에서 제외하라 ("미상"/"불명확" 같은 표기 금지)
				- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
				- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
				- mainCategory: 아래 분류표의 대분류 영문 값 중 하나
				- subCategories: 이 콘텐츠에 해당하는 중분류·소분류 라벨 전부 — 분류표의 표기 그대로
				  (예: 립틴트 콘텐츠면 ["립메이크업","립틴트"])
				- detectedProductCategories: 확인되는 제품들의 소분류 라벨 — 분류표의 표기 그대로
				- detectedProducts: 확인되는 제품명 {name(상품명), brand(그 제품의 브랜드, 미상이면 null)}
				- detectedDistributors: 확인되는 유통 채널 — %s 만, 그 외 상호는 제외
				- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
				  콘텐츠 유형 / 무드 / 편집 스타일 순 (썸네일 없이 판단 불가한 항목은 제외)
				- adType: organic|sponsored (캡션 표기+화면 종합 판정)

				[분류표 — 대분류(한글): 중분류[소분류, …]]
				%s""".formatted(taxonomy.distributorsPrompt(), taxonomy.promptTable());
	}

	@Override
	public ContentAttributes analyze(String caption, String thumbnailUrl) {
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		List<ContentBlockParam> blocks = new ArrayList<>();
		if (thumbnailUrl != null) {
			blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
					.source(download(thumbnailUrl))
					.build()));
		}
		blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
				.text("캡션: " + (caption == null ? "(없음)" : caption)).build()));
		StructuredMessageCreateParams<ContentAttributes> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(instructions(taxonomy))
				.outputConfig(ContentAttributes.class)
				.addUserMessageOfBlockParams(blocks)
				.addUserMessage(thumbnailUrl != null ? "위 썸네일과 캡션을 분석하라." : "위 캡션을 분석하라.")
				.build();
		StructuredMessage<ContentAttributes> message = client.messages().create(params);
		// 건당 비용 실측 근거 (F-2 스파이크·운영 모니터링)
		log.info("attribute usage: input={} output={} (thumbnail={})",
				message.usage().inputTokens(), message.usage().outputTokens(), thumbnailUrl != null);
		return sanitize(message.content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("속성 분석 응답에 본문 없음"))
				.text(), taxonomy);
	}

	/** 썸네일을 직접 내려받아 base64 소스로. 실패는 예외 → 콘텐츠 실패(일시 장애는 다음 실행 재대상). */
	private Base64ImageSource download(String thumbnailUrl) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(thumbnailUrl))
					.timeout(Duration.ofSeconds(15)).build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("썸네일 다운로드 실패 HTTP " + res.statusCode());
			}
			return Base64ImageSource.builder()
					.mediaType(mediaTypeOf(res.headers().firstValue("content-type").orElse(null)))
					.data(Base64.getEncoder().encodeToString(res.body()))
					.build();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("썸네일 다운로드 실패: " + thumbnailUrl, e);
		}
	}

	/** Content-Type → SDK MediaType. 인스타 CDN은 jpeg/webp 혼재 — 미상은 jpeg로 간주. */
	static Base64ImageSource.MediaType mediaTypeOf(String contentType) {
		if (contentType == null) {
			return Base64ImageSource.MediaType.IMAGE_JPEG;
		}
		return switch (contentType.split(";")[0].trim().toLowerCase()) {
			case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
			case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
			case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
			default -> Base64ImageSource.MediaType.IMAGE_JPEG;
		};
	}

	/**
	 * LLM이 어휘 밖 값을 지어낸 경우 제거한다 — 스칼라는 null로, 배열은 어휘 밖 원소만 걸러낸다
	 * (was가 verbatim 매칭하므로 어휘 밖 라벨은 필터에 안 잡히는 노이즈).
	 * detectedBrands·detectedProducts는 자유 텍스트라 통과. Synthesis의 등급 방어와 대칭.
	 */
	static ContentAttributes sanitize(ContentAttributes raw, BeautyTaxonomy taxonomy) {
		return new ContentAttributes(
				raw.detectedBrands(),
				keepIfIn(raw.sponsoredSignalLevel(), SIGNAL_LEVELS),
				raw.sponsoredSignalReasons(),
				raw.adDisclosure(),
				filterToVocabulary(raw.detectedProductCategories(), taxonomy.allSubLabels()),
				raw.detectedProducts(),
				raw.vlmAttributes(),
				keepIfIn(raw.mainCategory(), taxonomy.mainCategories()),
				filterToVocabulary(raw.subCategories(), taxonomy.allMidAndSubLabels()),
				filterToVocabulary(raw.detectedDistributors(), taxonomy.distributors()),
				keepIfIn(raw.adType(), AD_TYPES));
	}

	private static String keepIfIn(String value, Set<String> vocabulary) {
		return value != null && vocabulary.contains(value) ? value : null;
	}

	private static List<String> filterToVocabulary(List<String> values, Set<String> vocabulary) {
		return values == null ? null : values.stream().filter(vocabulary::contains).toList();
	}
}
```

- [ ] **Step 3: 구 파일 삭제 + 테스트 이관**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/caption
git rm analytics/src/main/java/com/celfit/analytics/llm/VisionPort.java \
       analytics/src/main/java/com/celfit/analytics/llm/VlmResult.java \
       analytics/src/main/java/com/celfit/analytics/llm/AnthropicVisionAnalyzer.java
git mv analytics/src/test/java/com/celfit/analytics/llm/AnthropicVisionAnalyzerTest.java \
       analytics/src/test/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzerTest.java
```

`AnthropicContentAttributeAnalyzerTest.java` 전체 재작성:

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 속성 분석 어휘 방어(sanitize)·프롬프트 조립 단위 테스트 — 클라이언트 불필요, 정적 메서드만 검증. */
class AnthropicContentAttributeAnalyzerTest {

	// sanitize가 어휘 스냅샷을 쓰는지 검증하기 위한 소형 픽스처 (실 시드 검증은 BeautyTaxonomySeedTest)
	private static final BeautyTaxonomy TAXONOMY = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "스킨"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트"),
			new BeautyTaxonomy.Entry("cleansing", "클렌징", "클렌징폼/젤", "클렌징폼"),
			new BeautyTaxonomy.Entry("haircare", "헤어케어", "샴푸/스케일러", "샴푸")),
			List.of("올리브영", "다이소"));

	private ContentAttributes attrsWith(String level, String adType) {
		return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), level,
				List.of("협찬 표기"), "표기 있음", List.of("클렌징폼"),
				List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
				List.of(new ContentAttributes.Attribute("무드", "화사함")), "skincare",
				List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), adType);
	}

	@Test
	void 어휘_밖_값은_null로_교체된다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				attrsWith("medium", "ad"), TAXONOMY);

		assertNull(sanitized.sponsoredSignalLevel());
		assertNull(sanitized.adType());
		// 나머지 필드는 유지된다
		assertEquals("브랜드A", sanitized.detectedBrands().get(0).name());
		assertEquals("skincare", sanitized.mainCategory());
		assertEquals("표기 있음", sanitized.adDisclosure());
	}

	@Test
	void 유효_어휘는_그대로_유지되고_제품명은_통과한다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				attrsWith("high", "sponsored"), TAXONOMY);

		assertEquals("high", sanitized.sponsoredSignalLevel());
		assertEquals("sponsored", sanitized.adType());
		assertEquals("skincare", sanitized.mainCategory());
		assertEquals(List.of("클렌징폼/젤", "클렌징폼"), sanitized.subCategories());
		assertEquals(List.of("클렌징폼"), sanitized.detectedProductCategories());
		assertEquals(List.of("올리브영"), sanitized.detectedDistributors());
		// 제품명은 자유 텍스트 — 어휘 필터 대상이 아니다
		assertEquals("딥클렌징폼", sanitized.detectedProducts().get(0).name());
		assertEquals("브랜드A", sanitized.detectedProducts().get(0).brand());
	}

	@Test
	void 분류표_밖_대분류는_null로_교체된다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of(), List.of(),
						List.of(), "hair", List.of("샴푸/스케일러"), List.of(), "organic"), TAXONOMY);

		assertNull(sanitized.mainCategory());
		assertEquals(List.of("샴푸/스케일러"), sanitized.subCategories()); // 라벨 자체는 어휘 안 — 유지
	}

	@Test
	void 분류표_밖_라벨은_배열에서_제거된다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음",
						List.of("립틴트", "틴트제품", "립메이크업"), List.of(), List.of(), "makeup",
						List.of("립메이크업", "립틴트", "입술화장"), List.of("올리브영", "쿠팡"), "organic"),
				TAXONOMY);

		assertEquals(List.of("립메이크업", "립틴트"), sanitized.subCategories());
		// product 카테고리 어휘는 소분류만 — 중분류(립메이크업)·비어휘(틴트제품) 제거
		assertEquals(List.of("립틴트"), sanitized.detectedProductCategories());
		assertEquals(List.of("올리브영"), sanitized.detectedDistributors());
	}

	@Test
	void 컨텐트타입은_SDK_미디어타입으로_매핑되고_미상은_jpeg다() {
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_WEBP,
				AnthropicContentAttributeAnalyzer.mediaTypeOf("image/webp"));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG,
				AnthropicContentAttributeAnalyzer.mediaTypeOf("image/jpeg; charset=binary"));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG,
				AnthropicContentAttributeAnalyzer.mediaTypeOf(null));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_PNG,
				AnthropicContentAttributeAnalyzer.mediaTypeOf("IMAGE/PNG"));
	}

	@Test
	void null_배열은_null로_유지된다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(null, null, null, null, null, null, null, null, null, null, null),
				TAXONOMY);

		assertNull(sanitized.subCategories());
		assertNull(sanitized.detectedProductCategories());
		assertNull(sanitized.detectedDistributors());
		assertNull(sanitized.detectedProducts());
		assertNull(sanitized.mainCategory());
	}

	@Test
	void 프롬프트는_어휘_스냅샷의_분류표와_유통사를_담는다() {
		String instructions = AnthropicContentAttributeAnalyzer.instructions(TAXONOMY);

		assertEquals(true, instructions.contains("올리브영|다이소"));
		assertEquals(true, instructions.contains("skincare(스킨케어)"));
		assertEquals(true, instructions.contains("detectedProducts"));
	}
}
```

이 시점에 `LlmConfig`·`ContentAnalysisJob`·`AnalyzeRunner`·`VlmSpikeRunner`·`ContentAnalysisJobTest`가 아직 구 타입을 참조해 컴파일 실패 — Task 4에서 해소. 커밋은 Task 4 끝에서 한 번에.

### Task 4: ContentAnalysisJob·배선 전환 + detected_products 저장

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AnalyzeRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/spike/VlmSpikeRunner.java`
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`

- [ ] **Step 1: ContentAnalysisJob 전환**

필드·생성자: `VisionPort vision`/`vlmEnabled` → `ContentAttributePort attributes`(필수)/`boolean thumbnailEnabled`. 클래스 주석에 "속성 분석은 캡션 주·썸네일 보조(2026-07-14 캡션 분류 스펙)" 추가.

```java
public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
		SynthesisPort synthesis, ContentAttributePort attributes, AnalyticsSettings settings,
		boolean thumbnailEnabled, Predicate<String> thumbnailAlive) {
	this.raw = rawJdbcTemplate;
	this.analysis = new JdbcTemplate(analysisDataSource);
	this.synthesis = synthesis;
	this.attributes = attributes;
	this.settings = settings;
	this.thumbnailEnabled = thumbnailEnabled;
	this.thumbnailAlive = thumbnailAlive;
}
```

`analyzeOne`의 VLM 블록(기존 `String thumbnailUrl = ...` ~ `VlmResult vlm = ...`)을 다음으로 교체:

```java
// 캡션 주·썸네일 보조: 썸네일은 게이트 on + 프리체크 생존일 때만 첨부, 만료·off여도 캡션으로 5종 산출.
// 캡션도 썸네일도 없으면 속성 분석에 넣을 입력이 없다 — 속성 컬럼만 NULL로 저장 (행 자체는 생성).
String caption = (String) content.get("caption");
String thumbnailUrl = (String) content.get("thumbnail_url");
boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
	log.info("썸네일 만료/접근 불가 — 캡션만으로 속성 분석: {}", shortCode);
}
boolean hasCaption = caption != null && !caption.isBlank();
ContentAttributes attrs = hasCaption || attachThumbnail
		? attributes.analyze(caption, attachThumbnail ? thumbnailUrl : null)
		: null;
```

Synthesis 호출의 `(String) content.get("caption")`은 지역변수 `caption` 재사용으로 정리. INSERT 문은 `detected_product_categories` 뒤에 `detected_products` 추가(placeholder `?::jsonb`), 값 바인딩은 `vlm` → `attrs` 전면 치환:

```java
analysis.update("""
		INSERT INTO content_analyses (short_code, model,
		  ai_content_summary, contents_pattern, ai_comment_insight,
		  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
		  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
		  category_top_percentile, category_avg_views, category_sample_size,
		  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
		  detected_product_categories, detected_products, vlm_attributes, main_category, sub_categories,
		  detected_distributors, ad_type,
		  comment_authenticity_grade, comment_authenticity_note)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
		        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""",
		shortCode, model,
		s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
		b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
		b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
		b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
		toJson(attrs == null ? null : attrs.detectedBrands()),
		attrs == null ? null : attrs.sponsoredSignalLevel(),
		toJson(attrs == null ? null : attrs.sponsoredSignalReasons()),
		attrs == null ? null : attrs.adDisclosure(),
		toJson(attrs == null ? null : attrs.detectedProductCategories()),
		toJson(attrs == null ? null : attrs.detectedProducts()),
		toJson(attrs == null ? null : attrs.vlmAttributes()),
		attrs == null ? null : attrs.mainCategory(),
		toJson(attrs == null ? null : attrs.subCategories()),
		toJson(attrs == null ? null : attrs.detectedDistributors()),
		attrs == null ? null : attrs.adType(),
		s.commentAuthenticityGrade(), s.commentAuthenticityNote());
```

- [ ] **Step 2: 배선 갱신**

`AnalyzeRunner.contentAnalysisJob`: `ObjectProvider<VisionPort> vision` → `ContentAttributePort attributes`(필수 빈), `vlmEnabled` 파라미터 주석에 "썸네일 첨부 게이트(기본 off — 캡션 기반 5종은 항상 산출)" 명시. 프로퍼티명 `analytics.vlm-enabled`는 유지.

```java
@Bean
public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
		@Qualifier("analysisDataSource") DataSource analysisDataSource,
		SynthesisPort synthesis, ContentAttributePort attributes, AnalyticsSettings settings,
		@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled) {
	return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, synthesis,
			attributes, settings, thumbnailEnabled, headPrecheck());
}
```

`LlmConfig`: `visionPort` 빈 교체 + 로더 빈 신설 (import `javax.sql.DataSource`, `org.springframework.beans.factory.annotation.Qualifier`):

```java
@Bean
public BeautyTaxonomyLoader beautyTaxonomyLoader(
		@Qualifier("analysisDataSource") DataSource analysisDataSource) {
	return new BeautyTaxonomyLoader(analysisDataSource);
}

@Bean
public ContentAttributePort contentAttributePort(AnthropicClient client, AnalyticsSettings settings,
		BeautyTaxonomyLoader taxonomyLoader) {
	return new AnthropicContentAttributeAnalyzer(client, settings, taxonomyLoader);
}
```

`VlmSpikeRunner`: 빈 메서드에 `@Qualifier("analysisDataSource") DataSource analysisDataSource` 파라미터 추가, 어댑터 생성·호출 갱신 (인자 순서: 캡션 먼저):

```java
var analyzer = new AnthropicContentAttributeAnalyzer(LlmClientFactory.fromEnv(),
		new AnalyticsSettings(rawJdbcTemplate), new BeautyTaxonomyLoader(analysisDataSource));
...
ContentAttributes r = analyzer.analyze(extraCaption, targets.get(0).thumbnailUrl());
...
ContentAttributes r = analyzer.analyze(t.caption(), t.thumbnailUrl());
```

(`VlmResult` import → `ContentAttributes`로 교체.)

- [ ] **Step 3: ContentAnalysisJobTest 갱신**

- `visionCalls` → `attributeCalls`(List<String> — 전달된 thumbnailUrl, null 가능). fake 교체:

```java
/** fake ContentAttributePort: 전달된 thumbnailUrl 기록(null=캡션만) + 고정 응답. */
ContentAttributePort fakeAttributePort() {
	return (caption, thumbnailUrl) -> {
		attributeCalls.add(thumbnailUrl);
		return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), "high",
				List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징폼"),
				List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
				List.of(new ContentAttributes.Attribute("무드", "화사함")), "cleansing",
				List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), "sponsored");
	};
}
```

- `rewireJob` 시그니처의 `vlmEnabled` → `thumbnailEnabled` (fakeVisionPort → fakeAttributePort), `setUp`의 job 생성도 동일.
- contents 시드에 `posted_at` 추가 (숙성 가드 대비 — Task 5에서 필수가 되지만 여기서 미리):

```java
db.update("""
		INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, posted_at, views, likes, comments) VALUES
		  ('post_a', 'acct1', 'https://img/a.jpg', '캡션A', 'reels', now() - interval '10 days', 11000, 520, 52),
		  ('post_b', 'acct1', 'https://img/b.jpg', '캡션B', 'feed', now() - interval '10 days', NULL, 2000, 100),
		  ('post_c', 'acct1', 'https://img/c.jpg', '캡션C', 'reels', now() - interval '10 days', 7000, 300, 30)""");
```

- 테스트 교체·추가 (기존 ①②③⑤·최신순·기준선 제외·빈 텍스트 케이스는 유지, 아래만 변경):

`vlm_off이면_...` 교체 → **썸네일 게이트 off여도 캡션 기반 속성이 저장된다**:

```java
@Test
void 썸네일_게이트_off여도_캡션_기반_속성이_저장된다() {
	int processed = job.run(); // 기본 게이트: thumbnailEnabled=false

	assertEquals(2, processed);
	// 속성 콜은 항상 수행되되 썸네일은 미첨부(null)
	assertEquals(java.util.Arrays.asList(null, null), attributeCalls);
	assertEquals("cleansing", db.queryForObject(
			"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
	assertEquals("sponsored", db.queryForObject(
			"SELECT ad_type FROM content_analyses WHERE short_code = 'post_a'", String.class));
}
```

`vlm_on이면_...` 교체 → **썸네일 게이트 on이면 생존 썸네일이 첨부되고 제품명까지 저장된다**:

```java
@Test
void 썸네일_게이트_on이면_생존_썸네일이_첨부되고_제품명까지_저장된다() {
	rewireJob(fakeSynthesisPort(), true);

	int processed = job.run();

	assertEquals(2, processed);
	// 수집 최신순: post_b(06-07) → post_a(06-05), 둘 다 썸네일 생존 → URL 첨부
	assertEquals(List.of("https://img/b.jpg", "https://img/a.jpg"), attributeCalls);
	assertEquals("cleansing", db.queryForObject(
			"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
	assertEquals("[\"클렌징폼/젤\", \"클렌징폼\"]", db.queryForObject(
			"SELECT sub_categories::text FROM content_analyses WHERE short_code = 'post_a'", String.class));
	assertEquals("[\"올리브영\"]", db.queryForObject(
			"SELECT detected_distributors::text FROM content_analyses WHERE short_code = 'post_a'", String.class));
	assertEquals("[{\"name\": \"딥클렌징폼\", \"brand\": \"브랜드A\"}]", db.queryForObject(
			"SELECT detected_products::text FROM content_analyses WHERE short_code = 'post_a'", String.class));
}
```

`썸네일_프리체크_실패면_...` 교체 → **프리체크 실패면 캡션만으로 속성을 산출한다**:

```java
@Test
void 썸네일_프리체크_실패면_캡션만으로_속성을_산출한다() {
	// 만료된 서명 URL 재현: post_a 썸네일만 죽어 있다 — 이제 VLM NULL이 아니라 캡션 단독 분석으로 간다
	rewireJob(fakeSynthesisPort(), true, url -> url.equals("https://img/b.jpg"));

	int processed = job.run();

	assertEquals(2, processed);
	// post_b는 썸네일 첨부, post_a는 캡션만(null)
	assertEquals(java.util.Arrays.asList("https://img/b.jpg", null), attributeCalls);
	assertEquals("cleansing", db.queryForObject(
			"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
}
```

신규 → **캡션도 썸네일도 없으면 속성 콜을 생략하고 컬럼은 NULL이다**:

```java
@Test
void 캡션도_썸네일도_없으면_속성_콜을_생략하고_컬럼은_NULL이다() {
	// 입력이 아무것도 없는 콘텐츠 — 속성 분석을 부를 수 없다 (행 자체는 생성돼 재대상 잠식 방지)
	db.update("DELETE FROM contents");
	db.update("""
			INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, posted_at, views, likes, comments)
			VALUES ('post_a', 'acct1', NULL, NULL, 'reels', now() - interval '10 days', 11000, 520, 52)""");

	int processed = job.run();

	assertEquals(1, processed);
	assertTrue(attributeCalls.isEmpty());
	assertNull(db.queryForObject(
			"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
	assertNull(db.queryForObject(
			"SELECT detected_products FROM content_analyses WHERE short_code = 'post_a'", String.class));
	// 종합 텍스트는 정상 저장
	assertEquals("요약: post_a", db.queryForObject(
			"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
}
```

※ 이 테스트는 post_a만 남기므로 comment_classifications의 post_a 분류가 남아 있어도 조건(분류 완료) 충족 — content_comments를 지우지 않으면 post_a 댓글 2건+분류 2건으로 조건 만족. `DELETE FROM contents` 후 재삽입 방식이 시드와 충돌하지 않는지 구현 시 확인(안 맞으면 신규 short_code `post_x`로 별도 삽입 + batch-limit 조정이 아니라 posted_at 시드만 다르게).

- [ ] **Step 4: 전체 컴파일·테스트**

Run: `./gradlew :analytics:test`
Expected: 전부 PASS (BeautyTaxonomyTest·SeedTest·AnalyzerTest·JobTest 포함)

- [ ] **Step 5: 커밋 (Task 1~4 일괄 — 중간 상태는 컴파일 불가라 분리 불가)**

```bash
git add -A
git commit -m "feat(analytics): 캡션 주·썸네일 보조 속성 분석 전환 + 분류 어휘 DB화(V30)

- VisionPort→ContentAttributePort: 캡션 항상 입력, 썸네일은 게이트 on+생존 시만 첨부 —
  썸네일 만료 시에도 캡션으로 5종(광고·카테고리·브랜드·제품·유통사) 산출
- BeautyTaxonomy를 beauty_taxonomy·beauty_distributors(analysis DB, V30 시드) 스냅샷+로더로 전환 —
  프롬프트와 sanitize가 같은 원천 유지, 시드는 celfit-front 배포본 verbatim
- detected_products jsonb 신설([{name, brand}]), main_category CHECK는 Java sanitize로 이관
- analytics.vlm-enabled는 썸네일 첨부 게이트로 의미 변경 (기본 off — 캡션 5종은 항상 산출)"
```

### Task 5: B3 숙성 가드 — 게시 후 3일 경과 조건

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (eligible 쿼리)
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — ContentAnalysisJobTest에 추가

```java
@Test
void 게시_후_3일_미경과_콘텐츠는_대상에서_제외된다() {
	// B3 숙성 가드(07-14 확정): content_analyses는 불변·재분석 없음 — 게시 직후 분석되면
	// 덜 여문 지표·댓글로 영구 고정된다. 기본 3일 경과 후에만 분석.
	db.update("UPDATE contents SET posted_at = now() - interval '1 day' WHERE short_code = 'post_a'");

	int processed = job.run();

	assertEquals(1, processed); // post_b만 (post_a는 숙성 미달, post_c는 미분류)
	assertEquals(0L, db.queryForObject(
			"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	assertFalse(synthesisCalls.stream().anyMatch(c -> c.shortCode().equals("post_a")));
}

@Test
void 숙성_일수는_app_setting으로_조정된다() {
	db.update("UPDATE contents SET posted_at = now() - interval '1 day' WHERE short_code = 'post_a'");
	db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-maturity-days', '0')");

	int processed = job.run();

	assertEquals(2, processed); // 가드 0일이면 post_a도 대상
}

@Test
void posted_at이_NULL인_콘텐츠는_대상에서_제외된다() {
	// 게시일을 모르면 숙성 여부를 판정할 수 없다 — 실데이터엔 NULL 없음(140/140 확인)
	db.update("UPDATE contents SET posted_at = NULL WHERE short_code = 'post_a'");

	int processed = job.run();

	assertEquals(1, processed); // post_b만
	assertEquals(0L, db.queryForObject(
			"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.analyze.ContentAnalysisJobTest'`
Expected: 신규 3케이스 FAIL (가드 없어 post_a가 분석됨)

- [ ] **Step 3: 구현**

`AnalyticsSettings`에 추가:

```java
/** 분석 대상 최소 숙성 일수 — 게시 직후 분석·영구 고정 방지 (B3 숙성 가드, 07-14 확정). */
public static final String KEY_ANALYZE_MATURITY_DAYS = "analytics.analyze-maturity-days";
static final int DEFAULT_ANALYZE_MATURITY_DAYS = 3;

public int analyzeMaturityDays() {
	return read(KEY_ANALYZE_MATURITY_DAYS).map(Integer::parseInt)
			.orElse(DEFAULT_ANALYZE_MATURITY_DAYS);
}
```

`ContentAnalysisJob.run()`의 eligible 쿼리에 조건 추가 (posted_at NULL은 부등식에서 자연 제외):

```java
Set<String> eligible = new HashSet<>(analysis.queryForList("""
		SELECT c.short_code FROM contents c
		WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
		  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
		       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
		  AND c.posted_at <= now() - make_interval(days => ?)""",
		String.class, settings.analyzeMaturityDays()));
```

클래스 상단 주석의 "대상:" 줄에 "AND 게시 후 N일 경과(기본 3 — B3 숙성 가드)" 추가.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.analyze.ContentAnalysisJobTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(analytics): B3 숙성 가드 — 게시 후 3일 경과 콘텐츠만 분석

content_analyses는 불변·재분석 없음이라 게시 직후 분석되면 덜 여문 지표·댓글로
영구 고정된다(매일 크롤 구조). app_setting analytics.analyze-maturity-days(기본 3)."
```

### Task 6: 전체 테스트 + (옵션) 실 실행 스모크

- [ ] **Step 1: 전체 테스트**

Run: `cd /Users/woomin/Project/hypenow-backend/.worktrees/caption && ./gradlew test`
Expected: crawler/analytics/was/contract-analysis 전부 PASS

- [ ] **Step 2 (옵션 — 비용 발생, 사용자 승인 시만): 실 실행 스모크**

```bash
docker start crawler-postgres-1
export ANTHROPIC_AUTH_TOKEN=...   # 또는 ANTHROPIC_API_KEY (.env는 자동 로드 안 됨)
docker exec crawler-postgres-1 psql -U crawler -d crawler -c \
  "INSERT INTO app_setting(key,value) VALUES ('analytics.analyze-batch-limit','2') ON CONFLICT (key) DO UPDATE SET value='2'"
./gradlew :analytics:bootRun --args='--analytics.analyze-on-startup=true'
# 확인: docker exec crawler-postgres-1 psql -U crawler -d analysis -c \
#   "SELECT short_code, main_category, ad_type, detected_products FROM content_analyses ORDER BY analyzed_at DESC LIMIT 2"
# 끝나면 batch-limit 원복
```

주의: V30이 공유 dev DB에 적용된다 — 실행 전 `flyway_schema_history`에 V30이 없는지 확인(병렬 세션 충돌 방지). 미실행 시에도 PR에는 영향 없음(테스트는 Testcontainers).

### Task 7: ARCHITECTURE.md 갱신 + PR

**Files:**
- Modify: `ARCHITECTURE.md` (§5 행 추가·§7 1줄·§8 두 행 제거 — 자기 행만 최소 수정, 병렬 PR 충돌 주의)

- [ ] **Step 1: §5 작업 트랙 표에 행 추가** (E 행 다음, G 행 앞)

```markdown
| B4 | 캡션 분류·숙성 가드 | 속성 분석을 캡션 주·썸네일 보조로 전환(5종: 광고·카테고리·브랜드·제품·유통사, `detected_products` 신설) + 어휘 DB화(V30 `beauty_taxonomy`) + 분석 대상 "게시 후 3일" 가드 | B3 | ✅ |
```

- [ ] **Step 2: §7 결정 기록 맨 위에 1줄 추가**

```markdown
| 2026-07-14 | **캡션 분류 + B3 숙성 가드** — 캡션 5종(광고 구분·카테고리·브랜드·제품·유통사)을 별도 잡이 아닌 기존 속성 콜 전환(캡션 항상·썸네일 생존 시만 첨부, 병합은 모델 안에서)으로. 어휘는 analysis DB `beauty_taxonomy`·`beauty_distributors`(V30 시드)로 이동 — BeautyTaxonomy는 로더 스냅샷, 프롬프트·sanitize 동일 원천 유지, main_category CHECK는 sanitize로 이관. `detected_products jsonb`([{name,brand}]) 신설. 분석 대상에 게시 후 3일 숙성 가드(`analytics.analyze-maturity-days`) | [specs/2026-07-14-caption-classification-design.md](docs/superpowers/specs/2026-07-14-caption-classification-design.md) |
```

- [ ] **Step 3: §8 미결에서 "B3 숙성 가드"·"캡션 분류 태스크" 두 행 제거**

- [ ] **Step 4: 계획 문서 상태 갱신** — 본 계획 문서 첫머리 상태를 `✅ 구현/실행/반영됨`으로 바꾸고 `docs/superpowers/plans/archive/`로 이동

- [ ] **Step 5: 커밋 + PR**

```bash
git add -A
git commit -m "docs: ARCHITECTURE §5 B4 행·§7 결정 기록 추가, §8 캡션 분류·숙성 가드 두 행 해소"
git push -u origin feat/caption-classification
gh pr create --base develop --title "feat(analytics): 캡션 분류(5종 산출·어휘 DB화) + B3 숙성 가드" --body "..."
```

PR 본문: 스펙 링크, 5종↔컬럼 매핑 표, V30 내용(공유 DB 주의), vlm-enabled 의미 변경, 숙성 가드 요약, 윈도우 24개 전환 세션과의 순서(이 PR 먼저).

---

## Self-Review 결과

- 스펙 §2(콜 전환)=Task 3·4, §3(컬럼 매핑·detected_products)=Task 2·4, §4(어휘 DB화)=Task 1·2, §5(V30·CHECK 삭제)=Task 2, §6(숙성 가드)=Task 5, §7(검증)=각 태스크 테스트+Task 6, §8(문서)=Task 7 — 커버 완료.
- Task 1~4가 컴파일 단위로 얽혀 커밋이 Task 4 끝 일괄인 점은 rename 전환의 특성상 수용 (테스트는 태스크별로 먼저 작성).
- SeedTest의 72행 검증은 본문 ※처럼 SQL count 버전으로 작성할 것.
