package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.BrandReadRepository;
import java.time.OffsetDateTime;
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
