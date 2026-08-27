# 성과 대시보드 인플루언서 집계 (PR ②) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/performance-dashboard/influencers` 신설 — PR ①의 `DashboardRef` 인덱스를 handle로 그룹핑한 인플루언서 집계(신규 DB 쿼리 없음).

**Architecture:** `DashboardRef`에 표시 필드 2종(displayName·profileImageUrl)을 additive로 확장하고, 순수 정적 집계기(`PerformanceInfluencerAggregator`)가 필터 적용된 ref를 handle로 접는다. 컨트롤러는 목록과 같은 정규화·필터 관용구를 공용 클래스로 뽑아 재사용한다(PR ① 최종 리뷰 인계 — 527줄 컨트롤러 분리).

**Tech Stack:** Java 21 · Spring Boot 4.1 · 기존 mock 기반 테스트 스타일(`@WebMvcTest`·Mockito).

**스펙:** [2026-08-27-perf-dashboard-list-api-optimization-design.md](../specs/2026-08-27-perf-dashboard-list-api-optimization-design.md) §4

## Global Constraints

- 주석·커밋 메시지 한국어, prefix `feat(was):`/`refactor(was):`/`test(was):`.
- 이 머신은 Docker Desktop — `DOCKER_HOST` 설정 금지. 이 계획의 테스트는 전부 mock 기반.
- 테스트는 모듈 단위: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"`.
- 계약 무결성 규칙 #1: nullable 필드 키 생략 금지, 명시적 null. **값이 없는 합계는 0이 아니라 null**(FE 요청 — views 0 vs 미제공 구분).
- 기존 `/contents`·`/comparison` 응답 계약 불변(Task 1의 ref 확장·Task 2의 리팩터는 동작 중립 — 기존 테스트 무수정 그린이 게이트).
- 집계 규칙은 스펙 §4 그대로: 지표 합산은 스냅샷 있는 게시물만·아는 값만·하나도 모르면 null, 좋아요 숨김은 likes 합계 제외+`likesKnownCount`, `ratedFollowers/ratedEngaged`는 팔로워·좋아요·댓글 3종 모두 아는 게시물만(게시물당 팔로워 1회 합산), `postCount`는 필터 통과 게시물 전체 수(스냅샷 유무 무관 — FE 확인 항목으로 회신에 이미 명시), handle 미상(빈 문자열) 콘텐츠는 집계 제외.

## File Structure

- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` — `DashboardRef` +2필드, `refOf`/`refOfPoolRow` 갱신.
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` — `resolveImageUrl` 공개(한 단어).
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardQueries.java` — 컨트롤러에서 옮긴 정규화·술어 공용 유틸(동작 중립).
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceInfluencerResponse.java` — 응답 record.
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceInfluencerAggregator.java` — 순수 정적 집계기.
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` — 유틸 위임 + `/influencers` 라우트.
- Test: 기존 `PerformanceContentAssemblerTest`·`V1PerformanceDashboardControllerTest` 확장 + Create `PerformanceInfluencerAggregatorTest`.

---

### Task 1: `DashboardRef`에 displayName·profileImageUrl 확장

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` (`resolveImageUrl` 가시성만)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Produces: `DashboardRef`의 `handle` 뒤에 `String displayName, String profileImageUrl` 2필드 추가(이후 필드 순서 불변). `BrandPostAssembler.resolveImageUrl(String imageObjectPath, String originalUrl)` — `static` → `public static`(본문 불변).

파생 규칙(카드 경로와 동치 — 이것이 이 태스크의 전부다):
- `refOf`(레거시·병합 카드): `content.item().displayName()`, `content.item().profileImageUrl()`.
- `refOfPoolRow`(풀 전용): `fromBrandPost`가 카드에서 쓰는 규칙을 ref에서 재현한다 —
  `fullName = author == null ? null : author.fullName()`;
  `displayName = fullName == null || fullName.isBlank() ? handle : fullName`;
  `profileImageUrl = author == null ? null : BrandPostAssembler.resolveImageUrl(author.imageObjectPath(), author.profilePicUrl())`
  (카드 경로 `brandPost`의 author 필드 산지와 동일 — 아카이브 경로 우선).

- [ ] **Step 1: 실패하는 테스트** — 기존 동치성 테스트(`index의_ref는_전량_조립_결과와_판정값이_일치한다`)의 콘텐츠별 비교 루프에 2줄 추가:

```java
			assertThat(ref.displayName()).isEqualTo(content.item().displayName());
			assertThat(ref.profileImageUrl()).isEqualTo(content.item().profileImageUrl());
