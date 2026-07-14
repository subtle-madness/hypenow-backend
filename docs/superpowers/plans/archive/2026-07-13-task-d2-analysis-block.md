# 태스크 D2: 상세 API에 분석 블록 additive 확장 Implementation Plan

> 상태: ✅ 구현/실행/반영됨 (2026-07-13)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/posts/{shortCode}` 응답에 B2·B3 산출물을 additive로 붙인다 — 댓글별 `aiCategory`(`comment_classifications`) + `analysis` 블록(`content_analyses` 1행). 엔드포인트는 그대로 1회 호출.

**Architecture:** D의 additive 계약을 이행하는 확장. `comment_classifications`·`content_analyses`는 분석 층 소유 테이블(미러 아님)이고 생산자는 부분 타입(Synthesis/VlmResult/Baseline)으로 쓰므로 "동일 형태의 생산자+소비자 쌍"이 성립하지 않는다 → 읽기 record는 **was 로컬**(§4-4 "남의 저장소는 읽기 전용 쿼리 + record 매핑"). 두 테이블 모두 분석 결과라 `content_comments` ⋈ `comment_classifications` SQL LEFT JOIN은 규율 위반 아님(금지는 분석 결과↔서비스 데이터 조인). jsonb는 이스케이프 문자열이 아닌 실 JSON 구조로 파싱해 내려보낸다(해석·분기 없음). 미분석 콘텐츠는 `analysis: null`, 미분류 댓글은 `aiCategory: null`.

**Tech Stack:** Java 21 / Spring Boot 4.1, JdbcClient, Jackson 3(`tools.jackson.*` — Boot 4 기본, ObjectMapper 빈 자동설정), Testcontainers 2.x, MockMvc.

**작업 위치:** worktree `.worktrees/task-d2` (브랜치 `feat/task-d-post-detail-api` 이어서 커밋). 메인 체크아웃은 다른 세션이 사용 중 — 절대 건드리지 않는다.

**소스 스키마 (분석 층 소유 — analytics Flyway V2·V3, 실물 확인 완료):**

- `comment_classifications`: id(=content_comments.id), short_code, **ai_category** ∈ (purchase|question|positive|adAware|friendTag|etc), model, classified_at
- `content_analyses`(short_code PK): analyzed_at, model, ai_content_summary, contents_pattern, ai_comment_insight,
  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
  category_top_percentile, category_avg_views, category_sample_size,
  detected_brands jsonb([{name,evidence}]), sponsored_signal_level(high|mid|low), sponsored_signal_reasons jsonb([str]),
  ad_disclosure, detected_product_categories jsonb([str]), vlm_attributes jsonb([{label,value}]),
  main_category, sub_categories jsonb([str]), ad_type(organic|sponsored),
  comment_authenticity_grade(high|normal|suspect), comment_authenticity_note

**응답 계약 확장 (additive — 기존 필드 불변):**

```jsonc
{
  "post": { /* 기존 그대로 */ },
  "account": { /* 기존 그대로 */ },
  "comments": { "collectedCount": 50, "items": [
    { "id": 1, "authorMasked": "hye***", "body": "…", "likeCount": 342, "aiCategory": "purchase" } ] },
  "analysis": {                                   // content_analyses 없으면 null
    "analyzedAt": "2026-07-12T…Z",
    "aiContentSummary": "…", "contentsPattern": "…", "aiCommentInsight": "…",
    "baseline": { "recentReelsAvgViews": 608899, "rankInRecentReels": 1, "recentReelsCount": 7,
                  "recentContentsCount": 12, "recent12AvgEngagementRate": 0.0380,
                  "recent12AvgLikeCount": 25574, "recent12AvgCommentCount": 3653 },
    "categoryContext": { "topPercentile": 2, "avgViews": 340000, "sampleSize": 880 },
    "content": { "detectedBrands": [{ "name": "Thelavicos", "evidence": "라벨 정면 반복 노출" }],
                 "sponsoredSignalLevel": "high", "sponsoredSignalReasons": ["…"],
                 "adDisclosure": "캡션 #협찬 표기 있음", "detectedProductCategories": ["클렌징"],
                 "attributes": [{ "label": "후킹 요소", "value": "…" }],
                 "mainCategory": "클렌징", "subCategories": ["필링·각질"], "adType": "sponsored" },
    "commentAuthenticity": { "grade": "high", "note": "…" }
  }
}
```

- 분류 어휘·라벨은 생산자 확정값 그대로 전달(§4-4). VLM 미실행 컬럼(NULL)은 null 그대로 — 빈 리스트로 뭉개지 않는다.
- `analysis.content`의 jsonb 5종은 파싱된 JSON 구조. `attributes`는 vlm_attributes의 응답 이름.
- 릴스 개별 바 차트 데이터는 **범위 밖** — 인플루언서 상세(E) 소관으로 확정(2026-07-13 사용자 결정).

## File Structure

```
was/src/main/java/com/celfit/was/postdetail/
  CommentRow.java                 [신규] 댓글+aiCategory LEFT JOIN 행 (was 로컬 record)
  ContentAnalysisRow.java         [신규] content_analyses 1행 (was 로컬 record, jsonb는 String)
  PostDetailRepository.java       [수정] findComments → CommentRow / findAnalysis 추가
  PostDetailResponse.java         [수정] Item.aiCategory + Analysis 블록 추가
  PostDetailAssembler.java        [수정] analysis 조립 + jsonb 파싱(ObjectMapper 주입)
  PostDetailController.java       [수정] findAnalysis 배선
