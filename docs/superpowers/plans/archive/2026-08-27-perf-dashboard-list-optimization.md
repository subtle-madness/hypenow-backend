# 성과 대시보드 목록 최적화 (PR ①) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/v1/performance-dashboard/contents`·`/comparison`을 경량 인덱스(2단 조립)로 재편하고 정렬·페이지네이션·`accountIds`·`authorUsername`·`snapshotMode=latest`·`previousDayValues`를 추가한다.

**Architecture:** 브랜드 목록 PR #602의 인덱스+하이드레이트 구조를 대시보드에 복제한다. 레거시 계열(유저당 ≤33행)은 현행 전량 조립 유지, 레거시와 겹치는 브랜드 코드만 풀 하이드레이트해 병합 의미론 보존, 나머지 브랜드 풀(지배 비용)은 경량 프로젝션으로 `DashboardRef`를 만든다. 필터·statusCounts·정렬·페이지 슬라이스·비교 집계는 전부 ref 위에서, 무거운 조립은 응답에 실을 코드만.

**Tech Stack:** Java 21 · Spring Boot 4.1 · JdbcClient · Mockito/`@WebMvcTest`(기존 테스트 스타일).

**스펙:** [2026-08-27-perf-dashboard-list-api-optimization-design.md](../../specs/2026-08-27-perf-dashboard-list-api-optimization-design.md) §1~§3

## Global Constraints

- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(was):`/`test(was):`/`refactor(was):`.
- 테스트는 모듈 단위: `./gradlew :was:test --tests "..."`. 이 계획의 테스트는 전부 mock 기반(Testcontainers 불요).
- 이 머신은 Docker Desktop이고 `DOCKER_HOST`는 **설정하지 않는 것이 정답**(08-09 확인 — CLAUDE.md의 colima 항목은 다른 머신 기준).
- 계약 무결성 규칙 #1: nullable 필드는 키 생략 금지, 명시적 null.
- 응답 계약의 기존 필드·값 의미는 불변(additive만). offset/limit 둘 다 생략 시 기존 전량 응답 유지.
- 대시보드 브랜드 풀 계약 3종을 반드시 승계: scope=ALL(정산 전 포함), 커버리지 클램프(coveredUntil, direct 면제), 노출 필터(direct-only는 등록자만) + own-first 다계정 병합.

## File Structure

- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` — 인덱스 프로젝션 컬럼 확장 + 최신 스냅샷 지표 프로젝션.
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` — 재사용 표면 공개(resolveSource·resolveAuthorsByKeys·campaignIdsByCode).
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentResponse.java` — `previousDayValues`·`withLatestSnapshotOnly()`.
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` — `DashboardRef`·`DashboardIndex`·`index()`·`hydratePage()`.
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java` — 입력을 ref로 전환.
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` — ref 기반 필터·정렬·페이지·신규 파라미터.
- Test: 같은 경로의 기존 4개 테스트 파일 갱신·확장.

---

### Task 1: 리포지토리 프로젝션 확장

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` (indexForBrand의 LatestViews 호출부)
- Test: 컴파일 회귀만(이 리포지토리는 mock 경유로 테스트됨 — 기존 테스트에서 record 생성자 컴파일 오류를 고친다)

**Interfaces:**
- Produces: `BrandPostIndexRow(String shortCode, OffsetDateTime takenAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt, Boolean isPaidPartnership, String caption, OffsetDateTime unavailableAt, String authorUsername, String authorIgUserId)` — 뒤 3개 필드 신설.
- Produces: `List<LatestSnapshotRow> findLatestSnapshotsForBrand(long brandId, OffsetDateTime cutoff, boolean enrichedOnly)` / `LatestSnapshotRow(String shortCode, LocalDate capturedOn, String contentType, Long views, Long likes, boolean likesHidden, Long comments)` — 기존 `findLatestViewsForBrand`/`LatestViewsRow`를 **대체**(중복 쿼리 방지).

- [ ] **Step 1: `findBrandPostIndex` 확장** — SELECT에 `t.unavailable_at, t.author_username, t.author_ig_user_id` 추가, `BrandPostIndexRow`에 `OffsetDateTime unavailableAt, String authorUsername, String authorIgUserId` 필드 추가(맨 뒤). javadoc에 "성과 대시보드 인덱스(2026-08-27 목록 최적화 설계)가 상태(hidden)·작성자 판정에 함께 쓴다 — 세 컬럼 모두 short 값이라 캡션 대비 폭 증가는 무시 수준" 취지 한 줄.

- [ ] **Step 2: `findLatestViewsForBrand` → `findLatestSnapshotsForBrand` 대체**

