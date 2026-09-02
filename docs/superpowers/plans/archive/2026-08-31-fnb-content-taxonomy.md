# F&B 콘텐츠 분류 (LLM 계층) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** F&B 콘텐츠 61,619건이 LLM 분석 파이프라인을 타게 하되, 서빙(랭킹·발굴)에는 한 건도 노출되지 않게 한다.

**Architecture:** 축(뷰티/F&B/홈리빙)을 어휘 테이블 `beauty_taxonomy.axis`에서 유도한다. `content_analyses`에 새 컬럼을 만들지 않고 `is_beauty`를 파생값으로 계속 채워 was 소비처를 무접촉으로 둔다. 분석 후보 뷰(04)를 서빙 뷰(02)에서 떼어내고, 분석 재료도 미러 테이블(`contents`)이 아니라 후보 뷰에서 읽어 미러 의존을 끊는다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(crawler/analytics/was/monitoring) · Postgres 16 · Flyway · Testcontainers · Vertex AI 배치

## Global Constraints

- 주석·로그·커밋 메시지는 **한국어**. 커밋 prefix는 `feat(모듈):` / `docs:` / `fix(모듈):`
- 신규 Flyway 마이그레이션은 **UTC 타임스탬프 채번** — `date -u +%Y%m%d%H%M%S`로 그때그때 딴다. 기존 `V1`~`V49` 파일은 절대 rename 금지
- 스키마 변경은 **expand only** — 이 계획의 마이그레이션에 `DROP`·`RENAME`·`SET NOT NULL`(신규 컬럼 DEFAULT 동반 제외)은 없다
- 테스트는 **모듈 단위**: `./gradlew :analytics:test`, `./gradlew :was:test`. 전체 `./gradlew test`는 PR 직전 1회
- **이 머신은 Docker Desktop이다 — `DOCKER_HOST`를 설정하지 말 것.** colima는 설치돼 있지 않다(CLAUDE.md의 colima 안내는 다른 머신 기준)
- SQL 하니스는 실데이터 컨테이너가 필요하다: `analytics/test/run.sh` (전체) / `analytics/test/run.sh test/04_analysis_candidates.test.sql` (단일). 컨테이너 이름이 다르면 `PG_CONTAINER`로 오버라이드
- **서빙 무변경이 이 작업의 하드 제약이다.** `analytics/views/02_serving.sql`·`01_recent_window.sql`·`20_landing_stats.sql`의 모수 조건(`i.beauty AND NOT i.beauty_company`)과 `MirrorConfig`는 건드리지 않는다
- 설계 정본: [docs/superpowers/specs/2026-08-31-fnb-content-taxonomy-design.md](../specs/2026-08-31-fnb-content-taxonomy-design.md)

## File Structure

**analytics — 어휘·축**
- `src/main/resources/db/migration/analysis/V<ts>__taxonomy_axis.sql` (신규) — `beauty_taxonomy.axis`·`beauty_distributors.axis` 컬럼
- `src/main/resources/db/migration/analysis/V<ts>__fnb_taxonomy.sql` (신규) — F&B 어휘 24행 + 유통사 11행
- `src/main/java/com/celfit/analytics/llm/BeautyTaxonomy.java` — 축 인지 스냅샷(Entry에 axis, Distributor record, `axisOf`)
- `src/main/java/com/celfit/analytics/llm/BeautyTaxonomyLoader.java` — axis 컬럼 로드

**analytics — 분류 로직**
- `src/main/java/com/celfit/analytics/llm/ContentAttributes.java` — `isRelevant`(LLM 응답) + `isBeauty`(파생) 분리
- `src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java` — sanitize 축 일반화 + 프롬프트
- `src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java` — 응답 스키마·프롬프트
- `src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` — self-heal 일반화 · 재료 소스 전환 · 배치 청크

**analytics — 뷰**
- `views/04_analysis_candidates.sql` — `v_analysis_source` 신설 + 후보 뷰를 그 위로 이설
- `seed/dummy.sql` — F&B 단독 계정 픽스처
- `test/04_analysis_candidates.test.sql` — F&B 포함·뷰티 회귀 단언

**was — 오염 차단 1줄**
- `src/main/java/com/celfit/was/v1/content/V1ContentRepository.java` — `findDistributorOptions`에 축 필터

---

### Task 1: 어휘 축 컬럼 + F&B 시드

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V<ts1>__taxonomy_axis.sql`
- Create: `analytics/src/main/resources/db/migration/analysis/V<ts2>__fnb_taxonomy.sql`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomySeedTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `beauty_taxonomy(main_value, main_label, mid_label, sub_label, main_order, mid_order, sub_order, axis)` · `beauty_distributors(name, sort, slug, axis)`. axis 값 어휘는 `'beauty'` / `'fnb'`

- [ ] **Step 1: 실패하는 시드 테스트를 쓴다**

`BeautyTaxonomySeedTest.java`에 아래 두 테스트를 추가한다(기존 테스트는 건드리지 않는다).

```java
	@Test
	void F&B_대분류_6개가_fnb축으로_시드된다() {
		List<String> mains = db.queryForList(
				"SELECT DISTINCT main_value FROM beauty_taxonomy WHERE axis = 'fnb' ORDER BY 1",
				String.class);
		assertEquals(List.of("alcohol", "beverage", "convenience", "health-food", "recipe", "snack"),
				mains);
		assertEquals(24, db.queryForObject(
				"SELECT count(*) FROM beauty_taxonomy WHERE axis = 'fnb'", Integer.class));
		// 기존 뷰티 어휘는 전부 beauty 축으로 남는다 (DEFAULT 백필)
		assertEquals(0, db.queryForObject(
				"SELECT count(*) FROM beauty_taxonomy WHERE axis NOT IN ('beauty','fnb')", Integer.class));
	}

	@Test
	void 소분류_라벨은_축_전체에서_유일하다() {
		// sub_categories는 정확 일치 매칭이라 라벨이 겹치면 필터가 오탐한다.
		// 요리/레시피의 '음료'를 '음료 레시피'로 분리한 이유 (설계 §3).
		List<String> dup = db.queryForList("""
				SELECT sub_label FROM beauty_taxonomy
				GROUP BY sub_label HAVING count(DISTINCT main_value) > 1""", String.class);
		assertEquals(List.of(), dup, "소분류 라벨이 여러 대분류에 걸침 — 필터 오탐");
	}

	@Test
	void F&B_유통사가_fnb축으로_시드된다() {
		assertEquals(11, db.queryForObject(
				"SELECT count(*) FROM beauty_distributors WHERE axis = 'fnb'", Integer.class));
		assertEquals(2, db.queryForObject(
				"SELECT count(*) FROM beauty_distributors WHERE axis = 'beauty'", Integer.class));
	}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.BeautyTaxonomySeedTest"
```

Expected: FAIL — `column "axis" does not exist`

- [ ] **Step 3: 축 컬럼 마이그레이션을 만든다**

```bash
date -u +%Y%m%d%H%M%S
```

그 값을 `<ts1>`으로 써서 `V<ts1>__taxonomy_axis.sql`:

```sql
-- 분류 어휘의 축(카테고리 계열) 컬럼 (2026-08-31). 설계:
-- docs/superpowers/specs/2026-08-31-fnb-content-taxonomy-design.md §2·§4
--
-- 축을 content_analyses의 컬럼이 아니라 어휘 테이블에 두는 이유: 카테고리는 계속 는다
-- (다음은 홈/리빙). 축마다 컬럼·LLM 필드·마이그레이션이 하나씩 늘어나는 구조를 피하고,
-- main_category가 있으면 그 대분류의 axis가 곧 콘텐츠의 축이 되게 한다.
-- 새 카테고리 추가 = 이 테이블에 INSERT 한 번.
--
-- 테이블 이름(beauty_*)은 유지한다 — 운영 DB에 있고 소비처가 여럿이라(was 랭킹 중분류 확장·
-- V35 카테고리 믹스·발굴 matview) rename 이득이 위험을 넘지 않는다(에스테틱 추가 때와 동일 판단).
--
-- DEFAULT 'beauty'로 기존 행이 전부 백필된다 — expand only, 롤링 배포 무해.
ALTER TABLE beauty_taxonomy ADD COLUMN axis text NOT NULL DEFAULT 'beauty';
ALTER TABLE beauty_distributors ADD COLUMN axis text NOT NULL DEFAULT 'beauty';

