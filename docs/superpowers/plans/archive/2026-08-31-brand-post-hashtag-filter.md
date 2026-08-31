# 브랜드 게시물 해시태그 필터 구현 계획

> 상태: ✅ 구현됨 · 2026-08-31 작성·동일 브랜치에서 구현 완료
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 모니터링 게시물 캡션에서 해시태그를 추출해 게시물별 태그 배열 + 브랜드 스코프 태그 facet을 내려주고, 태그 필터 파라미터로 수집된 게시물 내 필터를 제공한다.

**Architecture:** 조회 시 추출(A안, [spec](../../specs/2026-08-31-brand-post-hashtag-filter-design.md)). 인덱스 SQL(`findBrandPostIndex`)에 `m.caption`을 추가 프로젝션하고 `indexForBrand()`에서 Java 정규식으로 태그만 뽑아 `PostRef`에 싣는다(캡션은 버림 — ref는 계속 경량). 필터·facet은 기존 in-memory 필터/FacetAxis 패턴에 5번째 축으로 편입. DB 스키마·monitoring 모듈 무변경.

**Tech Stack:** Java 21, Spring Boot 4.1 (was 모듈만), JUnit 5 + AssertJ. Task 1~3은 순수 유닛 테스트라 Testcontainers 불요. Task 4 회귀만 colima + `DOCKER_HOST` 필요.

**실행 환경 준비 (Task 4에서만 필요):**
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```
colima 미기동이면 `colima start --cpu 8 --memory 12` (CLAUDE.md 참조).

**브랜치:** `feat/brand-post-hashtag-filter` (origin/develop 기준 신규). spec/plan이 있는 `docs/brand-post-hashtag-filter-spec` 브랜치는 PR 시 선머지 또는 같은 PR에 포함.

---

## 전체 파일 지도

| 파일 | 작업 |
|---|---|
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtags.java` | 신규 — 추출·정규화 순수 유틸 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagFacets.java` | 신규 — facet 집계·필터 판정 순수 유틸 |
| `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` | 인덱스 SQL에 caption 추가, `BrandPostIndexRow`에 필드 추가 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` | `PostRef`에 hashtags 추가, 생성 2지점 수정, `brandPost()`에 응답 필드 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java` | `hashtags` 필드 추가 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` | `hashtag` 파라미터, `PostFilters`·`FacetAxis`·`applyFilters`·`facets()` 확장 |
| `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtagsTest.java` | 신규 |
| `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagFacetsTest.java` | 신규 |
| `DECISIONS.md` | 결정 1줄 추가(spec 포인터) |

주의: `BrandPostIndexRow`·`PostRef`·`BrandPostResponse`는 record라 필드 추가 시 기존 생성자 호출부가 전부 컴파일 에러로 드러난다 — 테스트 포함 컴파일 에러를 따라가며 수정하는 것이 각 태스크의 일부다(놓침 방지 장치이지 사고가 아님).

---

### Task 1: `BrandCaptionHashtags` 추출 유틸 (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtags.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtagsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

기존 `BrandHashtagTagsTest`와 같은 관례(한국어 메서드명, AssertJ, Spring 컨텍스트 없음)를 따른다.