```java
	/**
	 * 게시물별 최신 스냅샷 1행의 지표 프로젝션(2026-08-27 대시보드 목록 최적화 설계에서 확장) —
	 * 정렬 키(views·likes·comments·engagement)와 대시보드 ref의 최신 지표 산출 전용. 시계열
	 * 전량({@link #findSnapshots})은 게시물당 최대 365행이라 지표만 필요한 경로에 싣지 않는다.
	 * content_type을 함께 주는 이유: 피드는 views를 null로 접는 서빙 규칙
	 * ({@code BrandPostAssembler.snapshotOf})을 호출부가 동일 적용해야 한다.
	 */
	public List<LatestSnapshotRow> findLatestSnapshotsForBrand(long brandId, OffsetDateTime cutoff,
			boolean enrichedOnly) {
		String enrichedFilter = enrichedOnly ? " AND t.enriched_at IS NOT NULL" : "";
		return jdbc.sql("""
				SELECT DISTINCT ON (s.short_code) s.short_code, s.captured_on, s.content_type,
				       s.views, s.likes, s.likes_hidden, s.comments
				FROM brand_post_snapshot s
				JOIN brand_tagged_post t ON t.short_code = s.short_code
				WHERE t.brand_id = :brandId
				  AND ( t.taken_at >= :cutoff OR t.direct_registered_at IS NOT NULL )
				""" + enrichedFilter + """

				ORDER BY s.short_code, s.captured_on DESC
				""")
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.query(LatestSnapshotRow.class)
				.list();
	}

	/** 게시물별 최신 스냅샷 지표({@link #findLatestSnapshotsForBrand}) — contentType은 피드 views null 규칙용. */
	public record LatestSnapshotRow(String shortCode, LocalDate capturedOn, String contentType,
			Long views, Long likes, boolean likesHidden, Long comments) {
	}
```

기존 `findLatestViewsForBrand`·`LatestViewsRow`는 삭제하고 `BrandPostAssembler.indexForBrand`의 호출부를 갱신:

```java
		if (withViews && !poolByCode.isEmpty()) {
			for (BrandReadRepository.LatestSnapshotRow row : brandReadRepository.findLatestSnapshotsForBrand(
					account.id(), windowCutoff(), true)) {
				viewsByCode.put(row.shortCode(),
						CONTENT_TYPE_REELS.equalsIgnoreCase(row.contentType()) ? row.views() : null);
			}
		}
```

- [ ] **Step 3: 컴파일·기존 테스트 확인**

Run: `./gradlew :was:compileJava :was:compileTestJava` → `BrandPostIndexRow`·`LatestViewsRow`를 직접 생성하는 테스트가 있으면 새 시그니처로 고친다(값은 `null, null, null` 채움). 이어서 `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"`
Expected: PASS (기존 브랜드 표면 동작 불변)

- [ ] **Step 4: Commit** — `refactor(was): 브랜드 인덱스 프로젝션에 상태·작성자 컬럼 + 최신 스냅샷 지표 확장 — 대시보드 2단 조립 재료`

---

### Task 2: `previousDayValues` 필드 (요청 2 후반)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` (legacyPost·fromBrandPost)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Produces: `PerformancePostResponse`에 `PreviousDayValues previousDayValues` 필드(snapshots 바로 뒤 위치), `record PreviousDayValues(Long views, Long likes, Long comments)`, 인스턴스 메서드 `PerformanceContentResponse withLatestSnapshotOnly()`.
- Produces: `PerformanceContentAssembler`에 `static PreviousDayValues previousDayValues(List<SnapshotResponse> snapshots)`.

- [ ] **Step 1: 실패하는 테스트 작성** — `PerformanceContentAssemblerTest`에 추가(기존 픽스처 헬퍼 재사용 — 스냅샷 2개짜리 콘텐츠를 조립하는 기존 스타일을 따른다):

```java
	@Test
	void 직전_스냅샷이_있으면_previousDayValues가_그_값이다() {
		// 기존 조립 픽스처에서 스냅샷 [8-05(v=100,l=10,c=1), 8-06(v=200,l=20,c=2)]인 콘텐츠를 준비
		// (파일의 기존 given...() 헬퍼로 레거시 아이템 1건 + 스냅샷 2건 스텁)
		PerformanceContentResponse content = /* 조립 결과 1건 */;
		assertThat(content.item().post().previousDayValues())
				.isEqualTo(new PerformanceContentResponse.PreviousDayValues(100L, 10L, 1L));
	}

	@Test
	void 스냅샷이_1개면_previousDayValues는_null이다() {
		PerformanceContentResponse content = /* 스냅샷 1건 콘텐츠 */;
		assertThat(content.item().post().previousDayValues()).isNull();
	}

	@Test
	void withLatestSnapshotOnly는_최신_1개만_남기고_previousDayValues를_보존한다() {
		PerformanceContentResponse trimmed = /* 스냅샷 2건 콘텐츠 */.withLatestSnapshotOnly();
		assertThat(trimmed.item().post().snapshots()).hasSize(1);
		assertThat(trimmed.item().post().snapshots().get(0).date()).isEqualTo("2026-08-06");
		assertThat(trimmed.item().post().previousDayValues().views()).isEqualTo(100L);
	}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"` / Expected: 컴파일 실패(`previousDayValues` 미정의)

- [ ] **Step 3: 구현**

`PerformanceContentResponse`:

```java
	/** 직전 스냅샷의 지표 3종(FE "▲오늘" 증가분 재료, 2026-08-27) — 직전 스냅샷이 없으면 객체 자체가 null. */
	public record PreviousDayValues(Long views, Long likes, Long comments) {
	}
```

`PerformancePostResponse` 필드 목록의 `snapshots` 바로 뒤에 `PreviousDayValues previousDayValues` 추가. 그리고:

```java
	/**
	 * snapshotMode=latest(2026-08-27) — 스냅샷을 최신 1개로 줄인 사본. previousDayValues는 전체
	 * 시계열에서 이미 계산돼 있어 그대로 보존된다(잘라낸 뒤 계산하면 항상 null이 되므로 순서 불변).
	 */
	public PerformanceContentResponse withLatestSnapshotOnly() {
		PerformancePostResponse post = item().post();
		if (post == null || post.snapshots().size() <= 1) {
			return this;
		}
		List<TrackingItemResponse.SnapshotResponse> latest = List.of(post.snapshots().get(post.snapshots().size() - 1));
		PerformancePostResponse trimmed = new PerformancePostResponse(post.url(), post.shortcode(),
				post.contentType(), post.uploadedAt(), post.caption(), post.matchedKeywords(),
				post.thumbnailUrl(), post.hiddenAt(), latest, post.previousDayValues(), post.commentsTotal(),
				post.commentsHidden(), post.commentsCollectedCount(), post.recentComments());
		return new PerformanceContentResponse(new PerformanceItemResponse(item().id(), item().mode(),
				item().status(), item().handle(), item().displayName(), item().profileImageUrl(),
				item().followers(), item().lastUploadedAt(), item().campaignId(), item().campaignName(),
				item().sourceUrl(), item().registeredAt(), item().trackingDays(), item().keywords(), trimmed,
				item().nextCheckAt()), source(), sponsorship(), canonicalPostId(), additionalSources(),
				brandAccountId());
	}
```

`PerformanceContentAssembler`:

```java
	/** 직전 스냅샷(마지막에서 두 번째)의 지표 3종 — 목록 카드 증가분 표기 재료(2026-08-27). 2개 미만이면 null. */
	static PerformanceContentResponse.PreviousDayValues previousDayValues(
			List<TrackingItemResponse.SnapshotResponse> snapshots) {
		if (snapshots == null || snapshots.size() < 2) {
			return null;
		}
		TrackingItemResponse.SnapshotResponse prev = snapshots.get(snapshots.size() - 2);
		return new PerformanceContentResponse.PreviousDayValues(prev.views(), prev.likes(), prev.comments());
	}
```

`legacyPost`·`fromBrandPost`의 `PerformancePostResponse` 생성에 `previousDayValues(snapshots)` 인자를 끼워 넣는다(각각 병합 후 스냅샷·브랜드 스냅샷 기준). 다른 생성 지점(테스트 픽스처)은 컴파일 오류를 따라 전부 갱신.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 대시보드 콘텐츠에 previousDayValues·withLatestSnapshotOnly 추가`

---

### Task 3: `BrandPostAssembler` 재사용 표면 공개

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java` (기존 테스트 그린 유지가 판정)

**Interfaces:**
- Produces: `public static String resolveSource(OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt, boolean registeredByUser)` (private → public — 코드 변경 없이 가시성만).
- Produces: `public record AuthorKey(String shortCode, String igUserId, String username)` + `public Map<String, AuthorRow> resolveAuthorsByKeys(List<AuthorKey> keys)` — 기존 `resolveAuthors(List<BrandTaggedPostRow>)`는 이것에 위임하도록 리팩터.
- Produces: `public Map<String, List<String>> campaignIdsByCode(long brandId, Set<String> codes)` (private → public).

- [ ] **Step 1: 리팩터** — `resolveAuthors` 본문을 `resolveAuthorsByKeys`로 옮기되 로직은 문자 그대로 유지(ig_user_id 1차 → 미해석분만 username 폴백, `AuthorKey` 필드로 치환). 기존 `resolveAuthors`는:

```java
	private Map<String, AuthorRow> resolveAuthors(List<BrandTaggedPostRow> posts) {
		return resolveAuthorsByKeys(posts.stream()
				.map(p -> new AuthorKey(p.shortCode(), p.authorIgUserId(), p.authorUsername())).toList());
	}
```

`resolveSource` 3인자 코어와 `campaignIdsByCode`는 시그니처 그대로 `public`으로. 각 javadoc에 "성과 대시보드 인덱스(2026-08-27)가 같은 판정을 공유한다 — 판정 함수 이원화 금지" 취지 한 줄.

- [ ] **Step 2: 확인·커밋** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"` → PASS 후 커밋 `refactor(was): 대시보드 인덱스가 공유할 판정·작성자 해석 표면 공개`

---

### Task 4: `DashboardRef`·`DashboardIndex`·`index()` (P0 본체)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Consumes: Task 1의 `BrandPostIndexRow`(9필드)·`findLatestSnapshotsForBrand`, Task 3의 `resolveSource`·`resolveAuthorsByKeys`·`campaignIdsByCode`, 기존 `BrandPostAssembler.hydrate(userId, account, viewerAccountType, BrandPostIndex, codes, withComments)`·`BrandPostIndex(refs, poolCodes, legacyByCode, ownedShortCodes)`·`BrandDirectPostRepository.shortCodesByUser(userId)`.
- Produces(컨트롤러·비교·후속 PR ②③이 소비):

