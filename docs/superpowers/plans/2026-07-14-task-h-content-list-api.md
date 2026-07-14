# 태스크 H: 랭킹 목록 API (`GET /api/contents`) Implementation Plan

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** celfit-front 랭킹 페이지의 목록 데이터를 서빙한다 — 프론트 URL 파라미터 계약 그대로, 기간=게시일 필터, 지표=end_date 시점 스냅샷, 분석 완료 콘텐츠만, 상위 100개.

**Architecture:** ARCHITECTURE §5 H·§7 2026-07-14 결정 이행. 목록 대상 = `postedAt ∈ 기간` ∧ `content_analyses` 존재 ∧ end_date 시점 스냅샷 존재(LATERAL 최신 1행 — D3와 동일 cutoff). 전부 분석 결과끼리의 조인이라 규율 위반 아님. 필터 값은 verbatim 매칭(§4-4 — 어휘 정합은 생산자 소관, VLM 개통 세션에 위임). VLM 컬럼이 NULL인 동안 카테고리·광고 필터는 매칭 0건으로 동작하고 데이터가 차면 자동으로 살아난다. **유통사 필터만 예외** — 저장 컬럼이 아직 없어 지정 시 빈 목록을 계약·테스트로 고정(컬럼 신설 후 조건 한 줄로 활성화).

**Tech Stack:** 기존 was 스택. 동적 WHERE는 StringBuilder+파라미터 맵(JdbcClient). 날짜 경계는 KST(D3와 동일 패턴).

**작업 위치:** worktree `.worktrees/task-d2` (브랜치 `feat/task-d-post-detail-api` 이어서). 메인 체크아웃 접근 금지.

**파라미터 계약 (프론트 URL 어휘 그대로 — 실측 2026-07-14):**

| param | 값 | 매칭 |
|---|---|---|
| `start_date`·`end_date` | yyyy-MM-dd, **필수**(누락·형식오류 400) | 게시일 `posted_at ∈ [start KST 0시, end+1일 KST 0시)` · 스냅샷 cutoff = end+1일 KST 0시 |
| `content_type` | reels\|feed | `c.content_type =` |
| `follower` | 3k-10k\|10k-30k\|30k-50k | `a.followers ∈ [min, max)` (경계는 프론트 FOLLOWER_RANGES: 3000/10000/30000/50000) |
| `q` | 검색어 | `c.caption ILIKE %q%` |
| `ad_type` | sponsored\|organic | `an.ad_type =` (NULL 미분류는 미매칭) |
| `main_category` | 영문 slug | `an.main_category =` |
| `mid_category`·`sub_category` | 한글 라벨 | `jsonb_exists(an.sub_categories, :값)` — 생산자가 중·소분류 라벨을 모두 배열에 넣는 어휘 계약 |
| `distributor` | 올리브영 등 | **컬럼 부재 — 지정 시 빈 목록** (활성화 지점 주석 고정) |
| `sort` | hype(기본)\|latest\|engagement | hype_score DESC NULLS LAST / posted_at DESC / ER DESC NULLS LAST (전부 2차 정렬 `c.short_code`) |

미지정 파라미터 = 필터 없음. 알 수 없는 enum 값(content_type=xx 등)은 매칭 0건(verbatim — 검증 분기 두지 않음).

**응답 계약:**

```jsonc
{
  "totalCount": 41,            // 필터 결과 총수 (LIMIT 무관)
  "items": [ {                 // 상위 100개
    "shortCode": "…", "thumbnailUrl": "…", "caption": "…",
    "postedAt": "…", "daysSincePosted": 10, "contentType": "reels",
    "account": { "handle": "…", "displayName": "…", "profileImageUrl": "…", "followers": 33325 },
    "views": 3307180, "likes": 42216, "comments": 86,
    "engagementRate": 0.0128, "hypeScore": 3307180,
    "metricsCapturedAt": "2026-07-08T06:05:18Z",
    "adType": "organic",              // an.ad_type — NULL이면 null
    "productCategories": ["아이라이너"],  // detected_product_categories — NULL이면 null
    "brandCount": 4                    // jsonb_array_length(detected_brands) — NULL이면 null
  } ]
}
```