```java
package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 캡션 해시태그 추출 규칙(스펙 2026-08-31 §3) — 인스타 링크화(#[\p{L}\p{N}_]+)와의 정합이 계약이다.
 */
class BrandCaptionHashtagsTest {

	@Test
	void 한글_영문_숫자_언더스코어_태그를_등장_순서대로_추출한다() {
		assertThat(BrandCaptionHashtags.extract("올영세일 시작! #올영세일 #OliveYoung #2026_pick 갑니다"))
				.containsExactly("올영세일", "OliveYoung", "2026_pick");
	}

	@Test
	void 정규화_키가_같은_태그는_첫_등장_표기_하나로_dedup한다() {
		assertThat(BrandCaptionHashtags.extract("#OliveYoung #oliveyoung #OLIVEYOUNG"))
				.containsExactly("OliveYoung");
	}

	@Test
	void 숫자만인_태그도_추출한다() {
		// 인스타는 숫자-only 태그도 링크화한다(스펙 검증 항목).
		assertThat(BrandCaptionHashtags.extract("새해 #2026 목표")).containsExactly("2026");
	}

	@Test
	void 캡션_중간에_붙은_태그도_추출한다() {
		// 인스타 파싱은 # 앞 문자를 제약하지 않는다 — "가나다#세일"의 #세일도 링크화된다.
		assertThat(BrandCaptionHashtags.extract("가나다#세일")).containsExactly("세일");
	}

	@Test
	void 연속_샵은_뒤의_유효_태그만_잡는다() {
		assertThat(BrandCaptionHashtags.extract("##세일")).containsExactly("세일");
	}

	@Test
	void 구두점과_공백에서_태그가_끝난다() {
		assertThat(BrandCaptionHashtags.extract("#세일! 그리고 #할인, 끝")).containsExactly("세일", "할인");
	}

	@Test
	void 전각_샵은_추출하지_않는다() {
		// 인스타가 전각 ＃(U+FF03)를 링크화하지 않음(스펙 검증 항목). 광고 표기 판정이 ＃를
		// 포함하는 것과 다른 이유는 목적이 달라서다 — 스펙 §3.
		assertThat(BrandCaptionHashtags.extract("＃세일 이벤트")).isEmpty();
	}

	@Test
	void 이모지는_태그를_끊는다() {
		// 알려진 갭(수용): 인스타는 #세일❤️을 한 태그로 링크화하지만 우리는 #세일로 자른다.
		assertThat(BrandCaptionHashtags.extract("#세일❤️ #❤️")).containsExactly("세일");
	}

	@Test
	void null과_빈_캡션은_빈_목록이다() {
		assertThat(BrandCaptionHashtags.extract(null)).isEmpty();
		assertThat(BrandCaptionHashtags.extract("")).isEmpty();
		assertThat(BrandCaptionHashtags.extract("태그 없는 캡션")).isEmpty();
	}

	@Test
	void 정규화는_ROOT_로케일_소문자다() {
		assertThat(BrandCaptionHashtags.normalize("OliveYoung")).isEqualTo("oliveyoung");
		assertThat(BrandCaptionHashtags.normalize("세일")).isEqualTo("세일");
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock   # 컴파일만이라 없어도 되지만 관례상
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandCaptionHashtagsTest"
```
Expected: 컴파일 실패 — `BrandCaptionHashtags` 심볼 없음.

- [ ] **Step 3: 최소 구현**

```java
package com.celfit.was.v1.brandmonitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 캡션 해시태그 추출(스펙 2026-08-31 §3). 규칙은 ASCII # + [\p{L}\p{N}_]+ — 인스타 링크화와
 * 일치가 계약이다(전각 ＃ 제외·이모지 갭 수용, 검증 근거는 스펙). 문자 집합은
 * {@link BrandHashtagTags}의 VALID_TAG와 동일 정의를 유지할 것.
 */
public final class BrandCaptionHashtags {

	private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

	private BrandCaptionHashtags() {
	}

	/** 등장 순서 유지, 정규화 키 기준 dedup(값은 첫 등장 원문 표기). */
	public static List<String> extract(String caption) {
		if (caption == null || caption.isEmpty()) {
			return List.of();
		}
		LinkedHashMap<String, String> firstByKey = new LinkedHashMap<>();
		Matcher m = HASHTAG.matcher(caption);
		while (m.find()) {
			firstByKey.putIfAbsent(normalize(m.group(1)), m.group(1));
		}
		return List.copyOf(firstByKey.values());
	}

	/** 집계·필터 키 — 인스타 태그는 대소문자 무시(#OliveYoung = #oliveyoung). */
	public static String normalize(String tag) {
		return tag.toLowerCase(Locale.ROOT);
	}
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandCaptionHashtagsTest"
```
Expected: BUILD SUCCESSFUL, 10개 전부 PASS.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtags.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtagsTest.java
git commit -m "feat(was): 캡션 해시태그 추출 유틸 — 인스타 링크화 규칙 정합"
```

---

### Task 2: 인덱스·응답에 태그 싣기

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java:148-172` (SQL), `:601-606` (record)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java:134-137` (PostRef), `:213`·`:230` 부근 (생성 2지점), `:610` 부근 (`brandPost()`)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java:21-59`
- Modify(컴파일 유도): `BrandPostAssemblerTest`·`BrandIndexCacheTest`·`BrandCollectionCapTest`·`BrandInfluencerAggregatorTest` 등 record 생성자 호출부