```java
	/** 대시보드 콘텐츠 1건의 경량 참조 — 필터·statusCounts·정렬·페이지·집계의 판정값 전부. */
	public record DashboardRef(String contentKey, String shortcode, String source, String sponsorship,
			String status, LocalDate uploadedOn, String brandAccountId, String campaignId,
			String handle, Long followers, Long latestViews, Long latestLikes, boolean latestLikesHidden,
			Long latestComments, boolean hasSnapshots) {
	}

	public record DashboardIndex(long userId, List<DashboardRef> refs, OffsetDateTime lastCollectedAt,
			Set<String> competitorBrandAccountIds,
			Map<String, PerformanceContentResponse> legacyCards,        // contentKey → 조립 완료 카드
			Map<String, String> brandByCode,                            // 풀 전용 shortcode → brandAccountId
			Map<String, BrandHydration> brandsById,                     // brandAccountId → 하이드레이트 재료
			Map<Long, CampaignRow> campaignsById) {
		public record BrandHydration(BrandAccountRow account, String accountType, Set<String> ownedShortCodes) {
		}
	}

	public DashboardIndex index(long userId)
```

- [ ] **Step 1: 실패하는 동치성 테스트 작성** — `PerformanceContentAssemblerTest`의 기존 repo-mock 픽스처(레거시 2건 + 브랜드 풀 2건, 겹침 1건짜리 시나리오가 이미 있다)를 재사용해:

```java
	@Test
	void index의_ref는_전량_조립_결과와_판정값이_일치한다() {
		// 기존 assembleSlim 계열 픽스처 그대로: 레거시 아이템(겹침 1) + 브랜드 풀 2코드.
		// 브랜드 풀 경량 경로용 스텁 추가: findBrandPostIndex(enrichedOnly=false)·
		// findLatestSnapshotsForBrand(enrichedOnly=false)·resolveAuthorsByKeys 입력이 될
		// findAuthors/findAuthorsByUsername·campaignIdsByCode용 postCampaignRepository.
		var index = assembler.index(USER_ID);
		var slim = assembler.assembleSlim(USER_ID);   // Task 7 전까지 존치 — 동치성 기준선

		assertThat(index.refs()).hasSameSizeAs(slim.contents());
		for (int i = 0; i < slim.contents().size(); i++) {
			var content = slim.contents().get(i);
			var ref = index.refs().get(i);   // index도 업로드 최신순 + contentKey 타이브레이크 정렬 계약
			assertThat(ref.contentKey()).isEqualTo(content.item().id());
			assertThat(ref.source()).isEqualTo(content.source());
			assertThat(ref.sponsorship()).isEqualTo(content.sponsorship());
			assertThat(ref.status()).isEqualTo(content.item().status());
			assertThat(ref.brandAccountId()).isEqualTo(content.brandAccountId());
			assertThat(ref.campaignId()).isEqualTo(content.item().campaignId());
			assertThat(ref.uploadedOn()).isEqualTo(PerformanceContentAssembler.uploadedOn(content));
			var snaps = content.item().post() == null ? List.<TrackingItemResponse.SnapshotResponse>of()
					: content.item().post().snapshots();
			if (snaps.isEmpty()) {
				assertThat(ref.hasSnapshots()).isFalse();
			} else {
				var latest = snaps.get(snaps.size() - 1);
				assertThat(ref.latestViews()).isEqualTo(latest.views());
				assertThat(ref.latestLikes()).isEqualTo(latest.likes());
				assertThat(ref.latestComments()).isEqualTo(latest.comments());
			}
		}
		assertThat(index.lastCollectedAt()).isEqualTo(slim.lastCollectedAt());
		assertThat(index.competitorBrandAccountIds()).isEqualTo(slim.competitorBrandAccountIds());
	}

	@Test
	void index는_풀_전용_코드에_스냅샷_시계열·댓글_조회를_하지_않는다() {
		assembler.index(USER_ID);
		then(brandReadRepository).should(never()).findSnapshots(argThat(codes ->
				codes.stream().anyMatch(POOL_ONLY_CODES::contains)));
		then(brandReadRepository).should(never()).findComments(anyCollection(), anyInt());
	}
```

커버리지 클램프·노출 필터·own-first 승계도 각 1케이스(기존 loadBrandPool 테스트의 시나리오를 index로 복제 — coveredUntil 앞 tagged 행 제외·direct 면제, direct-only 타인 미노출, 겹침 shortcode의 own 브랜드 귀속).

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"` / Expected: 컴파일 실패(`index` 미정의)

- [ ] **Step 3: 구현** — `PerformanceContentAssembler`에 `BrandPostCampaignRepository`·`BrandDirectPostRepository` 의존이 필요하면 `BrandPostAssembler` 공개 메서드 경유로 해소한다(campaignIdsByCode·shortCodesByUser는 hydrate 재료 — 직접 주입 최소화). 골격:

```java
	public DashboardIndex index(long userId) {
		// 1) 레거시 전량 조립(현행 유지 — 유저당 소량) + 링크·경쟁사·캠페인(현행 assemble와 동일)
		TrackingItemAssembler.AssembledList legacy = trackingItemAssembler.assembleList(userId);
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Set<String> competitorIds = competitorBrandAccountIds(links);
		Map<Long, CampaignRow> campaignsById = campaignRepository.findByUser(userId).stream()
				.collect(Collectors.toMap(CampaignRow::id, Function.identity()));

		// 2) 브랜드 풀 경량 인덱스 — own-first 순회, 브랜드별: findBrandPostIndex(ALL) → 커버리지
		//    클램프(직접 등록 면제) → 노출 필터(tagDetectedAt != null || owned) → putIfAbsent.
		//    최신 스냅샷 지표는 findLatestSnapshotsForBrand(ALL)로 별도 맵.
		// 3) 레거시 겹침 코드만 풀 하이드레이트(브랜드별 BrandPostIndex 어댑터로 hydrate 호출,
		//    withComments=false) → fromLegacy(item, shortcode, overlap, false)로 병합 카드 조립.
		//    겹침 없는 레거시는 fromLegacy(item, shortcode, null, false).
		// 4) 풀 전용 코드는 경량 ref 직조 — 작성자는 resolveAuthorsByKeys 배치(브랜드 넘어 합산),
		//    campaignId는 campaignIdsByCode(brandId, codes) head.
		// 5) refs = 레거시 카드 유도 ref + 풀 전용 ref, 업로드 최신순·contentKey 타이브레이크 정렬
		//    (현행 contents 정렬 계약과 동일 comparator).
	}
```

레거시 카드 → ref 유도(둘 다 이 클래스의 private static):

```java
	private static DashboardRef refOf(PerformanceContentResponse content) {
		PerformancePostResponse post = content.item().post();
		TrackingItemResponse.SnapshotResponse latest =
				post == null || post.snapshots().isEmpty() ? null
						: post.snapshots().get(post.snapshots().size() - 1);
		return new DashboardRef(content.item().id(), content.canonicalPostId(), content.source(),
				content.sponsorship(), content.item().status(), uploadedOn(content), content.brandAccountId(),
				content.item().campaignId(), content.item().handle(), content.item().followers(),
				latest == null ? null : latest.views(), latest == null ? null : latest.likes(),
				latest != null && latest.likesHidden(), latest == null ? null : latest.comments(),
				latest != null);
	}
```

풀 전용 경량 ref 직조 — **fromBrandPost와 같은 파생 규칙**(handle은 author_profile 우선·열거 관측 폴백 후 소문자, views 피드 null 규칙, status는 unavailable→hidden):

```java
	private static DashboardRef refOfPoolRow(String brandAccountId, BrandReadRepository.BrandPostIndexRow row,
			BrandReadRepository.LatestSnapshotRow snap, BrandReadRepository.AuthorRow author,
			List<String> campaignIds, boolean registeredByUser) {
		String username = author != null && author.username() != null ? author.username() : row.authorUsername();
		String handle = username == null ? "" : username.toLowerCase(Locale.ROOT);
		boolean reels = snap != null && "REELS".equalsIgnoreCase(snap.contentType());
		return new DashboardRef(SYNTHETIC_ID_PREFIX + row.shortCode(), row.shortCode(),
				BrandPostAssembler.resolveSource(row.tagDetectedAt(), row.directRegisteredAt(), registeredByUser),
				BrandSponsorshipClassifier.classify(row.isPaidPartnership(), row.caption()),
				row.unavailableAt() != null ? ItemStatus.HIDDEN : ItemStatus.TRACKING,
				KstTimestamps.toKstDate(row.takenAt()), brandAccountId,
				campaignIds.isEmpty() ? null : campaignIds.get(0), handle,
				author == null ? null : author.followers(),
				snap == null || !reels ? null : snap.views(),
				snap == null ? null : snap.likes(), snap != null && snap.likesHidden(),
				snap == null ? null : snap.comments(), snap != null);
	}
```

겹침 하이드레이트 어댑터(브랜드별):

```java
		BrandPostAssembler.BrandPostIndex adapter = new BrandPostAssembler.BrandPostIndex(
				List.of(), Set.copyOf(overlapCodesOfThisBrand), Map.of(), ownedShortCodes);
		List<BrandPostResponse> overlaps = brandPostAssembler.get().hydrate(userId, account, link.accountType(),
				adapter, List.copyOf(overlapCodesOfThisBrand), false);
```

구현 주의(전부 스펙 §1-2의 계약 승계):

- 클램프: `coveredOn = KstTimestamps.toKstDate(account.coveredUntil())`이 null 아니면 `row.directRegisteredAt() == null && KstTimestamps.toKstDate(row.takenAt()).isBefore(coveredOn)`인 행 제외 — `assembleBrandPosts`의 현행 술어와 동일.
- 노출 필터·owned 원장: `hasDirectRegistration`일 때만 `shortCodesByUser` 조회(현행 관용구). owned 집합은 `DashboardIndex.BrandHydration`에 실어 `hydratePage`가 재사용.
- `lastCollectedAt`: 현행 `lastCollectedAt(legacy, brand)` max 로직 재사용.
- 기존 `assemble`/`assembleSlim`은 이 태스크에서 **건드리지 않는다**(동치성 기준선 — 제거는 Task 7).

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 대시보드 경량 인덱스 패스 — DashboardRef/index(), 전량 조립과 판정 동치`

---

### Task 5: `hydratePage()`

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Produces: `public List<PerformanceContentResponse> hydratePage(DashboardIndex index, List<DashboardRef> page)` — 반환 순서 = page 순서, 목록 셰이프(댓글 없음).

- [ ] **Step 1: 실패하는 테스트** — 같은 픽스처에서:

```java
	@Test
	void hydratePage_전량은_assembleSlim과_같은_응답을_만든다() {
		var index = assembler.index(USER_ID);
		List<PerformanceContentResponse> hydrated = assembler.hydratePage(index, index.refs());
		assertThat(hydrated).isEqualTo(assembler.assembleSlim(USER_ID).contents());
	}

	@Test
	void hydratePage는_페이지_코드만_무거운_조회를_한다() {
		var index = assembler.index(USER_ID);
		// 풀 전용 ref 1건만 페이지로
		var page = index.refs().stream().filter(r -> index.brandByCode().containsKey(r.shortcode())).limit(1).toList();
		assembler.hydratePage(index, page);
		then(brandReadRepository).should().findSnapshots(argThat(codes -> codes.size() == 1));
	}