COMMENT ON COLUMN beauty_taxonomy.axis IS
  '카테고리 축 — beauty|fnb(|home_living). content_analyses.is_beauty의 파생 근거.';
COMMENT ON COLUMN beauty_distributors.axis IS
  '카테고리 축 — 그 축의 콘텐츠에만 유효한 유통사. 뷰티 게시물의 편의점 태그는 sanitize가 드랍한다.';
```

- [ ] **Step 4: F&B 어휘 시드 마이그레이션을 만든다**

```bash
date -u +%Y%m%d%H%M%S
```

그 값을 `<ts2>`로 써서 `V<ts2>__fnb_taxonomy.sql`:

```sql
-- F&B 어휘 시드 (2026-08-31). 출처: 피처링 콘텐츠 랭킹 필터 트리(app.featuring.co, 08-31 채취).
-- 경쟁 서비스가 시장에서 검증한 분류라 자체 발명보다 낫고, 대분류 slug는 피처링 URL 파라미터
-- 값을 그대로 쓴다(main_category=beverage 등).
--
-- ⚠️ 요리/레시피의 소분류는 피처링 원본이 '음료'인데 중분류 '음료'와 문자열이 같다.
-- sub_categories는 정확 일치 매칭이라 중분류 필터가 이 소분류를 오탐하므로 '음료 레시피'로
-- 분리했다(에스테틱의 '필링 시술' ↔ 클렌징 '필링' 선례와 동일).
--
-- 순수 additive INSERT — expand-contract 안전, 롤링 배포 무해.
INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label, main_order, mid_order, sub_order, axis) VALUES
  ('beverage','음료','음료','탄산',8,1,1,'fnb'),
  ('beverage','음료','음료','주스',8,1,2,'fnb'),
  ('beverage','음료','음료','기능성음료',8,1,3,'fnb'),
  ('beverage','음료','음료','커피',8,1,4,'fnb'),
  ('beverage','음료','음료','단백질음료',8,1,5,'fnb'),
  ('alcohol','주류','주류','소주',9,1,1,'fnb'),
  ('alcohol','주류','주류','맥주',9,1,2,'fnb'),
  ('convenience','가공/간편식','가공/간편식','즉석식품',10,1,1,'fnb'),
  ('convenience','가공/간편식','가공/간편식','밀키트',10,1,2,'fnb'),
  ('convenience','가공/간편식','가공/간편식','면류',10,1,3,'fnb'),
  ('convenience','가공/간편식','가공/간편식','이유식',10,1,4,'fnb'),
  ('snack','간식류','간식류','과자',11,1,1,'fnb'),
  ('snack','간식류','간식류','초콜릿',11,1,2,'fnb'),
  ('snack','간식류','간식류','아이스크림',11,1,3,'fnb'),
  ('snack','간식류','간식류','젤리',11,1,4,'fnb'),
  ('health-food','건강식품','건강식품','영양제',12,1,1,'fnb'),
  ('health-food','건강식품','건강식품','비타민',12,1,2,'fnb'),
  ('health-food','건강식품','건강식품','유산균',12,1,3,'fnb'),
  ('health-food','건강식품','건강식품','프로틴',12,1,4,'fnb'),
  ('health-food','건강식품','건강식품','다이어트',12,1,5,'fnb'),
  ('health-food','건강식품','건강식품','이너뷰티',12,1,6,'fnb'),
  ('recipe','요리/레시피','요리/레시피','요리',13,1,1,'fnb'),
  ('recipe','요리/레시피','요리/레시피','디저트/베이킹',13,1,2,'fnb'),
  ('recipe','요리/레시피','요리/레시피','음료 레시피',13,1,3,'fnb');

-- F&B 유통 채널. 뷰티(올리브영·다이소)와 축이 다르다 — 프롬프트는 전체를 축 라벨과 함께 싣고
-- sanitize가 축 정합성을 검사한다(설계 §4).
INSERT INTO beauty_distributors (name, sort, slug, axis) VALUES
  ('GS25', 10, 'gs25', 'fnb'),
  ('CU', 11, 'cu', 'fnb'),
  ('세븐일레븐', 12, 'seven-eleven', 'fnb'),
  ('이마트24', 13, 'emart24', 'fnb'),
  ('이마트', 14, 'emart', 'fnb'),
  ('홈플러스', 15, 'homeplus', 'fnb'),
  ('롯데마트', 16, 'lottemart', 'fnb'),
  ('코스트코', 17, 'costco', 'fnb'),
  ('쿠팡', 18, 'coupang', 'fnb'),
  ('마켓컬리', 19, 'kurly', 'fnb'),
  ('네이버쇼핑', 20, 'naver-shopping', 'fnb');
```

- [ ] **Step 5: 테스트 통과를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.BeautyTaxonomySeedTest"
```

Expected: PASS (신규 3개 + 기존 테스트 전부)

- [ ] **Step 6: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomySeedTest.java
git commit -m "feat(analytics): 분류 어휘에 축 컬럼 추가 + F&B 어휘 24행·유통사 11행 시드"
```

---

### Task 2: BeautyTaxonomy 축 인지

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/BeautyTaxonomy.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/BeautyTaxonomyLoader.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomyTest.java`

**Interfaces:**
- Consumes: Task 1의 `axis` 컬럼
- Produces:
  - `BeautyTaxonomy.Entry(String mainValue, String mainLabel, String midLabel, String subLabel, String axis)`
  - `BeautyTaxonomy.Distributor(String name, String axis)`
  - `BeautyTaxonomy(List<Entry> entries, List<Distributor> distributors)` — 생성자 시그니처 변경
  - `String axisOf(String mainValue)` — 어휘 밖이면 `null`
  - `String distributorAxisOf(String name)` — 어휘 밖이면 `null`
  - `Set<String> distributors()` — 기존 유지(전체 이름, 축 무관)
  - `String distributorsPrompt()` — 축 라벨 포함 렌더링
  - `String promptTable()` — 축 라벨 포함 렌더링

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BeautyTaxonomyTest.java`에 추가한다. 기존 테스트가 4-인자 `Entry`를 쓰고 있으면 5-인자로 함께 고친다(축 `"beauty"`).

```java
	@Test
	void axisOf는_대분류의_축을_돌려준다() {
		BeautyTaxonomy t = new BeautyTaxonomy(
				List.of(new BeautyTaxonomy.Entry("skincare", "스킨케어", "크림", "크림", "beauty"),
						new BeautyTaxonomy.Entry("beverage", "음료", "음료", "탄산", "fnb")),
				List.of(new BeautyTaxonomy.Distributor("올리브영", "beauty"),
						new BeautyTaxonomy.Distributor("GS25", "fnb")));
		assertEquals("beauty", t.axisOf("skincare"));
		assertEquals("fnb", t.axisOf("beverage"));
		assertNull(t.axisOf("nonexistent"));
	}

	@Test
	void distributorAxisOf는_유통사의_축을_돌려준다() {
		BeautyTaxonomy t = new BeautyTaxonomy(
				List.of(new BeautyTaxonomy.Entry("skincare", "스킨케어", "크림", "크림", "beauty")),
				List.of(new BeautyTaxonomy.Distributor("올리브영", "beauty"),
						new BeautyTaxonomy.Distributor("GS25", "fnb")));
		assertEquals("beauty", t.distributorAxisOf("올리브영"));
		assertEquals("fnb", t.distributorAxisOf("GS25"));
		assertNull(t.distributorAxisOf("없는곳"));
	}

	@Test
	void 프롬프트_렌더링은_축을_밝힌다() {
		BeautyTaxonomy t = new BeautyTaxonomy(
				List.of(new BeautyTaxonomy.Entry("skincare", "스킨케어", "크림", "크림", "beauty"),
						new BeautyTaxonomy.Entry("beverage", "음료", "음료", "탄산", "fnb")),
				List.of(new BeautyTaxonomy.Distributor("올리브영", "beauty"),
						new BeautyTaxonomy.Distributor("GS25", "fnb")));
		assertTrue(t.promptTable().contains("[beauty] skincare(스킨케어)"));
		assertTrue(t.promptTable().contains("[fnb] beverage(음료)"));
		assertTrue(t.distributorsPrompt().contains("올리브영(beauty)"));
		assertTrue(t.distributorsPrompt().contains("GS25(fnb)"));
	}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.BeautyTaxonomyTest"