- [ ] **Step 1: 인덱스 SQL에 caption 프로젝션 추가**

`findBrandPostIndex`의 SELECT에서 `caption_marker` 줄 다음에 추가:

```sql
       m.caption,
```

(줄 155 `(m.caption IS NOT NULL AND lower(m.caption) ~ :markerRegex) AS caption_marker,` 뒤.)
LEFT JOIN이라 meta 없는 행은 caption null — Java 쪽 null 허용 필수.

- [ ] **Step 2: `BrandPostIndexRow`에 필드 추가**

record 끝에 `String caption` 추가 (JdbcClient는 이름 매핑이라 위치 무관하지만 끝이 관례):

```java
public record BrandPostIndexRow(String shortCode, OffsetDateTime takenAt, OffsetDateTime tagDetectedAt,
		OffsetDateTime directRegisteredAt, OffsetDateTime unavailableAt, String rawAuthorUsername,
		String authorIgUserId, Boolean isPaidPartnership, boolean captionMarker, String contentType,
		String adVerdict, String authorUsername, String authorFullName, String authorProfilePicUrl,
		String authorImageObjectPath, Long authorFollowers, String caption) {
}
```

- [ ] **Step 3: `PostRef`에 hashtags 필드 추가**

```java
	public record PostRef(String shortcode, String source, String sponsorship, LocalDate uploadedOn,
			Long latestViews, String contentType, String adVerdict, String authorUsername,
			String authorFullName, String authorProfilePicUrl, Long authorFollowers, String takenAtKst,
			List<String> hashtags) {
	}
```

javadoc에 한 줄 추가: `@param hashtags 캡션 추출 태그(등장 순, 정규화 키 dedup — BrandCaptionHashtags). 캡션 자체는 ref에 싣지 않는다(경량 유지).`

- [ ] **Step 4: 생성 2지점 수정**

`indexForBrand()`의 풀 행 → ref (줄 213 부근): 기존 마지막 인자 뒤에
`BrandCaptionHashtags.extract(row.caption())` 추가.

레거시 카드 → ref (줄 230 부근): 마지막 인자 뒤에
`BrandCaptionHashtags.extract(legacy.caption())` 추가 (레거시 `BrandPostResponse`는 caption 필드 보유).

- [ ] **Step 5: `BrandPostResponse`에 `hashtags` 필드 추가 + `brandPost()` 채움**

record의 `adEvidence` 필드 뒤(= `seededAuthor` 앞)에 `List<String> hashtags` 추가. `brandPost()`(줄 610 부근)의 해당 위치 인자로:

```java
meta == null ? List.of() : BrandCaptionHashtags.extract(meta.caption()),
```

`brandPost()` 외에 `BrandPostResponse`를 생성하는 곳(레거시 조립 `assembleLegacyPending` 경로 등)은 컴파일 에러로 드러난다 — 그 지점에서 쓸 수 있는 caption 소스로 같은 `BrandCaptionHashtags.extract(...)`를 넣고, caption이 아예 없는 지점이면 `List.of()`.

- [ ] **Step 6: 컴파일 에러 전부 해소**

```bash
./gradlew :was:compileJava :was:compileTestJava
```
테스트 코드의 `PostRef`/`BrandPostIndexRow`/`BrandPostResponse` 생성자 호출부는 의미 없는 지점이면 `List.of()` / `null`(caption)로 채운다. 기존 단언은 건드리지 않는다.