```

- [ ] **Step 2: 실패 확인** — Run: 위와 동일 테스트 클래스 / Expected: 컴파일 실패
- [ ] **Step 3: 구현**

```java
	/** 페이지에 실을 ref만 풀 카드로 — 레거시 카드는 index()가 이미 조립(재사용), 풀 전용만 브랜드별 hydrate. */
	public List<PerformanceContentResponse> hydratePage(DashboardIndex index, List<DashboardRef> page) {
		Map<String, List<String>> codesByBrand = new LinkedHashMap<>();
		for (DashboardRef ref : page) {
			if (!index.legacyCards().containsKey(ref.contentKey())) {
				codesByBrand.computeIfAbsent(index.brandByCode().get(ref.shortcode()), k -> new ArrayList<>())
						.add(ref.shortcode());
			}
		}
		Map<String, PerformanceContentResponse> poolCards = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : codesByBrand.entrySet()) {
			DashboardIndex.BrandHydration brand = index.brandsById().get(entry.getKey());
			BrandPostAssembler.BrandPostIndex adapter = new BrandPostAssembler.BrandPostIndex(
					List.of(), Set.copyOf(entry.getValue()), Map.of(), brand.ownedShortCodes());
			for (BrandPostResponse post : brandPostAssembler.get().hydrate(index.userId(), brand.account(),
					brand.accountType(), adapter, entry.getValue(), false)) {
				poolCards.put(post.shortcode(), fromBrandPost(post, index.campaignsById()));
			}
		}
		List<PerformanceContentResponse> out = new ArrayList<>(page.size());
		for (DashboardRef ref : page) {
			PerformanceContentResponse card = index.legacyCards().get(ref.contentKey());
			if (card == null) {
				card = poolCards.get(ref.shortcode());
			}
			if (card != null) {
				out.add(card);
			}
		}
		return out;
	}
```

- [ ] **Step 4: 통과 확인** → Run 동일 / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 대시보드 하이드레이트 패스 — 페이지 코드만 풀 카드 조립`

---

### Task 6: `/comparison` 입력을 ref로 전환

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` (comparison 라우트만)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java`, `V1PerformanceDashboardControllerTest.java`(comparison 스텁만)

**Interfaces:**
- Consumes: Task 4의 `DashboardRef`·`index()`.
- Produces: `assemble(long userId, List<DashboardRef> refs)` / 시각 주입 오버로드 동형. 집계 입력이 ref의 `brandAccountId`·`uploadedOn`·`latestViews/latestLikes/latestLikesHidden/latestComments`·`hasSnapshots`로 바뀐다(값은 최신 스냅샷 유래로 동일 — 결과 불변).

- [ ] **Step 1: 테스트 마이그레이션 먼저** — `PerformanceComparisonAssemblerTest`의 콘텐츠 픽스처 헬퍼를 `DashboardRef` 생성으로 치환(각 테스트의 시나리오·기대값은 그대로). `aggregate`가 쓰던 `latestSnapshot(content)` 유래 값이 ref 필드에 직접 있으므로 헬퍼는 단순해진다.
- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 구현** — `assemble`·`compare`·`aggregate`의 원소 타입을 `DashboardRef`로 치환: 그룹핑 `ref.brandAccountId()`, 구간 판정 `ref.uploadedOn()`, 지표 `ref.latestViews()`·`ref.latestLikes()`(likesHidden 규칙 동일)·`ref.latestComments()`, 스냅샷 없는 콘텐츠는 `!ref.hasSnapshots()`로 현행 "latest == null" 분기와 동일 처리. 컨트롤러 comparison 라우트는 `assembler.index(userId)` 후 분류 필터(source·sponsorship·campaignId — 현행과 동일 술어를 ref 필드로)를 걸어 넘긴다.
- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 비교 집계를 대시보드 인덱스로 전환 — 전량 풀 조립 고정비 제거`

---

### Task 7: `/contents` 컨트롤러 재편 — 정렬·페이지·신규 파라미터

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` (`assembleSlim` 제거)
- Modify: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerfDiagnosisHarnessTest.java` (`assembleSlim` → `index`+`hydratePage` 경로로 계측 대상 교체)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java`

**Interfaces:**
- Consumes: `index(userId)`·`hydratePage(index, page)`·`withLatestSnapshotOnly()`.
- Produces: HTTP 계약 — `sort=uploaded|views|likes|comments|engagement`(기본 uploaded)·`order=desc|asc`(기본 desc)·`offset`(≥0)·`limit`(1..100, 기본 100, 둘 다 생략 시 전량)·`accountIds`(쉼표 목록)·`authorUsername`·`snapshotMode=full|latest`·`meta.page={offset,limit|null}`.