was/src/test/java/com/celfit/was/postdetail/
  PostDetailRepositoryTest.java   [수정] 분류·분석 시드 + 신규 assert
  PostDetailAssemblerTest.java    [수정] 시그니처 갱신 + analysis 조립 테스트
  PostDetailControllerTest.java   [수정] jsonPath 확장
```

**테스트 기대값 근거:** 시드는 위 응답 계약 예시 값을 그대로 사용. 조인 결과 — 분류 있는 댓글은 해당 ai_category, 없는 댓글은 null.

---

### Task 1: 리포지토리 확장 — aiCategory 조인 + 분석 1행 조회 (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/postdetail/CommentRow.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/ContentAnalysisRow.java`
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java`
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java`

- [ ] **Step 1: 실패하는 테스트로 수정**

`PostDetailRepositoryTest.java`에 반영:

(a) `setUpTables()`에 분석 층 테이블 2종 DDL·시드 추가 (기존 DDL·시드 뒤에):

```java
		jdbcTemplate.execute("DROP TABLE IF EXISTS comment_classifications");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("""
				CREATE TABLE comment_classifications (
				    id            bigint PRIMARY KEY,
				    short_code    text   NOT NULL,
				    ai_category   text   NOT NULL,
				    model         text   NOT NULL,
				    classified_at timestamptz NOT NULL DEFAULT now()
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code                   text PRIMARY KEY,
				    analyzed_at                  timestamptz NOT NULL DEFAULT now(),
				    model                        text NOT NULL,
				    ai_content_summary           text,
				    contents_pattern             text,
				    ai_comment_insight           text,
				    recent_reels_avg_views       bigint,
				    rank_in_recent_reels         smallint,
				    recent_reels_count           smallint,
				    recent_contents_count        smallint,
				    recent12_avg_engagement_rate numeric,
				    recent12_avg_like_count      bigint,
				    recent12_avg_comment_count   bigint,
				    category_top_percentile      smallint,
				    category_avg_views           bigint,
				    category_sample_size         bigint,
				    detected_brands              jsonb,
				    sponsored_signal_level       text,
				    sponsored_signal_reasons     jsonb,
				    ad_disclosure                text,
				    detected_product_categories  jsonb,
				    vlm_attributes               jsonb,
				    main_category                text,
				    sub_categories               jsonb,
				    ad_type                      text,
				    comment_authenticity_grade   text,
				    comment_authenticity_note    text
				)""");
		jdbcTemplate.update("""
				INSERT INTO comment_classifications(id, short_code, ai_category, model) VALUES
				 (1, 'mari01', 'purchase', 'test-model'),
				 (3, 'mari01', 'positive', 'test-model')
				""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses(short_code, model, ai_content_summary, contents_pattern,
				  ai_comment_insight, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				  recent_contents_count, recent12_avg_engagement_rate, recent12_avg_like_count,
				  recent12_avg_comment_count, category_top_percentile, category_avg_views,
				  category_sample_size, detected_brands, sponsored_signal_level, sponsored_signal_reasons,
				  ad_disclosure, detected_product_categories, vlm_attributes, main_category,
				  sub_categories, ad_type, comment_authenticity_grade, comment_authenticity_note) VALUES
				 ('mari01', 'test-model', '본인 평균 대비 3.1배 터진 콘텐츠', '실연형에서 터지는 패턴',
				  '구매 전환형 반응', 608899, 1, 7,
				  12, 0.0380, 25574,
				  3653, 2, 340000,
				  880, '[{"name":"Thelavicos","evidence":"라벨 정면 반복 노출"}]'::jsonb, 'high',
				  '["단일 브랜드 반복 클로즈업"]'::jsonb,
				  '캡션 #협찬 표기 있음', '["클렌징"]'::jsonb,
				  '[{"label":"후킹 요소","value":"문제 제기형 자막"}]'::jsonb, '클렌징',
				  '["필링·각질"]'::jsonb, 'sponsored', 'high', '진정성 높음')
				""");
```