```

Expected: FAIL — 컴파일 오류(`Entry` 인자 수 불일치, `Distributor` 없음)

- [ ] **Step 3: BeautyTaxonomy를 축 인지로 바꾼다**

`Entry`에 `axis`를 더하고, `distributors`를 `Distributor` 목록으로 바꾼다. 두 개의 축 조회 맵을 필드로 만든다.

```java
	/** 소분류당 1행. 목록 순서 = 시드 정렬 순서 (프롬프트 분류표 렌더링 순서). */
	public record Entry(String mainValue, String mainLabel, String midLabel, String subLabel,
			String axis) {
	}

	/** 유통사 1행 — 축이 다르면 그 축의 콘텐츠에서만 유효하다. */
	public record Distributor(String name, String axis) {
	}
```

생성자에서 축 맵을 만든다(기존 필드·로직은 그대로 두고 아래를 더한다).

```java
	private final Map<String, String> mainAxis;         // 대분류 slug → 축
	private final Map<String, String> distributorAxis;  // 유통사 이름 → 축

	public BeautyTaxonomy(List<Entry> entries, List<Distributor> distributors) {
		this.entries = List.copyOf(entries);
		this.distributors = distributors.stream().map(Distributor::name).toList();
		// … 기존 mainCategories·midAndSubLabels·subLabels·labelToMain·mainOrder 조립 그대로 …
		this.distributorSet = Set.copyOf(this.distributors);
		Map<String, String> axes = new LinkedHashMap<>();
		for (Entry e : entries) {
			axes.putIfAbsent(e.mainValue(), e.axis());
		}
		this.mainAxis = Map.copyOf(axes);
		this.distributorAxis = distributors.stream()
				.collect(Collectors.toUnmodifiableMap(Distributor::name, Distributor::axis));
	}

	/** 대분류가 속한 축 — 어휘 밖이면 null. sanitize의 is_beauty 파생 근거. */
	public String axisOf(String mainValue) {
		return mainValue == null ? null : mainAxis.get(mainValue);
	}

	/** 유통사가 속한 축 — 어휘 밖이면 null. */
	public String distributorAxisOf(String name) {
		return name == null ? null : distributorAxis.get(name);
	}
```

프롬프트 렌더링 두 개에 축을 싣는다.

```java
	/** 프롬프트에 넣는 유통사 나열 — "올리브영(beauty)|다이소(beauty)|GS25(fnb)|…". */
	public String distributorsPrompt() {
		return distributorAxis.entrySet().stream()
				.map(e -> "%s(%s)".formatted(e.getKey(), e.getValue()))
				.collect(Collectors.joining("|"));
	}
```

`promptTable()`의 대분류 줄 포맷만 축 접두로 바꾼다 — 나머지 조립 로직은 그대로다.

```java
		return tree.entrySet().stream()
				.map(main -> "[%s] %s(%s): %s".formatted(
						mainAxis.get(main.getKey()), main.getKey(), mainLabels.get(main.getKey()),
						main.getValue().entrySet().stream()
								.map(mid -> "%s[%s]".formatted(mid.getKey(), String.join(", ", mid.getValue())))
								.collect(Collectors.joining(" · "))))
				.collect(Collectors.joining("\n"));
```

`distributorsPrompt()`가 `distributorAxis`(LinkedHashMap 아님) 순서에 의존하지 않도록, `distributorAxis`는 `LinkedHashMap`으로 조립한 뒤 `Collections.unmodifiableMap`으로 감싼다. 순서가 곧 프롬프트 순서이므로 시드 `sort` 순서를 보존해야 한다.

- [ ] **Step 4: Loader가 axis를 읽게 한다**

```java
	private BeautyTaxonomy load() {
		List<BeautyTaxonomy.Entry> entries = analysis.query("""
				SELECT main_value, main_label, mid_label, sub_label, axis
				FROM beauty_taxonomy ORDER BY main_order, mid_order, sub_order""",
				(rs, i) -> new BeautyTaxonomy.Entry(
						rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5)));
		List<BeautyTaxonomy.Distributor> distributors = analysis.query(
				"SELECT name, axis FROM beauty_distributors ORDER BY sort",
				(rs, i) -> new BeautyTaxonomy.Distributor(rs.getString(1), rs.getString(2)));
		if (entries.isEmpty() || distributors.isEmpty()) {
			throw new IllegalStateException("분류 어휘 테이블이 비어 있음 — V30 시드 확인");
		}
		return new BeautyTaxonomy(entries, distributors);
	}
```

- [ ] **Step 5: 테스트 통과를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.*"
```

Expected: PASS. 컴파일 오류가 나는 다른 테스트(4-인자 `Entry` 사용처)는 5-인자로 고친다 — 축은 전부 `"beauty"`.

- [ ] **Step 6: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm analytics/src/test/java/com/celfit/analytics/llm
git commit -m "feat(analytics): 분류 어휘 스냅샷 축 인지 — axisOf·distributorAxisOf·프롬프트 축 표기"
```

---

### Task 3: sanitize 축 일반화

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/ContentAttributes.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java:143-186`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java:409`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzerTest.java`

**Interfaces:**
- Consumes: `BeautyTaxonomy.axisOf` · `distributorAxisOf` (Task 2)
- Produces:
  - `ContentAttributes(..., String adType, Boolean isRelevant, Boolean isBeauty)` — 마지막 두 컴포넌트. `isRelevant`는 LLM 응답(분류표 어느 대분류에든 해당하는가), `isBeauty`는 sanitize가 채우는 **파생값**(축이 beauty일 때만 true)
  - `ContentAttributes.asUnclassified()` — `asNonBeauty()` 대체. `isRelevant=false, isBeauty=false`인 사본
  - `AnthropicContentAttributeAnalyzer.sanitize(ContentAttributes raw, BeautyTaxonomy taxonomy)` — 시그니처 불변

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`AnthropicContentAttributeAnalyzerTest.java`에 추가한다.

```java
	private static BeautyTaxonomy taxonomy() {
		return new BeautyTaxonomy(
				List.of(new BeautyTaxonomy.Entry("skincare", "스킨케어", "크림", "크림", "beauty"),
						new BeautyTaxonomy.Entry("beverage", "음료", "음료", "탄산", "fnb")),
				List.of(new BeautyTaxonomy.Distributor("올리브영", "beauty"),
						new BeautyTaxonomy.Distributor("GS25", "fnb")));
	}

	private static ContentAttributes raw(String main, List<String> subs, List<String> dists,
			Boolean relevant) {
		return new ContentAttributes(null, null, null, null, null, null, null,
				main, subs, dists, "organic", relevant, null);
	}

	@Test
	void 뷰티_콘텐츠는_기존과_동일하게_is_beauty_true다() {
		ContentAttributes out = AnthropicContentAttributeAnalyzer.sanitize(
				raw("skincare", List.of("크림"), List.of("올리브영"), true), taxonomy());
		assertEquals("skincare", out.mainCategory());
		assertTrue(out.isBeauty());
		assertEquals(List.of("올리브영"), out.detectedDistributors());
	}

	@Test
	void F&B_콘텐츠는_대분류가_살고_is_beauty는_false다() {
		ContentAttributes out = AnthropicContentAttributeAnalyzer.sanitize(
				raw("beverage", List.of("탄산"), List.of("GS25"), true), taxonomy());
		assertEquals("beverage", out.mainCategory());
		assertFalse(out.isBeauty());
		assertEquals(List.of("GS25"), out.detectedDistributors());
	}

	@Test
	void 축이_다른_유통사는_드랍된다() {
		ContentAttributes out = AnthropicContentAttributeAnalyzer.sanitize(
				raw("skincare", List.of("크림"), List.of("GS25", "올리브영"), true), taxonomy());
		assertEquals(List.of("올리브영"), out.detectedDistributors());
	}

	@Test
	void 무관_콘텐츠는_대분류와_유통사가_모두_비워진다() {
		ContentAttributes out = AnthropicContentAttributeAnalyzer.sanitize(
				raw("skincare", List.of("크림"), List.of("올리브영"), false), taxonomy());
		assertNull(out.mainCategory());
		assertFalse(out.isBeauty());
		assertEquals(List.of(), out.detectedDistributors());
	}

	@Test
	void 어휘밖_대분류는_서브라벨로_역유도된다() {
		ContentAttributes out = AnthropicContentAttributeAnalyzer.sanitize(
				raw("unknown", List.of("탄산"), List.of(), true), taxonomy());
		assertEquals("beverage", out.mainCategory());
		assertFalse(out.isBeauty());
	}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.AnthropicContentAttributeAnalyzerTest"
```

