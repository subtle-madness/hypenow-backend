package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.MatchedTagRow;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
		return new BrandPostResponse(code, "100", source,
				"https://www.instagram.com/reel/" + code + "/", code, "reels",
				"2026-08-06T10:00:00+09:00", "캡션 원문", "https://cdn/thumb.jpg", null, null,
				"https://www.instagram.com/hashtag_influencer/", "hashtag_influencer", "해시태그 인플루언서",
				"https://cdn/author.jpg", false, 1000L, "unknown", null, "tracking",
				"2026-08-06T11:00:00+09:00", null, latest, latest == null ? List.of() : List.of(latest),
				comments, false, 0L, List.of(), List.of(), "2026-08-06T11:00:00+09:00",
				"2026-08-08T03:00:00+09:00", null, List.of(), List.of(), false);
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
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findMatchedTags(org.mockito.ArgumentMatchers.anyLong(), any());
	}
}