(b) 기존 `댓글은_좋아요_내림차순으로_전부_읽는다` 테스트를 `CommentRow` 기준으로 수정 (기대값 유지 + aiCategory 검증 추가):

```java
	@Test
	void 댓글은_좋아요_내림차순으로_전부_읽고_분류를_함께_싣는다() {
		List<CommentRow> comments = repository.findComments("mari01");

		assertThat(comments).hasSize(5);
		assertThat(comments).extracting(CommentRow::likeCount)
				.containsExactly(342L, 289L, 289L, 214L, null);
		// like_count 동률(289)은 id 오름차순: 3번(seo***) → 5번(tie***)
		assertThat(comments).extracting(CommentRow::id)
				.containsExactly(1L, 3L, 5L, 2L, 4L);
		assertThat(comments.getFirst().authorMasked()).isEqualTo("hye***");
		// 분류: id 1=purchase, 3=positive, 나머지는 미분류 null
		assertThat(comments).extracting(CommentRow::aiCategory)
				.containsExactly("purchase", "positive", null, null, null);
	}
```

(임포트에서 `com.celfit.contract.analysis.ContentComment` 제거, `CommentRow`는 같은 패키지)

(c) 신규 테스트 2개 추가:

```java
	@Test
	void shortCode로_분석_1행을_읽는다() {
		Optional<ContentAnalysisRow> found = repository.findAnalysis("mari01");

		assertThat(found).isPresent();
		ContentAnalysisRow row = found.get();
		assertThat(row.aiContentSummary()).isEqualTo("본인 평균 대비 3.1배 터진 콘텐츠");
		assertThat(row.recentReelsAvgViews()).isEqualTo(608899L);
		assertThat(row.rankInRecentReels()).isEqualTo(1);
		assertThat(row.recent12AvgEngagementRate()).isEqualByComparingTo(new BigDecimal("0.0380"));
		assertThat(row.categoryTopPercentile()).isEqualTo(2);
		assertThat(row.detectedBrandsJson()).contains("Thelavicos");
		assertThat(row.vlmAttributesJson()).contains("후킹 요소");
		assertThat(row.adType()).isEqualTo("sponsored");
		assertThat(row.commentAuthenticityGrade()).isEqualTo("high");
		assertThat(row.analyzedAt()).isNotNull();
	}

	@Test
	void 미분석_콘텐츠는_분석이_empty다() {
		assertThat(repository.findAnalysis("mari02")).isEmpty();
	}
```

(임포트 추가: `java.math.BigDecimal`)

(d) 저하 테스트에 분석 테이블 2종 DROP·assert 추가 — 기존 `미러_테이블이_없으면_빈_값으로_저하한다`에:

```java
		jdbcTemplate.execute("DROP TABLE comment_classifications");
		jdbcTemplate.execute("DROP TABLE content_analyses");
		// (기존 assert 유지)
		assertThat(repository.findAnalysis("mari01")).isEmpty();
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailRepositoryTest*'` (worktree 루트에서)
Expected: FAIL — `CommentRow`/`ContentAnalysisRow`/`findAnalysis` 심볼 없음

- [ ] **Step 3: 로컬 record 2종 작성**

`was/src/main/java/com/celfit/was/postdetail/CommentRow.java`:

```java
package com.celfit.was.postdetail;

/**
 * 댓글 + AI 분류 LEFT JOIN 1행 (content_comments ⋈ comment_classifications — 둘 다 분석 결과라 조인 허용).
 * 분석 층 소유 테이블은 생산자와 공유 형태가 성립하지 않아 was 로컬 record다(§4-4). 미분류면 aiCategory null.
 */
public record CommentRow(Long id, String authorMasked, String body, Long likeCount, String aiCategory) {
}
```

`was/src/main/java/com/celfit/was/postdetail/ContentAnalysisRow.java`:

