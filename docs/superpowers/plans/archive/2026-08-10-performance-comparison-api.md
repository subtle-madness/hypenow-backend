# 성과 비교 집계 API 구현 계획

> 상태: ✅ 실행됨 (2026-08-10 — 태스크 5개 전부 완료, 최종 리뷰 통과)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/performance-dashboard/comparison` — 브랜드 계정 × 5구간(업로드일 기준, KST) 성과 집계 엔드포인트.

**Architecture:** 목록 API와 같은 `PerformanceContentAssembler.assemble()` 전량을 재사용해 메모리에서 필터·그룹핑·합산한다(숫자가 목록과 정의상 일치). 신규 `PerformanceComparisonAssembler`는 구간 산출·집계를 정적 순수 함수로 두고(DB 없이 단위 테스트), 계정 로딩(브랜드 연결 → brand_account)만 인스턴스 배선. 컨트롤러는 HTTP 표면(필터 값 공간 검증)만.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5 + Mockito(BDDMockito), `@WebMvcTest`(패키지 주의: `org.springframework.boot.webmvc.test.autoconfigure`).

**Spec:** [docs/superpowers/specs/2026-08-10-performance-comparison-api-design.md](../specs/2026-08-10-performance-comparison-api-design.md)

## Global Constraints

- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(was):`/`test(was):`.
- nullable 응답 필드는 키 생략 없이 명시적 null(계약 무결성 #1). DTO는 record.
- 테스트는 모듈 단위: `./gradlew :was:test --tests "..."`. 이 머신 도커는 Docker Desktop(`DOCKER_HOST` 미설정이 정답) — 다만 이 계획의 테스트는 전부 컨테이너 불필요(순수 단위 + WebMvcTest).
- covered 판정: `BrandAccountRow.lastSweptAt() != null`이면 5구간 전부 true, 아니면 전부 false(스펙 §covered — 등록일 기준 아님).
- 합계 규칙: non-null 값의 합, non-null이 하나도 없으면(0건 포함) null. `contentCount`·`*Count`는 항상 int(null 아님).
- 구간 5개 키·순서 고정: `1w`, `1w_1m`, `1m_3m`, `3m_6m`, `6m_12m`.

---

### Task 1: 응답 DTO + 구간 산출 순수 함수

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java` (이 태스크에선 구간 산출만)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java`

**Interfaces:**
- Consumes: 없음(신규).
- Produces: `PerformanceComparisonResponse(List<AccountComparison> accounts)`; 내부 record `AccountComparison(String brandAccountId, String username, String collectionStartedAt, List<Bucket> buckets)`; `Bucket(String key, boolean covered, int contentCount, Long views, Long likes, Long comments, Long followersSum, int viewsMissingCount, int likesHiddenCount, int followersMissingCount)`; `PerformanceComparisonAssembler.BucketRange(String key, LocalDate from, LocalDate to)` (패키지 공개); `static List<BucketRange> bucketRanges(LocalDate today)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.perfdashboard.PerformanceComparisonAssembler.BucketRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 비교 집계 순수 함수 검증 — 구간 산출·귀속·합산 전부 DB 없이 고정한다(스펙 2026-08-10). */
class PerformanceComparisonAssemblerTest {

	// ---------- 구간 산출 ----------

	@Test
	void 구간_5개가_FE_표_정의대로_나온다() {
		List<BucketRange> ranges = PerformanceComparisonAssembler.bucketRanges(LocalDate.parse("2026-08-10"));

		assertThat(ranges).containsExactly(
				new BucketRange("1w", LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-10")),
				new BucketRange("1w_1m", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-08-03")),
				new BucketRange("1m_3m", LocalDate.parse("2026-05-10"), LocalDate.parse("2026-07-09")),
				new BucketRange("3m_6m", LocalDate.parse("2026-02-10"), LocalDate.parse("2026-05-09")),
				new BucketRange("6m_12m", LocalDate.parse("2025-08-10"), LocalDate.parse("2026-02-09")));
	}

	@Test
	void 월말_클램프에도_구간이_겹치지_않는다() {
		// 3-31 기준: minusMonths(1)=2-28 — 클램프가 일어나는 날짜에서 경계 역전·겹침이 없어야 한다.
		List<BucketRange> ranges = PerformanceComparisonAssembler.bucketRanges(LocalDate.parse("2026-03-31"));

		for (int i = 0; i < ranges.size(); i++) {
			assertThat(ranges.get(i).from()).isBeforeOrEqualTo(ranges.get(i).to());
			if (i > 0) {
				assertThat(ranges.get(i).to()).isBefore(ranges.get(i - 1).from());
			}
		}
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"`
Expected: 컴파일 실패(`PerformanceComparisonAssembler` 미존재).

- [ ] **Step 3: 최소 구현**

`PerformanceComparisonResponse.java`:

```java
package com.celfit.was.v1.perfdashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 성과 비교 집계 응답(스펙 2026-08-10) — 브랜드 계정 × 5구간(업로드일 기준, KST).
 * 참여율은 계산하지 않는다 — followersSum(분모)만 내리고 FE가 (likes+comments)÷followersSum으로
 * 계산한다(비율을 미리 내리면 구간·계정 합산 시 재평균이 불가능해진다 — FE 규칙 ②).
 *
 * <p>nullable 필드는 키를 생략하지 않고 명시적 null(계약 무결성 #1). 집계 합은 non-null 값의
 * 합이고, non-null이 하나도 없으면(0건 포함) null이다 — 합 0(전부 관측됐는데 0)과 null(전부
 * 미제공)을 FE가 다르게 표시한다(FE 규칙 ③ — 피드는 views가 항상 null).
 */
public record PerformanceComparisonResponse(List<AccountComparison> accounts) {

	/** 브랜드 계정 1개의 비교 축 — collectionStartedAt은 brand_account.registered_at(KST ISO). */
	public record AccountComparison(String brandAccountId, String username, String collectionStartedAt,
			List<Bucket> buckets) {
	}

	/**
	 * 구간 1개 집계. covered는 계정 단위 판정이다 — 한 번이라도 스윕을 완주(lastSweptAt 존재)했으면
	 * 5구간 전부 true: 백필이 등록 윈도우 365일 전체를 열거하므로 등록 시점과 무관하다(스펙 §covered
	 * — 등록일 기준이 아니다). false는 "아직 첫 수집 전"이라는 뜻이고 집계값은 그대로 내린다
	 * (direct 콘텐츠는 레거시 파이프라인이라 스윕 전에도 존재할 수 있다).
	 */
	public record Bucket(
			@Schema(allowableValues = {"1w", "1w_1m", "1m_3m", "3m_6m", "6m_12m"}) String key,
			boolean covered,
			int contentCount,
			Long views,
			Long likes,
			Long comments,
			Long followersSum,
			int viewsMissingCount,
			int likesHiddenCount,
			int followersMissingCount) {
	}
}
```

`PerformanceComparisonAssembler.java` (이 태스크 분량):

```java
package com.celfit.was.v1.perfdashboard;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 성과 비교 집계 조립(스펙 2026-08-10) — 목록 API가 조립한 전량(필터 적용 후)을 받아 브랜드
 * 계정 × 5구간으로 합산한다. 구간 산출·합산은 전부 정적 순수 함수라 DB 없이 단위 테스트한다.
 */
@Component
public class PerformanceComparisonAssembler {

	/** 구간 1개(양끝 포함) — 업로드일이 [from, to]에 들면 귀속. */
	record BucketRange(String key, LocalDate from, LocalDate to) {
	}

	/**
	 * FE 표 그대로의 5구간(서로 안 겹침, 업로드일 기준·KST 달력일). 달력월 연산은
	 * {@link LocalDate#minusMonths}(말일 클램프)라 월말 기준일에도 경계가 역전되지 않는다.
	 */
	static List<BucketRange> bucketRanges(LocalDate today) {
		return List.of(
				new BucketRange("1w", today.minusDays(6), today),
				new BucketRange("1w_1m", today.minusMonths(1), today.minusDays(7)),
				new BucketRange("1m_3m", today.minusMonths(3), today.minusMonths(1).minusDays(1)),
				new BucketRange("3m_6m", today.minusMonths(6), today.minusMonths(3).minusDays(1)),
				new BucketRange("6m_12m", today.minusMonths(12), today.minusMonths(6).minusDays(1)));
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"`
Expected: PASS 2건.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonResponse.java \
        was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java \
        was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java
git commit -m "feat(was): 성과 비교 응답 DTO + 5구간 산출"
```

---

### Task 2: 계정 1개 집계 순수 함수

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java`

**Interfaces:**
- Consumes: Task 1의 `BucketRange`·`bucketRanges`; `BrandReadRepository.BrandAccountRow`(기존 — 16필드 record, 시그니처는 Step 1 픽스처 참조); `PerformanceContentAssembler.uploadedOn(content)`(기존 공개 정적 — 업로드일 키); `KstTimestamps.toKstIso(OffsetDateTime)`(기존).
- Produces: `static PerformanceComparisonResponse.AccountComparison compare(BrandAccountRow account, List<PerformanceContentResponse> accountContents, List<BucketRange> ranges)` — accountContents는 이미 이 계정으로 귀속된 것만 받는다(그룹핑은 Task 3).

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 테스트 클래스에 추가:

```java
	// ---------- 계정 집계 ----------
	// (클래스 상단 import에 추가: com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow,
	//  com.celfit.was.v1.monitoring.TrackingItemResponse, java.time.OffsetDateTime)

	private static final List<BucketRange> RANGES =
			PerformanceComparisonAssembler.bucketRanges(LocalDate.parse("2026-08-10"));

	/** ready 계정(lastSweptAt 존재) — covered 전 구간 true의 기준 픽스처. */
	private static BrandAccountRow readyAccount() {
		return new BrandAccountRow(2L, "cclime.beauty", LocalDate.parse("2026-08-10"),
				OffsetDateTime.parse("2026-08-09T18:00:00Z"), OffsetDateTime.parse("2026-05-14T00:12:00Z"),
				OffsetDateTime.parse("2026-05-14T01:00:00Z"), null,
				4143L, 15L, 82L, "", "끌리메 뷰티", null, true, null, "ACTIVE");
	}

	private static TrackingItemResponse.SnapshotResponse snapshot(Long views, Long likes,
			boolean likesHidden, Long comments) {
		return new TrackingItemResponse.SnapshotResponse("2026-08-09", views, likes, likesHidden,
				comments, null, null, false, null);
	}

	/** 콘텐츠 픽스처 — 스냅샷 없이 만들면 post.snapshots는 빈 목록(관측 전무)이다. */
	private static PerformanceContentResponse content(String shortcode, String brandAccountId,
			String uploadedAt, Long followers, TrackingItemResponse.SnapshotResponse... snapshots) {
		PerformanceContentResponse.PerformancePostResponse post = uploadedAt == null ? null
				: new PerformanceContentResponse.PerformancePostResponse(
						"https://www.instagram.com/p/" + shortcode + "/", shortcode, "reels", uploadedAt,
						"", List.of(), null, null, List.of(snapshots), null, false, 0, List.of());
		return new PerformanceContentResponse(
				new PerformanceContentResponse.PerformanceItemResponse(shortcode, "url", "tracking",
						"handle", "이름", null, followers, null, null, null, null, "2026-01-01", 90,
						null, post, null),
				"tagged", "unknown", shortcode, List.of(), brandAccountId);
	}

	@Test
	void 업로드일이_구간_경계에_정확히_귀속된다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				content("A", "2", "2026-08-04", 100L, snapshot(10L, 1L, false, 1L)),   // 1w 하한
				content("B", "2", "2026-08-03", 100L, snapshot(10L, 1L, false, 1L)),   // 1w_1m 상한
				content("C", "2", "2025-08-10", 100L, snapshot(10L, 1L, false, 1L)),   // 6m_12m 하한
				content("D", "2", "2025-08-09", 100L, snapshot(10L, 1L, false, 1L)),   // 12개월 밖 — 제외
				content("E", "2", null, 100L)),                                        // 업로드일 미상 — 제외
				RANGES);

		assertThat(result.brandAccountId()).isEqualTo("2");
		assertThat(result.username()).isEqualTo("cclime.beauty");
		assertThat(result.collectionStartedAt()).isEqualTo("2026-05-14T09:12:00+09:00");
		assertThat(result.buckets()).extracting("key", "contentCount").containsExactly(
				org.assertj.core.groups.Tuple.tuple("1w", 1),
				org.assertj.core.groups.Tuple.tuple("1w_1m", 1),
				org.assertj.core.groups.Tuple.tuple("1m_3m", 0),
				org.assertj.core.groups.Tuple.tuple("3m_6m", 0),
				org.assertj.core.groups.Tuple.tuple("6m_12m", 1));
	}

	@Test
	void 합계는_non_null만_더하고_전부_null이면_null이다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				// views 87400+20, likes 2800+null, comments 320+8 — 피드(views null)는 결측 카운트로.
				content("A", "2", "2026-08-09", 400000L, snapshot(87400L, 2800L, false, 320L)),
				content("B", "2", "2026-08-08", 12000L, snapshot(20L, null, true, 8L)),
				content("C", "2", "2026-08-07", null, snapshot(null, 24L, false, null))),
				RANGES);

		var oneWeek = result.buckets().get(0);
		assertThat(oneWeek.contentCount()).isEqualTo(3);
		assertThat(oneWeek.views()).isEqualTo(87420L);
		assertThat(oneWeek.likes()).isEqualTo(2824L);
		assertThat(oneWeek.comments()).isEqualTo(328L);
		assertThat(oneWeek.followersSum()).isEqualTo(412000L);
		assertThat(oneWeek.viewsMissingCount()).isEqualTo(1);
		assertThat(oneWeek.likesHiddenCount()).isEqualTo(1);
		assertThat(oneWeek.followersMissingCount()).isEqualTo(1);

		// 0건 구간은 합 전부 null(0이 아니다 — FE 규칙 ③), 카운트는 0.
		var empty = result.buckets().get(2);
		assertThat(empty.contentCount()).isZero();
		assertThat(empty.views()).isNull();
		assertThat(empty.likes()).isNull();
		assertThat(empty.comments()).isNull();
		assertThat(empty.followersSum()).isNull();
		assertThat(empty.viewsMissingCount()).isZero();
	}

	@Test
	void 스냅샷이_없는_콘텐츠는_지표_결측으로_센다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				content("A", "2", "2026-08-09", 100L)),   // 스냅샷 0개 — 관측 전무
				RANGES);

		var oneWeek = result.buckets().get(0);
		assertThat(oneWeek.contentCount()).isEqualTo(1);
		assertThat(oneWeek.views()).isNull();
		assertThat(oneWeek.viewsMissingCount()).isEqualTo(1);
		// 숨김은 관측이 있어야 셀 수 있다 — 스냅샷 자체가 없으면 hidden 아님.
		assertThat(oneWeek.likesHiddenCount()).isZero();
		assertThat(oneWeek.followersSum()).isEqualTo(100L);
	}

	@Test
	void 지표는_최신_스냅샷에서_읽는다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				// 스냅샷은 날짜 오름차순 계약 — 마지막(08-09)이 최신이다.
				content("A", "2", "2026-08-09", 100L,
						new TrackingItemResponse.SnapshotResponse("2026-08-08", 50L, 5L, false, 2L,
								null, null, false, null),
						snapshot(70L, 7L, false, 3L))),
				RANGES);

		assertThat(result.buckets().get(0).views()).isEqualTo(70L);
		assertThat(result.buckets().get(0).likes()).isEqualTo(7L);
	}

	@Test
	void 스윕_완주_전_계정은_전_구간_covered_false다() {
		BrandAccountRow collecting = new BrandAccountRow(3L, "laperi_kr", null, null,
				OffsetDateTime.parse("2026-08-09T00:00:00Z"), null, null,
				null, null, null, "", "", null, null, null, "ACTIVE");

		var ready = PerformanceComparisonAssembler.compare(readyAccount(), List.of(), RANGES);
		var notReady = PerformanceComparisonAssembler.compare(collecting, List.of(), RANGES);

		assertThat(ready.buckets()).allSatisfy(b -> assertThat(b.covered()).isTrue());
		assertThat(notReady.buckets()).allSatisfy(b -> assertThat(b.covered()).isFalse());
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"`
Expected: 컴파일 실패(`compare` 미존재).

- [ ] **Step 3: 구현** — `PerformanceComparisonAssembler`에 추가:

```java
	// (import 추가: com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow,
	//  com.celfit.was.v1.common.KstTimestamps, com.celfit.was.v1.monitoring.TrackingItemResponse,
	//  java.util.ArrayList)

	/**
	 * 계정 1개 집계 — accountContents는 이미 이 계정으로 귀속된 콘텐츠만 받는다(그룹핑은 호출부).
	 * covered는 계정 단위다: 한 번이라도 스윕 완주(lastSweptAt 존재 = collectionStatus ready)면
	 * 전 구간 true — 백필이 등록 윈도우 365일 전체를 열거하므로 등록 시점과 무관하다(스펙 §covered).
	 * false여도 집계값은 그대로 내린다(direct는 레거시 파이프라인이라 스윕 전에도 존재할 수 있다).
	 */
	static PerformanceComparisonResponse.AccountComparison compare(BrandAccountRow account,
			List<PerformanceContentResponse> accountContents, List<BucketRange> ranges) {
		boolean covered = account.lastSweptAt() != null;
		List<PerformanceComparisonResponse.Bucket> buckets = new ArrayList<>(ranges.size());
		for (BucketRange range : ranges) {
			buckets.add(aggregate(range, covered, accountContents));
		}
		return new PerformanceComparisonResponse.AccountComparison(String.valueOf(account.id()),
				account.username(), KstTimestamps.toKstIso(account.registeredAt()), List.copyOf(buckets));
	}

	/**
	 * 구간 1개 합산 — 지표는 콘텐츠별 <b>최신 스냅샷</b>(날짜 오름차순 계약이라 마지막 원소 — 목록의
	 * commentsTotal과 같은 관용구). 합은 non-null만 더하고 non-null이 하나도 없으면(0건 포함) null:
	 * 합 0(전부 관측됐는데 0)과 null(전부 미제공)을 FE가 다르게 그린다(규칙 ③ — 피드는 views 항상 null).
	 */
	private static PerformanceComparisonResponse.Bucket aggregate(BucketRange range, boolean covered,
			List<PerformanceContentResponse> contents) {
		int contentCount = 0;
		Long views = null;
		Long likes = null;
		Long comments = null;
		Long followersSum = null;
		int viewsMissing = 0;
		int likesHidden = 0;
		int followersMissing = 0;
		for (PerformanceContentResponse content : contents) {
			java.time.LocalDate uploadedOn = PerformanceContentAssembler.uploadedOn(content);
			// 업로드일 미상(post 없는 collecting 등)·구간 밖은 어느 구간에도 안 든다(스펙 §구간).
			if (uploadedOn == null || uploadedOn.isBefore(range.from()) || uploadedOn.isAfter(range.to())) {
				continue;
			}
			contentCount++;

			TrackingItemResponse.SnapshotResponse latest = latestSnapshot(content);
			views = accumulate(views, latest == null ? null : latest.views());
			likes = accumulate(likes, latest == null ? null : latest.likes());
			comments = accumulate(comments, latest == null ? null : latest.comments());
			followersSum = accumulate(followersSum, content.item().followers());

			if (latest == null || latest.views() == null) {
				viewsMissing++;
			}
			// 숨김은 관측이 있어야 셀 수 있다 — 스냅샷 자체가 없으면 결측이지 숨김이 아니다.
			if (latest != null && latest.likesHidden()) {
				likesHidden++;
			}
			if (content.item().followers() == null) {
				followersMissing++;
			}
		}
		return new PerformanceComparisonResponse.Bucket(range.key(), covered, contentCount,
				views, likes, comments, followersSum, viewsMissing, likesHidden, followersMissing);
	}

	/** null 유지 합산 — 첫 non-null 값에서 합이 시작되고, value가 null이면 sum을 건드리지 않는다. */
	private static Long accumulate(Long sum, Long value) {
		if (value == null) {
			return sum;
		}
		return sum == null ? value : sum + value;
	}

	/** 스냅샷은 날짜 오름차순 계약 — 마지막이 최신. post가 없거나 스냅샷 0개면 null(관측 전무). */
	private static TrackingItemResponse.SnapshotResponse latestSnapshot(PerformanceContentResponse content) {
		var post = content.item().post();
		if (post == null || post.snapshots() == null || post.snapshots().isEmpty()) {
			return null;
		}
		return post.snapshots().get(post.snapshots().size() - 1);
	}
```

(구현 시 `java.time.LocalDate`는 이미 import돼 있으므로 정규화한다 — 위 코드의 FQN은 계획 표기용.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"`
Expected: PASS 7건.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java \
        was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java
git commit -m "feat(was): 성과 비교 계정 집계 순수 함수 — null 유지 합산·covered 판정"
```

---

### Task 3: 계정 로딩 + 그룹핑 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java`

**Interfaces:**
- Consumes: `BrandLinkRepository.findAllActiveByUser(long userId)` → `List<BrandLinkRow>`(record: `id, userId, brandId, username, createdAt, deletedAt`); `BrandReadRepository.findAccount(long brandId)` → `Optional<BrandAccountRow>`; Task 2의 `compare`.
- Produces: `public PerformanceComparisonResponse assemble(long userId, List<PerformanceContentResponse> contents)` — contents는 컨트롤러가 분류 필터를 적용한 결과. 내부적으로 `assemble(userId, contents, LocalDate today)` 패키지 공개 오버로드(테스트용 시각 주입).

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 테스트 클래스에 추가. 클래스에 Mockito 배선 도입:

```java
// 클래스 선언부를 @ExtendWith(MockitoExtension.class)로 바꾸고 필드 추가
// (import 추가: com.celfit.was.monitoring.BrandLinkRepository, com.celfit.was.monitoring.BrandLinkRow,
//  com.celfit.was.monitoring.BrandReadRepository, java.util.Optional,
//  org.junit.jupiter.api.extension.ExtendWith, org.mockito.Mock,
//  org.mockito.junit.jupiter.MockitoExtension, static org.mockito.BDDMockito.given)

@ExtendWith(MockitoExtension.class)
class PerformanceComparisonAssemblerTest {

	@Mock
	BrandLinkRepository linkRepository;
	@Mock
	BrandReadRepository brandReadRepository;

	private PerformanceComparisonAssembler assembler() {
		return new PerformanceComparisonAssembler(linkRepository, Optional.of(brandReadRepository));
	}

	private static BrandLinkRow link(long brandId, String username) {
		return new BrandLinkRow(brandId, 7L, brandId, username,
				OffsetDateTime.parse("2026-05-14T00:12:00Z"), null);
	}
```

테스트 3건:

```java
	// ---------- 배선(계정 로딩·그룹핑) ----------

	@Test
	void 연결_순서대로_계정이_실리고_individual은_어느_계정에도_안_붙는다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(2L, "cclime.beauty"), link(3L, "laperi_kr")));
		given(brandReadRepository.findAccount(2L)).willReturn(Optional.of(readyAccount()));
		given(brandReadRepository.findAccount(3L)).willReturn(Optional.of(
				new BrandAccountRow(3L, "laperi_kr", null, null,
						OffsetDateTime.parse("2026-08-09T00:00:00Z"), null, null,
						null, null, null, "", "", null, null, null, "ACTIVE")));

		var response = assembler().assemble(7L, List.of(
				content("A", "2", "2026-08-09", 100L, snapshot(10L, 1L, false, 1L)),
				content("B", "3", "2026-08-09", 100L, snapshot(20L, 2L, false, 2L)),
				content("C", null, "2026-08-09", 100L, snapshot(30L, 3L, false, 3L))),   // individual
				LocalDate.parse("2026-08-10"));

		assertThat(response.accounts()).extracting("brandAccountId", "username")
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("2", "cclime.beauty"),
						org.assertj.core.groups.Tuple.tuple("3", "laperi_kr"));
		// individual(brandAccountId null)은 계정 귀속 불가라 어느 막대에도 없다(스펙 §집계 규칙).
		assertThat(response.accounts().get(0).buckets().get(0).views()).isEqualTo(10L);
		assertThat(response.accounts().get(1).buckets().get(0).views()).isEqualTo(20L);
		// 0건 계정도 실린다 — 두 계정 모두 5구간 전부 존재.
		assertThat(response.accounts()).allSatisfy(a -> assertThat(a.buckets()).hasSize(5));
	}

	@Test
	void monitoring_계정_행이_없는_연결은_경고_후_생략한다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(2L, "cclime.beauty"), link(9L, "ghost")));
		given(brandReadRepository.findAccount(2L)).willReturn(Optional.of(readyAccount()));
		given(brandReadRepository.findAccount(9L)).willReturn(Optional.empty());

		var response = assembler().assemble(7L, List.of(), LocalDate.parse("2026-08-10"));

		assertThat(response.accounts()).hasSize(1);
		assertThat(response.accounts().get(0).brandAccountId()).isEqualTo("2");
	}

	@Test
	void monitoring_비활성_환경은_빈_계정_목록이다() {
		var disabled = new PerformanceComparisonAssembler(linkRepository, Optional.empty());

		assertThat(disabled.assemble(7L, List.of(), LocalDate.parse("2026-08-10")).accounts()).isEmpty();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"`
Expected: 컴파일 실패(생성자·`assemble` 미존재).

- [ ] **Step 3: 구현** — 필드·생성자·assemble 추가:

```java
	// (import 추가: com.celfit.was.monitoring.BrandLinkRepository, com.celfit.was.monitoring.BrandLinkRow,
	//  com.celfit.was.monitoring.BrandReadRepository, java.util.Map, java.util.Optional,
	//  java.util.stream.Collectors, org.slf4j.Logger, org.slf4j.LoggerFactory)

	private static final Logger log = LoggerFactory.getLogger(PerformanceComparisonAssembler.class);

	private final BrandLinkRepository linkRepository;
	private final Optional<BrandReadRepository> brandReadRepository;

	public PerformanceComparisonAssembler(BrandLinkRepository linkRepository,
			Optional<BrandReadRepository> brandReadRepository) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
	}

	/** 컨트롤러 진입점 — contents는 분류 필터(source·sponsorship·campaignId) 적용 후 전량. */
	public PerformanceComparisonResponse assemble(long userId, List<PerformanceContentResponse> contents) {
		return assemble(userId, contents, LocalDate.now(KstTimestamps.KST));
	}

	/**
	 * 시각 주입 오버로드(테스트용). 계정 축은 활성 브랜드 연결 순서 그대로다 — 콘텐츠 0건 계정도
	 * 실린다(비교 화면의 축은 "연결된 계정"이지 "콘텐츠 있는 계정"이 아니다).
	 * individual(brandAccountId null)은 계정 귀속이 불가능해 어느 막대에도 안 든다(스펙 §집계 규칙
	 * — source=individual 필터 시 전 구간이 비는 것은 의도된 동작).
	 */
	PerformanceComparisonResponse assemble(long userId, List<PerformanceContentResponse> contents,
			LocalDate today) {
		if (brandReadRepository.isEmpty()) {
			return new PerformanceComparisonResponse(List.of());   // monitoring 비활성 — 비교 축 없음
		}
		List<BucketRange> ranges = bucketRanges(today);
		Map<String, List<PerformanceContentResponse>> byBrand = contents.stream()
				.filter(c -> c.brandAccountId() != null)
				.collect(Collectors.groupingBy(PerformanceContentResponse::brandAccountId));

		List<PerformanceComparisonResponse.AccountComparison> accounts = new ArrayList<>();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			Optional<BrandAccountRow> account = brandReadRepository.get().findAccount(link.brandId());
			if (account.isEmpty()) {
				// 연결은 살아 있는데 monitoring 계정 행이 없는 상태 — 목록 API와 동일하게 그 계정만 뺀다.
				log.warn("브랜드 연결의 monitoring 계정 행 부재 — 비교 축 생략 userId={}, brandId={}",
						userId, link.brandId());
				continue;
			}
			accounts.add(compare(account.get(),
					byBrand.getOrDefault(String.valueOf(link.brandId()), List.of()), ranges));
		}
		return new PerformanceComparisonResponse(List.copyOf(accounts));
	}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceComparisonAssemblerTest"`
Expected: PASS 10건.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java \
        was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java
git commit -m "feat(was): 성과 비교 계정 로딩·그룹핑 배선 — individual 제외, 연결 순서 유지"
```

---

### Task 4: 컨트롤러 엔드포인트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java`

**Interfaces:**
- Consumes: Task 3의 `PerformanceComparisonAssembler.assemble(long, List<PerformanceContentResponse>)`; 기존 `PerformanceContentAssembler.assemble(userId)`; 기존 private `normalizeFilter`·`matchesCampaign`(같은 클래스라 그대로 재사용).
- Produces: `GET /v1/performance-dashboard/comparison` — `ApiResponse<PerformanceComparisonResponse>`(FE는 `data.accounts`로 읽는다).

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 컨트롤러 테스트에 추가.
  `@MockitoBean PerformanceComparisonAssembler comparisonAssembler;` 필드를 추가하고
  (import: `org.mockito.ArgumentCaptor`, `static org.mockito.ArgumentMatchers.eq`,
  `static org.mockito.ArgumentMatchers.anyList`), 테스트 3건:

```java
	// ---------- comparison ----------

	private static final String COMPARISON = "/v1/performance-dashboard/comparison";

	@Test
	void comparison은_분류_필터를_걸어_비교_어셈블러에_넘긴다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", null, null),
				content("2", "SC2", "tracking", "2026-08-06", "tagged", "sponsored", null, "100"));
		given(comparisonAssembler.assemble(eq(7L), anyList())).willReturn(
				new PerformanceComparisonResponse(List.of(
						new PerformanceComparisonResponse.AccountComparison("100", "cclime.beauty",
								"2026-05-14T09:12:00+09:00", List.of(
										new PerformanceComparisonResponse.Bucket("1w", true, 1,
												null, 5L, 2L, 1000L, 1, 0, 0))))));

		mockMvc.perform(get(COMPARISON + "?source=tagged").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accounts.length()").value(1))
				.andExpect(jsonPath("$.data.accounts[0].brandAccountId").value("100"))
				.andExpect(jsonPath("$.data.accounts[0].buckets[0].key").value("1w"))
				.andExpect(jsonPath("$.data.accounts[0].buckets[0].covered").value(true))
				// null 합은 키를 유지한 명시적 null이다(계약 무결성 #1 — FE 규칙 ③).
				.andExpect(jsonPath("$.data.accounts[0].buckets[0]", Matchers.hasKey("views")))
				.andExpect(jsonPath("$.data.accounts[0].buckets[0].views").value(Matchers.nullValue()));

		// source=tagged 필터가 비교 모수에 적용됐는지 — individual 1건이 걸러져 tagged만 남아야 한다.
		ArgumentCaptor<List<PerformanceContentResponse>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).source()).isEqualTo("tagged");
	}

	@Test
	void comparison은_허용_값_밖_필터에_400이다() throws Exception {
		mockMvc.perform(get(COMPARISON + "?source=banana").with(user(principal())))
				.andExpect(status().isBadRequest());
		then(comparisonAssembler).should(never()).assemble(anyLong(), anyList());
	}

	@Test
	void comparison은_campaignId_none을_캠페인_없음으로_거른다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", "c-1", "100"),
				content("2", "SC2", "tracking", "2026-08-06", "tagged", "unknown", null, "100"));
		given(comparisonAssembler.assemble(eq(7L), anyList()))
				.willReturn(new PerformanceComparisonResponse(List.of()));

		mockMvc.perform(get(COMPARISON + "?campaignId=none").with(user(principal())))
				.andExpect(status().isOk());

		ArgumentCaptor<List<PerformanceContentResponse>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).item().campaignId()).isNull();
	}
```

주의: 기존 테스트의 `content(...)` 헬퍼 시그니처를 그대로 쓴다 — 8인자 오버로드
`content(id, shortcode, status, uploadedAt, source, sponsorship, campaignId, brandAccountId)`가
이미 있다(기존 테스트 파일 하단 참조 — 없으면 4인자 헬퍼가 위임하는 형태로 존재하니 실제
파일의 헬퍼를 확인해 맞출 것). `assertThat`은 `org.assertj.core.api.Assertions.assertThat`
정적 import 추가.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"`
Expected: 컴파일 실패(엔드포인트·빈 미존재) 또는 404.

- [ ] **Step 3: 구현** — 컨트롤러에 의존성·엔드포인트 추가:

```java
	// 필드·생성자 확장
	private final PerformanceComparisonAssembler comparisonAssembler;

	public V1PerformanceDashboardController(PerformanceContentAssembler assembler,
			PerformanceComparisonAssembler comparisonAssembler) {
		this.assembler = assembler;
		this.comparisonAssembler = comparisonAssembler;
	}

	/**
	 * 성과 비교 집계(스펙 2026-08-10) — 브랜드 계정 × 5구간. 기간 파라미터는 없다(5구간 항상 전부).
	 * 모수는 목록과 같은 조립 전량에 분류 필터(source·sponsorship·campaignId)만 건 것 — 목록·비교
	 * 막대의 숫자가 정의상 일치한다. individual은 계정 귀속이 불가능해 집계에서 빠진다
	 * (source=individual이면 전 구간이 빈다 — 의도된 동작).
	 */
	@GetMapping("/comparison")
	public ApiResponse<PerformanceComparisonResponse> comparison(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String campaignFilter = normalizeFilter(campaignId);

		List<PerformanceContentResponse> filtered = assembler.assemble(principal.getUserId()).contents().stream()
				.filter(c -> (sourceFilter == null || sourceFilter.equals(c.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(c.sponsorship()))
						&& matchesCampaign(c, campaignFilter))
				.toList();
		return ApiResponse.ok(comparisonAssembler.assemble(principal.getUserId(), filtered));
	}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"`
Expected: PASS(기존 + 신규 3건).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java \
        was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java
git commit -m "feat(was): 성과 비교 집계 엔드포인트 — GET /v1/performance-dashboard/comparison"
```

---

### Task 5: 모듈 검증 + 문서 갱신 + PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 1건 추가)

**Interfaces:**
- Consumes: Task 1~4 전부.
- Produces: develop 대상 PR.

- [ ] **Step 1: was 모듈 전체 테스트**

Run: `./gradlew :was:test`
Expected: 전부 PASS. (Testcontainers 클래스가 포함되므로 Docker Desktop이 떠 있어야 한다.
대량 실패가 나면 테스트 결함이 아니라 도커 데몬부터 확인 — CLAUDE.md 함정 참조.)

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 기록**

```markdown
## 2026-08-10 성과 비교 집계 API — covered는 등록일이 아니라 수집 완료 기준

`GET /v1/performance-dashboard/comparison`(브랜드 계정 × 5구간 집계) 신설. FE 원안은
"등록 전 구간은 빗금(covered=false)"이었으나, 백필이 등록 윈도우 365일 전체를 열거하므로
(크롤링 정책 v1) 등록일 기준 판정은 실제 수집된 과거 구간을 가리는 오보가 된다. covered는
계정 단위 — lastSweptAt 존재(ready)면 5구간 전부 true. 집계는 목록 API와 같은 조립 전량
재사용(SQL 집계 기각 — 목록·막대 숫자 일치 우선). individual은 brandAccountId가 없어
비교에서 제외(source=individual이면 전 구간이 빈다). 합계는 non-null만 더하고 전무하면
null(0과 구분 — 피드 views). 참여율은 서버가 계산하지 않고 followersSum(분모)만 내린다.
스펙: docs/superpowers/specs/2026-08-10-performance-comparison-api-design.md
```

- [ ] **Step 3: 커밋 + PR**

```bash
git add DECISIONS.md
git commit -m "docs: 성과 비교 집계 API 결정 기록"
git push -u origin hotfix/performance-comparison-api
gh pr create --base develop --title "feat(was): 성과 비교 집계 API — 계정 × 5구간" --body "..."
```

PR 본문에 포함: 스펙 링크, covered 기준 변경(FE 회신 필요 — "빗금은 첫 수집 전 상태에서만"),
`source=individual` 시 전 구간 빈 결과가 의도된 동작이라는 것, 응답이 `data.accounts`로
래핑된다는 것(FE 스케치의 최상위 `accounts`는 표준 ApiResponse 래퍼 안에 있다).
