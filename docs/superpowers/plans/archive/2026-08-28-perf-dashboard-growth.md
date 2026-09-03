# 성과 대시보드 시계열 집계 /growth (PR ③) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/performance-dashboard/growth` 신설 — `DashboardRef`를 업로드일(KST)로 버킷팅한 시계열 집계(개요 탭 전용, 신규 DB 쿼리 없음).

**Architecture:** 순수 정적 집계기(`PerformanceGrowthAggregator`)가 필터 적용된 ref를 granularity(day/week/month) 버킷으로 접고, 총계 시리즈 + 계정별 시리즈를 만든다. 필터·페이지 정규화는 PR ②의 `DashboardQueries` 재사용. 컨트롤러는 라우트·정규화·필터만.

**Tech Stack:** Java 21 · Spring Boot 4.1 · 기존 mock 기반 테스트 스타일.

**스펙:** [2026-08-27-perf-dashboard-list-api-optimization-design.md](../../specs/archive/2026-08-27-perf-dashboard-list-api-optimization-design.md) §5 (선행: PR ① ref 인덱스, PR ② DashboardQueries)

## Global Constraints

- 주석·커밋 메시지 한국어, prefix `feat(was):`/`docs:`.
- Docker Desktop — `DOCKER_HOST` 설정 금지. 테스트 전부 mock 기반.
- 테스트: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"`.
- 계약 무결성 규칙 #1 + FE 결측 구분: **값 없는 합계는 0이 아니라 null**. 결측 규칙(08-06 계약): 조회수 미제공(피드)·좋아요 숨김·팔로워 미확인은 합계에서 빼고 각각 카운트로 노출.
- 기존 표면(`/contents`·`/comparison`·`/influencers`·단건) 계약 불변 — 이 PR은 additive 라우트 1개 + 문서뿐.
- 버킷 경계는 전부 KST 달력일: `week`는 ISO 월요일 시작, `month`는 달력월. `from/to` 지정 시 빈 버킷 포함 **연속 생성**(contentCount 0·합계 null), 생략 시 데이터가 있는 범위(최소~최대 업로드일). 업로드일 미상 ref는 제외.

## File Structure

- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceGrowthResponse.java` — 응답 record(시리즈·포인트).
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceGrowthAggregator.java` — 순수 정적 집계기(버킷 산출 포함).
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` — `/growth` 라우트.
- Test: Create `PerformanceGrowthAggregatorTest.java` + `V1PerformanceDashboardControllerTest.java` 확장.

---

### Task 1: 성장 집계기 (순수 함수, TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceGrowthResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceGrowthAggregator.java`
- Test: Create `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceGrowthAggregatorTest.java`

**Interfaces:**
- Consumes: `PerformanceContentAssembler.DashboardRef`(17필드 — uploadedOn·brandAccountId·followers·latestViews/Likes/LikesHidden/Comments·hasSnapshots 사용).
- Produces:

```java
/**
 * 성과 대시보드 시계열 집계 응답(스펙 §5) — 개요 탭(요약 스트립·계정 성장·계정 비교) 전용.
 * 값이 없는 합계는 0이 아니라 null(FE 결측 구분 — 조회수 0과 피드 미제공을 가른다).
 */
public record PerformanceGrowthResponse(
		String granularity,                       // day | week | month
		List<AccountSeries> accounts,             // 계정별 시리즈 — 항상 포함(계정 비교 차트·CSV 재료)
		List<Point> points) {                     // 총계 시리즈(individual 미귀속 포함)

	/** 계정 1개의 시리즈 — points의 버킷 경계·개수는 총계와 동일하다(차트 축 공유). */
	public record AccountSeries(String brandAccountId, List<Point> points) {
	}

	/**
	 * 버킷 1개 — start·end는 KST 달력일(양끝 포함). 결측 규칙(08-06 계약): 합계는 아는 값만 더하고
	 * 하나도 모르면 null, 못 더한 것은 카운트로 노출한다.
	 */
	public record Point(
			String start, String end,             // YYYY-MM-DD
			int contentCount,                     // 버킷 내 게시물 수(스냅샷 유무 무관 — 차트 지표 중 하나)
			Long views, Long likes, Long comments,
			Long followersSum,                    // 게시물별 작성자 팔로워 합(참여율 분모) — 아는 것만
			int viewsMissingCount,                // 조회수 미상(피드 null·스냅샷 없음)
			int likesHiddenCount,                 // 좋아요 숨김 관측 수
			int followersMissingCount) {          // 작성자 팔로워 미확인 수
	}
}
```

