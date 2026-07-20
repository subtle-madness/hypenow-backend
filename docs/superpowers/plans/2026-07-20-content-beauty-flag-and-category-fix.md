# 콘텐츠 뷰티 판별 + main_category NULL 정합 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 통합 1콜 속성 분석에 `isBeauty`를 신설해 콘텐츠 단위 뷰티 여부를 판별하고, main_category NULL 342건의 두 원인(비뷰티 미표현·sanitize 드랍)을 고쳐 서빙에서 비뷰티를 제외한다.

**Architecture:** analytics(생산자)가 `isBeauty`를 LLM 통합 콜로 확정하고 `content_analyses.is_beauty`에 저장한다. 어휘 밖 mainCategory는 sub_categories→대분류 역유도로 복구하고, 복구 실패한 뷰티 콘텐츠는 행을 남기지 않아 재대상화한다. was(소비자)는 boolean 필터만 건다(§4-4). 04 후보 뷰는 raw 계층이라 무변경.

**Tech Stack:** Java 21 · Spring Boot 4 · Gradle 멀티모듈(analytics/was) · Flyway(analysis DB) · JUnit5 · Testcontainers · AssertJ · Gemini/Anthropic LLM 어댑터(포트 fake 테스트)

**설계 근거:** [specs/2026-07-20-content-beauty-flag-and-category-fix-design.md](../specs/2026-07-20-content-beauty-flag-and-category-fix-design.md)

---

## 파일 구조

**analytics (생산자)**
- `db/migration/analysis/V34__content_is_beauty.sql` *(신설)* — is_beauty 컬럼 + 기존 뷰티 백필
- `llm/BeautyTaxonomy.java` *(수정)* — `deriveMain()` 역유도 + 라벨→대분류 맵
- `llm/ContentAttributes.java` *(수정)* — `isBeauty` 필드(마지막 위치)
- `llm/GeminiContentAnalyzer.java` *(수정)* — 스키마·프롬프트·Output·parse에 isBeauty
- `llm/AnthropicContentAttributeAnalyzer.java` *(수정)* — 프롬프트 문항 + sanitize 역유도·isBeauty 통과
- `analyze/ContentAnalysisWriter.java` *(수정)* — INSERT에 is_beauty
- `analyze/ContentAnalysisJob.java` *(수정)* — 뷰티·미분류 실패 가드
- `ops/reprocess_uncategorized_content_analyses.sql` *(신설)* — 342건 self-heal

**was (소비자)**
- `v1/content/V1ContentRepository.java` *(수정)* — 랭킹 `is_beauty = true`
- `v1/influencer/V1InfluencerRepository.java` *(수정)* — recentContents `IS DISTINCT FROM false`

**테스트** — `BeautyTaxonomyTest`, `AnthropicContentAttributeAnalyzerTest`, `GeminiContentAnalyzerTest`, `ContentAnalysisJobTest`, `V1ContentRepositoryTest`, `V1InfluencerRepositoryTest` (전부 기존 파일 확장, 실 API 호출 금지 — 포트 fake). **04 뷰 미변경이라 SQL 하니스 회귀 없음.**

**커밋 컨벤션:** 한국어, prefix `feat(analytics):`/`feat(was):`/`docs:`.

---