Expected: FAIL — 컴파일 오류(`ContentAttributes` 인자 수 불일치)

- [ ] **Step 3: ContentAttributes를 두 축으로 나눈다**

```java
/**
 * 콘텐츠 속성 — LLM 1콜 산출물. 분류 어휘는 {@link BeautyTaxonomy}(analysis DB 시드)이고
 * 어댑터 sanitize가 어휘 밖 값을 걸러낸다.
 *
 * <p>{@code isRelevant}는 LLM 응답(분류표의 어느 대분류에든 해당하는 콘텐츠인가),
 * {@code isBeauty}는 sanitize가 채우는 <b>파생값</b>이다 — 확정된 대분류의 축이 beauty일 때만 true.
 * 파생으로 둔 이유: 카테고리가 늘어도(F&B·홈리빙) content_analyses에 컬럼을 더하지 않고,
 * was 소비처가 의존하는 is_beauty의 의미를 그대로 보존하기 위해서다(설계 §2).
 * sanitize 이전 인스턴스의 isBeauty는 항상 null이다.
 */
public record ContentAttributes(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Product> detectedProducts,
		List<Attribute> vlmAttributes, String mainCategory, List<String> subCategories,
		List<String> detectedDistributors, String adType, Boolean isRelevant, Boolean isBeauty) {

	/**
	 * 미분류 종결 — 분류표 어느 대분류에도 확정되지 않은 사본. 대분류를 끝내 못 정한 콘텐츠를
	 * 이 상태로 저장해 재분석 루프를 끊는다(07-21 무한 재대상 종결과 같은 처방, 축 중립화).
	 */
	public ContentAttributes asUnclassified() {
		return new ContentAttributes(detectedBrands, sponsoredSignalLevel, sponsoredSignalReasons,
				adDisclosure, detectedProductCategories, detectedProducts, vlmAttributes,
				mainCategory, subCategories, detectedDistributors, adType, false, false);
	}
}
```

기존 `asNonBeauty()`는 삭제한다(호출처는 Step 5에서 고친다).

- [ ] **Step 4: sanitize를 축 기반으로 바꾼다**

`AnthropicContentAttributeAnalyzer.sanitize()` 본문에서 `if (!Boolean.TRUE.equals(raw.isBeauty())) { main = null; }` 블록을 아래로 교체하고, 유통사 필터를 축 인지로 바꾼다.

```java
		// 분류표 어느 대분류에도 해당하지 않는 콘텐츠(일상·여행 등)는 대분류를 확정하지 않는다.
		// 축이 늘어도 이 규칙은 그대로다 — "main_category 있음 ⇒ 어떤 축엔가 속함"이 생산자 불변식.
		if (!Boolean.TRUE.equals(raw.isRelevant())) {
			main = null;
		}
		String axis = taxonomy.axisOf(main);
		// is_beauty는 파생 — was 소비처(랭킹·카테고리 믹스·발굴 게이트)가 이 컬럼 하나로
		// 비뷰티를 걸러내므로, 축이 beauty일 때만 true여야 F&B가 서빙에 새지 않는다(설계 §8).
		boolean isBeauty = "beauty".equals(axis);
		// 유통사는 그 콘텐츠 축의 것만 남긴다 — 뷰티 게시물의 'GS25'는 드랍.
		// 축을 판정 못 한 콘텐츠(main=null)는 어느 축 필터에도 안 걸리는 고아 값이 되므로 비운다.
		List<String> dists = raw.detectedDistributors() == null ? null
				: raw.detectedDistributors().stream()
						.filter(d -> axis != null && axis.equals(taxonomy.distributorAxisOf(d)))
						.toList();
		return new ContentAttributes(
				raw.detectedBrands(),
				keepIfIn(raw.sponsoredSignalLevel(), SIGNAL_LEVELS),
				raw.sponsoredSignalReasons(),
				raw.adDisclosure(),
				prodCats,
				raw.detectedProducts(),
				raw.vlmAttributes(),
				main,
				subs,
				dists,
				keepIfIn(raw.adType(), AD_TYPES),
				raw.isRelevant(),
				isBeauty);
```

클래스 상단 주석(`:140` 부근 "비뷰티(isBeauty≠true)면 대분류를 확정하지 않는다(생산자 불변식)")도 같은 취지로 고친다.

- [ ] **Step 5: self-heal 종결 조건을 축 중립으로 바꾼다**

`ContentAnalysisJob.java:409`:

```java
		// 분류표 어느 대분류에도 확정되지 않았는데 isRelevant=true면, 재실행해도 결과가 같아
		// (temperature 0 결정론) 매 실행 재대상 루프가 된다 — 미분류로 종결 저장해 루프를 끊는다.
		if (attrs != null && Boolean.TRUE.equals(attrs.isRelevant()) && attrs.mainCategory() == null) {
			attrs = attrs.asUnclassified();
		}
```

주변 로그 문구에 "뷰티 콘텐츠인데 대분류 미분류"가 있으면 "분류 대상인데 대분류 미분류"로 고친다.

- [ ] **Step 6: 테스트 통과를 확인한다**

```bash
./gradlew :analytics:test
```

Expected: PASS. 컴파일 오류가 나는 다른 사용처(`ContentAnalysisJobTest`·`GeminiContentAnalyzerTest` 등)는 새 시그니처로 고친다.

- [ ] **Step 7: 커밋**

```bash
git add analytics/src
git commit -m "feat(analytics): sanitize 축 일반화 — isRelevant 응답 + is_beauty 파생 + 유통사 축 정합"
```

---

### Task 4: 프롬프트·응답 스키마 축 중립화

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java:27-57,93-130,170-204`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java:49-` (instructions)
- Test: `analytics/src/test/java/com/celfit/analytics/llm/GeminiContentAnalyzerTest.java`

**Interfaces:**
- Consumes: Task 2의 `promptTable()`·`distributorsPrompt()`, Task 3의 `ContentAttributes`
- Produces: 응답 JSON 키가 `isBeauty` → `isRelevant`로 바뀐다. `GeminiContentAnalyzer.parse(ObjectMapper, String, BeautyTaxonomy)` 시그니처는 불변

- [ ] **Step 1: 실패하는 파싱 테스트를 쓴다**

```java
	@Test
	void isRelevant_응답을_파싱해_축으로_is_beauty를_판정한다() throws Exception {
		String json = """
				{"detectedBrands":null,"sponsoredSignalLevel":"low","sponsoredSignalReasons":null,
				 "adDisclosure":"표기 없음","detectedProductCategories":["탄산"],"detectedProducts":null,
				 "vlmAttributes":null,"isRelevant":true,"mainCategory":"beverage",
				 "subCategories":["음료","탄산"],"detectedDistributors":["GS25"],"adType":"organic",
				 "aiContentSummary":"요약","contentsPattern":"패턴","aiCommentInsight":"인사이트",
				 "commentAuthenticityGrade":"normal","commentAuthenticityNote":"note"}""";
		ContentInsight out = GeminiContentAnalyzer.parse(new ObjectMapper(), json, taxonomy());
		assertEquals("beverage", out.attributes().mainCategory());
		assertFalse(out.attributes().isBeauty());
		assertEquals(List.of("GS25"), out.attributes().detectedDistributors());
	}
```

