package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.MatchedTagRow;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 구 해시태그 전용 목록의 <b>리라우팅</b> 조립(2026-08-27 해시태그 직접 수집 설계 §3) — 응답 셰이프는
 * 그대로 두고 데이터 산지만 통합 풀로 옮겼다. 격리·창 판정은 {@link BrandPostAssembler}가 이미
 * 끝낸 결과를 그대로 쓰므로, 여기서 고정하는 것은 <b>셰이프 매핑과 source=hashtag 부분집합 선택</b>뿐이다.
 */
class BrandHashtagPostAssemblerTest {

	private static BrandPostResponse post(String code, String source, Long likes, Long comments) {
		TrackingItemResponse.SnapshotResponse latest = likes == null ? null
				: new TrackingItemResponse.SnapshotResponse("2026-08-07", 500L, likes, false, comments,
						null, null, false, null);
		return new BrandPostResponse(code, "100", source, null,
				"https://www.instagram.com/reel/" + code + "/", code, "reels",
				"2026-08-06T10:00:00+09:00", "캡션 원문", "https://cdn/thumb.jpg", null, null,
				"https://www.instagram.com/hashtag_influencer/", "hashtag_influencer", "해시태그 인플루언서",
				"https://cdn/author.jpg", false, 1000L, "unknown", null, "tracking",
				"2026-08-06T11:00:00+09:00", null, latest, latest == null ? List.of() : List.of(latest),
				comments, false, 0L, List.of(), List.of(), "2026-08-06T11:00:00+09:00",
				"2026-08-08T03:00:00+09:00", null, List.of(), List.of(), List.of(), false);
	}