- 지표·경과일·metricsCapturedAt 의미는 D3와 동일(스냅샷 값, end_date 기준). ER=(likes+comments)/views 4자리 HALF_UP(views NULL/0 → null).
- 정렬 engagement의 ER은 SQL에서 `(s.likes+s.comments)::numeric / NULLIF(s.views,0)`.

## File Structure

```
was/src/main/java/com/celfit/was/contentlist/
  ContentListQuery.java        [신규] 파라미터 홀더 record (+정적 팩토리에서 KST 경계 계산)
  ContentListRow.java          [신규] 조인 결과 1행 (was 로컬 record)
  ContentListRepository.java   [신규] 동적 WHERE 목록/카운트 쿼리
  ContentListResponse.java     [신규] totalCount + items
  ContentListAssembler.java    [신규] 행 → 카드 (ER·경과일 계산, jsonb 파싱)
  ContentListController.java   [신규] GET /api/contents
was/src/test/java/com/celfit/was/contentlist/
  ContentListRepositoryTest.java  [신규] Testcontainers — 필터·정렬·as-of 매트릭스
  ContentListAssemblerTest.java   [신규] 단위
  ContentListControllerTest.java  [신규] MockMvc — snake_case 매핑·400
```

기존 postdetail 패키지는 건드리지 않는다 (ER·경과일 헬퍼 2개는 의도적 소규모 중복 — 공용 util 신설은 §4-4 취지상 보류).

**테스트 시드 매트릭스 (Testcontainers, 기대값 근거):**

계정 2: `alpha`(followers 5000) · `beta`(followers 20000)
콘텐츠 5 (posted_at은 KST 정오 = UTC 03:00):

| short_code | 계정 | posted_at(KST) | type | 분석 | 스냅샷(cutoff=07-04 KST 0시 이전) | 비고 |
|---|---|---|---|---|---|---|
| h1 | alpha | 06-28 | reels | ○ main=makeup, subs=["립메이크업","립틴트"], ad=sponsored, prodcat=["립틴트"], brands 2개 | ○ 07-01: v=1000, l=100, c=10, hype=1000 | 기간 내 |
| h2 | beta | 07-01 | reels | ○ main=skincare, subs=["스킨/토너","토너"], ad=organic | ○ 07-03: v=5000, l=50, c=5, hype=5000 | 기간 내, hype 1위 |
| h3 | alpha | 07-02 | feed | ○ (VLM 전부 NULL) | ○ 07-03: v=NULL, l=300, c=30, hype=330 | VLM null·피드 |
| h4 | beta | 07-03 | reels | **분석 없음** | ○ 07-03: v=9999… | 분석 미완 → 제외 |
| h5 | alpha | 07-02 | reels | ○ | **cutoff 이전 스냅샷 없음**(07-05만) | 시점 부재 → 제외 |
| h6 | alpha | 06-20 | reels | ○ | ○ 06-21 | 기간 밖 → 제외 |

기본 조회(start=2026-06-27, end=2026-07-03): **h1·h2·h3만, totalCount=3**, hype 정렬 = h2(5000)·h1(1000)·h3(330).
- ER: h2=(50+5)/5000=0.0110, h1=(100+10)/1000=0.1100, h3=null → engagement 정렬 = h1·h2·h3(null last)
- latest 정렬 = h3(07-02)·h2(07-01)·h1(06-28)
- follower=3k-10k → alpha만(h1·h3) / content_type=feed → h3 / q="토너캡션" → 캡션 시드로 h2만
- ad_type=sponsored → h1 / organic → h2 (h3은 NULL이라 어느 쪽에도 안 나옴)
- main_category=makeup → h1 / sub_category=립틴트 → h1 / mid_category=립메이크업 → h1
- distributor=올리브영 → **빈 목록, totalCount=0**
- daysSincePosted(h1, end=07-03): 06-28 KST 정오 → 07-04 KST 0시 = 5일 12시간 → **5**