`taxonomy()` 헬퍼는 Task 3의 것과 동일하게 이 테스트 클래스에도 둔다(테스트 간 공유 금지 — 각 클래스가 자기 픽스처를 갖는다).

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.llm.GeminiContentAnalyzerTest"
```

Expected: FAIL — `isRelevant` 키를 `Output` record가 모른다

- [ ] **Step 3: Gemini 응답 스키마와 record를 고친다**

`RESPONSE_SCHEMA`에서 `"isBeauty":{"type":"boolean"}` → `"isRelevant":{"type":"boolean"}`, `required` 배열 2곳(`:53`, `:57`)의 `"isBeauty"` → `"isRelevant"`.

`Output` record의 `Boolean isBeauty` → `Boolean isRelevant`, `parse()`의 `o.isBeauty()` → `o.isRelevant()`. `ContentAttributes` 생성자 인자에 파생 `isBeauty` 자리로 `null`을 넘긴다(sanitize가 채운다).

```java
		ContentAttributes attrs = AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType(), o.isRelevant(), null), taxonomy);
```

- [ ] **Step 4: 프롬프트 문구를 축 중립으로 바꾼다 (Gemini)**

`GeminiContentAnalyzer.instructions()`에서 아래를 교체한다.

```java
				당신은 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 한 번의 분석에서
				[파트 A] 캡션 속성 추출과 [파트 B] 성과 종합을 함께 수행한다. 한국어로 답한다.

				[파트 A — 캡션 속성 추출]
				캡션(과 썸네일이 주어지면 썸네일)에서 다음을 추출하라.
				확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라.
				분류표의 어느 대분류에도 해당하지 않으면 mainCategory는 null이다.

				- isRelevant: 이 콘텐츠가 분류표의 대분류 중 하나에 해당하는가 (true/false).
				  제품·시술·루틴·리뷰·요리 등이면 true, 인플루언서가 뷰티·F&B라도 무관한
				  일상·여행·반려동물 등이면 false. mainCategory와 독립적으로 반드시 판정하라.
```

그리고 `mainCategory` 항목 설명 아래에 규칙 두 줄을 더한다.

```java
				- mainCategory: 아래 분류표의 대분류 영문 값 중 하나
				  ※ 섭취하는 제품(건강기능식품·단백질·다이어트 식품·이너뷰티 포함)은 뷰티 목적이어도
				    F&B 축으로 분류하라 — 제형이 아니라 섭취 여부가 기준이다.
```

`adType` 항목 아래에 공구 규칙을 더한다.

```java
				  ※ 공동구매(공구)는 인플루언서가 대가를 받고 판매하는 상업 콘텐츠다 —
				    sponsored로 판정하라.
```

`detectedDistributors` 항목 설명을 축 인지로 바꾼다.

```java
				- detectedDistributors: 확인되는 유통 채널 — %s 만, 그 외 상호는 제외.
				  괄호 안은 그 유통사가 속한 축이다 — mainCategory와 같은 축의 유통사만 답하라.
```

- [ ] **Step 5: 프롬프트 문구를 축 중립으로 바꾼다 (Anthropic)**

`AnthropicContentAttributeAnalyzer.instructions()`도 같은 취지로 고친다. 이 어댑터는 현재 운영에서 쓰이지 않지만(최근 30일 분석 85,071건 전부 `gemini-3.1-flash-lite`), 프롬프트가 두 벌 갈리면 프로바이더를 되돌릴 때 조용히 다르게 동작한다.

```java
				당신은 브랜드 콘텐츠 분석가다. 캡션(과 썸네일이 주어지면 썸네일)을 보고 다음을 추출하라.
				확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라. 한국어로.

				- isRelevant: 이 콘텐츠가 분류표의 대분류 중 하나에 해당하는가 (true/false).
				  제품·시술·루틴·리뷰·요리 등이면 true, 인플루언서가 뷰티·F&B라도 무관한
				  일상·여행·반려동물 등이면 false. mainCategory와 독립적으로 반드시 판정하라.
```

Gemini 쪽에 더한 3개 규칙(섭취 제품 F&B 우선 · 공구 sponsored · 유통사 축 일치)을 이 프롬프트에도 같은 문장으로 넣는다.

- [ ] **Step 6: 테스트 통과를 확인한다**

```bash
./gradlew :analytics:test
```

Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add analytics/src
git commit -m "feat(analytics): 분석 프롬프트·응답 스키마 축 중립화 — isRelevant, F&B 우선·공구 sponsored 규칙"
```

---

### Task 5: was 유통사 옵션 축 필터

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentRepository.java:38-42`
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1의 `beauty_distributors.axis`
- Produces: `findDistributorOptions()` 반환값이 뷰티 축 유통사로 한정된다

**왜 필요한가:** `findDistributorOptions()`는 어휘 테이블을 필터 없이 통째로 읽는다. Task 1이 F&B 유통사 11행을 넣는 순간, 이 한 줄이 없으면 **뷰티 랭킹 화면의 유통사 드롭다운에 편의점이 뜬다.** 서빙 확장이 아니라 오염 차단이며, 이번 범위의 유일한 was 변경이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`V1ContentRepositoryTest.java`에 추가한다. 이 테스트 클래스가 쓰는 시드에 F&B 유통사 행을 넣고 조회 결과에 안 섞이는지 본다.

```java
	@Test
	void 유통사_옵션은_뷰티축만_노출한다() {
		jdbcClient.sql("""
				INSERT INTO beauty_distributors (name, sort, slug, axis)
				VALUES ('GS25', 99, 'gs25', 'fnb') ON CONFLICT DO NOTHING""").update();
		List<Map<String, Object>> options = repository.findDistributorOptions();
		assertThat(options).extracting(o -> o.get("id")).doesNotContain("gs25");
		assertThat(options).extracting(o -> o.get("id")).contains("oliveyoung");
	}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentRepositoryTest"
```

Expected: FAIL — 옵션 목록에 `gs25`가 들어 있다

- [ ] **Step 3: 축 필터를 넣는다**

```java
	/**
	 * meta.distributors 옵션 — 뷰티 축 어휘 전체, id(슬러그) 오름차순 (스펙 6.1).
	 * 축 필터가 필요한 이유: 어휘 테이블은 F&B 유통사(편의점·마트)도 함께 담는데(2026-08-31),
	 * 필터 없이 읽으면 뷰티 랭킹 화면 드롭다운에 편의점이 노출된다. F&B 서빙을 열 때
	 * 요청 축에 따라 고르도록 바꾼다(설계 §8-1).
	 */
	public List<Map<String, Object>> findDistributorOptions() {
		return jdbcClient.sql("SELECT slug, name FROM beauty_distributors WHERE axis = 'beauty' ORDER BY slug")
				.query((rs, i) -> Map.<String, Object>of("id", rs.getString("slug"), "name", rs.getString("name")))
				.list();
	}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.content.*"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add was/src