```java
package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * content_analyses 1행 (분석 층 소유 — was 로컬 읽기 record, §4-4).
 * jsonb 컬럼은 ::text로 받아 어셈블러가 JSON 구조로 파싱한다. VLM 미실행 컬럼은 null 그대로.
 */
public record ContentAnalysisRow(
		OffsetDateTime analyzedAt,
		String aiContentSummary,
		String contentsPattern,
		String aiCommentInsight,
		Long recentReelsAvgViews,
		Integer rankInRecentReels,
		Integer recentReelsCount,
		Integer recentContentsCount,
		BigDecimal recent12AvgEngagementRate,
		Long recent12AvgLikeCount,
		Long recent12AvgCommentCount,
		Integer categoryTopPercentile,
		Long categoryAvgViews,
		Long categorySampleSize,
		String detectedBrandsJson,
		String sponsoredSignalLevel,
		String sponsoredSignalReasonsJson,
		String adDisclosure,
		String detectedProductCategoriesJson,
		String vlmAttributesJson,
		String mainCategory,
		String subCategoriesJson,
		String adType,
		String commentAuthenticityGrade,
		String commentAuthenticityNote) {
}
```

- [ ] **Step 4: Repository 수정**

`PostDetailRepository.java` — `findComments` 반환 타입·SQL 교체, `findAnalysis` 추가, `ContentComment` 임포트 제거:

```java
	public List<CommentRow> findComments(String shortCode) {
		return safeQuery("content_comments", List::of, () -> jdbcClient.sql("""
				SELECT m.id, m.author_masked, m.body, m.like_count, k.ai_category
				FROM content_comments m
				LEFT JOIN comment_classifications k ON k.id = m.id
				WHERE m.short_code = :shortCode
				ORDER BY m.like_count DESC NULLS LAST, m.id
				""")
				.param("shortCode", shortCode)
				.query(CommentRow.class)
				.list());
	}

	/** content_analyses 1행 — 분석 전이면 empty (응답의 analysis 블록이 null이 된다). */
	public Optional<ContentAnalysisRow> findAnalysis(String shortCode) {
		return safeQuery("content_analyses", Optional::empty, () -> jdbcClient.sql("""
				SELECT analyzed_at, ai_content_summary, contents_pattern, ai_comment_insight,
				       recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate, recent12_avg_like_count,
				       recent12_avg_comment_count, category_top_percentile, category_avg_views,
				       category_sample_size,
				       detected_brands::text              AS detected_brands_json,
				       sponsored_signal_level,
				       sponsored_signal_reasons::text     AS sponsored_signal_reasons_json,
				       ad_disclosure,
				       detected_product_categories::text  AS detected_product_categories_json,
				       vlm_attributes::text               AS vlm_attributes_json,
				       main_category,
				       sub_categories::text               AS sub_categories_json,
				       ad_type, comment_authenticity_grade, comment_authenticity_note
				FROM content_analyses
				WHERE short_code = :shortCode
				""")
				.param("shortCode", shortCode)
				.query(ContentAnalysisRow.class)
				.optional());
	}
```

주의: `comment_classifications` 테이블이 없을 때 LEFT JOIN 쿼리 전체가 실패한다 — `safeQuery`가 잡아 빈 목록으로 저하되므로 기존 계약(테이블 부재 → 404 경로)이 유지된다. 단 B2 이후엔 두 테이블이 함께 배포되므로 실운영 문제 없음.

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailRepositoryTest*'`
Expected: 8 tests PASS (기존 6 중 댓글 테스트 1개 개명 + 신규 2)

이 시점에 Assembler·Controller가 `ContentComment`를 참조해 컴파일 실패할 수 있다 — 그 경우 Task 2·3에서 고치므로 **리포지토리 테스트만** 컴파일되도록 Assembler 시그니처를 임시로 바꾸지 말고, Task 2를 같은 커밋 전에 이어서 진행한 뒤 한 번에 검증해도 된다(아래 Task 2 완료 후 커밋).

- [ ] **Step 6: Commit은 Task 2와 함께** (컴파일 단위가 얽혀 있으므로 Task 2 완료 후 한 커밋)

---

### Task 2: 응답 record·어셈블러 확장 (TDD)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`

- [ ] **Step 1: 실패하는 테스트로 수정**

`PostDetailAssemblerTest.java` 전면 반영:

(a) 어셈블러 생성이 ObjectMapper를 받는다:

```java
	private final PostDetailAssembler assembler =
			new PostDetailAssembler(JsonMapper.builder().build(), fixedClock);
```

(임포트 추가: `tools.jackson.databind.json.JsonMapper` — 컴파일 에러 시 `tools.jackson.*` 하위에서 컴파일러 제안으로 확정, 클래스명 JsonMapper 유지)

(b) 기존 테스트의 댓글 타입을 `CommentRow`로 교체 (`ContentComment` 임포트 제거):