```

풀 전용 픽스처의 `AuthorRow`에 `fullName`·`imageObjectPath`(아카이브 경로 케이스 1개 포함)가 이미 있는지 확인하고, 없으면 POOL2 쪽 author 스텁에 값을 채워 아카이브 경로(`/img/...`) 파생이 실제로 판별되게 한다(베이스라인 `BrandPostResponse`의 `authorFullName`·`authorProfilePicUrl`도 대응).

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"` / Expected: 컴파일 실패(`displayName()` 미정의)
- [ ] **Step 3: 구현** — record 필드 추가(`handle` 바로 뒤), `refOf`/`refOfPoolRow` 위 규칙대로, `resolveImageUrl` public 전환(+공개 이유 한 줄: 대시보드 ref가 카드와 같은 아카이브 우선 규칙을 공유). `DashboardRef`를 직접 생성하는 테스트 헬퍼(컨트롤러 테스트의 `refOf` 등)는 컴파일 오류를 따라 `content.item().displayName(), content.item().profileImageUrl()`로 갱신.
- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): DashboardRef에 작성자 표시 필드 확장 — 인플루언서 집계 재료`

---

### Task 2: 정규화·술어 공용화 리팩터 (동작 중립)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardQueries.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Test: 기존 `V1PerformanceDashboardControllerTest` **무수정 그린**이 게이트

**Interfaces:**
- Produces: 패키지 전용 final 유틸 클래스 `DashboardQueries`(인스턴스화 금지 private 생성자). 컨트롤러의 다음 멤버를 **본문 그대로**(verbatim) 옮기고 컨트롤러는 정적 위임 호출로 치환한다:
  - `record PageParams(int offset, int limit)` + `normalizePage(Integer limit, Integer offset)` (+상수 `PAGE_LIMIT_MAX=100`, `PAGE_LIMIT_DEFAULT=100`)
  - `normalizeFilter(String raw, String param, String... allowed)` / `normalizeFilter(String raw)`
  - `normalizeAccountIds(String raw)`
  - `normalizeAccountType(String raw, boolean brandSpecified)`
  - `parseDate(String raw, String param)`
  - `matchesCampaign(String campaignId, String filter)` (+`CAMPAIGN_NONE` 상수)
  - `withinUploadWindow(LocalDate uploadedOn, LocalDate from, LocalDate to)`
  - `matchesAccountType(...)` — ref·경쟁사 집합 기반 술어
  - meta의 `page` 서브맵 생성(`pageMeta(PageParams page)` — `{offset, limit|null}` LinkedHashMap)
  - `FILTER_ALL` 상수
- 컨트롤러에 남는 것: 라우트·흐름·statusCounts meta·comparator(정렬은 목록 전용 — engagement 정의가 표면마다 달라 공용화하지 않는다, YAGNI).

- [ ] **Step 1: 이동** — 위 멤버를 `DashboardQueries`로 verbatim 이동(가시성은 패키지 전용 static), 컨트롤러는 `DashboardQueries.normalizePage(...)` 식으로 치환. import 정리. 클래스 javadoc: "목록·인플루언서·(후속) growth 표면이 공유하는 쿼리 정규화·술어 — 값 공간·기본값·400 메시지가 표면 간에 갈라지지 않게 한 곳에 둔다(PR ① 최종 리뷰 인계)."
- [ ] **Step 2: 게이트** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: **기존 테스트 무수정 전부 PASS**
- [ ] **Step 3: Commit** — `refactor(was): 대시보드 쿼리 정규화·술어를 DashboardQueries로 공용화 — 표면 간 계약 단일화`

---

### Task 3: 인플루언서 집계기 (순수 함수, TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceInfluencerResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceInfluencerAggregator.java`
- Test: Create `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceInfluencerAggregatorTest.java`

**Interfaces:**
- Produces:

```java
/**
 * 인플루언서 집계 1행(스펙 §4, FE 제안 셰이프 그대로) — 값이 없는 합계는 0이 아니라 null
 * (계약 무결성 규칙 #1 + FE 결측 구분 요구).
 */
public record PerformanceInfluencerResponse(
		String handle,
		String displayName,
		String profileImageUrl,
		Long followers,
		int postCount,
		int sponsoredCount,
		int likesKnownCount,
		String latestPostAt,        // YYYY-MM-DD, 업로드일 미상뿐이면 null
		Long views, Long likes, Long comments,
		Long ratedFollowers, Long ratedEngaged,
		List<String> brandAccountIds) {
}
```

```java
public final class PerformanceInfluencerAggregator {
	/** 필터 적용 후 ref 전량 → handle별 집계(입력 순서와 무관하게 결정적). 정렬은 호출부 몫. */
	public static List<PerformanceInfluencerResponse> aggregate(
			List<PerformanceContentAssembler.DashboardRef> refs)
}
```