git commit -m "fix(was): 유통사 옵션을 뷰티 축으로 한정 — F&B 어휘 유입 시 랭킹 필터 오염 차단"
```

---

### Task 6: 분석 후보 뷰를 서빙에서 분리

**Files:**
- Modify: `analytics/views/04_analysis_candidates.sql`
- Modify: `analytics/seed/dummy.sql:26-40`
- Test: `analytics/test/04_analysis_candidates.test.sql`

**Interfaces:**
- Consumes: 없음(SQL 층)
- Produces: `analytics.v_analysis_source` 뷰 — 컬럼 `short_code, account_handle, posted_at, content_type(소문자), content_id, caption, thumbnail_url, views, likes, comments, metric_captured_at, ad_marked, followers, recency_rank`. `analytics.v_analysis_candidates`의 출력 컬럼은 **불변**(short_code, content_type, account_handle, uploaded_at, caption, thumbnail_url, followers, views, likes, comments, metric_captured_at, timely, ad_marked)

**급소:** `in_window`가 참조하던 `v_recent_content`(01)도 뷰티 게이트다. 04만 고치면 F&B는 `in_window`가 항상 false가 되어 백로그 대부분이 후보에서 빠진다. 새 소스 뷰가 `recency_rank`를 자체 계산해야 한다.

- [ ] **Step 1: F&B 단독 계정 픽스처를 시드에 넣는다**

`analytics/seed/dummy.sql`의 influencer INSERT 뒤에 추가한다.

```sql
-- F&B 단독 계정(뷰티 아님) — 04 후보 뷰가 서빙 모수(뷰티)에서 분리됐는지 검증하는 픽스처.
-- 서빙 뷰(01·02·20)에는 절대 안 나타나야 한다.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990006,'dummy_fb','QUALIFIED', 7000, false, false, timestamptz '2026-06-01 00:00:00+09');
UPDATE influencer SET fnb = true, fnb_company = false, fnb_class = 'INFLUENCER'
WHERE username = 'dummy_fb';

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990120,'dummy_fb1','REELS','dummy_fb',99990006, now() - interval '4 day','PENDING', now() - interval '4 day','ENUMERATION',0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990006,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_fb1","product_type":"clips","taken_at":1780000000,"like_count":150,"comment_count":15,"play_count":4000,"caption":{"text":"오늘의 밀키트 후기"},"image_versions2":{"candidates":[{"url":"https://thumb/fb1.jpg"}]}}}]}}'::jsonb, now() - interval '1 day');
```

`raw_media_page`가 `crawl_run_id` 외래키를 요구하면 기존 `99990000` 런을 그대로 재사용한다(위 코드가 그렇게 돼 있다).

- [ ] **Step 2: 실패하는 하니스 단언을 쓴다**

`analytics/test/04_analysis_candidates.test.sql`의 `DO $$` 블록 안에 추가한다.

```sql
  -- 2026-08-31: 후보 뷰를 서빙 뷰(02)에서 분리 — F&B 단독 계정 콘텐츠가 후보에 든다.
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_fb1'),
    'F&B 단독 계정 콘텐츠가 분석 후보에 없음 — 04가 아직 서빙 모수를 상속 중';
  -- in_window는 01(뷰티 게이트)이 아니라 소스 뷰가 자체 계산한다 — 아니면 F&B는 항상 false다.
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates
                 WHERE short_code = 'dummy_fb1' AND NOT timely),
    'F&B 콘텐츠가 in_window 경로로 안 들어옴 — recency_rank 자체 계산 확인';
  -- 서빙은 불변 — F&B는 랭킹·최근창에 한 건도 없어야 한다.
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_contents WHERE account_handle = 'dummy_fb'),
    'F&B 계정이 서빙 뷰 v_contents에 노출됨 — 서빙 무변경 위반';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_recent_content WHERE owner_username = 'dummy_fb'),
    'F&B 계정이 최근창 뷰에 노출됨 — 서빙 무변경 위반';
```

- [ ] **Step 3: 실패를 확인한다**

```bash
analytics/test/run.sh test/04_analysis_candidates.test.sql
```

Expected: FAIL — `F&B 단독 계정 콘텐츠가 분석 후보에 없음`

- [ ] **Step 4: 소스 뷰를 신설하고 후보 뷰를 이설한다**

`analytics/views/04_analysis_candidates.sql`의 `CREATE OR REPLACE VIEW analytics.v_analysis_candidates` 바로 앞에 소스 뷰를 넣는다.

```sql
-- 분석 후보의 소스 (2026-08-31 신설). 서빙 뷰 v_contents(02)에서 떼어낸다.
-- 왜: 미러가 `SELECT * FROM v_contents`로 통째 복사하므로, 분석 모수를 넓히려고 02를 건드리면
-- 그 즉시 랭킹 API가 열린다. 분석 모수(뷰티 ∪ F&B)와 서빙 모수(뷰티)는 이제 서로 독립이다.
-- hype_score는 04가 쓰지 않으므로 계산하지 않는다 — v_contents보다 오히려 짧다.
-- recency_rank를 여기서 직접 매기는 이유: 구 04는 최근창 판정을 v_recent_content(01)에
-- 위임했는데 01도 뷰티 게이트라, 그대로 두면 F&B는 in_window가 영원히 false가 된다.
-- 홈/리빙을 추가할 땐 아래 모수에 OR 한 항이 는다(계정 축은 crawler 컬럼이라 어휘에서 유도 불가).
CREATE OR REPLACE VIEW analytics.v_analysis_source AS
WITH pool AS (
  SELECT c.content_id, c.short_code, c.owner_username, c.uploaded_at, c.content_type
  FROM analytics.v_base_content c
  JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
  WHERE c.origin = 'ENUMERATION'
    AND i.status = 'QUALIFIED'
    AND ( (i.beauty AND NOT i.beauty_company)
       OR (i.fnb    AND NOT i.fnb_company) )
)
SELECT
  p.content_id,
  p.short_code,
  p.owner_username AS account_handle,
  p.uploaded_at    AS posted_at,
  lower(p.content_type) AS content_type,
  d.caption,
  d.thumbnail_url,
  m.views,
  m.likes,
  m.comments_count AS comments,
  m.captured_at    AS metric_captured_at,
  m.paid_partnership AS ad_marked,
  pr.followers,
  row_number() OVER (PARTITION BY p.owner_username
                     ORDER BY p.uploaded_at DESC, p.content_id DESC) AS recency_rank