- [ ] **Step 1: 기존 테스트 마이그레이션 + 신규 실패 테스트** — 스텁 경계를 교체한다. 헬퍼:

```java
	/** ref·카드 쌍 스텁 — index()가 refs를, hydratePage()가 넘어온 ref 순서대로 대응 카드를 준다. */
	private void givenIndexed(Set<String> competitorIds, PerformanceContentResponse... contents) {
		List<PerformanceContentAssembler.DashboardRef> refs =
				Arrays.stream(contents).map(TestRefs::refOf).toList();   // refOf = Task 4의 유도 규칙과 동일한 테스트 헬퍼
		Map<String, PerformanceContentResponse> byKey = Arrays.stream(contents)
				.collect(Collectors.toMap(c -> c.item().id(), Function.identity()));
		var index = new PerformanceContentAssembler.DashboardIndex(7L, refs,
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), competitorIds, byKey, Map.of(), Map.of(), Map.of());
		lenient().when(assembler.index(7L)).thenReturn(index);
		lenient().when(assembler.hydratePage(eq(index), anyList())).thenAnswer(inv ->
				((List<PerformanceContentAssembler.DashboardRef>) inv.getArgument(1)).stream()
						.map(r -> byKey.get(r.contentKey())).toList());
	}
```

기존 테스트들은 `givenAssembled` → `givenIndexed`로 치환(시나리오·기대값 불변 — statusCounts·필터 계약이 ref 경로에서도 같음을 이 치환 자체가 고정한다). 신규 테스트:

```java
	@Test
	void 정렬_views_desc는_최신_스냅샷_조회수_내림차순이고_null은_마지막이다() throws Exception {
		givenIndexed(contentWithViews("1", 100L), contentWithViews("2", null), contentWithViews("3", 300L));
		mockMvc.perform(get(CONTENTS + "?sort=views").with(user(principal())))
				.andExpect(jsonPath("$.data[0].item.id").value("3"))
				.andExpect(jsonPath("$.data[1].item.id").value("1"))
				.andExpect(jsonPath("$.data[2].item.id").value("2"));
	}

	@Test
	void 정렬_asc여도_null은_마지막이다() throws Exception { /* sort=views&order=asc → 1,3,2 */ }

	@Test
	void engagement는_좋아요_숨김이나_팔로워_미상이면_순위에서_빠진다() throws Exception { /* 마지막 배치 */ }

	@Test
	void 페이지_두_쪽의_합은_전량이고_중복이_없다() throws Exception {
		// 3건 → offset=0&limit=2, offset=2&limit=2 두 요청의 id 합집합 = 전체·교집합 없음,
		// 두 요청 모두 meta.total=3, meta.page.offset/limit 각각 반영
	}

	@Test
	void 페이지_파라미터_생략은_전량이고_meta_page는_0_null이다() throws Exception { /* 기존 응답 + page={0,null} */ }

	@Test
	void limit_범위_밖은_400이다() throws Exception { /* limit=0, limit=101, offset=-1 각각 400 */ }

	@Test
	void accountIds는_쉼표_목록이고_brandAccountId보다_우선한다() throws Exception {
		// accountIds=12,15&brandAccountId=99 → 12·15 소속만. accountIds 명시 시 accountType=all 함의도 여기서 고정
	}

	@Test
	void authorUsername은_그_작성자_게시물만이다() throws Exception { /* 대소문자 무시 일치 */ }

	@Test
	void snapshotMode_latest는_스냅샷을_최신_1개로_줄인다() throws Exception {
		// hydrate 카드(스냅샷 2개) → 응답 snapshots 길이 1 + previousDayValues 존재.
		// snapshotMode 생략 → 스냅샷 2개 그대로(하위 호환)
	}

	@Test
	void sort_order_snapshotMode_값_공간_밖은_400이다() throws Exception { }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"` / Expected: FAIL/컴파일 실패
- [ ] **Step 3: 구현** — contents() 재편(분류 술어·statusCounts·기간·status 필터는 현행 로직을 ref 필드로 이식):

