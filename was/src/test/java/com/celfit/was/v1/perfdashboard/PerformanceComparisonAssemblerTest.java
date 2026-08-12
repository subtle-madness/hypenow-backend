package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import static org.mockito.BDDMockito.given;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceComparisonAssembler.BucketRange;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 비교 집계 순수 함수 검증 — 구간 산출·귀속·합산 전부 DB 없이 고정한다(스펙 2026-08-10). */
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
		return link(brandId, username, BrandAccountType.OWN);
	}

	private static BrandLinkRow link(long brandId, String username, String accountType) {
		return new BrandLinkRow(brandId, 7L, brandId, username, accountType,
				OffsetDateTime.parse("2026-05-14T00:12:00Z"), null);
	}

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

	// ---------- 계정 집계 ----------

	private static final LocalDate TODAY = LocalDate.parse("2026-08-10");
	private static final List<BucketRange> RANGES = PerformanceComparisonAssembler.bucketRanges(TODAY);

	/**
	 * 백필 완주+스윕 안착 계정 — 창(months)만 바꿔 covered 매트릭스에 재사용한다.
	 * collection_started_at은 registered_at(05-14)과 다른 값(06-01)으로 고정 — 확장 시 갱신되는
	 * 앵커라 두 값이 갈라질 수 있음을 픽스처가 드러낸다.
	 */
	private static BrandAccountRow completedAccount(int months) {
		return new BrandAccountRow(2L, "cclime.beauty", LocalDate.parse("2026-08-10"),
				OffsetDateTime.parse("2026-08-09T18:00:00Z"), OffsetDateTime.parse("2026-05-14T00:12:00Z"),
				OffsetDateTime.parse("2026-05-14T01:00:00Z"), null,
				4143L, 15L, 82L, "", "끌리메 뷰티", null, true, null, "ACTIVE", null,
				months, OffsetDateTime.parse("2026-06-01T00:00:00Z"));
	}

	/** 12개월(전 구간 covered) 기준 픽스처. */
	private static BrandAccountRow readyAccount() {
		return completedAccount(12);
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
		var result = PerformanceComparisonAssembler.compare(readyAccount(), BrandAccountType.OWN, List.of(
				content("A", "2", "2026-08-04", 100L, snapshot(10L, 1L, false, 1L)),   // 1w 하한
				content("B", "2", "2026-08-03", 100L, snapshot(10L, 1L, false, 1L)),   // 1w_1m 상한
				content("C", "2", "2025-08-10", 100L, snapshot(10L, 1L, false, 1L)),   // 6m_12m 하한
				content("D", "2", "2025-08-09", 100L, snapshot(10L, 1L, false, 1L)),   // 12개월 밖 — 제외
				content("E", "2", null, 100L)),                                        // 업로드일 미상 — 제외
				RANGES, TODAY);

		assertThat(result.brandAccountId()).isEqualTo("2");
		assertThat(result.username()).isEqualTo("cclime.beauty");
		// 브랜드 계정 API와 같은 앵커(collection_started_at, 확장 시 갱신) — registered_at(05-14) 아님.
		assertThat(result.collectionStartedAt()).isEqualTo("2026-06-01T09:00:00+09:00");
		assertThat(result.buckets()).extracting("key", "contentCount").containsExactly(
				tuple("1w", 1),
				tuple("1w_1m", 1),
				tuple("1m_3m", 0),
				tuple("3m_6m", 0),
				tuple("6m_12m", 1));
	}

	@Test
	void 합계는_non_null만_더하고_전부_null이면_null이다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), BrandAccountType.OWN, List.of(
				// views 87400+20, likes 2800+null, comments 320+8 — 피드(views null)는 결측 카운트로.
				content("A", "2", "2026-08-09", 400000L, snapshot(87400L, 2800L, false, 320L)),
				content("B", "2", "2026-08-08", 12000L, snapshot(20L, null, true, 8L)),
				content("C", "2", "2026-08-07", null, snapshot(null, 24L, false, null))),
				RANGES, TODAY);

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
		var result = PerformanceComparisonAssembler.compare(readyAccount(), BrandAccountType.OWN, List.of(
				content("A", "2", "2026-08-09", 100L)),   // 스냅샷 0개 — 관측 전무
				RANGES, TODAY);

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
		var result = PerformanceComparisonAssembler.compare(readyAccount(), BrandAccountType.OWN, List.of(
				// 스냅샷은 날짜 오름차순 계약 — 마지막(08-09)이 최신이다.
				content("A", "2", "2026-08-09", 100L,
						new TrackingItemResponse.SnapshotResponse("2026-08-08", 50L, 5L, false, 2L,
								null, null, false, null),
						snapshot(70L, 7L, false, 3L))),
				RANGES, TODAY);

		assertThat(result.buckets().get(0).views()).isEqualTo(70L);
		assertThat(result.buckets().get(0).likes()).isEqualTo(7L);
	}

	@Test
	void 백필_완주_전_계정은_전_구간_covered_false다() {
		BrandAccountRow collecting = new BrandAccountRow(3L, "laperi_kr", null, null,
				OffsetDateTime.parse("2026-08-09T00:00:00Z"), null, null,
				null, null, null, "", "", null, null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-09T00:00:00Z"));
		// 08-12 스트리밍 백필: 서빙 창(30일)만 커버해도 last_swept_at이 먼저 찍힌다 — 이 상태는
		// 365일 전량이 아니라서 covered는 false여야 한다(판정 기준을 backfill_completed_at으로 옮긴 이유).
		BrandAccountRow earlyServing = new BrandAccountRow(4L, "hypenow_kr", LocalDate.parse("2026-08-10"),
				OffsetDateTime.parse("2026-08-09T18:00:00Z"), OffsetDateTime.parse("2026-08-09T00:00:00Z"),
				null, null, null, null, null, "", "", null, null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-09T00:00:00Z"));

		var ready = PerformanceComparisonAssembler.compare(readyAccount(), BrandAccountType.OWN,
				List.of(), RANGES, TODAY);
		var notReady = PerformanceComparisonAssembler.compare(collecting, BrandAccountType.OWN,
				List.of(), RANGES, TODAY);
		var early = PerformanceComparisonAssembler.compare(earlyServing, BrandAccountType.OWN,
				List.of(), RANGES, TODAY);

		assertThat(ready.buckets()).allSatisfy(b -> assertThat(b.covered()).isTrue());
		assertThat(notReady.buckets()).allSatisfy(b -> assertThat(b.covered()).isFalse());
		assertThat(early.buckets()).allSatisfy(b -> assertThat(b.covered()).isFalse());
	}

	@Test
	void 버킷_covered는_collectionMonths_창_안에서만_true다() {
		// 완주해도 창 밖 버킷은 수집한 적 자체가 없다 — covered=true·contentCount=0이면 FE가
		// "그 기간 게시물 없음"으로 오독한다(#454 리뷰 ②). 버킷 먼 쪽 경계(from)가 창 안이어야 true.
		Map<Integer, List<Boolean>> expected = Map.of(
				1, List.of(true, true, false, false, false),
				3, List.of(true, true, true, false, false),
				6, List.of(true, true, true, true, false),
				12, List.of(true, true, true, true, true));

		expected.forEach((months, coveredByBucket) -> {
			var result = PerformanceComparisonAssembler.compare(completedAccount(months),
					BrandAccountType.OWN, List.of(), RANGES, TODAY);
			assertThat(result.buckets())
					.extracting(PerformanceComparisonResponse.Bucket::covered)
					.as("months=%d", months)
					.containsExactlyElementsOf(coveredByBucket);
		});
	}

	@Test
	void 월말_클램프에서도_창_하한과_버킷_하한이_정렬된다() {
		// 3-31 기준 minusMonths 클램프 — 창 하한과 1m_3m 하한이 같은 연산(minusMonths(3))이라
		// 정확히 일치해 경계 버킷이 창 밖으로 밀려나지 않는다.
		LocalDate today = LocalDate.parse("2026-03-31");
		var result = PerformanceComparisonAssembler.compare(completedAccount(3), BrandAccountType.OWN,
				List.of(), PerformanceComparisonAssembler.bucketRanges(today), today);

		assertThat(result.buckets()).extracting("key", "covered").containsExactly(
				tuple("1w", true), tuple("1w_1m", true), tuple("1m_3m", true),
				tuple("3m_6m", false), tuple("6m_12m", false));
	}

	@Test
	void 확장_중_계정은_전_구간_covered_false다() {
		// 기간 확장(스펙 §3)은 collection_months를 먼저 올리고(backfill_completed_at 보존,
		// last_swept_on NULL) 백필을 재제출한다 — 창 기준 판정만 하면 새 구간이 데이터 없이
		// covered=true가 되어 같은 오보가 재발한다. 완주 이력+last_swept_on 빔 = 확장 중 → 보수적 false.
		BrandAccountRow expanding = new BrandAccountRow(5L, "brandy_kr", null,
				OffsetDateTime.parse("2026-08-09T18:00:00Z"), OffsetDateTime.parse("2026-05-14T00:12:00Z"),
				OffsetDateTime.parse("2026-05-14T01:00:00Z"), null,
				null, null, null, "", "", null, null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-10T00:00:00Z"));

		var result = PerformanceComparisonAssembler.compare(expanding, BrandAccountType.OWN,
				List.of(), RANGES, TODAY);

		assertThat(result.buckets()).allSatisfy(b -> assertThat(b.covered()).isFalse());
	}

	// ---------- 배선(계정 로딩·그룹핑) ----------

	@Test
	void 연결_순서대로_계정이_실리고_individual은_어느_계정에도_안_붙는다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(2L, "cclime.beauty"), link(3L, "laperi_kr")));
		given(brandReadRepository.findAccount(2L)).willReturn(Optional.of(readyAccount()));
		given(brandReadRepository.findAccount(3L)).willReturn(Optional.of(
				new BrandAccountRow(3L, "laperi_kr", null, null,
						OffsetDateTime.parse("2026-08-09T00:00:00Z"), null, null,
						null, null, null, "", "", null, null, null, "ACTIVE", null,
						12, OffsetDateTime.parse("2026-08-09T00:00:00Z"))));

		var response = assembler().assemble(7L, List.of(
				content("A", "2", "2026-08-09", 100L, snapshot(10L, 1L, false, 1L)),
				content("B", "3", "2026-08-09", 100L, snapshot(20L, 2L, false, 2L)),
				content("C", null, "2026-08-09", 100L, snapshot(30L, 3L, false, 3L))),   // individual
				LocalDate.parse("2026-08-10"));

		assertThat(response.accounts()).extracting("brandAccountId", "username")
				.containsExactly(
						tuple("2", "cclime.beauty"),
						tuple("3", "laperi_kr"));
		// individual(brandAccountId null)은 계정 귀속 불가라 어느 막대에도 없다(스펙 §집계 규칙).
		assertThat(response.accounts().get(0).buckets().get(0).views()).isEqualTo(10L);
		assertThat(response.accounts().get(1).buckets().get(0).views()).isEqualTo(20L);
		// 0건 계정도 실린다 — 두 계정 모두 5구간 전부 존재.
		assertThat(response.accounts()).allSatisfy(a -> assertThat(a.buckets()).hasSize(5));
	}

	@Test
	void 계정별_accountType은_링크에서_실리고_경쟁사도_축에_남는다() {
		// 비교 화면엔 accountType 필터가 없다(스펙 §6) — 경쟁사 계정도 그대로 축에 실린다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(2L, "cclime.beauty"), link(3L, "laperi_kr", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(2L)).willReturn(Optional.of(readyAccount()));
		given(brandReadRepository.findAccount(3L)).willReturn(Optional.of(
				new BrandAccountRow(3L, "laperi_kr", null, null,
						OffsetDateTime.parse("2026-08-09T00:00:00Z"), null, null,
						null, null, null, "", "", null, null, null, "ACTIVE", null,
						12, OffsetDateTime.parse("2026-08-09T00:00:00Z"))));

		var response = assembler().assemble(7L, List.of(), LocalDate.parse("2026-08-10"));

		assertThat(response.accounts()).extracting("brandAccountId", "accountType")
				.containsExactly(tuple("2", "own"), tuple("3", "competitor"));
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
}