FROM pool p
JOIN analytics.v_base_detail   d USING (content_id)
JOIN analytics.v_pinned_metrics m USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = p.owner_username;
```

후보 뷰 본문을 아래로 바꾼다. 출력 컬럼·성숙 가드·최적화 배리어(`OFFSET 0`)는 그대로 승계한다.

```sql
CREATE OR REPLACE VIEW analytics.v_analysis_candidates AS
SELECT
  short_code, content_type, account_handle, uploaded_at, caption, thumbnail_url,
  followers, views, likes, comments, metric_captured_at, timely, ad_marked
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
    (v.recency_rank <= COALESCE(
       (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12)) AS in_window
  FROM analytics.v_analysis_source v
  CROSS JOIN LATERAL (
    SELECT EXISTS (
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
    AND (v.posted_at AT TIME ZONE 'Asia/Seoul')::date
          + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
          + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
        <= (now() AT TIME ZONE 'Asia/Seoul')::date
  OFFSET 0
) candidates
WHERE timely OR in_window;
```

구 버전의 `v_serving_content` 조인(timely EXISTS 안)은 사라진다 — `content_id`와 `content_type`을 소스 뷰가 직접 들고 있어서다. 파일 상단 주석의 "뷰티 모수 ∩ ENUMERATION" 문구를 "분석 모수(뷰티 ∪ F&B) ∩ ENUMERATION"으로 고치고, 위 두 단락(분리 이유·recency_rank 자체 계산)을 헤더에도 요약해 남긴다.

- [ ] **Step 5: 하니스 전체를 돌린다**

```bash
analytics/test/run.sh
```

Expected: ALL GREEN. `dummy_fb` 추가로 계정 수를 세는 다른 단언(`20_landing_stats.test.sql` 등)이 깨지면, **기대값을 고치지 말고 먼저 확인한다** — F&B 계정이 서빙 집계에 잡혔다면 그건 서빙 무변경 위반이다. 뷰티 게이트가 제대로 걸려 있으면 계정 수는 변하지 않아야 정상이다.

- [ ] **Step 6: 커밋**

```bash
git add analytics/views/04_analysis_candidates.sql analytics/seed/dummy.sql analytics/test/04_analysis_candidates.test.sql
git commit -m "feat(analytics): 분석 후보 뷰를 서빙에서 분리 — v_analysis_source 신설, F&B 모수 편입"
```

---

### Task 7: 분석 재료를 미러에서 후보 뷰로 전환

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java:54-58,157-190,197-265,359-370`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`

**Interfaces:**
- Consumes: Task 6의 `v_analysis_candidates` 컬럼
- Produces: `resolveTargets(boolean timely)`가 `List<String>` 대신 `List<Map<String, Object>>`(후보 행)을 돌려준다. 각 행의 키는 `short_code, account_handle, caption, content_type, thumbnail_url, views, likes, comments, ad_marked` — 구 `contents` 조회가 돌려주던 키 이름과 **정확히 같다**(하위 조립 `GeminiBatchLines`가 이 키 계약에 의존)

**세 번째 문:** `resolveTargets`에 미러 미도달 게이트가 있다.

```java
Set<String> mirrored = new HashSet<>(analysis.queryForList("SELECT short_code FROM contents", String.class));
if (!mirrored.contains(shortCode)) { mirrorMissing++; continue; }
```

`contents`는 뷰티 게이트를 통과한 미러라 **F&B 후보는 100% 여기서 스킵된다.** 04를 열어도 로그 한 줄 남기고 전부 사라진다. 재료를 후보 뷰에서 직접 읽으면 게이트 자체가 불필요해진다(미러 지연 스킵도 함께 사라져 뷰티 경로도 개선된다).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ContentAnalysisJobTest.java`에 추가한다. 기존 테스트가 `contents` 시드에 의존하면 그대로 두고, 미러에 없는 후보가 처리되는지만 새로 본다.

```java
	@Test
	void 미러에_없는_후보도_분석된다() {
		// F&B 콘텐츠는 서빙 미러(contents)에 없다 — 후보 뷰에서 재료를 직접 읽어야 한다.
		seedCandidate("fnb_only_1", "dummy_fb", "밀키트 후기", false);   // contents에는 넣지 않는다
		JobResult result = job.runLateBackfill();
		assertEquals(1, result.processed());
		assertEquals(1, countAnalyses("fnb_only_1"));
	}
```

`seedCandidate`/`countAnalyses`는 이 테스트 클래스의 기존 헬퍼 관용구를 따른다. 기존 헬퍼가 `contents`에도 넣고 있으면, 미러에 넣지 않는 변형을 추가한다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"
```

Expected: FAIL — `processed()`가 0 (미러 부재로 스킵)

- [ ] **Step 3: 후보 SQL이 재료까지 가져오게 한다**

```java
	// 후보 자격은 raw 후보 뷰가 정본(07-28 캘린더일 정합 — 뷰 04 주석 참조).
	// 재료(캡션·지표·핸들)도 이 뷰에서 함께 읽는다(2026-08-31) — 구 버전은 analysis DB의 미러
	// 테이블 contents에서 읽었는데, 미러는 뷰티 서빙 모수라 F&B 후보가 전부 "미러 부재"로
	// 스킵됐다. 뷰가 이미 같은 컬럼을 들고 있어 조회 1회가 줄고 미러 지연 스킵도 사라진다.
	private static final String CANDIDATES_SQL = """
			SELECT short_code, account_handle, caption, content_type, thumbnail_url,
			       views, likes, comments, ad_marked
			FROM v_analysis_candidates
			WHERE timely = ?
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""";
```

`resolveTargets`를 행 반환으로 바꾸고 미러 게이트를 제거한다.

```java
	private List<Map<String, Object>> resolveTargets(boolean timely) {
		List<Map<String, Object>> candidates = new ArrayList<>();
		raw.query(CANDIDATES_SQL, rs -> {
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
		}, timely);
		Set<String> analyzed = new HashSet<>(
				analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
		Set<String> commentBlocked = new HashSet<>(analysis.queryForList("""
				SELECT DISTINCT m.short_code FROM content_comments m
				WHERE NOT EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = m.short_code)""",
				String.class));
		// 미러 미도달 게이트는 제거했다 — 재료를 미러가 아니라 후보 뷰에서 읽으므로 조회 실패가
		// 구조적으로 발생하지 않는다(구 게이트의 목적이 그 실패 방지였다).
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

- [ ] **Step 4: 소비처 두 곳을 행 기반으로 고친다**

`submitBatch(boolean timely, List<Map<String, Object>> targets, Baselines baselines)` — 루프 안의 `analysis.queryForMap("… FROM contents WHERE short_code = ?")`를 지우고 인자로 받은 행을 그대로 쓴다.

```java
		for (Map<String, Object> content : targets) {
			String shortCode = (String) content.get("short_code");
			Baseline b = baselines.withBaseline().get(shortCode);
			if (b == null) {
				Baseline accountAvg = baselines.accountBaseline().get((String) content.get("account_handle"));
				b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
			}
			// … categoryCounts 조회부터 이하 기존 로직 그대로 …
			Map<String, Object> row = new LinkedHashMap<>(content);
			row.remove("short_code");   // 구 contents 조회 결과에 없던 키 — 프롬프트 입력 계약 보존
			// … row.put(...) 기준선 채우기 그대로 …
		}
```

`analyzeOne(String shortCode, …)`도 `analyzeOne(Map<String, Object> content, …)`로 바꿔 첫 줄의 `analysis.queryForMap(… FROM contents …)`를 지운다. 호출부(`runOnline`)는 `targets` 행을 그대로 넘긴다. `shortCode`가 필요한 곳은 `(String) content.get("short_code")`로 얻는다.

`analysis.queryForList("SELECT short_code FROM contents", String.class)` 호출은 이제 없어야 한다.

- [ ] **Step 5: 테스트 통과를 확인한다**

```bash
./gradlew :analytics:test
```

Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add analytics/src
git commit -m "feat(analytics): 분석 재료를 미러에서 후보 뷰로 전환 — 미러 미도달 게이트 제거"
```

---

### Task 8: 배치 제출 청크 분할

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java:197-265`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`

**Interfaces:**
- Consumes: Task 7의 행 기반 `targets`
- Produces: `submitBatch`가 청크당 배치 1건을 제출하고 `content_batch_jobs`에 청크당 1행을 남긴다. 반환 `JobResult.processed()`는 **전체 제출 건수 합계**

**왜:** 현재 `submitBatch`는 대상 전량을 배치 1건으로 제출한다. 실측 최대는 3,063건이었고 `sidecar_jsonl` 설계 주석의 전제는 "~450행 × 수백 바이트 = 수백 KB"다. F&B 백로그 61,619건을 한 배치로 밀면 한 컬럼에 수십 MB가 들어가고 Vertex 배치 파일 한도에도 걸린다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
	@Test
	void 대상이_청크_상한을_넘으면_배치를_나눠_제출한다() {
		for (int i = 0; i < ContentAnalysisJob.BATCH_CHUNK + 10; i++) {
			seedCandidate("bulk_" + i, "dummy_a", "캡션 " + i, false);
		}
		JobResult result = job.runLateBackfill();
		assertEquals(ContentAnalysisJob.BATCH_CHUNK + 10, result.processed());
		assertEquals(2, countBatchJobs());   // 청크 2개 = content_batch_jobs 2행
	}
```

`countBatchJobs()`는 `SELECT count(*) FROM content_batch_jobs`를 세는 헬퍼로 추가한다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"
```

Expected: FAIL — `content_batch_jobs`가 1행

- [ ] **Step 3: 청크 루프를 넣는다**

현재 `submitBatch` 본문에서 **pending 선수거와 빈 대상 가드만 남기고**, JSONL 조립·업로드·INSERT를 `submitOneChunk`로 추출한다.

```java
	/**
	 * 배치 1건당 제출 상한 (2026-08-31). 실측 최대 제출량(3,063건)에 맞춘 값 —
	 * sidecar_jsonl이 한 컬럼에 들어가고 Vertex 배치 파일 한도도 있어 무한정 키울 수 없다.
	 * F&B 백로그(6만여 건) 일괄 개방 시 이 값 단위로 나뉘어 제출된다.
	 */
	static final int BATCH_CHUNK = 3000;

	private JobResult submitBatch(boolean timely, List<Map<String, Object>> targets,
			Baselines baselines) {
		JobResult swept = collectJob.run();
		if (swept.processed() > 0 || swept.failed() > 0) {
			log.info("배치 제출 전 pending 수거 — {}건 저장, {}건 실패", swept.processed(), swept.failed());
		}
		if (targets.isEmpty()) {
			log.info("배치 제출 대상 없음 — 제출 생략 (timely={})", timely);
			return new JobResult(0, 0, false);
		}
		int submitted = 0;
		for (int from = 0; from < targets.size(); from += BATCH_CHUNK) {
			List<Map<String, Object>> chunk =
					targets.subList(from, Math.min(from + BATCH_CHUNK, targets.size()));
			submitOneChunk(timely, chunk, baselines);
			submitted += chunk.size();
		}
		log.info("분석 배치 제출 완료 — 총 {}건, 청크 {}개, timely={}",
				submitted, (targets.size() + BATCH_CHUNK - 1) / BATCH_CHUNK, timely);
		return new JobResult(submitted, 0, false);
	}
```

`submitOneChunk(boolean timely, List<Map<String, Object>> targets, Baselines baselines)`는 구 `submitBatch`에서 pending 수거·빈 가드·`return`을 뺀 나머지(JSONL 조립 루프 → `uploadFile` → `createBatch` → `content_batch_jobs` INSERT → 로그) 그대로다. 마지막 로그는 청크 단위임을 밝힌다.

```java
		log.info("분석 배치 청크 제출 — batch={}, {}건, timely={}", batchName, targets.size(), timely);
```

- [ ] **Step 4: 테스트 통과를 확인한다**

```bash
./gradlew :analytics:test
```

Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add analytics/src
git commit -m "feat(analytics): 배치 제출 청크 분할 — 백로그 일괄 개방 시 배치당 3,000건"
```

---

### Task 9: 문서 갱신 + PR

**Files:**
- Modify: `DECISIONS.md` (맨 위 행 추가)
- Create: `docs/tracks/LL2-fnb-콘텐츠-분류.md`
- Modify: `ARCHITECTURE.md:§3` (분석 뷰 설명의 "서빙 모수는 뷰티 인플루언서" 문장에 04 분리 반영)
- Move: `docs/superpowers/plans/2026-08-31-fnb-content-taxonomy.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: 전체 테스트를 돌린다**

```bash
./gradlew test
```

Expected: 4모듈 GREEN. (전체 실행은 PR 직전 1회 — CLAUDE.md 컨벤션)

- [ ] **Step 2: SQL 하니스 전체를 돌린다**

```bash
analytics/test/run.sh
```

Expected: ALL GREEN

- [ ] **Step 3: 트랙 문서를 만든다**

`docs/tracks/LL2-fnb-콘텐츠-분류.md`에 트랙 LL과 같은 형식으로 쓴다 — 소속 트랙군(설계 문서 링크), 의존(LL), 상태, 태스크 표(이 계획의 Task 1~8), 주요 결정(축을 어휘에서 유도 / is_beauty 파생 / 04 서빙 분리 / 미러 게이트 제거 / 공구 sponsored / F&B 우선), 검증, 후속(서빙 개방 · 홈리빙 어휘 · 유통사 목록 확정).

트랙 LL 문서의 "후속 후보 — 카테고리 서빙 개편" 항목에 이 트랙 링크를 건다.

- [ ] **Step 4: DECISIONS.md 맨 위에 한 행을 추가한다**

날짜 `2026-08-31`, 제목 **"F&B 콘텐츠 분류 — 축을 어휘에서 유도, 서빙은 무변경"**. 본문에 담을 것: 수집은 이미 켜져 있는데(운영 `fnb.pipeline-enabled=true`) 분석이 0건이던 원인 세 가지(04가 서빙 뷰 상속 · sanitize의 비뷰티 대분류 삭제 · `resolveTargets`의 미러 미도달 게이트), 축을 `beauty_taxonomy.axis`로 유도해 `content_analyses` 스키마를 안 늘린 이유(홈/리빙 예정), `is_beauty` 파생 유지로 was 무접촉(유통사 옵션 축 필터 1줄만 예외), 실측 후보 61,619건 일괄 개방, 기각한 대안(`is_fnb` 컬럼 · 불변식 폐기 · 테이블 rename). 링크는 설계 문서·트랙·마이그레이션 2건.

- [ ] **Step 5: 계획 문서를 아카이브로 옮긴다**

```bash
git mv docs/superpowers/plans/2026-08-31-fnb-content-taxonomy.md docs/superpowers/plans/archive/
```

- [ ] **Step 6: 커밋하고 PR을 연다**

```bash
git add -A
git commit -m "docs: F&B 콘텐츠 분류 트랙 문서·결정 기록 + 계획 아카이브"
git push -u origin feature/fnb-category-addition-ad196e
gh pr create --base develop --title "feat(analytics): F&B 콘텐츠 분류 — 축을 어휘에서 유도" --body "$(cat <<'EOF'
## 무엇을

수집은 이미 돌지만(운영 `fnb.pipeline-enabled=true`) LLM 분석이 0건이던 F&B 콘텐츠를
분석 파이프라인에 편입한다. 서빙(랭킹·발굴)에는 노출하지 않는다.

운영 실측: F&B 단독 계정 5,575개 / 분석 후보 61,619건 / 분석 0건·미러 0건.

## 막고 있던 문 세 개

1. `04_analysis_candidates`가 서빙 뷰 `v_contents`(02) 위에 얹혀 뷰티 모수를 상속
2. `sanitize()`의 "비뷰티면 `main_category=null`" 게이트 — 어휘를 넣어도 분류가 지워짐
3. `resolveTargets()`의 미러 미도달 게이트 — F&B는 미러(`contents`)에 없어 100% 스킵

## 어떻게

축을 `beauty_taxonomy.axis`에서 유도한다. `content_analyses`에 컬럼을 더하지 않고
`is_beauty`를 파생값으로 계속 채워 was 소비처를 무접촉으로 둔다.
**홈/리빙 추가 = 어휘 INSERT 한 번 + 04 모수에 OR 한 항.**

## 서빙 무변경

was 소비처 5곳이 전부 `is_beauty IS TRUE`로 걸러진다(설계 §8 점검표).
유일한 was 변경은 `findDistributorOptions`의 축 필터 1줄 — 서빙 확장이 아니라,
F&B 유통사가 뷰티 랭킹 드롭다운에 노출되는 것을 막는 오염 차단이다.

## 배포 순서

analytics 마이그레이션이 먼저 적용돼야 was의 축 필터 쿼리가 산다. develop→staging→main
승격에서는 두 모듈이 같은 릴리스로 나가 순서 역전이 없지만, 수동 개입 시 analytics 우선.

설계: docs/superpowers/specs/2026-08-31-fnb-content-taxonomy-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 7: 운영 실행 (배포 후)**

배포 완료 후 analytics 어드민(`/ui`)에서 분석 잡을 수동 트리거한다. `runLateBackfill()` 경로가 F&B 백로그를 청크 단위로 제출한다. 관측 지점:

```sql
-- 제출 현황
SELECT status, count(*), sum(submitted_count) FROM content_batch_jobs GROUP BY status;
-- 축별 분석 누적
SELECT t.axis, count(*) FROM content_analyses a
JOIN (SELECT DISTINCT main_value, axis FROM beauty_taxonomy) t ON t.main_value = a.main_category
GROUP BY t.axis;
```

제출은 오늘 안에 끝내는 것이 목표이고, 수거(`ContentBatchCollectJob`)는 Vertex 배치 처리 시간에 달려 있어 며칠 걸릴 수 있다.

---

## 검증 요약

| 항목 | 방법 |
|---|---|
| 어휘 시드 | `BeautyTaxonomySeedTest` — F&B 24행·유통사 11행·소분류 라벨 유일성 |
| 축 조회 | `BeautyTaxonomyTest` — `axisOf`·`distributorAxisOf`·프롬프트 축 표기 |
| sanitize 동치성 | `AnthropicContentAttributeAnalyzerTest` — 뷰티 기존 동작 불변, F&B 대분류 생존, 축 불일치 유통사 드랍, 무관 콘텐츠 비움 |
| 응답 파싱 | `GeminiContentAnalyzerTest` — `isRelevant` 파싱 → 축으로 `is_beauty` 판정 |
| was 오염 차단 | `V1ContentRepositoryTest` — 유통사 옵션에 F&B 미포함 |
| 후보 뷰 분리 | `04_analysis_candidates.test.sql` — F&B 포함 + `v_contents`·`v_recent_content` 미노출 |
| 미러 비의존 | `ContentAnalysisJobTest` — 미러에 없는 후보도 분석됨 |
| 배치 청크 | `ContentAnalysisJobTest` — 상한 초과 시 `content_batch_jobs` 2행 |
| 서빙 무변경 | `analytics/test/run.sh` 전체 + `./gradlew :was:test` |

## 미해결로 남기는 것

- **프롬프트 회귀 실측** — `isBeauty`→`isRelevant` 문구 변경이 기존 뷰티 분류를 흔들지 않는지는 단위 테스트로 증명되지 않는다. 배포 후 며칠간 뷰티 콘텐츠의 `main_category` 분포와 `ad_type` 비율을 이전 구간과 비교해 확인한다. 눈에 띄게 갈리면 프롬프트 문구를 되돌리고 축 판정만 파생으로 남기는 선택지가 있다
- **F&B 유통사 11곳 목록** — 설계 §4 초안 그대로다. 운영 데이터가 쌓이면 실제 등장 빈도로 조정
- **`beauty_taxonomy` 이름** — 축이 셋이 되면 더 어색해진다. 서빙 개편 때 rename을 재검토