```java
	// 파라미터 추가: sort, order, offset, limit, accountIds, authorUsername, snapshotMode
	// 흐름: index(userId) → 분류 술어(source·sponsorship·campaign·brand(accountIds 우선)·accountType·
	//       authorUsername) → counted(=statusCounts 모수) → status·기간 필터 → 정렬 → total 확정 →
	//       페이지 슬라이스 → hydratePage → snapshotMode=latest면 withLatestSnapshotOnly() 매핑 → meta

	private static final String SORT_UPLOADED = "uploaded";
	private static final int PAGE_LIMIT_MAX = 100;      // 브랜드 목록(PR #602)과 같은 캡
	private static final int PAGE_LIMIT_DEFAULT = 100;

	/** views·likes·comments·engagement 정렬 키 — likesHidden은 미상이라 null(항상 마지막). */
	private static Comparator<PerformanceContentAssembler.DashboardRef> comparator(String sortKey, boolean asc) {
		Comparator<PerformanceContentAssembler.DashboardRef> tie = Comparator
				.comparing(PerformanceContentAssembler.DashboardRef::uploadedOn,
						Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(PerformanceContentAssembler.DashboardRef::contentKey);
		if (SORT_UPLOADED.equals(sortKey)) {
			return Comparator.comparing(PerformanceContentAssembler.DashboardRef::uploadedOn,
							Comparator.nullsLast(asc ? Comparator.naturalOrder() : Comparator.reverseOrder()))
					.thenComparing(PerformanceContentAssembler.DashboardRef::contentKey);
		}
		Function<PerformanceContentAssembler.DashboardRef, Double> key = switch (sortKey) {
			case "views" -> r -> r.latestViews() == null ? null : r.latestViews().doubleValue();
			case "likes" -> r -> r.latestLikesHidden() || r.latestLikes() == null ? null
					: r.latestLikes().doubleValue();
			case "comments" -> r -> r.latestComments() == null ? null : r.latestComments().doubleValue();
			default -> V1PerformanceDashboardController::engagementOf;   // "engagement"
		};
		return Comparator.comparing(key,
						Comparator.nullsLast(asc ? Comparator.naturalOrder() : Comparator.reverseOrder()))
				.thenComparing(tie);
	}

	/** 참여율 = (최신 likes+comments) ÷ 작성자 팔로워 — 분자·분모 미상(숨김 포함)·팔로워 0은 순위 제외. */
	private static Double engagementOf(PerformanceContentAssembler.DashboardRef r) {
		if (r.followers() == null || r.followers() <= 0 || r.latestLikesHidden()
				|| r.latestLikes() == null || r.latestComments() == null) {
			return null;
		}
		return (r.latestLikes() + r.latestComments()) / (double) r.followers();
	}
```

`normalizePage`는 브랜드 컨트롤러의 `PageParams` 관용구를 그대로 복제(400 메시지 동일). `accountIds` 정규화:

```java
	/** 쉼표 목록 — 미지정·all은 null(필터 없음), 빈 항목은 무시, 전부 비면 null. */
	private static Set<String> normalizeAccountIds(String raw) {
		if (raw == null || raw.isBlank() || FILTER_ALL.equals(raw)) {
			return null;
		}
		Set<String> ids = Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		return ids.isEmpty() ? null : ids;
	}
```

브랜드 술어: `accountIds != null ? accountIds.contains(ref.brandAccountId()) : (brandFilter == null || brandFilter.equals(ref.brandAccountId()))`. `normalizeAccountType`의 함의 인자는 `brandFilter != null || accountIds != null`. `authorUsername` 술어는 분류 필터에 포함(statusCounts 모수에도 적용 — 인플루언서 뷰의 상태 뱃지가 그 작성자 기준이어야 한다, javadoc으로 명시). meta는 기존 `meta()` 유지 + `page` 키 추가(브랜드 컨트롤러 pageMeta 관용구). `assembleSlim`은 삭제하고 `PerfDiagnosisHarnessTest`의 계측 대상을 `index`+`hydratePage`(전량·페이지 두 케이스)로 교체.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 대시보드 목록 2단 조립 전환 + 정렬·페이지네이션·accountIds·authorUsername·snapshotMode`

---

### Task 8: 정리·전체 검증

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` (`assembleBrandPosts` 고아 확인)
- Modify: `docs/superpowers/specs/2026-08-13-performance-dashboard-etag-design.md` (§7 대체 주석)

- [ ] **Step 1: 고아 코드 정리** — `grep -rn 'assembleBrandPosts' --include='*.java' was/src`로 사용처 확인. 대시보드 전환 후 호출부가 없으면 `assembleBrandPosts`·`filterVisibleToUser` 등 그 경로 전용 헬퍼를 삭제(테스트 포함). 남은 사용처가 있으면 삭제하지 않고 javadoc의 "공개 이유(성과 대시보드)"만 실사용처로 교정.
- [ ] **Step 2: ETag 설계 문서 §7에 대체 주석** — "페이지네이션 기각(§7)은 2026-08-27 대시보드 목록 최적화 설계로 대체됨(전제였던 '전량 수신 후 클라이언트 필터' 구조가 UI 개편으로 소멸)" 한 줄을 §7 머리에 추가.
- [ ] **Step 3: 모듈 테스트 전체** — Run: `./gradlew :was:test` / Expected: PASS (Testcontainers 필요한 통합 테스트 포함 — Docker Desktop 기동 확인, `DOCKER_HOST`는 설정하지 않는다)
- [ ] **Step 4: Commit** — `refactor(was): 대시보드 전환 후 고아 조립 경로 정리 + ETag 설계 문서 갱신`

---

## 완료 판정 (PR ① 범위)

- 스펙 §7의 PR ① 항목: ref-counts 동치(테스트), 페이지 합=전량·정렬 안정성(테스트), snapshotMode 계약(테스트), 파라미터 400(테스트), 생략 시 전량 하위 호환(테스트).
- `PerfDiagnosisHarnessTest`(운영 덤프, 로컬 5434 + `PERF_DIAG=1`)로 전/후 실측 — 첫 페이지(limit=50) 기준 조립 시간이 데이터 전량이 아니라 페이지 크기에 비례하는지 확인하고 수치를 PR 본문에 기록.
- PR 생성 시 이 plan을 `plans/archive/`로 이동(세션 위생 규칙).