## Task 1: BeautyTaxonomy.deriveMain — 서브라벨 → 대분류 역유도

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/BeautyTaxonomy.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomyTest.java`

- [ ] **Step 1: 실패 테스트 작성** — `BeautyTaxonomyTest`에 아래 테스트 추가 (기존 FIXTURE 사용, 애매 라벨용 소형 픽스처 별도).

```java
	@Test
	void 단일_소분류_라벨은_해당_대분류로_역유도된다() {
		assertEquals("makeup", FIXTURE.deriveMain(List.of("립틴트")));
	}

	@Test
	void 중분류_라벨도_대분류로_역유도된다() {
		assertEquals("makeup", FIXTURE.deriveMain(List.of("립메이크업")));
	}

	@Test
	void 최다_득표_대분류로_역유도된다() {
		// makeup 2표(립틴트·립스틱) > skincare 1표(스킨)
		assertEquals("makeup", FIXTURE.deriveMain(List.of("스킨", "립틴트", "립스틱")));
	}

	@Test
	void 동점이면_분류표_앞선_대분류로_결정론적_tie_break한다() {
		// skincare 1표·makeup 1표 동점 → 픽스처 순서상 skincare가 앞(main_order 개념)
		assertEquals("skincare", FIXTURE.deriveMain(List.of("스킨", "립틴트")));
	}

	@Test
	void 어휘_밖_라벨만_있으면_null이다() {
		assertNull(FIXTURE.deriveMain(List.of("없는라벨")));
		assertNull(FIXTURE.deriveMain(List.of()));
		assertNull(FIXTURE.deriveMain(null));
	}

	@Test
	void 여러_대분류에_걸치는_애매한_라벨은_투표에서_제외된다() {
		// "겸용"이 skincare·makeup 둘 다에 속하면 단일 대분류로 못 정하므로 집계 제외 → 남은 단일표로 결정
		BeautyTaxonomy ambiguous = new BeautyTaxonomy(List.of(
				new BeautyTaxonomy.Entry("skincare", "스킨케어", "겸용", "겸용"),
				new BeautyTaxonomy.Entry("makeup", "메이크업", "겸용", "겸용"),
				new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트")),
				List.of("올리브영"));
		assertEquals("makeup", ambiguous.deriveMain(List.of("겸용", "립틴트")));
		assertNull(ambiguous.deriveMain(List.of("겸용"))); // 애매 라벨만 → 결정 불가
	}
```

`assertNull` import 추가 필요: `import static org.junit.jupiter.api.Assertions.assertNull;`

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.llm.BeautyTaxonomyTest'`
Expected: FAIL — `deriveMain` 메서드 없음(컴파일 에러).

- [ ] **Step 3: 구현** — `BeautyTaxonomy`에 필드 2개 + 생성자 조립 + `deriveMain` 추가.

생성자 필드 선언부(기존 필드들 아래)에 추가:
```java
	private final Map<String, String> labelToMain; // mid·sub 라벨 → 단일 대분류(애매하면 제외)
	private final List<String> mainOrder;          // 대분류 최초 등장 순서(분류표 순 tie-break)
```

생성자 본문 끝(`this.subLabels = ...` 다음)에 추가:
```java
		Map<String, Set<String>> byLabel = new LinkedHashMap<>();
		List<String> order = new ArrayList<>();
		for (Entry e : entries) {
			if (!order.contains(e.mainValue())) {
				order.add(e.mainValue());
			}
			byLabel.computeIfAbsent(e.midLabel(), k -> new HashSet<>()).add(e.mainValue());
			byLabel.computeIfAbsent(e.subLabel(), k -> new HashSet<>()).add(e.mainValue());
		}
		Map<String, String> single = new HashMap<>();
		byLabel.forEach((label, mains) -> {
			if (mains.size() == 1) {
				single.put(label, mains.iterator().next());
			}
		});
		this.labelToMain = Map.copyOf(single);
		this.mainOrder = List.copyOf(order);
```

클래스에 메서드 추가:
```java
	/**
	 * 유효 라벨(중·소분류)들이 가리키는 대분류를 최다 득표로 역유도한다 (sanitize 복구용).
	 * 여러 대분류에 걸치는 애매한 라벨은 집계에서 제외, 동점은 분류표 앞선 대분류로 결정론적 tie-break.
	 * @return 역유도된 대분류 slug, 매칭 라벨이 없으면 null.
	 */
	public String deriveMain(List<String> labels) {
		if (labels == null) {
			return null;
		}
		Map<String, Long> votes = new LinkedHashMap<>();
		for (String label : labels) {
			String main = labelToMain.get(label);
			if (main != null) {
				votes.merge(main, 1L, Long::sum);
			}
		}
		if (votes.isEmpty()) {
			return null;
		}
		long max = votes.values().stream().max(Long::compare).orElseThrow();
		return votes.entrySet().stream()
				.filter(e -> e.getValue() == max)
				.map(Map.Entry::getKey)
				.min(java.util.Comparator.comparingInt(mainOrder::indexOf))
				.orElseThrow();
	}
```

import 추가: `java.util.HashMap`, `java.util.HashSet`(이미 `Set`·`LinkedHashMap`·`ArrayList`·`List`·`Map`·`Collectors`·`Stream` import됨 — 없는 것만 추가).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.llm.BeautyTaxonomyTest'`
Expected: PASS (기존 5 + 신규 6).

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/BeautyTaxonomy.java \
        analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomyTest.java
git commit -m "feat(analytics): 분류 어휘 역유도(deriveMain) — 서브라벨→대분류"
```

---

## Task 2: V34 마이그레이션 — is_beauty 컬럼 + 기존 뷰티 백필

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V34__content_is_beauty.sql`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 콘텐츠 단위 뷰티 여부 (07-20). 통합 1콜 속성 분석의 isBeauty를 저장.
--   true  : 뷰티 콘텐츠 (랭킹·recentContents 노출 대상)
--   false : 비뷰티(뷰티 인플루언서의 일상글 등) — 서빙 제외, NOT EXISTS로 재분석 루프 이탈
--   null  : 미판정 (V34 이전 행 중 main_category NULL 실패분 — ops 재분석 대상)
-- 기존 행 백필: main_category가 있으면 뷰티가 확정이므로 true. NULL 카테고리 행은 손대지 않는다
--   (ops/reprocess_uncategorized_content_analyses.sql이 삭제→재분석으로 채운다).
ALTER TABLE content_analyses ADD COLUMN is_beauty boolean;

UPDATE content_analyses SET is_beauty = true WHERE main_category IS NOT NULL;
```

- [ ] **Step 2: 마이그레이션 적용 확인 (analytics 테스트가 resetAndMigrate로 검증)**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.analyze.ContentAnalysisJobTest' --tests 'com.celfit.analytics.testsupport.*' 2>&1 | tail -20`
Expected: 마이그레이션 파싱/적용 에러 없이 기존 테스트 GREEN (컬럼 추가는 기존 INSERT에 무해 — Writer는 Task 4에서 채움).

> 참고: 이 시점엔 Writer가 아직 is_beauty를 안 넣으므로 기존 잡 테스트는 그대로 통과한다.

- [ ] **Step 3: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis/V34__content_is_beauty.sql
git commit -m "feat(analytics): V34 content_analyses.is_beauty 컬럼 + 기존 뷰티 백필"
```

---

## Task 3: ContentAttributes.isBeauty 필드 — LLM 산출·스키마·저장 배선 (원자적)

> record에 필드를 추가하면 모든 positional 생성자가 동시에 깨진다. **이 태스크 하나에서** 모든 콜사이트를 고쳐 컴파일을 회복한다. 역유도(복구)와 잡 가드는 Task 4·5에서. isBeauty는 record **마지막 필드**로 append(스키마 생성 순서와 무관 — Jackson은 이름 매핑).

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/ContentAttributes.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisWriter.java`
- Test: `GeminiContentAnalyzerTest`, `AnthropicContentAttributeAnalyzerTest`, `ContentAnalysisJobTest` (생성자 인자 append)

- [ ] **Step 1: 실패 테스트 작성** — `GeminiContentAnalyzerTest`.

RESPONSE 상수의 mainCategory 앞에 isBeauty 추가 (JSON):
```java
	static final String RESPONSE = """
			{"detectedBrands":[{"name":"브랜드A","evidence":"캡션 언급"}],
			 "sponsoredSignalLevel":"엉뚱값","sponsoredSignalReasons":["#협찬"],
			 "adDisclosure":"표기 있음","detectedProductCategories":["클렌징폼","없는라벨"],
			 "detectedProducts":[{"name":"딥클렌징폼","brand":null}],
			 "vlmAttributes":[],"isBeauty":true,"mainCategory":"cleansing","subCategories":["클렌징폼/젤","클렌징폼"],
			 "detectedDistributors":["올리브영","쿠팡"],"adType":"sponsored",
			 "aiContentSummary":"평균 대비 1.2배","contentsPattern":"클렌징 루틴형",
			 "aiCommentInsight":"표본 부족","commentAuthenticityGrade":"이상값","commentAuthenticityNote":"근거"}""";
```

신규 테스트 2개 추가:
```java
	@Test
	void isBeauty를_파싱해_속성에_싣는다() {
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertEquals(Boolean.TRUE, r.attributes().isBeauty());
	}

	@Test
	void 스키마는_isBeauty를_요구하고_mainCategory_앞에서_생성한다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String schema = calls.get(0).schema();
		assertTrue(schema.contains("\"isBeauty\""));
		assertTrue(schema.indexOf("\"isBeauty\"") < schema.lastIndexOf("\"mainCategory\"")); // propertyOrdering 상 앞
	}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.llm.GeminiContentAnalyzerTest'`
Expected: FAIL — `isBeauty()` 메서드 없음(컴파일 에러).

- [ ] **Step 3: ContentAttributes에 isBeauty 필드 추가 (마지막)**

```java
public record ContentAttributes(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Product> detectedProducts,
		List<Attribute> vlmAttributes, String mainCategory, List<String> subCategories,
		List<String> detectedDistributors, String adType, Boolean isBeauty) {
```
(record 본문의 중첩 record Brand/Product/Attribute는 그대로.)

클래스 주석에 한 줄 추가: `isBeauty는 콘텐츠 단위 뷰티 여부(어휘 없음 — sanitize 통과, 잡이 실패 판정에 사용).`

- [ ] **Step 4: GeminiContentAnalyzer — 스키마·프롬프트·Output·parse**

`RESPONSE_SCHEMA`의 `properties`에 vlmAttributes 다음(mainCategory 앞)에 추가:
```
				  "isBeauty":{"type":"boolean"},
```
`required` 배열에 `"isBeauty"` 추가(mainCategory 앞), `propertyOrdering` 배열에도 `"isBeauty"`를 `"mainCategory"` 앞에 추가. 세 곳 모두.

`instructions()` 파트 A 안내에서 mainCategory 항목 위에 문항 추가:
```
					- isBeauty: 이 콘텐츠가 뷰티 콘텐츠인가 (true/false). 뷰티 제품·시술·루틴·리뷰 등이면 true,
					  뷰티 인플루언서라도 일상·여행·음식 등 뷰티와 무관하면 false. mainCategory와 독립적으로 반드시 판정하라.
```
(기존 "뷰티와 무관한 콘텐츠면 mainCategory는 null이다." 문장은 유지.)

`Output` record 필드에 isBeauty 추가(vlmAttributes 다음):
```java
	record Output(List<ContentAttributes.Brand> detectedBrands, String sponsoredSignalLevel,
			List<String> sponsoredSignalReasons, String adDisclosure,
			List<String> detectedProductCategories, List<ContentAttributes.Product> detectedProducts,
			List<ContentAttributes.Attribute> vlmAttributes, Boolean isBeauty, String mainCategory,
			List<String> subCategories, List<String> detectedDistributors, String adType,
			String aiContentSummary, String contentsPattern, String aiCommentInsight,
			String commentAuthenticityGrade, String commentAuthenticityNote) {}
```

`parse()`의 `new ContentAttributes(...)` 마지막 인자로 `o.isBeauty()` 추가:
```java
		ContentAttributes attrs = AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType(), o.isBeauty()), taxonomy);
```

- [ ] **Step 5: AnthropicContentAttributeAnalyzer — 프롬프트 문항 + sanitize 통과**

`instructions()`의 mainCategory 항목 위에 동일 isBeauty 문항 추가:
```
					- isBeauty: 이 콘텐츠가 뷰티 콘텐츠인가 (true/false). 뷰티 제품·시술·루틴·리뷰 등이면 true,
					  뷰티 인플루언서라도 일상·여행·음식 등 뷰티와 무관하면 false. mainCategory와 독립적으로 반드시 판정하라.
```

`sanitize()` return의 `new ContentAttributes(...)` 마지막 인자로 `raw.isBeauty()` 추가(복구는 Task 4):
```java
				keepIfIn(raw.adType(), AD_TYPES),
				raw.isBeauty());
```

- [ ] **Step 6: ContentAnalysisWriter — INSERT에 is_beauty**

컬럼 목록 끝(`metric_timeliness` 앞이든 뒤든 무방 — 값 순서만 맞추면 됨)에 `is_beauty` 추가. `metric_timeliness` 다음에 두는 것으로 통일:
```java
				  detected_distributors, ad_type,
				  comment_authenticity_grade, comment_authenticity_note, metric_timeliness, is_beauty)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)"""
```
values 바인딩 끝(`metricTimeliness` 다음)에 추가:
```java
				s.commentAuthenticityGrade(), s.commentAuthenticityNote(), metricTimeliness,
				attrs == null ? null : attrs.isBeauty());
```
(플레이스홀더 `?` 1개 추가 — VALUES에 총 30개.)

- [ ] **Step 7: 테스트 생성자 인자 append (컴파일 회복)**

`AnthropicContentAttributeAnalyzerTest`:
- `attrsWith(...)`의 `new ContentAttributes(...)` 마지막에 `, true` 추가(뷰티 케이스).
- `분류표_밖_대분류는_null로_교체된다`의 인라인 생성자 마지막에 `, true` 추가.
- `분류표_밖_라벨은_배열에서_제거된다`의 인라인 생성자 마지막에 `, true` 추가.
- `null_배열은_null로_유지된다`의 `new ContentAttributes(null, ×11)` → null 12개로 (`, null` 하나 추가).

`ContentAnalysisJobTest`의 `fakeInsightPort()` 내 `new ContentAttributes(...)` 마지막에 `, true` 추가(mainCategory="cleansing"인 뷰티 케이스).

- [ ] **Step 8: 전체 analytics 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.llm.*' --tests 'com.celfit.analytics.analyze.ContentAnalysisJobTest'`
Expected: PASS (신규 Gemini 2건 포함, 기존 전부 GREEN).

- [ ] **Step 9: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/ContentAttributes.java \
        analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java \
        analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java \
        analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisWriter.java \
        analytics/src/test/java/com/celfit/analytics/llm/GeminiContentAnalyzerTest.java \
        analytics/src/test/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzerTest.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "feat(analytics): isBeauty 통합 1콜 배선 — 스키마·프롬프트·파싱·저장"
```

---

## Task 4: sanitize 역유도 복구 — 어휘 밖 mainCategory 보정

> Task 1의 `deriveMain`을 sanitize에 연결. 어휘 밖/null mainCategory를 sub_categories·detectedProductCategories의 유효 라벨로 역유도. **기존 테스트 `분류표_밖_대분류는_null로_교체된다`는 이제 복구되므로 기대값을 바꾼다**(이것이 버그 (b) 픽스).

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzerTest.java`

- [ ] **Step 1: 기존 테스트 기대값 수정 + 신규 테스트 작성**

`분류표_밖_대분류는_null로_교체된다`를 아래로 교체(이름·기대값 변경):
```java
	@Test
	void 분류표_밖_대분류는_서브카테고리로_역유도된다() {
		// slug 어휘 밖 값(hair)이라도 sub_categories의 유효 라벨(샴푸/스케일러→haircare)로 복구
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of(), List.of(),
						List.of(), "hair", List.of("샴푸/스케일러"), List.of(), "organic", true), TAXONOMY);

		assertEquals("haircare", sanitized.mainCategory()); // 드랍 대신 역유도 복구
		assertEquals(List.of("샴푸/스케일러"), sanitized.subCategories());
	}
```

신규 테스트 추가:
```java
	@Test
	void 어휘_밖_라벨뿐이면_역유도도_실패해_null이다() {
		// 유효 sub·productCategory가 하나도 없으면 복구 불가 → null 유지
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of("없는라벨"), List.of(),
						List.of(), "hair", List.of("없는중분류"), List.of(), "organic", true), TAXONOMY);

		assertNull(sanitized.mainCategory());
	}

	@Test
	void 제품카테고리로도_역유도된다() {
		// sub_categories 비어도 detectedProductCategories의 유효 소분류로 복구
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of("립틴트"), List.of(),
						List.of(), null, List.of(), List.of(), "organic", true), TAXONOMY);

		assertEquals("makeup", sanitized.mainCategory());
	}
```
(`null_배열은_null로_유지된다`는 main=null·sub=null·prodCat=null이라 역유도 signal 없음 → null 유지, 변경 불필요.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.llm.AnthropicContentAttributeAnalyzerTest'`
Expected: FAIL — `분류표_밖_대분류는_서브카테고리로_역유도된다`가 여전히 null 반환.

- [ ] **Step 3: sanitize에 역유도 삽입**

`sanitize()`를 아래로 교체(로컬 추출 + main null이면 deriveMain):
```java
	static ContentAttributes sanitize(ContentAttributes raw, BeautyTaxonomy taxonomy) {
		List<String> subs = filterToVocabulary(raw.subCategories(), taxonomy.allMidAndSubLabels());
		List<String> prodCats = filterToVocabulary(raw.detectedProductCategories(), taxonomy.allSubLabels());
		String main = keepIfIn(raw.mainCategory(), taxonomy.mainCategories());
		if (main == null) {
			// 어휘 밖/미상 대분류는 드랍 대신 유효 서브라벨로 역유도 복구 (예: ["선크림","컬러립밤"]→suncare)
			List<String> signal = new ArrayList<>();
			if (subs != null) {
				signal.addAll(subs);
			}
			if (prodCats != null) {
				signal.addAll(prodCats);
			}
			main = taxonomy.deriveMain(signal);
		}
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
				filterToVocabulary(raw.detectedDistributors(), taxonomy.distributors()),
				keepIfIn(raw.adType(), AD_TYPES),
				raw.isBeauty());
	}
```
(`java.util.ArrayList`·`java.util.List`는 이미 import됨.)

메서드 Javadoc의 "스칼라는 null로" 문장 다음에 한 줄 보강: `단 mainCategory는 어휘 밖이면 유효 서브라벨로 역유도 복구한다(드랍 아님).`

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.llm.AnthropicContentAttributeAnalyzerTest' --tests 'com.celfit.analytics.llm.GeminiContentAnalyzerTest'`
Expected: PASS (Gemini의 `어휘_밖_값은_sanitize로_걸러진다`는 mainCategory="cleansing"이 유효라 역유도 미발동 — 영향 없음).

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzer.java \
        analytics/src/test/java/com/celfit/analytics/llm/AnthropicContentAttributeAnalyzerTest.java
git commit -m "fix(analytics): sanitize 어휘 밖 대분류를 서브라벨로 역유도 복구"
```

---

## Task 5: ContentAnalysisJob — 뷰티·미분류 실패 가드 + 비뷰티 저장

> 실패 시맨틱(설계 3-3): 뷰티인데 복구 후에도 mainCategory null이면 **행 미기록→재대상**. 비뷰티(is_beauty=false)는 정상 저장돼 재분석 루프에 안 빠진다.

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`

- [ ] **Step 1: 실패 테스트 작성** — `ContentAnalysisJobTest`에 추가.

```java
	@Test
	void 비뷰티_콘텐츠는_is_beauty_false로_저장되고_재분석_루프에_안_빠진다() {
		// isBeauty=false + mainCategory=null(비뷰티라 자연 null) — 행은 기록되되 서빙에서 제외될 값
		rewireJob((content, thumbnailUrl) -> {
			insightCalls.add(content);
			ContentAttributes nonBeauty = new ContentAttributes(List.of(), null, List.of(), "표기 없음",
					List.of(), List.of(), List.of(), null, List.of(), List.of(), "organic", false);
			return new ContentInsightPort.ContentInsight(nonBeauty,
					new Synthesis("요약: " + content.shortCode(), "패턴", "인사이트", "normal", "근거"));
		}, false);

		int processed = job.run().processed();

		assertEquals(2, processed); // post_a·post_b 모두 저장(비뷰티도 행 생성)
		assertEquals(Boolean.FALSE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'post_a'", Boolean.class));
		assertNull(db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 뷰티인데_미분류면_행을_안_남기고_재대상화된다() {
		// isBeauty=true인데 복구 후에도 mainCategory=null → 실패 격리(skip) → 다음 실행 재대상
		rewireJob((content, thumbnailUrl) -> {
			insightCalls.add(content);
			ContentAttributes beautyNoCat = new ContentAttributes(List.of(), null, List.of(), "표기 없음",
					List.of(), List.of(), List.of(), null, List.of(), List.of(), "organic", true);
			Synthesis s = content.shortCode().equals("post_a")
					? new Synthesis("요약: post_a", "패턴", "인사이트", "normal", "근거") // 뷰티+미분류
					: new Synthesis("요약: " + content.shortCode(), "패턴", "인사이트", "normal", "근거");
			ContentAttributes attrs = content.shortCode().equals("post_a") ? beautyNoCat
					: new ContentAttributes(List.of(), null, List.of(), "표기 없음", List.of(), List.of(),
							List.of(), "makeup", List.of(), List.of(), "organic", true); // post_b는 정상
			return new ContentInsightPort.ContentInsight(attrs, s);
		}, false);

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만 성공, post_a는 skip
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class));
	}

	@Test
	void 정상_뷰티_콘텐츠는_is_beauty_true로_저장된다() {
		// 기존 fakeInsightPort는 isBeauty=true·mainCategory=cleansing
		job.run();
		assertEquals(Boolean.TRUE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'post_a'", Boolean.class));
	}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.analyze.ContentAnalysisJobTest'`
Expected: FAIL — `뷰티인데_미분류면...`이 post_a 행을 저장해 count=1 (가드 없음). 나머지 신규는 is_beauty 컬럼 값이 null(Writer는 Task 3에서 채우므로 실제로는 값 있음 — 이 케이스는 가드 실패가 주 원인).

- [ ] **Step 3: 잡에 실패 가드 삽입**

`analyzeOne`의 빈-종합 가드(`if (s.aiContentSummary() == null ...) throw ...`) **다음**, `ContentAnalysisWriter.insert(...)` **앞**에 추가:
```java
		// 뷰티 콘텐츠인데 복구 후에도 대분류를 못 얻으면 행을 남기지 않는다 — 저장되면 NOT EXISTS로
		// 영영 재분석 제외되므로, 실패 격리(skip)로 다음 실행에 재대상화(self-heal). 비뷰티(is_beauty=false)는
		// 정상 저장돼 루프에 안 빠진다. (설계 2026-07-20 §3-3)
		if (attrs != null && Boolean.TRUE.equals(attrs.isBeauty()) && attrs.mainCategory() == null) {
			throw new IllegalStateException("뷰티 콘텐츠인데 대분류 미분류 — 재대상: " + shortCode);
		}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests 'com.celfit.analytics.analyze.ContentAnalysisJobTest'`
Expected: PASS (기존 + 신규 3건).

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "feat(analytics): 뷰티·미분류는 행 미기록 재대상, 비뷰티는 is_beauty=false 저장"
```

---

## Task 6: ops 재분석 스크립트 — 기존 342건 self-heal

**Files:**
- Create: `analytics/ops/reprocess_uncategorized_content_analyses.sql`

- [ ] **Step 1: 스크립트 작성** (전례 `ops/reconcile_content_analyses.sql` 포맷 — dry-run ROLLBACK).

```sql
-- [일회성 운영 SQL] main_category 미분류 실패분 재분석 — 콘텐츠 뷰티 판별(V34) 후속.
--
-- 배경: V34 이전 분석분 중 main_category NULL 342건은 두 원인이 섞여 있다.
--   (a) 비뷰티 콘텐츠 — 새 파이프라인은 is_beauty=false로 저장해 서빙 제외.
--   (b) sanitize 드랍 — 새 파이프라인은 서브라벨→대분류 역유도로 복구.
--   둘 다 "행이 있으면 NOT EXISTS로 영영 재분석 제외"라 지금은 못 고친다 → 삭제해 재자격시킨다(self-heal).
--
-- 대상: is_beauty IS NULL AND main_category IS NULL (= V34 백필이 손대지 않은 pre-V34 실패분).
--   ⚠️ is_beauty=false(재분석으로 생긴 정상 비뷰티 행)는 건드리지 않는다 → 재실행 멱등.
-- 삭제는 안전: 지금 자격 없는 행만 지우므로 후보 뷰가 즉시 되살리지 않는다. 여전히 후보인 것만
--   데일리 잡이 새 프롬프트·스키마(isBeauty·역유도)로 재분석한다. 더 이상 후보 아닌 것(늦크롤 등)은
--   삭제만 되고 유입 없음 — 어차피 실패/비뷰티라 손실 아님.
--
-- 실행 위치: analysis DB (운영 postgres / DB `analysis`). V34 적용 후에 돌린다.
-- 무료 쿼터(일 ~1,500콜) 내 소화 가능. dry-run은 맨 끝 ROLLBACK, 반영은 COMMIT으로 바꿔 실행.

BEGIN;

\echo '=== 삭제 대상 총계 (pre-V34 미분류 실패분) ==='
SELECT count(*) AS to_delete
FROM content_analyses
WHERE is_beauty IS NULL AND main_category IS NULL;

\echo '=== 삭제 전/후 content_analyses 총계 ==='
SELECT count(*) AS before_delete FROM content_analyses;
DELETE FROM content_analyses WHERE is_beauty IS NULL AND main_category IS NULL;
SELECT count(*) AS after_delete FROM content_analyses;

ROLLBACK;
```

- [ ] **Step 2: 문법 검증 (로컬 analysis DB dry-run, 있으면)**

Run: `PG_CONTAINER=${PG_CONTAINER:-crawler-postgres-1}; docker exec -i "$PG_CONTAINER" psql -U crawler -d analysis < analytics/ops/reprocess_uncategorized_content_analyses.sql 2>&1 | tail -15 || echo "로컬 DB 없음 — 운영 반영 시 검증"`
Expected: dry-run 카운트 출력 후 ROLLBACK, 에러 없음. (로컬 DB 없으면 스킵 — 운영 승인 후 실행.)

- [ ] **Step 3: 커밋**

```bash
git add analytics/ops/reprocess_uncategorized_content_analyses.sql
git commit -m "feat(analytics): 미분류 실패분 재분석 ops 스크립트 (self-heal)"
```

---

## Task 7: was 랭킹 — 비뷰티 제외 (is_beauty = true)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentRepository.java`
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentRepositoryTest.java`

- [ ] **Step 1: 실패 테스트 작성** — 테스트 DDL·시드에 is_beauty 반영 + 비뷰티 제외 케이스.

테스트 `content_analyses` DDL에 컬럼 추가(`detected_distributors` 다음):
```java
			jdbcTemplate.execute("""
					CREATE TABLE content_analyses (
					    short_code            text PRIMARY KEY,
					    main_category         text,
					    sub_categories        jsonb,
					    ad_type               text,
					    detected_brands       jsonb,
					    detected_products     jsonb,
					    detected_distributors jsonb,
					    is_beauty             boolean
					)""");
```

기존 content_analyses 시드 INSERT에 컬럼·값 반영(전부 뷰티=true) + 비뷰티 릴스 1행 추가. 먼저 contents에 nb1 추가(기존 contents INSERT 끝에):
```java
				 ('f2', 'beta', 'https://thumb/f2.jpg', '피드 정렬용', '2026-07-03T03:00:00Z', 'feed',
				  NULL, 'https://ig/f2', 800, 80, 8, 350, '2026-07-06T03:00:00Z'),
				 ('nb1', 'alpha', 'https://thumb/nb1.jpg', '일상 브이로그', '2026-07-02T03:00:00Z', 'reels',
				  22, 'https://ig/nb1', 5000, 500, 50, 800, '2026-07-05T03:00:00Z')
```
content_analyses INSERT 교체(is_beauty 열 추가, nb1은 비뷰티):
```java
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors, is_beauty) VALUES
				 ('r1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, '[{"name":"제품A"}]'::jsonb, '["다이소"]'::jsonb, true),
				 ('r2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, '[]'::jsonb, true),
				 ('r9', 'makeup', '["립틴트"]'::jsonb, 'sponsored', NULL, NULL, NULL, true),
				 ('f1', 'makeup', '["립틴트"]'::jsonb, 'organic', NULL, NULL, NULL, true),
				 ('f2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, NULL, true),
				 ('nb1', NULL, NULL, 'organic', NULL, NULL, NULL, false)
				""");
```

신규 테스트 추가:
```java
	@Test
	void 비뷰티_콘텐츠는_랭킹에서_제외된다() {
		// nb1(is_beauty=false)은 hype 800으로 최상위지만 목록에서 빠진다
		List<ContentCardRow> rows = repository.findCards(query());

		assertThat(rows).extracting(ContentCardRow::shortCode).doesNotContain("nb1");
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("r2", "r1", "r9");
	}
```
(기존 `분석_행_없는_콘텐츠는_제외된다`는 여전히 r2·r1·r9 — nb1이 필터로 빠지므로 그대로 통과. `countCards`도 3 유지.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.content.V1ContentRepositoryTest'`
Expected: FAIL — 필터 없어 nb1이 hype 800으로 목록 최상위에 껴서 `비뷰티..제외`·`분석_행_없는..` 둘 다 실패.

- [ ] **Step 3: 랭킹 WHERE에 필터 추가**

`buildWhere()`의 초기 `StringBuilder` FROM/WHERE 절에 `is_beauty` 조건 추가:
```java
		StringBuilder sb = new StringBuilder("""

				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				WHERE c.posted_at >= :start AND c.posted_at < :end
				  AND c.content_type = :contentType
				  AND an.is_beauty = true
				""");
```
클래스 Javadoc의 "분석 완료만 노출" 옆에 `∧ 뷰티만(is_beauty=true)` 보강.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.content.V1ContentRepositoryTest'`
Expected: PASS (신규 + 기존 전부).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/content/V1ContentRepository.java \
        was/src/test/java/com/celfit/was/v1/content/V1ContentRepositoryTest.java
git commit -m "feat(was): 랭킹에서 비뷰티 콘텐츠 제외 (is_beauty=true)"
```

---

## Task 8: was 인플루언서 상세 — 비뷰티 제외, 미분석 유지

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerRepository.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerRepositoryTest.java`

- [ ] **Step 1: 실패 테스트 작성** — DDL에 is_beauty, 시드에 뷰티/미분석/비뷰티 3종.

테스트 `content_analyses` DDL에 `is_beauty boolean` 추가(Task 7 Step 1과 동일 형태):
```java
				CREATE TABLE content_analyses (
				    short_code            text PRIMARY KEY,
				    main_category         text,
				    sub_categories        jsonb,
				    ad_type               text,
				    detected_brands       jsonb,
				    detected_products     jsonb,
				    detected_distributors jsonb,
				    is_beauty             boolean
				)""");
```

contents 시드에 비뷰티 a3 추가(a1·a2 다음, a2보다 최신):
```java
				 ('a2', 'alpha', 'https://thumb/a2.jpg', '분석 미완 릴스', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/a2', 9999, 999, 99, 999, '2026-07-07T03:00:00Z'),
				 ('a3', 'alpha', 'https://thumb/a3.jpg', '일상 브이로그', '2026-07-06T03:00:00Z', 'reels',
				  18, 'https://ig/a3', 3000, 300, 30, 700, '2026-07-09T03:00:00Z')
```
content_analyses 시드 교체(a1 뷰티=true, a3 비뷰티=false, a2는 미분석이라 행 없음):
```java
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors, is_beauty) VALUES
				 ('a1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, NULL, NULL, true),
				 ('a3', NULL, NULL, 'organic', NULL, NULL, NULL, false)
				""");
```

기존 테스트 `최근_카드는_분석_미완_게시물도_포함하고_posted_at_내림차순이다` 기대값 수정(a3는 비뷰티라 제외, a2 미분석은 유지):
```java
	@Test
	void 최근_카드는_분석_미완은_포함하되_비뷰티는_제외한다() {
		List<ContentCardRow> rows = repository.findRecentCards("alpha");

		// a3(비뷰티) 제외, a2(미분석)는 유지 — posted_at DESC: a2(07-04) → a1(07-02)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("a2", "a1");
		assertThat(rows).extracting(ContentCardRow::shortCode).doesNotContain("a3");
	}
```
(`분석_미완_게시물은_분석_필드가_null이고_지표는_채워진다`는 a2 대상 — 그대로 통과.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.influencer.V1InfluencerRepositoryTest'`
Expected: FAIL — 필터 없어 a3(비뷰티)가 최신이라 목록에 껴서 기대 `[a2, a1]` 어긋남.

- [ ] **Step 3: recentContents WHERE에 필터 추가**

`findRecentCards()`의 쿼리 WHERE에 조건 추가:
```java
		return jdbcClient.sql(ContentCardRow.SELECT + """

				FROM contents c
				LEFT JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				WHERE c.account_handle = :h
				  AND (an.is_beauty IS DISTINCT FROM false)
				ORDER BY c.posted_at DESC, c.short_code
				LIMIT 12
				""").param("h", handle).query(ContentCardRow.class).list();
```
Javadoc 보강: `확정 비뷰티(is_beauty=false)는 제외하되 미분석(LEFT JOIN null)은 유지 — "실제 최신 12개".`

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.influencer.V1InfluencerRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerRepository.java \
        was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerRepositoryTest.java
git commit -m "feat(was): recentContents에서 비뷰티 제외, 미분석은 유지"
```

---

## Task 9: 문서 갱신 — ARCHITECTURE §5/§7

**Files:**
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: §5 상세 분석 작업 트랙 표에 행 추가** (B4 아래).

```markdown
| B5 | 콘텐츠 뷰티 판별 + 정합 픽스 | 통합 1콜에 `isBeauty` 신설(content_analyses.is_beauty) + sanitize 어휘 밖 대분류 서브라벨 역유도 복구 + 뷰티·미분류는 행 미기록 재대상·비뷰티는 저장(루프 이탈) + was 서빙 비뷰티 제외(랭킹 is_beauty=true·recentContents IS DISTINCT FROM false) + 342건 self-heal ops. 커버리지 최신12 확장은 자매 세션 | B4 | ✅ (V34, PR 대기) |
```

- [ ] **Step 2: §7 결정 기록 맨 위에 행 추가**

```markdown
| 2026-07-20 | **콘텐츠 단위 뷰티 판별 + main_category NULL 정합** — 운영 811건 중 342건(42%) main_category NULL의 두 원인((a)비뷰티 미표현 (b)sanitize 드랍)을 통합 처리. ①통합 1콜 속성 프롬프트·스키마에 `isBeauty` 추가(별도 콜 없음)·`content_analyses.is_beauty`(V34, main_category 있는 기존 행은 true 백필). ②sanitize가 어휘 밖 대분류를 드랍 대신 sub_categories·detectedProductCategories 유효 라벨로 **역유도 복구**(`BeautyTaxonomy.deriveMain`, 최다 득표·동점은 분류표 순 tie-break, 재질의 없음). ③실패 시맨틱: 뷰티인데 복구 후에도 미분류면 **행 미기록→NOT EXISTS 재대상**(self-heal), 비뷰티는 is_beauty=false 저장해 루프 이탈. ④서빙: 비뷰티는 계약 확장 없이 API에서 제외 — 랭킹 `is_beauty=true`, recentContents `IS DISTINCT FROM false`(미분석 유지), 카테고리 믹스는 기존 main_category NOT NULL로 자동 제외. ⑤기존 342건은 `ops/reprocess_uncategorized_content_analyses.sql`(is_beauty NULL∧main NULL 삭제→재자격, 멱등). 04 후보 뷰는 raw 계층이라 무변경(SQL 하니스 회귀 없음). 트레이드오프: sub 없이 isBeauty=true만 주는 극소수는 매 실행 재시도(무료 쿼터 무해, 시도 상한은 후속). 자매 태스크(커버리지 최신12 확장)는 이 머지 후 별도 세션 | [specs/2026-07-20-content-beauty-flag-and-category-fix-design.md](docs/superpowers/specs/2026-07-20-content-beauty-flag-and-category-fix-design.md) |
```

- [ ] **Step 3: 마지막 갱신 날짜 갱신** — 문서 상단 `> 마지막 갱신: 2026-07-19` → `2026-07-20`.

- [ ] **Step 4: 커밋**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 콘텐츠 뷰티 판별 + 정합 픽스 트랙·결정 기록 (B5)"
```

---

## Task 10: 전체 검증 + PR

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL — analytics·was 전 테스트 GREEN.

- [ ] **Step 2: 정적 확인 — is_beauty 배선 누락 없음**

Run: `grep -rn "is_beauty\|isBeauty" analytics/src/main was/src/main | grep -v test`
Expected: 마이그레이션·Writer·Repository·record·어댑터에 일관 등장, 누락 없음.

- [ ] **Step 3: 커밋 히스토리 확인 후 PR 생성 (develop 대상)**

Run:
```bash
git log --oneline origin/develop..HEAD
git push -u origin feat/content-beauty-flag
gh pr create --base develop --title "feat: 콘텐츠 뷰티 판별 + main_category NULL 정합 픽스" --body "$(cat <<'EOF'
## 요약
- 통합 1콜 속성 분석에 `isBeauty` 신설(별도 LLM 콜 없음) + `content_analyses.is_beauty`(V34)
- sanitize 어휘 밖 대분류를 서브라벨→대분류 **역유도 복구**(드랍 폐기 — 버그 (b) 픽스)
- 실패 시맨틱: 뷰티·미분류는 행 미기록→재대상(self-heal), 비뷰티는 is_beauty=false 저장(루프 이탈)
- 서빙: 비뷰티 제외 — 랭킹 `is_beauty=true`, recentContents `IS DISTINCT FROM false`(미분석 유지), 계약 무변경
- 기존 342건 재분석 ops 스크립트(멱등)

## 범위 밖(후속)
- 분석 커버리지 최신 12개 확장 — 자매 세션(이 머지 후, V36+)
- 시도 상한(attempt cap) — 잔여 루프 관측 후

## 운영 반영(승인 후)
V34 마이그레이션 → was/analytics 배포 → ops 재분석. 04 뷰 무변경이라 뷰 적용 불필요.

설계: docs/superpowers/specs/2026-07-20-content-beauty-flag-and-category-fix-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
Expected: PR 생성 URL 출력. **push·PR은 사용자 승인 후에만** (직접 push 금지 규칙).

---

## Self-Review 체크

- **Spec 커버리지:** 요구사항 1(isBeauty)=Task 3 · 2(sanitize 복구)=Task 1+4 · 3(실패 시맨틱)=Task 5 · 4(342 재분석)=Task 6 · 5(서빙)=Task 7+8. 컬럼=Task 2. 문서=Task 9. 전부 태스크 매핑됨.
- **Placeholder:** 없음(모든 스텝 실제 코드·명령·기대값 포함).
- **타입 일관성:** `isBeauty`는 `Boolean`(nullable) 전 구간 통일. `deriveMain(List<String>)→String`. record는 마지막 필드 append로 전 콜사이트 정합. Writer VALUES 30개(29+is_beauty).
- **컴파일 원자성:** record 변경(Task 3)이 모든 positional 콜사이트를 한 커밋에서 수정 — 중간 커밋도 빌드 가능.
