package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.monitoring.BrandReadRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 해시태그 발견 게시물 조립 규칙 단위 고정(스펙 §8, 별도 탭 결정 2026-08-12) — 리포지토리 없이
 * row record를 손으로 만들어 순수 변환만 검증한다. 배선(소유권·404)은 컨트롤러 슬라이스 테스트가 덮는다.
 */
class BrandHashtagPostAssemblerTest {

	@Test
	void postUrl은_콘텐츠_타입과_무관하게_항상_p_경로다() {
		var reels = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", "REELS"));
		var feed = BrandHashtagPostAssembler.toResponse(hashtagRow("III", "FEED"));

		assertThat(reels.postUrl()).isEqualTo("https://www.instagram.com/p/HHH/");
		assertThat(reels.contentType()).isEqualTo("reels");
		assertThat(feed.postUrl()).isEqualTo("https://www.instagram.com/p/III/");
		assertThat(feed.contentType()).isEqualTo("feed");
	}

	@Test
	void contentType_불명은_피드로_접는다() {
		var post = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", null));

		assertThat(post.contentType()).isEqualTo("feed");
	}

	@Test
	void 캡션과_author_지표는_열거_행에서_그대로_옮긴다() {
		var post = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", "REELS"));

		assertThat(post.shortcode()).isEqualTo("HHH");
		assertThat(post.matchedTag()).isEqualTo("#브랜드명");
		assertThat(post.caption()).isEqualTo("해시태그 캡션");
		assertThat(post.thumbnailUrl()).isEqualTo("https://cdn/hashtag-thumb.jpg");
		assertThat(post.authorUsername()).isEqualTo("hashtag_influencer");
		assertThat(post.authorFullName()).isEqualTo("해시태그 인플루언서");
		assertThat(post.authorProfilePicUrl()).isEqualTo("https://cdn/hashtag-author.jpg");
		assertThat(post.authorProfileUrl()).isEqualTo("https://www.instagram.com/hashtag_influencer/");
		assertThat(post.likes()).isEqualTo(20L);
		assertThat(post.comments()).isEqualTo(3L);
		assertThat(post.takenAt()).isEqualTo("2026-08-06T10:00:00+09:00");
		assertThat(post.firstSeenAt()).isEqualTo("2026-08-06T11:00:00+09:00");
	}

	@Test
	void 협찬은_캡션_확정_키워드로만_판정한다() {
		var sponsored = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", "REELS", "오늘의 #협찬 후기"));
		var organic = BrandHashtagPostAssembler.toResponse(hashtagRow("III", "REELS", "그냥 일상 기록"));

		assertThat(sponsored.sponsorship()).isEqualTo("sponsored");
		assertThat(organic.sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 무효_스킴_이미지_URL은_null로_강등한다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "javascript:alert(1)", OffsetDateTime.parse("2026-08-06T01:00:00Z"),
				"캡션", "REELS", "data:image/png;base64,AAAA", 20L, 3L,
				OffsetDateTime.parse("2026-08-06T02:00:00Z"), null);

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.authorProfilePicUrl()).isNull();
		assertThat(post.thumbnailUrl()).isNull();
	}

	@Test
	void author_username이_없으면_프로필_URL도_null이다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", null, null, null,
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS", null, null, null,
				OffsetDateTime.parse("2026-08-06T02:00:00Z"), null);

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.authorUsername()).isNull();
		assertThat(post.authorProfileUrl()).isNull();
		assertThat(post.likes()).isNull();
		assertThat(post.comments()).isNull();
	}

	/** image_object_path(monitoring 자체 아카이브 결과)가 있으면 원본 CDN URL보다 그걸 우선 서빙한다.
	 *  게시자 프로필은 아카이브 산지가 없어(보강 보류) 원본 그대로다. */
	@Test
	void 아카이브된_썸네일은_img_상대경로를_우선_서빙한다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				"monitor-hashtag-post/HHH.jpg");

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.thumbnailUrl()).isEqualTo("/img/monitor-hashtag-post/HHH.jpg");
		assertThat(post.authorProfilePicUrl()).isEqualTo("https://cdn/hashtag-author.jpg");
	}

	// ---------- 자사 제외 문자열 즉시 반영(2026-08-12 태그 관리 확장 짝) ----------

	/**
	 * 제외 문자열이 활성이면 조회 시점에 즉시 걸린다 — monitoring 스윕(SELF 판정)을 기다리지 않는다.
	 * matchesExclusion과 같은 대소문자 무시 contains 의미론(username에 term이 부분 포함되면 제외).
	 */
	@Test
	void 저장된_게시물이_제외_문자열_추가로_즉시_숨는다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findHashtagPosts(eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "cclime_influencer")));
		given(repository.findActiveExclusionTerms(1L)).willReturn(List.of("cclime"));

		var assembler = new BrandHashtagPostAssembler(repository);
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(1L);

		assertThat(result).isEmpty();
	}

	/** 제외 문자열을 삭제(비활성화)하면 같은 게시물이 다시 보인다 — findActiveExclusionTerms가 빈 목록을 준다. */
	@Test
	void 제외_문자열_삭제로_다시_보인다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findHashtagPosts(eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "cclime_influencer")));
		given(repository.findActiveExclusionTerms(1L)).willReturn(List.of());

		var assembler = new BrandHashtagPostAssembler(repository);
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(1L);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).shortcode()).isEqualTo("HHH");
	}

	@Test
	void 제외_문자열은_대소문자_무시_부분_포함으로_매칭된다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findHashtagPosts(eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "CClime_Official")));
		given(repository.findActiveExclusionTerms(1L)).willReturn(List.of("cclime"));

		var assembler = new BrandHashtagPostAssembler(repository);
		assertThat(assembler.assembleForBrand(1L)).isEmpty();
	}

	@Test
	void 무관한_게시자는_제외_문자열과_무관하게_남는다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findHashtagPosts(eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "unrelated_influencer")));
		given(repository.findActiveExclusionTerms(1L)).willReturn(List.of("cclime"));

		var assembler = new BrandHashtagPostAssembler(repository);
		assertThat(assembler.assembleForBrand(1L)).hasSize(1);
	}

	private static BrandReadRepository.BrandHashtagPostRow hashtagRowWithAuthor(String code, String authorUsername) {
		return new BrandReadRepository.BrandHashtagPostRow(code, "#브랜드명", authorUsername,
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null);
	}

	// ---------- 픽스처 ----------

	private static BrandReadRepository.BrandHashtagPostRow hashtagRow(String code, String contentType) {
		return hashtagRow(code, contentType, "해시태그 캡션");
	}

	private static BrandReadRepository.BrandHashtagPostRow hashtagRow(String code, String contentType,
			String caption) {
		return new BrandReadRepository.BrandHashtagPostRow(code, "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), caption, contentType,
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				null);
	}
}