```java
public final class PerformanceGrowthAggregator {

	public enum Granularity { DAY, WEEK, MONTH }

	/**
	 * 필터 적용 후 ref → 시계열 집계. 업로드일 미상 ref는 제외. from/to가 null이면 데이터 범위
	 * (최소~최대 업로드일)로 버킷을 만들고, 지정되면 그 구간의 버킷을 빈 버킷 포함 연속 생성한다.
	 *
	 * @param accountIds 계정 축 — 시리즈를 만들 brandAccountId 목록(0건 계정도 시리즈 유지).
	 *                   총계 points는 accountIds와 무관하게 refs 전량(미귀속 individual 포함)이다.
	 */
	public static PerformanceGrowthResponse aggregate(List<PerformanceContentAssembler.DashboardRef> refs,
			Granularity granularity, LocalDate from, LocalDate to, List<String> accountIds)

	/** 버킷 시작일 — DAY: 그대로, WEEK: ISO 월요일, MONTH: 1일. (패키지 공개 — 경계 테스트 대상) */
	static LocalDate bucketStart(LocalDate date, Granularity granularity)

	/** 버킷 종료일(양끝 포함) — DAY: 시작일, WEEK: +6일, MONTH: 월말. */
	static LocalDate bucketEnd(LocalDate start, Granularity granularity)
}
```

집계 규칙(전부 테스트 대상):
1. 업로드일 미상 ref 제외(총계·계정 시리즈 공통).
2. 버킷 귀속: `bucketStart(uploadedOn, g)` 키. 연속 생성: `bucketStart(rangeFrom)`부터 `bucketStart(rangeTo)`까지 granularity 걸음으로 전진(DAY +1일 / WEEK +1주 / MONTH +1달) — 사이 빈 버킷도 Point 생성(contentCount 0, 합계 4종 null, 카운트 3종 0).
3. 범위: from/to 둘 다 null이면 유효 ref의 min/max uploadedOn. 유효 ref 0건이고 from/to도 없으면 `points`·각 계정 시리즈 전부 빈 목록.
4. `contentCount` = 버킷 내 ref 수(스냅샷 유무 무관).
5. `views`: `hasSnapshots && latestViews != null`인 ref만 합산, 하나도 없으면 null. `viewsMissingCount` = 버킷 내 `!(hasSnapshots && latestViews != null)`인 ref 수(피드 null·스냅샷 없음 포함).
6. `likes`: `hasSnapshots && !latestLikesHidden && latestLikes != null`만 합산, 없으면 null. `likesHiddenCount` = `hasSnapshots && latestLikesHidden`인 ref 수.
7. `comments`: `hasSnapshots && latestComments != null`만 합산, 없으면 null.
8. `followersSum`: `followers != null`인 ref의 합(스냅샷 무관 — 작성자 속성), 없으면 null. `followersMissingCount` = `followers == null`인 ref 수.
9. `accounts`: 인자 `accountIds`의 **순서대로** 시리즈 생성 — 각 계정의 points는 그 계정 귀속 ref만으로 같은 버킷 범위(총계와 동일 경계·개수)를 다시 접은 것. 콘텐츠 0건 계정도 빈 버킷 시리즈 유지. 미귀속(individual)은 총계에만 실린다.
10. 결정성: 입력 순서와 무관(버킷 키 정렬 오름차순 출력).