- [ ] **Step 7: 관련 유닛 테스트 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest" \
  --tests "com.celfit.was.v1.brandmonitoring.BrandIndexCacheTest" \
  --tests "com.celfit.was.v1.brandmonitoring.BrandCollectionCapTest" \
  --tests "com.celfit.was.v1.brandmonitoring.BrandInfluencerAggregatorTest"
```
Expected: PASS (일부는 Testcontainers 필요할 수 있음 — 그 경우 `DOCKER_HOST` export 확인).

- [ ] **Step 8: 커밋**

```bash
git add -A was/src
git commit -m "feat(was): 게시물 인덱스·응답에 캡션 해시태그 탑재"
```

---

### Task 3: 필터 파라미터 + facet (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagFacets.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagFacetsTest.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java:108-175` (파라미터), `:372-406` (facets), `:428-455` (PostFilters·FacetAxis·applyFilters)

- [ ] **Step 1: facet·필터 판정 유틸의 실패하는 테스트 작성**

`PostRef`는 public record라 테스트에서 직접 생성한다. 헬퍼로 소음 제거:

```java
package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BrandHashtagFacetsTest {

	private static BrandPostAssembler.PostRef ref(String shortcode, String... hashtags) {
		return new BrandPostAssembler.PostRef(shortcode, null, null, null, null, null, null,
				null, null, null, null, null, List.of(hashtags));
	}

	@Test
	void 게시물_수_내림차순으로_집계하고_동수는_키_사전순이다() {
		List<BrandHashtagFacets.Entry> facet = BrandHashtagFacets.of(List.of(
				ref("a", "세일", "올영"), ref("b", "세일"), ref("c", "할인")));
		assertThat(facet).containsExactly(
				new BrandHashtagFacets.Entry("세일", 2),
				new BrandHashtagFacets.Entry("올영", 1),
				new BrandHashtagFacets.Entry("할인", 1));
	}

	@Test
	void 대소문자_변형은_한_키로_합산하고_최빈_원문_표기를_쓴다() {
		// a·b가 "OliveYoung", c가 "oliveyoung" — 최빈 표기 OliveYoung으로 노출.
		List<BrandHashtagFacets.Entry> facet = BrandHashtagFacets.of(List.of(
				ref("a", "OliveYoung"), ref("b", "OliveYoung"), ref("c", "oliveyoung")));
		assertThat(facet).containsExactly(new BrandHashtagFacets.Entry("OliveYoung", 3));
	}

	@Test
	void 필터_판정은_정규화_키로_대소문자_무시_매칭한다() {
		BrandPostAssembler.PostRef r = ref("a", "OliveYoung", "세일");
		assertThat(BrandHashtagFacets.matches(r, "oliveyoung")).isTrue();
		assertThat(BrandHashtagFacets.matches(r, "세일")).isTrue();
		assertThat(BrandHashtagFacets.matches(r, "할인")).isFalse();
	}

	@Test
	void 필터_키_파싱은_앞의_샵을_떼고_정규화하며_빈_값은_null이다() {
		assertThat(BrandHashtagFacets.filterKey("#OliveYoung")).isEqualTo("oliveyoung");
		assertThat(BrandHashtagFacets.filterKey("세일")).isEqualTo("세일");
		assertThat(BrandHashtagFacets.filterKey("  ")).isNull();
		assertThat(BrandHashtagFacets.filterKey(null)).isNull();
		assertThat(BrandHashtagFacets.filterKey("#")).isNull();
	}

	@Test
	void 태그_없는_게시물만_있으면_빈_facet이다() {
		assertThat(BrandHashtagFacets.of(List.of(ref("a")))).isEmpty();
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandHashtagFacetsTest"
```
Expected: 컴파일 실패 — `BrandHashtagFacets` 심볼 없음.

- [ ] **Step 3: 최소 구현**