집계 규칙(전부 이 태스크의 테스트 대상):
1. `handle`이 null·빈 문자열·공백인 ref는 **집계에서 제외**(작성자 미상).
2. `postCount` = 그 handle의 ref 수(스냅샷 유무 무관).
3. `sponsoredCount` = `sponsorship == "sponsored"` 수.
4. 지표 합산은 `hasSnapshots()` ref만 대상: `views`/`comments`는 값을 아는 행만 합산, **하나도 모르면 null**(0 아님). `likes`는 `!latestLikesHidden() && latestLikes() != null`인 행만 합산·하나도 없으면 null, `likesKnownCount` = 그 행의 수.
5. `ratedFollowers`/`ratedEngaged` = `followers() != null && hasSnapshots() && !latestLikesHidden() && latestLikes() != null && latestComments() != null`인 게시물만 대상으로, 게시물마다 `followers`를 1회씩 더한 합과 `(latestLikes + latestComments)` 합. 대상 게시물이 없으면 둘 다 null.
6. `latestPostAt` = `uploadedOn` 최댓값의 ISO 문자열(`toString()`), 전부 미상이면 null.
7. 대표 표시값(`displayName`·`profileImageUrl`·`followers`) = **업로드 최신 ref 우선으로 첫 non-null**(업로드일 미상 ref는 마지막 순번). 전부 null이면 null(displayName은 마지막 폴백으로 handle).
8. `brandAccountIds` = 비null `brandAccountId`의 등장 순 distinct 목록(individual 미귀속은 목록에 안 실림 — 빈 목록 가능).
9. handle 대소문자: ref의 handle은 이미 소문자 계약(PR ①) — 그대로 키로 쓴다(재정규화 없음, 방어적 `toLowerCase` 금지 — 이원화 방지).

- [ ] **Step 1: 실패하는 테스트** — 테이블 주도로 규칙별 최소 1케이스. 핵심 케이스 코드(픽스처 헬퍼 `ref(...)`는 `DashboardRef` 직접 생성 — 17필드를 명시 인자로 받는 로컬 빌더를 만들어 가독성 확보):

```java
	@Test
	void 지표는_아는_값만_합산하고_하나도_모르면_null이다() {
		// A: views=100·likes=10(공개)·comments=1 / B: 피드(views null)·likes 숨김·comments 2 / C: 스냅샷 없음
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", true, 100L, 10L, false, 1L),
				ref("a", "2026-08-05", true, null, 999L, true, 2L),
				ref("a", "2026-08-04", false, null, null, false, null)));
		var row = rows.get(0);
		assertThat(row.postCount()).isEqualTo(3);          // 스냅샷 없어도 센다
		assertThat(row.views()).isEqualTo(100L);           // 아는 값만
		assertThat(row.likes()).isEqualTo(10L);            // 숨김 제외
		assertThat(row.likesKnownCount()).isEqualTo(1);
		assertThat(row.comments()).isEqualTo(3L);          // 1+2 (숨김은 likes만 영향)
	}

	@Test
	void 지표를_하나도_모르면_0이_아니라_null이다() { /* 전부 스냅샷 없음 → views·likes·comments·rated* 전부 null, postCount 2 */ }

	@Test
	void rated는_팔로워와_좋아요_댓글을_모두_아는_게시물만_게시물당_팔로워_1회로_합산한다() {
		// followers=1000 게시물 2건(적격) + followers null 1건 + likes 숨김 1건
		// → ratedFollowers=2000, ratedEngaged=(10+1)+(20+2)=33
	}

	@Test
	void handle_미상_ref는_집계에서_빠진다() { /* handle "" ref만 → 빈 결과 */ }

	@Test
	void 대표_표시값은_업로드_최신_ref_우선_첫_non_null이다() {
		// 최신 ref는 displayName null·구 ref에 "뷰티러버" → "뷰티러버" 채택; profileImageUrl 동일 규칙
	}

	@Test
	void brandAccountIds는_등장_순_distinct이고_미귀속은_안_실린다() { }

	@Test
	void latestPostAt은_최신_업로드일이고_전부_미상이면_null이다() { }

	@Test
	void sponsoredCount는_sponsored만_센다() { }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceInfluencerAggregatorTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 구현** — handle별 `LinkedHashMap` 그룹핑 후 그룹 내에서 규칙 1~9 순수 계산. 대표값 선정은 그룹을 업로드 최신순(null 마지막)으로 정렬한 사본에서 첫 non-null 스캔.
- [ ] **Step 4: 통과 확인** — Run 동일 / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 인플루언서 집계기 — ref 그룹핑 순수 함수(스펙 §4 규칙)`

---

### Task 4: `/influencers` 라우트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java`

**Interfaces:**
- Consumes: Task 1~3 전부 + 기존 `assembler.index(userId)`.
- Produces: `GET /v1/performance-dashboard/influencers` —