```java
		List<CommentRow> comments = List.of(
				new CommentRow(1L, "hye***", "이거 어디서 살 수 있어요??", 342L, "purchase"),
				new CommentRow(3L, "seo***", "언니 피부 미쳤다", 289L, null));
```

기존 4개 테스트의 `assembler.toResponse(...)` 호출은 4번째 인자로 `Optional.empty()`를 받는다 (예: `assembler.toResponse(reels(), account(), comments, Optional.empty())`). 기존 assert에 추가:

```java
		assertThat(response.comments().items().getFirst().aiCategory()).isEqualTo("purchase");
		assertThat(response.comments().items().get(1).aiCategory()).isNull();
		assertThat(response.analysis()).isNull();
```

(`analysis null` assert는 첫 테스트에만. `Optional` 임포트 추가)

(c) 신규 테스트 추가:

```java
	private ContentAnalysisRow analysisRow() {
		return new ContentAnalysisRow(
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				"본인 평균 대비 3.1배 터진 콘텐츠", "실연형에서 터지는 패턴", "구매 전환형 반응",
				608899L, 1, 7, 12, new BigDecimal("0.0380"), 25574L, 3653L,
				2, 340000L, 880L,
				"[{\"name\":\"Thelavicos\",\"evidence\":\"라벨 정면 반복 노출\"}]", "high",
				"[\"단일 브랜드 반복 클로즈업\"]", "캡션 #협찬 표기 있음", "[\"클렌징\"]",
				"[{\"label\":\"후킹 요소\",\"value\":\"문제 제기형 자막\"}]", "클렌징",
				"[\"필링·각질\"]", "sponsored", "high", "진정성 높음");
	}

	@Test
	void 분석_1행을_analysis_블록으로_조립한다() {
		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.of(analysisRow()));

		PostDetailResponse.Analysis analysis = response.analysis();
		assertThat(analysis.aiContentSummary()).isEqualTo("본인 평균 대비 3.1배 터진 콘텐츠");
		assertThat(analysis.baseline().recentReelsAvgViews()).isEqualTo(608899L);
		assertThat(analysis.baseline().rankInRecentReels()).isEqualTo(1);
		assertThat(analysis.baseline().recent12AvgEngagementRate())
				.isEqualByComparingTo(new BigDecimal("0.0380"));
		assertThat(analysis.categoryContext().topPercentile()).isEqualTo(2);
		assertThat(analysis.categoryContext().avgViews()).isEqualTo(340000L);
		assertThat(analysis.content().detectedBrands()).hasSize(1);
		assertThat(analysis.content().detectedBrands().getFirst().name()).isEqualTo("Thelavicos");
		assertThat(analysis.content().detectedBrands().getFirst().evidence()).isEqualTo("라벨 정면 반복 노출");
		assertThat(analysis.content().sponsoredSignalReasons()).containsExactly("단일 브랜드 반복 클로즈업");
		assertThat(analysis.content().attributes().getFirst().label()).isEqualTo("후킹 요소");
		assertThat(analysis.content().subCategories()).containsExactly("필링·각질");
		assertThat(analysis.content().adType()).isEqualTo("sponsored");
		assertThat(analysis.commentAuthenticity().grade()).isEqualTo("high");
	}

	@Test
	void VLM_미실행이면_jsonb_필드가_null_그대로다() {
		ContentAnalysisRow vlmSkipped = new ContentAnalysisRow(
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				"요약", null, null,
				608899L, 1, 7, 12, new BigDecimal("0.0380"), 25574L, 3653L,
				2, 340000L, 880L,
				null, null, null, null, null, null, null, null, null, null, null);

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.of(vlmSkipped));

		PostDetailResponse.Analysis.Content content = response.analysis().content();
		assertThat(content.detectedBrands()).isNull();
		assertThat(content.sponsoredSignalReasons()).isNull();
		assertThat(content.attributes()).isNull();
		assertThat(content.subCategories()).isNull();
		assertThat(content.adType()).isNull();
	}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: FAIL — `Analysis` 심볼 없음 / 시그니처 불일치

- [ ] **Step 3: PostDetailResponse 확장**

`PostDetailResponse.java` — 최상위를 `PostDetailResponse(Post post, Account account, Comments comments, Analysis analysis)`로 바꾸고, `Comments.Item`에 aiCategory 추가, `Analysis` 블록 신설. 클래스 Javadoc의 "LLM 산출 블록은 필드 자체가 없고 …" 문장을 "LLM 산출 블록: 댓글 aiCategory(B2)·analysis(B3) — 미분석이면 null"로 갱신:

```java
	/** 미분류 댓글은 aiCategory null. 어휘(purchase|question|positive|adAware|friendTag|etc)는 분석 층이 확정. */
	public record Comments(int collectedCount, List<Item> items) {

		public record Item(Long id, String authorMasked, String body, Long likeCount, String aiCategory) {
		}
	}

	/** content_analyses 1행 (분석 시점 고정 스냅샷) — 미분석 콘텐츠면 블록 전체가 null. */
	public record Analysis(
			OffsetDateTime analyzedAt,
			String aiContentSummary,
			String contentsPattern,
			String aiCommentInsight,
			Baseline baseline,
			CategoryContext categoryContext,
			Content content,
			CommentAuthenticity commentAuthenticity) {

		/** 분석 시점의 계정 기준선 — 최신 미러와 다를 수 있다(의도: AI 텍스트가 참조한 수치와 동일 시점). */
		public record Baseline(
				Long recentReelsAvgViews,
				Integer rankInRecentReels,
				Integer recentReelsCount,
				Integer recentContentsCount,
				BigDecimal recent12AvgEngagementRate,
				Long recent12AvgLikeCount,
				Long recent12AvgCommentCount) {
		}

		public record CategoryContext(Integer topPercentile, Long avgViews, Long sampleSize) {
		}

		/** VLM 산출 — 미실행 항목은 null 그대로(빈 리스트로 뭉개지 않음). */
		public record Content(
				List<Brand> detectedBrands,
				String sponsoredSignalLevel,
				List<String> sponsoredSignalReasons,
				String adDisclosure,
				List<String> detectedProductCategories,
				List<Attribute> attributes,
				String mainCategory,
				List<String> subCategories,
				String adType) {

			public record Brand(String name, String evidence) {
			}

			public record Attribute(String label, String value) {
			}
		}

		public record CommentAuthenticity(String grade, String note) {
		}
	}