---

### Task 1: 쿼리 홀더 + 리포지토리 (TDD)

**Files:**
- Create: `was/src/test/java/com/celfit/was/contentlist/ContentListRepositoryTest.java`
- Create: `was/src/main/java/com/celfit/was/contentlist/ContentListQuery.java`
- Create: `was/src/main/java/com/celfit/was/contentlist/ContentListRow.java`
- Create: `was/src/main/java/com/celfit/was/contentlist/ContentListRepository.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ContentListRepositoryTest.java` — `IntegrationTest` 상속, `@BeforeEach`에서 5테이블 DROP/CREATE(기존 PostDetailRepositoryTest와 동일 DDL: accounts·contents·content_analyses·content_metric_snapshots — content_comments 불필요) 후 위 시드 매트릭스 INSERT. 테스트 12개:

```java
	private ContentListQuery query() {
		return ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, null, null, "hype");
	}
	// ContentListQuery.of(startDate, endDate, mainCategory, midCategory, subCategory,
	//                     contentType, follower, adType, distributor, q는 별도 오버로드 없이 null 허용 …, sort)

	@Test
	void 기간_분석완료_시점스냅샷_교집합만_나온다() {
		List<ContentListRow> rows = repository.findContents(query());
		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h2", "h1", "h3");
		assertThat(repository.countContents(query())).isEqualTo(3);
	}

	@Test
	void hype_정렬은_스냅샷_hype_score_내림차순이다() { /* h2(5000) h1(1000) h3(330) */ }

	@Test
	void latest_정렬은_게시일_내림차순이다() { /* h3 h2 h1 */ }

	@Test
	void engagement_정렬은_스냅샷_ER_내림차순_null_last다() { /* h1(0.11) h2(0.011) h3(null) */ }

	@Test
	void 지표는_cutoff_이전_최신_스냅샷_값이다() { /* h2.views=5000, h2.capturedAt=07-03 시드값 */ }

	@Test
	void follower_구간_필터() { /* 3k-10k → h1 h3 */ }

	@Test
	void content_type_필터() { /* feed → h3 */ }

	@Test
	void 캡션_키워드_필터() { /* q → h2만 (시드 캡션에 키워드 포함) */ }

	@Test
	void ad_type_필터는_미분류를_제외한다() { /* sponsored → h1 / organic → h2 */ }

	@Test
	void 카테고리_필터는_verbatim_매칭이다() { /* main=makeup → h1, sub=립틴트 → h1, mid=립메이크업 → h1 */ }

	@Test
	void distributor_지정_시_빈_목록이다() { /* rows empty, count 0 */ }

	@Test
	void 테이블_부재_시_빈_값으로_저하한다() { /* 전 테이블 DROP → findContents empty, count 0 */ }
```

(각 케이스의 완전한 assert는 시드 매트릭스 기대값 표를 그대로 코드화. `ContentListRow`는 카드 필드 전부: shortCode, thumbnailUrl, caption, postedAt, contentType, handle, displayName, profileImageUrl, followers, views, likes, comments, hypeScore, capturedAt, adType, productCategoriesJson, brandCount)

- [ ] **Step 2: 실행 — 컴파일 실패 확인**

- [ ] **Step 3: 구현**

`ContentListQuery.java` — 파라미터 + KST 경계 파생:

```java
package com.celfit.was.contentlist;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 목록 조회 조건 — 프론트 URL 파라미터(§7 2026-07-14 계약)의 홀더.
 * startInstant/cutoff는 KST 날짜 경계를 UTC 오프셋으로 정규화한 값 (D3와 동일 규칙:
 * 기간 = [start 0시, end 다음날 0시), 스냅샷 cutoff = end 다음날 0시).
 */
public record ContentListQuery(
		OffsetDateTime startInstant, OffsetDateTime cutoff,
		String mainCategory, String midCategory, String subCategory,
		String contentType, FollowerRange follower, String adType,
		String distributor, String q, String sort) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 프론트 follower 구간 값 — 경계는 [min, max) (프론트 FOLLOWER_RANGES와 동일). */
	public enum FollowerRange {
		R3K_10K(3_000, 10_000), R10K_30K(10_000, 30_000), R30K_50K(30_000, 50_000);

		final long min;
		final long max;

		FollowerRange(long min, long max) {
			this.min = min;
			this.max = max;
		}

		/** 프론트 값(3k-10k 등) → 구간. 모르는 값은 null(필터 무시가 아니라 매칭 0을 원하면 호출부에서 처리 불필요 — 프론트 고정 어휘). */
		public static FollowerRange from(String value) {
			return switch (value) {
				case "3k-10k" -> R3K_10K;
				case "10k-30k" -> R10K_30K;
				case "30k-50k" -> R30K_50K;
				default -> null;
			};
		}
	}

	public static ContentListQuery of(LocalDate startDate, LocalDate endDate,
			String mainCategory, String midCategory, String subCategory,
			String contentType, String follower, String adType,
			String distributor, String q, String sort) {
		return new ContentListQuery(
				startDate.atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
				endDate.plusDays(1).atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
				mainCategory, midCategory, subCategory, contentType,
				follower == null ? null : FollowerRange.from(follower),
				adType, distributor, q, sort == null ? "hype" : sort);
	}
}
```

(테스트 편의를 위해 `of`의 10-인자 시그니처를 계획 테스트 코드와 맞출 것 — 테스트의 `query()` 헬퍼는 q 인자를 포함해 11인자로 호출하도록 테스트 작성 시 통일)

`ContentListRow.java` — 위 17개 필드 record (Javadoc: was 로컬 — 조인 결과라 공유 형태 미성립, §4-4).

`ContentListRepository.java`:

```java
package com.celfit.was.contentlist;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 랭킹 목록 조회 — contents ⋈ accounts ⋈ content_analyses(분석 완료만) ⋈ LATERAL 최신 스냅샷.
 * 전부 분석 결과끼리의 조인(§4-4 허용). 필터 값은 verbatim 매칭 — 어휘는 생산자 소유.
 * distributor는 저장 컬럼 신설 전까지 지정 시 매칭 0(아래 주석 지점에서 활성화).
 */
@Repository
public class ContentListRepository {

	private static final Logger log = LoggerFactory.getLogger(ContentListRepository.class);
	private static final int LIMIT = 100;

	private final JdbcClient jdbcClient;

	public ContentListRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<ContentListRow> findContents(ContentListQuery query) {
		return safeQuery(List::of, () -> {
			Sql sql = buildWhere(query);
			return jdbcClient.sql("""
					SELECT c.short_code, c.thumbnail_url, c.caption, c.posted_at, c.content_type,
					       a.handle, a.display_name, a.profile_image_url, a.followers,
					       s.views, s.likes, s.comments, s.hype_score, s.captured_at,
					       an.ad_type,
					       an.detected_product_categories::text AS product_categories_json,
					       jsonb_array_length(an.detected_brands) AS brand_count
					""" + sql.fromWhere + orderBy(query.sort()) + "\nLIMIT " + LIMIT)
					.params(sql.params)
					.query(ContentListRow.class)
					.list();
		});
	}

	public long countContents(ContentListQuery query) {
		return safeQuery(() -> 0L, () -> {
			Sql sql = buildWhere(query);
			return jdbcClient.sql("SELECT count(*)" + sql.fromWhere)
					.params(sql.params)
					.query(Long.class)
					.single();
		});
	}

	private record Sql(String fromWhere, Map<String, Object> params) {
	}

	private Sql buildWhere(ContentListQuery q) {
		StringBuilder sb = new StringBuilder("""

				FROM contents c
				JOIN accounts a ON a.handle = c.account_handle
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN LATERAL (
				  SELECT views, likes, comments, hype_score, captured_at
				  FROM content_metric_snapshots m
				  WHERE m.short_code = c.short_code AND m.captured_at < :cutoff
				  ORDER BY m.captured_at DESC LIMIT 1
				) s ON true
				WHERE c.posted_at >= :startInstant AND c.posted_at < :cutoff
				""");
		Map<String, Object> params = new HashMap<>();
		params.put("startInstant", q.startInstant());
		params.put("cutoff", q.cutoff());
		if (q.contentType() != null) {
			sb.append(" AND c.content_type = :contentType");
			params.put("contentType", q.contentType());
		}
		if (q.follower() != null) {
			sb.append(" AND a.followers >= :followerMin AND a.followers < :followerMax");
			params.put("followerMin", q.follower().min);
			params.put("followerMax", q.follower().max);
		}
		if (q.q() != null && !q.q().isBlank()) {
			sb.append(" AND c.caption ILIKE :caption");
			params.put("caption", "%" + q.q() + "%");
		}
		if (q.adType() != null) {
			sb.append(" AND an.ad_type = :adType");
			params.put("adType", q.adType());
		}
		if (q.mainCategory() != null) {
			sb.append(" AND an.main_category = :mainCategory");
			params.put("mainCategory", q.mainCategory());
		}
		if (q.midCategory() != null) {
			sb.append(" AND jsonb_exists(an.sub_categories, :midCategory)");
			params.put("midCategory", q.midCategory());
		}
		if (q.subCategory() != null) {
			sb.append(" AND jsonb_exists(an.sub_categories, :subCategory)");
			params.put("subCategory", q.subCategory());
		}
		if (q.distributor() != null) {
			// 유통사 저장 컬럼 신설 전 — 지정 시 매칭 0 (신설 후 이 줄을 jsonb_exists(an.detected_distributors, :distributor)로 교체)
			sb.append(" AND false");
		}
		return new Sql(sb.toString(), params);
	}

	private String orderBy(String sort) {
		return switch (sort) {
			case "latest" -> "\nORDER BY c.posted_at DESC, c.short_code";
			case "engagement" ->
				"\nORDER BY (s.likes + s.comments)::numeric / NULLIF(s.views, 0) DESC NULLS LAST, c.short_code";
			default -> "\nORDER BY s.hype_score DESC NULLS LAST, c.short_code";
		};
	}

	private <T> T safeQuery(Supplier<T> fallback, Supplier<T> query) {
		try {
			return query.get();
		} catch (DataAccessException e) {
			log.warn("목록 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
			return fallback.get();
		}
	}
}
```