```
파라미터: uploadedFrom/uploadedTo(YYYY-MM-DD) · sponsorship · accountIds(쉼표) · campaignId ·
  accountType · sort=views|likes|comments|engagement|posts|latest(기본 views) ·
  order=desc|asc(기본 desc) · offset/limit(1..100 기본 100, 둘 다 생략 시 전량)
응답: { data: [PerformanceInfluencerResponse...], meta: { total, page: {offset, limit|null} } }
```

구현 규칙:
- 필터는 전부 집계 **모수**에 적용된다(목록의 statusCounts 같은 예외 없음): `sponsorship`·`matchesCampaign`·`accountIds`(단수 brandAccountId 파라미터는 이 표면엔 **없다** — 신설 표면이라 구계약 짐을 지지 않는다)·`matchesAccountType`(accountIds 명시 시 all 함의 — 목록과 동일 규칙)·`withinUploadWindow`(업로드일 미상 ref는 기간 지정 시 제외 — 목록과 동일). 전부 `DashboardQueries` 위임.
- 정렬 키: `views|likes|comments` = 응답 행의 해당 합계, `engagement` = `ratedEngaged/(double) ratedFollowers`(분모 null·0이면 null), `posts` = postCount, `latest` = latestPostAt. **null 키는 order와 무관하게 항상 마지막**, 타이브레이크 `handle`(전순서). 값 공간 밖 400(`sort 값이 올바르지 않아요.`).
- 페이지는 정렬 후 슬라이스, `meta.total` = 집계 행 전체 수(페이지 전).

- [ ] **Step 1: 실패하는 테스트** — 기존 `givenIndexed` 헬퍼 재사용(ref 픽스처에 작성자·지표 채움):

```java
	@Test
	void influencers는_작성자별로_접고_기본_views_내림차순이다() throws Exception {
		// 작성자 a(views 합 300)·b(views 합 100) → data[0].handle=a, meta.total=2
		// data[0]에 postCount·likesKnownCount·brandAccountIds까지 단정(집계기 배선 확인)
	}

	@Test
	void influencers_필터는_집계_모수에_적용된다() throws Exception {
		// sponsorship=sponsored → organic만 올린 작성자는 행 자체가 사라지고 total도 준다
	}

	@Test
	void influencers_페이지는_정렬_후_슬라이스고_생략_시_전량이다() throws Exception {
		// offset=1&limit=1 → 두 번째 작성자만, meta.page={1,1}; 생략 → 전량 + page={0,null}
	}

	@Test
	void influencers_engagement_정렬은_분모_미상을_마지막에_둔다() throws Exception { }

	@Test
	void influencers_sort_값_공간_밖은_400이다() throws Exception { }

	@Test
	void influencers_accountIds는_함의를_포함해_목록과_같은_규칙이다() throws Exception {
		// 경쟁사 브랜드 id를 accountIds로 명시 → 그 작성자가 보인다(accountType=all 함의)
	}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"` / Expected: FAIL(404 라우트 없음)
- [ ] **Step 3: 구현** — 라우트 추가: 정규화(전부 `DashboardQueries`) → `index(userId)` → ref 필터 → `PerformanceInfluencerAggregator.aggregate` → comparator(응답 행 기준) → total → 슬라이스 → `ApiResponse.ok(data, meta)`. comparator는 `Comparator.comparing(키, nullsLast(방향)).thenComparing(handle)` — 목록 comparator와 같은 관용구(단 키가 응답 행이라 별도 메서드 `influencerComparator(sortKey, asc)`).
- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 인기 인플루언서 집계 엔드포인트 — /performance-dashboard/influencers`

---

### Task 5: 문서·전체 검증

**Files:**
- Modify: `docs/superpowers/specs/2026-08-27-perf-dashboard-list-api-optimization-design.md` (상태 헤더만)
- Modify: `DECISIONS.md` (기존 행의 구현 상태 문구만)

- [ ] **Step 1: 상태 갱신** — 스펙 헤더 `§1~§3(PR ①) 구현됨 · §4~§6 미구현` → `§1~§4(PR ①·②) 구현됨 · §5~§6 미구현`. DECISIONS.md 해당 행의 `(PR ① 목록·비교 구현됨 · ②~④ 미구현)` → `(PR ①·② 구현됨 · ③~④ 미구현)`.
- [ ] **Step 2: 모듈 전체** — Run: `./gradlew :was:test` / Expected: PASS(실제 카운트 확인)
- [ ] **Step 3: Commit** — `docs: PR ② 상태 갱신 — 스펙 §4 구현됨`

---

## 완료 판정

- 스펙 §4 응답 셰이프·집계 규칙 전부 태스크로 커버(규칙별 테스트 존재).
- 기존 표면 무회귀: Task 1·2가 기존 테스트 무수정 그린으로 고정.
- PR 생성 시 이 plan을 `plans/archive/`로 이동.