```

- [ ] **Step 4: PostDetailAssembler 확장**

시그니처: `toResponse(Content content, Account account, List<CommentRow> comments, Optional<ContentAnalysisRow> analysis)`. ObjectMapper 주입(`tools.jackson.databind.ObjectMapper`). 핵심 조각:

```java
	public PostDetailResponse toResponse(Content content, Account account,
			List<CommentRow> comments, Optional<ContentAnalysisRow> analysis) {
		return new PostDetailResponse(
				/* post 블록 기존 그대로 */,
				/* account 블록 기존 그대로 */,
				new PostDetailResponse.Comments(
						comments.size(),
						comments.stream()
								.map(c -> new PostDetailResponse.Comments.Item(
										c.id(), c.authorMasked(), c.body(), c.likeCount(), c.aiCategory()))
								.toList()),
				analysis.map(this::toAnalysis).orElse(null));
	}

	private PostDetailResponse.Analysis toAnalysis(ContentAnalysisRow row) {
		return new PostDetailResponse.Analysis(
				row.analyzedAt(),
				row.aiContentSummary(), row.contentsPattern(), row.aiCommentInsight(),
				new PostDetailResponse.Analysis.Baseline(
						row.recentReelsAvgViews(), row.rankInRecentReels(), row.recentReelsCount(),
						row.recentContentsCount(), row.recent12AvgEngagementRate(),
						row.recent12AvgLikeCount(), row.recent12AvgCommentCount()),
				new PostDetailResponse.Analysis.CategoryContext(
						row.categoryTopPercentile(), row.categoryAvgViews(), row.categorySampleSize()),
				new PostDetailResponse.Analysis.Content(
						parse(row.detectedBrandsJson(), BRAND_LIST),
						row.sponsoredSignalLevel(),
						parse(row.sponsoredSignalReasonsJson(), STRING_LIST),
						row.adDisclosure(),
						parse(row.detectedProductCategoriesJson(), STRING_LIST),
						parse(row.vlmAttributesJson(), ATTRIBUTE_LIST),
						row.mainCategory(),
						parse(row.subCategoriesJson(), STRING_LIST),
						row.adType()),
				new PostDetailResponse.Analysis.CommentAuthenticity(
						row.commentAuthenticityGrade(), row.commentAuthenticityNote()));
	}

	/** jsonb 원문을 응답 구조로 — null(VLM 미실행)은 null 그대로 전달. */
	private <T> T parse(String json, TypeReference<T> type) {
		if (json == null) {
			return null;
		}
		return objectMapper.readValue(json, type);
	}
```

TypeReference 상수 3종(클래스 필드):

```java
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<List<PostDetailResponse.Analysis.Content.Brand>> BRAND_LIST =
			new TypeReference<>() {
	};
	private static final TypeReference<List<PostDetailResponse.Analysis.Content.Attribute>> ATTRIBUTE_LIST =
			new TypeReference<>() {
	};