- [ ] **Step 1: 실패하는 테스트** — 규칙별 최소 1케이스. 핵심 스케치(픽스처 `ref(...)` 로컬 빌더 — PR ② 집계기 테스트의 관용구 재사용):

```java
	@Test
	void month_버킷은_달력월이고_빈_달도_연속_생성된다() {
		// 2026-06-15 1건, 2026-08-03 2건, from/to 생략 → 6·7·8월 3버킷, 7월은 contentCount 0·합계 null
		var res = aggregate(refs, MONTH, null, null, List.of());
		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2026-06-01", "2026-07-01", "2026-08-01");
		assertThat(res.points().get(0).end()).isEqualTo("2026-06-30");
		assertThat(res.points().get(1).contentCount()).isZero();
		assertThat(res.points().get(1).views()).isNull();
	}

	@Test
	void week_버킷은_ISO_월요일_시작이다() {
		// 2026-08-27(목) → start 2026-08-24(월), end 2026-08-30(일)
	}

	@Test
	void day_버킷은_하루_단위다() { /* start == end */ }

	@Test
	void from_to_지정_시_그_구간의_버킷을_만들고_구간_밖_ref는_없다() {
		// 호출부가 이미 기간 필터를 하지만 집계기 단독으로도 from/to 밖 uploadedOn은 어느 버킷에도 안 실린다
	}

	@Test
	void 결측_규칙_조회수_좋아요숨김_팔로워를_각각_카운트한다() {
		// 릴스(views 100)·피드(views null)·스냅샷 없음·likes 숨김·followers null 조합 5건 → 합계·카운트 표 검증
	}

	@Test
	void followersSum은_게시물별_작성자_팔로워_합이다() { /* 같은 작성자 2건 → 2회 합산(참여율 분모 정의) */ }

	@Test
	void 계정_시리즈는_귀속_ref만_접고_0건_계정도_빈_시리즈를_유지한다() {
		// accountIds=[12, 15], ref는 12 귀속 1건 + individual 1건
		// → accounts[0](12): 해당 버킷 contentCount 1 / accounts[1](15): 전 버킷 0
		// → 총계 points: individual 포함 contentCount 2
	}

	@Test
	void 업로드일_미상_ref는_어디에도_안_실린다() { }

	@Test
	void 유효_ref가_없고_범위도_없으면_빈_시리즈다() { }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceGrowthAggregatorTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 구현** — `TreeMap<LocalDate, List<DashboardRef>>` 버킷팅 → 범위 산출 → 연속 버킷 생성 루프(각 버킷 Point 접기) → 계정별로 같은 범위 재사용. `bucketStart`: WEEK는 `date.with(java.time.DayOfWeek.MONDAY)`(ISO — `TemporalAdjusters` 불필요, `with(DayOfWeek)`가 같은 주 월요일), MONTH는 `date.withDayOfMonth(1)`. 전진: `plusDays(1)/plusWeeks(1)/plusMonths(1)`. 합산 헬퍼는 PR ② 집계기의 `accumulate` 관용구(각자 private 3줄 — 기존 결정대로 비공용).
- [ ] **Step 4: 통과 확인** — Run 동일 / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 성장 시계열 집계기 — ref 버킷팅 순수 함수(스펙 §5 규칙)`

---

### Task 2: `/growth` 라우트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java`

**Interfaces:**
- Consumes: Task 1 + `assembler.index(userId)` + `DashboardQueries`(parseDate·normalizeFilter·normalizeAccountIds·normalizeAccountType·matchesCampaign·matchesBrand·matchesAccountType·withinUploadWindow·FILTER_ALL).
- Produces:

```
GET /v1/performance-dashboard/growth
  ?from&to (YYYY-MM-DD, 형식 밖 400 — parseDate 관용구)
  &granularity=day|week|month (기본 month, 값 밖 400 "granularity 값이 올바르지 않아요.")
  &sponsorship &accountIds(쉼표) &campaignId &accountType
응답: ApiResponse<PerformanceGrowthResponse> — data 하나(목록형 meta 없음, FE 제안 셰이프)
```

구현 규칙:
- 필터는 목록과 같은 분류 축(스펙 §5 — sponsorship·accountIds·campaignId·accountType, 전부 `DashboardQueries` 위임). accountIds 명시 시 accountType=all 함의(동일 규칙). from/to는 `withinUploadWindow`로 ref 필터에도 적용(업로드일 미상 제외)하고 집계기에도 범위로 전달.
- **계정 축 결정**: `accountIds` 지정 시 그 목록(순서 유지), 미지정 시 `index.brandsById().keySet()`의 콘텐츠 유무 무관 전체(연결 활성 브랜드 — `/comparison`의 "축은 연결된 계정" 규칙과 동형)에서 accountType 필터(competitor 제외 등)를 통과하는 계정만. 근거를 javadoc으로 명시.
- 400 검증은 `index()` 호출 전.

- [ ] **Step 1: 실패하는 테스트** — `givenIndexed` 관용구:

```java
	@Test
	void growth는_월_버킷_총계와_계정_시리즈를_내린다() throws Exception {
		// 브랜드 12 귀속 1건(8월) + individual 1건(8월) → points[0].contentCount=2,
		// accounts엔 12만(개인 미귀속 시리즈 없음), accounts[0].points[0].contentCount=1
	}

	@Test
	void growth_필터는_집계_모수에_적용된다() throws Exception { /* sponsorship=sponsored */ }

	@Test
	void growth_from_to는_빈_버킷_연속_생성과_기간_필터를_함께_건다() throws Exception { }

	@Test
	void growth_granularity_값_밖은_400이고_index를_부르지_않는다() throws Exception { }

	@Test
	void growth는_하이드레이션을_부르지_않는다() throws Exception {
		// then(assembler).should(never()).hydratePage(any(), anyList());
	}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"` / Expected: FAIL(404)
- [ ] **Step 3: 구현** — 정규화(granularity는 컨트롤러 로컬 `normalizeGranularity` — 값 공간이 이 표면 전용) → `index()` → 분류+기간 필터 → 계정 축 결정 → `PerformanceGrowthAggregator.aggregate(filtered, g, from, to, accountAxis)` → `ApiResponse.ok(...)`.
- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 서버 시계열 집계 엔드포인트 — /performance-dashboard/growth`

---

### Task 3: 문서·전체 검증

**Files:**
- Modify: `docs/superpowers/specs/archive/2026-08-27-perf-dashboard-list-api-optimization-design.md` (상태 헤더만: `§1~§4(PR ①·②) 구현됨 · §5~§6 미구현` → `§1~§5(PR ①~③) 구현됨 · §6 미구현`)
- Modify: `DECISIONS.md` (`(PR ①·② 구현됨 · ③~④ 미구현)` → `(PR ①~③ 구현됨 · ④ 미구현)`)

- [ ] **Step 1: 상태 갱신** — 위 두 줄만.
- [ ] **Step 2: 모듈 전체** — Run: `./gradlew :was:test` / Expected: PASS(실제 카운트 확인)
- [ ] **Step 3: Commit** — `docs: PR ③ 상태 갱신 — 스펙 §5 구현됨`

---

## 완료 판정

- 스펙 §5 전 항목(버킷 3종 경계·연속 생성·결측 3카운트·followersSum·accounts[] 항상·individual 규칙·업로드일 미상 제외) 태스크 커버.
- 기존 표면 무회귀(additive 라우트뿐 — 기존 테스트 무수정 그린).
- PR 생성 시 이 plan을 `plans/archive/`로 이동.