	private static BrandAccountRow account() {
		return new BrandAccountRow(100L, "lizda_official", LocalDate.of(2026, 8, 8),
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null, 30876L, 12L, 340L, null, "리즈다",
				"https://cdn/pic.jpg", true, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"), false, null);
	}

	@Test
	void source_hashtag_행만_구_셰이프로_내려준다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("TAG1", "tagged", 10L, 2L), post("HHH", "hashtag", 20L, 3L)));
		given(repository.findMatchedTags(eq(100L), any()))
				.willReturn(List.of(new MatchedTagRow("HHH", "끌리메")));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);
		var result = assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2025, 8, 8));

		assertThat(result).singleElement().satisfies(row -> {
			assertThat(row.shortcode()).isEqualTo("HHH");
			assertThat(row.postUrl()).isEqualTo("https://www.instagram.com/p/HHH/");
			assertThat(row.matchedTag()).isEqualTo("끌리메");
			assertThat(row.takenAt()).isEqualTo("2026-08-06T10:00:00+09:00");
			assertThat(row.caption()).isEqualTo("캡션 원문");
			assertThat(row.contentType()).isEqualTo("reels");
			assertThat(row.thumbnailUrl()).isEqualTo("https://cdn/thumb.jpg");
			assertThat(row.authorUsername()).isEqualTo("hashtag_influencer");
			assertThat(row.authorFullName()).isEqualTo("해시태그 인플루언서");
			assertThat(row.authorProfilePicUrl()).isEqualTo("https://cdn/author.jpg");
			assertThat(row.authorProfileUrl()).isEqualTo("https://www.instagram.com/hashtag_influencer/");
			assertThat(row.likes()).isEqualTo(20L);
			assertThat(row.comments()).isEqualTo(3L);
			assertThat(row.sponsorship()).isEqualTo("unknown");
			assertThat(row.firstSeenAt()).isEqualTo("2026-08-06T11:00:00+09:00");
			// 해시태그 게시물이 이제 전부 성과 측정 풀 소속이라 배지는 항상 켜진다.
			assertThat(row.brandPostId()).isEqualTo("HHH");
		});
	}

	/** 조회자의 신청 기간(링크 창) 밖 게시물은 본 목록과 마찬가지로 빠진다 — 두 화면이 어긋나면 안 된다. */
	@Test
	void 링크_창_밖_게시물은_빠진다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("HHH", "hashtag", 20L, 3L)));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);
		var result = assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2026, 8, 7));   // 창 시작이 게시물 업로드일(08-06)보다 뒤

		assertThat(result).isEmpty();
	}

	/** 스냅샷이 아직 없으면 지표는 null이다(구 셰이프도 nullable) — 조회 자체는 성공해야 한다. */
	@Test
	void 스냅샷이_없으면_지표는_null이다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("HHH", "hashtag", null, null)));
		given(repository.findMatchedTags(eq(100L), any())).willReturn(List.of());

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);
		var result = assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2025, 8, 8));

		assertThat(result).singleElement().satisfies(row -> {
			assertThat(row.likes()).isNull();
			assertThat(row.comments()).isNull();
			assertThat(row.matchedTag()).isNull();   // 매칭 기록이 없으면 배지 문구도 없다
		});
	}

	@Test
	void 해시태그_행이_없으면_빈_목록이고_매칭_태그를_조회하지_않는다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("TAG1", "tagged", 10L, 2L)));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);

		assertThat(assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2025, 8, 8))).isEmpty();
		verify(repository, never()).findMatchedTags(any(Long.class), any());
	}

	// ---------- count 전용 경로(P2, 2026-08-27 develop 도입 → 해시태그 직접 수집 전환 이후 재구현) ----------
	//
	// countForBrand는 이제 {@link BrandPostAssembler#indexForBrand}의 경량 산지를 직접 탄다(하이드레이트
	// 없음) — assembleForBrand(전량 조립)와 판정 산지가 다르므로, 아래 테스트는 그 둘이 같은 시드에서
	// 같은 값을 내는지(등가 계약)까지 함께 고정한다.

	@Test
	void count는_source_hashtag만_센다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.indexForBrand(eq(7L), any(), eq(false))).willReturn(index(
				ref("TAG1", BrandPostAssembler.SOURCE_TAGGED, LocalDate.of(2026, 8, 6)),
				ref("HHH", BrandPostAssembler.SOURCE_HASHTAG, LocalDate.of(2026, 8, 6))));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);

		assertThat(assembler.countForBrand(7L, account(), LocalDate.of(2025, 8, 8))).isEqualTo(1);
	}

	/** 링크 창 밖 게시물은 목록(withinWindow)과 같은 규칙으로 제외한다 — 두 화면이 어긋나면 안 된다. */
	@Test
	void count는_링크_창_밖_게시물을_제외한다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.indexForBrand(eq(7L), any(), eq(false))).willReturn(index(
				ref("HHH", BrandPostAssembler.SOURCE_HASHTAG, LocalDate.of(2026, 8, 6))));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);

		// 창 시작이 게시물 업로드일(08-06)보다 뒤
		assertThat(assembler.countForBrand(7L, account(), LocalDate.of(2026, 8, 7))).isZero();
	}

	/** 인덱스가 비어 있으면(브랜드 풀에 판정 통과 행이 하나도 없음) count는 0이다. */
	@Test
	void 인덱스가_비어있으면_count는_0이다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.indexForBrand(eq(7L), any(), eq(false))).willReturn(index());

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);

		assertThat(assembler.countForBrand(7L, account(), LocalDate.of(2025, 8, 8))).isZero();
	}

	/**
	 * count는 목록과 <b>같은 판정 산지</b>(indexForBrand의 isVisible·resolveSource — 등록자 전용 노출·
	 * 해시태그 격리 전부 그 안에서 끝난다)를 슬림 경로 위에서 태운 값이다 — 판정을 복제하면 뱃지 숫자와
	 * 목록 길이가 조용히 갈라진다.
	 */
	@Test
	void count는_목록과_같은_값이다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		LocalDate windowStart = LocalDate.of(2025, 8, 8);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("TAG1", "tagged", 10L, 2L), post("HHH", "hashtag", 20L, 3L)));
		given(repository.findMatchedTags(eq(100L), any()))
				.willReturn(List.of(new MatchedTagRow("HHH", "끌리메")));
		given(brandPostAssembler.indexForBrand(eq(7L), any(), eq(false))).willReturn(index(
				ref("TAG1", BrandPostAssembler.SOURCE_TAGGED, LocalDate.of(2026, 8, 6)),
				ref("HHH", BrandPostAssembler.SOURCE_HASHTAG, LocalDate.of(2026, 8, 6))));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);

		assertThat(assembler.countForBrand(7L, account(), windowStart))
				.isEqualTo(assembler.assembleForBrand(7L, account(), BrandAccountType.OWN, windowStart).size());
	}

	// ---------- 픽스처 ----------

	private static BrandPostAssembler.PostRef ref(String code, String source, LocalDate uploadedOn) {
		return new BrandPostAssembler.PostRef(code, source, "unknown", uploadedOn, null, "reels", null,
				"hashtag_influencer", "해시태그 인플루언서", "https://cdn/author.jpg", 1000L,
				"2026-08-06T10:00:00+09:00", List.of());
	}

	private static BrandPostAssembler.BrandPostIndex index(BrandPostAssembler.PostRef... refs) {
		Set<String> poolCodes = new LinkedHashSet<>();
		for (BrandPostAssembler.PostRef ref : refs) {
			poolCodes.add(ref.shortcode());
		}
		return new BrandPostAssembler.BrandPostIndex(List.of(refs), poolCodes, Map.of(), Set.of(), Map.of());
	}
}