주의: `jsonb_array_length(an.detected_brands)`는 NULL jsonb에 NULL을 반환한다(정상). JdbcClient `.params(Map)` 사용.

- [ ] **Step 4: 실행 — 12개 PASS 확인**

- [ ] **Step 5: Commit** — `feat(was): 랭킹 목록 조회 — 기간·분석완료·as-of 스냅샷 교집합 + 필터·정렬 (동적 WHERE)`

---

### Task 2: 어셈블러 + 응답 record (TDD)

**Files:**
- Create: `was/src/test/java/com/celfit/was/contentlist/ContentListAssemblerTest.java`
- Create: `was/src/main/java/com/celfit/was/contentlist/ContentListResponse.java`
- Create: `was/src/main/java/com/celfit/was/contentlist/ContentListAssembler.java`

- [ ] **Step 1: 실패하는 테스트 작성** — 3개: (1) 행→카드 전체 필드 매핑(ER 0.1100·경과일 5·productCategories 파싱), (2) VLM null 행은 adType·productCategories·brandCount null, (3) 피드(views null) ER null. reference(경과일 기준)는 `ContentListQuery.cutoff()` 값을 인자로 받는다.

- [ ] **Step 2: 구현**

`ContentListResponse.java` — `record ContentListResponse(long totalCount, List<Item> items)` + `Item`(응답 계약의 카드 필드 그대로, 중첩 `Account`).

`ContentListAssembler.java` — `toResponse(long totalCount, List<ContentListRow> rows, OffsetDateTime reference)`. ER·경과일은 PostDetailAssembler와 동일 시맨틱의 로컬 헬퍼(4자리 HALF_UP, 24h 단위 — 의도적 소규모 중복, Javadoc에 명시). productCategoriesJson은 ObjectMapper로 List<String> 파싱(null 패스스루).