```java
package com.celfit.was.v1.brandmonitoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 브랜드 스코프 해시태그 facet 집계·필터 판정(스펙 2026-08-31). 키는
 * {@link BrandCaptionHashtags#normalize}, 노출 표기는 최빈 원문(동수는 첫 등장)이다.
 * PostRef.hashtags가 게시물 단위 dedup을 이미 보장하므로 count = 그 태그가 있는 게시물 수.
 */
public final class BrandHashtagFacets {

	/** facet 1행 — tag는 표시용 원문 표기, count는 게시물 수. public인 이유: 응답 meta에 실려 Jackson 직렬화 대상. */
	public record Entry(String tag, long count) {
	}

	private BrandHashtagFacets() {
	}

	static List<Entry> of(List<BrandPostAssembler.PostRef> refs) {
		Map<String, Long> postCountByKey = new LinkedHashMap<>();
		Map<String, Map<String, Long>> rawFreqByKey = new HashMap<>();
		for (BrandPostAssembler.PostRef ref : refs) {
			for (String raw : ref.hashtags()) {
				String key = BrandCaptionHashtags.normalize(raw);
				postCountByKey.merge(key, 1L, Long::sum);
				rawFreqByKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(raw, 1L, Long::sum);
			}
		}
		List<Entry> entries = new ArrayList<>();
		postCountByKey.forEach((key, count) -> entries.add(new Entry(displayOf(rawFreqByKey.get(key)), count)));
		entries.sort((a, b) -> a.count() == b.count()
				? BrandCaptionHashtags.normalize(a.tag()).compareTo(BrandCaptionHashtags.normalize(b.tag()))
				: Long.compare(b.count(), a.count()));
		return entries;
	}

	/** 최빈 원문 표기(동수는 첫 등장 — LinkedHashMap 순회 순서가 보장). */
	private static String displayOf(Map<String, Long> rawFreq) {
		String best = null;
		long bestCount = -1;
		for (Map.Entry<String, Long> e : rawFreq.entrySet()) {
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		return best;
	}

	static boolean matches(BrandPostAssembler.PostRef ref, String filterKey) {
		for (String raw : ref.hashtags()) {
			if (BrandCaptionHashtags.normalize(raw).equals(filterKey)) {
				return true;
			}
		}
		return false;
	}

	/** 요청 파라미터 → 정규화 필터 키. 앞의 # 허용, 공백·빈 값은 null(필터 미적용). */
	static String filterKey(String param) {
		if (param == null) {
			return null;
		}
		String s = param.strip();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		return s.isBlank() ? null : BrandCaptionHashtags.normalize(s);
	}
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandHashtagFacetsTest"
```
Expected: PASS.

- [ ] **Step 5: 컨트롤러 결선**

`V1BrandPostsController`에서:

(a) `list()` 파라미터 추가 (`keyword` 뒤):
```java
		@RequestParam(required = false) String hashtag,
```

(b) `PostFilters`에 필드 추가 (record 끝):
```java
	private record PostFilters(String source, String sponsorship, String contentType, FollowerBand follower,
			String keyword, String authorUsername, boolean adRisk, boolean adGateOpen, LocalDate from,
			LocalDate to, String hashtagKey) {
	}
```
생성 지점에서 `BrandHashtagFacets.filterKey(hashtag)`를 넘긴다 (PostFilters를 만드는 기존 코드를 찾아 마지막 인자로 추가 — 컴파일 에러가 지점을 알려준다).

(c) `FacetAxis`에 축 추가:
```java
	private enum FacetAxis { SOURCE, SPONSORSHIP, CONTENT_TYPE, AD_RISK, HASHTAG, NONE }
```
enum 위 javadoc의 "칩은 4축뿐이다"를 "칩은 5축(해시태그 포함)"으로 갱신.

(d) `applyFilters()`에 필터 추가 (`adRisk` 필터 줄 다음):
```java
				.filter(r -> released == FacetAxis.HASHTAG || f.hashtagKey() == null
						|| BrandHashtagFacets.matches(r, f.hashtagKey()))
```