```

(임포트: `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.type.TypeReference`, `java.util.Optional` — Jackson 3 임포트가 컴파일 에러를 내면 `tools.jackson.*` 하위에서 컴파일러 제안으로 확정. Jackson 3의 readValue는 unchecked 예외라 try/catch 불필요)

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: 7 tests PASS (기존 5 + 신규 2). 이 시점에 Controller 컴파일 실패가 남아 있으면 Task 3에서 처리 — `:was:test`가 Controller 때문에 전체 컴파일 실패한다면 Task 3 Step 3까지 이어서 진행 후 한 번에 검증.

- [ ] **Step 6: Commit (Task 1+2 묶음)**

```bash
git add was/src/main/java/com/celfit/was/postdetail/ was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java
git commit -m "feat(was): 분석 결과 조회·조립 — 댓글 aiCategory 조인 + analysis 블록 (B2·B3 additive)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

(Controller 수정이 이 커밋에 필요하면 Task 3을 먼저 완료하고 함께 커밋 — 커밋은 항상 컴파일·테스트 그린 상태에서)

---

### Task 3: 컨트롤러 배선 + 응답 검증 (TDD)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`

- [ ] **Step 1: 실패하는 테스트로 수정**

`PostDetailControllerTest.java`:

(a) `givenMari01()`의 댓글 스텁을 `CommentRow`로 교체하고 분석 스텁 추가 (`ContentComment` 임포트 제거, `ContentAnalysisRow`·`Optional` 활용):

```java
		given(repository.findComments("mari01")).willReturn(List.of(
				new CommentRow(1L, "hye***", "이거 어디서 살 수 있어요??", 342L, "purchase"),
				new CommentRow(3L, "seo***", "언니 피부 미쳤다", 289L, null)));
		given(repository.findAnalysis("mari01")).willReturn(Optional.of(new ContentAnalysisRow(
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				"본인 평균 대비 3.1배 터진 콘텐츠", "실연형에서 터지는 패턴", "구매 전환형 반응",
				608899L, 1, 7, 12, new BigDecimal("0.0380"), 25574L, 3653L,
				2, 340000L, 880L,
				"[{\"name\":\"Thelavicos\",\"evidence\":\"라벨 정면 반복 노출\"}]", "high",
				"[\"단일 브랜드 반복 클로즈업\"]", "캡션 #협찬 표기 있음", "[\"클렌징\"]",
				"[{\"label\":\"후킹 요소\",\"value\":\"문제 제기형 자막\"}]", "클렌징",
				"[\"필링·각질\"]", "sponsored", "high", "진정성 높음")));
```

(b) 첫 테스트에 jsonPath 추가:

```java
				.andExpect(jsonPath("$.comments.items[0].aiCategory").value("purchase"))
				.andExpect(jsonPath("$.comments.items[1].aiCategory").doesNotExist())
				.andExpect(jsonPath("$.analysis.aiContentSummary").value("본인 평균 대비 3.1배 터진 콘텐츠"))
				.andExpect(jsonPath("$.analysis.baseline.recentReelsAvgViews").value(608899))
				.andExpect(jsonPath("$.analysis.categoryContext.topPercentile").value(2))
				.andExpect(jsonPath("$.analysis.content.detectedBrands[0].name").value("Thelavicos"))
				.andExpect(jsonPath("$.analysis.content.adType").value("sponsored"))
				.andExpect(jsonPath("$.analysis.commentAuthenticity.grade").value("high"))
```

> `items[1].aiCategory`는 null이라 Jackson 기본 직렬화에서 `"aiCategory":null`로 존재한다 — `doesNotExist()`가 실패하면 `.value(nullValue())`(org.hamcrest.Matchers.nullValue, `jsonPath("$...", nullValue())` 형태)로 바꿔 검증한다. 의미는 "null로 내려간다"의 고정.

(c) 미분석 케이스 테스트 추가:

```java
	@Test
	void 미분석_콘텐츠는_analysis가_null이다() throws Exception {
		givenMari01();
		given(repository.findAnalysis("mari01")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/posts/mari01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.analysis").value(org.hamcrest.Matchers.nullValue()));
	}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailControllerTest*'`
Expected: FAIL (컴파일 또는 assert)

- [ ] **Step 3: 컨트롤러 수정**

`PostDetailController.java`의 매핑 메서드:

```java
	@GetMapping("/api/posts/{shortCode}")
	public PostDetailResponse postDetail(@PathVariable String shortCode) {
		return repository.findContent(shortCode)
				.map(content -> assembler.toResponse(
						content,
						repository.findAccount(content.accountHandle()).orElse(null),
						repository.findComments(shortCode),
						repository.findAnalysis(shortCode)))
				// reason은 서버 로그용 — Boot 4 기본 에러 응답 본문에는 실리지 않는다 (프론트 계약은 상태코드 404).
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode));
	}
```

- [ ] **Step 4: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL — Repository 7 · Assembler 7 · Controller 5 전부 PASS

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/postdetail/PostDetailController.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java
git commit -m "feat(was): 상세 API에 analysis 블록·댓글 aiCategory 배선 — 1회 호출로 모달 완성

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: E2E 검증 (실 analysis DB)

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 테스트** — `./gradlew test` (worktree 루트) → BUILD SUCCESSFUL

- [ ] **Step 2: 실DB 상태 확인 + 서빙 검증**

```bash
docker start crawler-postgres-1
docker exec -i crawler-postgres-1 psql -U crawler -d analysis -tAc \
  "SELECT count(*) FROM content_analyses; SELECT count(*) FROM comment_classifications;"
./gradlew :was:bootRun &   # worktree 루트에서 (8081)
```

분석 행이 있으면: 해당 short_code로 curl → `analysis` 블록·`aiCategory` 채워짐 확인.
분석 행이 없으면: 아무 short_code로 curl → `"analysis": null` + 댓글 `aiCategory: null` (additive null 경로) 확인.
공통: 404·기존 필드 불변 확인.

- [ ] **Step 3: 서버 종료 + working tree clean 확인**

---

### Task 5: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§5 D 행 내용, §7 결정 기록)
- Modify: `docs/superpowers/plans/2026-07-13-task-d2-analysis-block.md` (상태 헤더)

- [ ] **Step 1: §5 D 행** — "비LLM 블록(post/account/comments) 서빙. B2·B3 산출물의 additive 확장은 후속" → "post/account/comments + analysis 블록·댓글 aiCategory (B2·B3 산출물 포함, 1회 호출)"

- [ ] **Step 2: §7 결정 기록 맨 위에 추가**

```markdown
| 2026-07-13 | 태스크 D2: 상세 API에 B2·B3 산출물 additive 확장 — comments.items[].aiCategory(LEFT JOIN, 미분류 null) + analysis 블록(content_analyses 1행, 미분석 null). 읽기 record는 was 로컬(분석 층 소유 테이블은 공유 형태 미성립 — §4-4), jsonb는 실 JSON 구조로 서빙. 릴스 개별 바 차트는 인플루언서 상세(E) 소관 | [plans/2026-07-13-task-d2-analysis-block.md](docs/superpowers/plans/2026-07-13-task-d2-analysis-block.md) |
```

- [ ] **Step 3: 이 계획 상태 헤더** — `🟢 활성` → `✅ 구현/실행/반영됨 (2026-07-13)`

- [ ] **Step 4: Commit** — `docs: D2 완료 반영 (analysis 블록·aiCategory 계약)`

---

## 완료 기준 (DoD)

- was 테스트 20개(리포지토리 8·어셈블러 7·컨트롤러 5) + 전 모듈 그린
- 실DB에서 1회 호출 응답에 analysis 블록/aiCategory(또는 additive null 경로) 확인, 기존 필드 불변
- 계약 문서화: 이 계획의 응답 계약 + ARCHITECTURE 결정 기록

## 다루지 않는 것

- 릴스 개별 바 차트 데이터 (E 소관 — 사용자 확정), 랭킹 목록 API, C1·C2·E·G
- analytics 모듈 변경 일절 없음 (다른 세션 작업 중)

## Self-Review 체크 결과 (작성 시 수행)

- **스펙 커버리지**: aiCategory 조인 ✅(T1) · analysis 블록 ✅(T1~3) · jsonb 실 구조 파싱 ✅(T2) · null 시맨틱(미분석/미분류/VLM 미실행) ✅(T1~3 테스트) · 1회 호출 유지 ✅(T3) · E2E ✅(T4) · 문서 ✅(T5)
- **플레이스홀더 없음**: 코드 블록 전부 완성본. Jackson 3 임포트·jsonPath null 검증만 컴파일러/러너 확정 단서 명시.
- **타입 일관성**: ContentAnalysisRow 25개 컴포넌트 = findAnalysis SELECT 25개 컬럼(순서 일치, jsonb 5종은 `*_json` 별칭) = V3 DDL 대조 완료. CommentRow 5개 = 조인 SELECT 5개. toResponse 시그니처가 T2 테스트·T3 컨트롤러와 일치.