- [ ] **Step 3: 실행 — 3개 PASS → Commit** — `feat(was): 목록 카드 조립 — as-of 지표·VLM null 패스스루`

---

### Task 3: 컨트롤러 (TDD)

**Files:**
- Create: `was/src/test/java/com/celfit/was/contentlist/ContentListControllerTest.java`
- Create: `was/src/main/java/com/celfit/was/contentlist/ContentListController.java`

- [ ] **Step 1: 실패하는 테스트 작성** — `@WebMvcTest(controllers = ContentListController.class, properties = "was.cors.allowed-origins=http://localhost:3000")` + `@Import({ContentListAssembler.class, ClockConfig.class, WebConfig.class})`, `@MockitoBean` 리포지토리 2메서드 스텁. 테스트 4개:
  1. snake_case 파라미터 전체가 ContentListQuery로 매핑돼 리포지토리에 전달된다 (ArgumentCaptor로 startInstant=`2026-06-26T15:00Z`·cutoff=`2026-07-03T15:00Z`·각 필터 값 검증) + jsonPath totalCount·items[0] 필드
  2. start_date 누락 → 400
  3. end_date 형식 오류 → 400
  4. 파라미터 최소(기간만) → 200, 필터 null·sort=hype 기본

- [ ] **Step 2: 구현**

```java
	@GetMapping("/api/contents")
	public ContentListResponse contents(
			@RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(name = "main_category", required = false) String mainCategory,
			@RequestParam(name = "mid_category", required = false) String midCategory,
			@RequestParam(name = "sub_category", required = false) String subCategory,
			@RequestParam(name = "content_type", required = false) String contentType,
			@RequestParam(required = false) String follower,
			@RequestParam(name = "ad_type", required = false) String adType,
			@RequestParam(required = false) String distributor,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String sort) {
		ContentListQuery query = ContentListQuery.of(startDate, endDate, mainCategory, midCategory,
				subCategory, contentType, follower, adType, distributor, q, sort);
		return assembler.toResponse(
				repository.countContents(query), repository.findContents(query), query.cutoff());
	}
```

(필수 파라미터 누락은 Spring 기본 400 — MissingServletRequestParameterException)

- [ ] **Step 3: was 전체 테스트 그린 → Commit** — `feat(was): GET /api/contents 랭킹 목록 API — 프론트 URL 파라미터 계약`

---

### Task 4: E2E (실 analysis DB)

- [ ] `./gradlew test` 전 모듈 그린
- [ ] worktree에서 `:was:bootRun` 후:
  - `GET /api/contents?start_date=2026-05-01&end_date=2026-07-09` → 분석 완료 콘텐츠(현재 2건)가 스냅샷 지표로 나오는지, totalCount 확인
  - `&sort=engagement`·`&follower=...`·`&q=...` 동작 확인
  - `&main_category=skincare` → 0건 (VLM null — 예상 동작), `&distributor=올리브영` → 0건
  - `start_date` 누락 → 400
- [ ] 서버 종료, working tree clean

---

### Task 5: 문서 갱신

- [ ] ARCHITECTURE §5 H 행 ⬜→✅ (서술은 유지 — 이미 계약과 일치)
- [ ] 이 계획 상태 헤더 ✅
- [ ] Commit — `docs: H 완료 반영 (랭킹 목록 API)`

---

## 완료 기준 (DoD)

- 신규 테스트 19개(리포지토리 12·어셈블러 3·컨트롤러 4) + was 전체·전 모듈 그린
- 실DB E2E: 목록이 as-of 지표로 서빙, VLM null 필터는 0건으로 자연 저하, 400 동작
- distributor 활성화 지점이 주석·테스트로 고정

## 다루지 않는 것

- 유통사 컬럼·VLM 어휘 산출(분리 세션 — 칩 발행됨), 페이지네이션(상위 100 고정), 목록형/카드형(프론트 표시 문제), E(인플루언서 API)·G(후보 저장)