(e) `facets()`에 추가 (`adRisk` put 다음):
```java
		facets.put("hashtags", BrandHashtagFacets.of(applyFilters(all, f, FacetAxis.HASHTAG)));
```
자기 축 해제 패턴 — 태그를 선택해도 칩 목록이 무너지지 않는다(기존 4축과 동형).

- [ ] **Step 6: 컴파일 + 컨트롤러 관련 테스트**

```bash
./gradlew :was:compileJava :was:compileTestJava
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"
```
Expected: PASS (기존 테스트는 additive 변경이라 깨지지 않아야 정상 — 깨지면 PostFilters 생성 지점 수정 누락).

- [ ] **Step 7: 통합 테스트 1건 추가**

`V1BrandPostsControllerTest`를 읽고 그 파일의 기존 시딩 헬퍼·요청 관례를 그대로 따라, 캡션에 해시태그가 든 게시물 2건 이상을 시딩하는 테스트를 추가한다. 단언은 다음을 고정:

```java
	// 응답 게시물에 hashtags 배열이 실리고, meta.facets.hashtags가 {tag, count} 목록이며,
	// ?hashtag=<태그>로 해당 태그 게시물만 남고 facet의 그 태그 count는 유지된다(자기 축 해제).
```
구체 단언 3개: ① 게시물 아이템 `hashtags`에 시딩 태그 포함, ② `meta.facets.hashtags[0].tag/count` 기대값, ③ `?hashtag=` 필터 시 목록 건수 감소 + facets.hashtags에 두 태그 모두 잔존.

```bash
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"
```
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add -A was/src
git commit -m "feat(was): 브랜드 게시물 해시태그 필터·facet — 5번째 필터 축"
```

---

### Task 4: 모듈 회귀 + 문서

- [ ] **Step 1: was 모듈 전체 테스트**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test
```
Expected: 0 fail. (전체 `./gradlew test`는 PR 직전에만 — CLAUDE.md.)

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 추가**

기존 항목 형식을 그대로 따라 1건:

```markdown
- 2026-08-31 **브랜드 게시물 해시태그 필터는 조회 시 정규식 추출** — AI 판단·수집 시 저장 불채택.
  규칙 `#[\p{L}\p{N}_]+`(전각 ＃ 제외 — 인스타 링크화 검증), 근거·갭은
  [spec](docs/superpowers/specs/2026-08-31-brand-post-hashtag-filter-design.md).
```

(DECISIONS.md의 실제 항목 포맷이 다르면 그 포맷을 따른다.)

- [ ] **Step 3: 커밋 + push**

```bash
git add DECISIONS.md
git commit -m "docs: 해시태그 필터 조회 시 추출 결정 기록"
git push -u origin feat/brand-post-hashtag-filter
```

- [ ] **Step 4: 마무리 보고**

PR은 열지 않는다 — push까지만 하고 사용자에게 PR 개설 여부를 묻는다(전역 지침).
보고에 포함: 테스트 결과, FE 계약 요약(응답 `hashtags` 필드 + `meta.facets.hashtags` + `?hashtag=` 파라미터, additive라 하위호환), 이 plan 문서의 `plans/archive/` 이동은 PR에 포함할 것.

---

## 셀프 리뷰 노트 (작성 시 확인 완료)

- 스펙 §1(정규식)·§2(A안)·§3(추출 규칙)·§4(정규화)·API 계약·테스트 목록 전부 태스크에 대응됨.
- 타입 일관성: `BrandCaptionHashtags.extract/normalize`, `BrandHashtagFacets.of/matches/filterKey/Entry`, `PostRef.hashtags`, `PostFilters.hashtagKey`, facet 키 `"hashtags"`, 파라미터 `hashtag` — 태스크 간 동일 명칭 사용.
- 알려진 유동 지점(컴파일 에러로 드러나는 record 생성자 호출부, PostFilters 생성 지점, 통합 테스트 시딩 헬퍼)은 "에러를 따라 수정" 지시로 명시 — 줄번호는 develop 이동에 따라 어긋날 수 있으니 심볼 기준으로 찾을 것.
