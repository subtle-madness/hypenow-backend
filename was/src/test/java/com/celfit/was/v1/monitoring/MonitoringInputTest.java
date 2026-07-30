package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** MonitoringInput 파서(6.27) — 링크 3형(p/reel/reels)·프로필 경유·쿼리스트링·share·핸들 정규화. */
class MonitoringInputTest {

	@Test
	void p_링크는_shortcode를_추출하고_canonical은_p로_고정한다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/p/DGxxxxx/");

		assertThat(result).isInstanceOf(MonitoringInput.Post.class);
		MonitoringInput.Post post = (MonitoringInput.Post) result;
		assertThat(post.shortCode()).isEqualTo("DGxxxxx");
		assertThat(post.canonicalUrl()).isEqualTo("https://www.instagram.com/p/DGxxxxx/");
	}

	@Test
	void reel_링크는_그대로_reel로_canonical화된다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/reel/ABC123/");

		MonitoringInput.Post post = (MonitoringInput.Post) result;
		assertThat(post.shortCode()).isEqualTo("ABC123");
		assertThat(post.canonicalUrl()).isEqualTo("https://www.instagram.com/reel/ABC123/");
	}

	@Test
	void reels_링크는_reel로_접혀_canonical화된다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/reels/ABC123/");

		MonitoringInput.Post post = (MonitoringInput.Post) result;
		assertThat(post.shortCode()).isEqualTo("ABC123");
		assertThat(post.canonicalUrl()).isEqualTo("https://www.instagram.com/reel/ABC123/");
	}

	@Test
	void 프로필_경유_주소도_shortcode를_추출한다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/glowdeep.official/reel/ABC123/");

		MonitoringInput.Post post = (MonitoringInput.Post) result;
		assertThat(post.shortCode()).isEqualTo("ABC123");
		assertThat(post.canonicalUrl()).isEqualTo("https://www.instagram.com/reel/ABC123/");
	}

	@Test
	void 쿼리스트링이_붙은_URL도_shortcode만_추출한다() {
		MonitoringInput result =
				MonitoringInput.parsePost("https://www.instagram.com/reel/ABC123/?utm_source=ig_web_copy_link");

		MonitoringInput.Post post = (MonitoringInput.Post) result;
		assertThat(post.shortCode()).isEqualTo("ABC123");
		assertThat(post.canonicalUrl()).isEqualTo("https://www.instagram.com/reel/ABC123/");
	}

	@Test
	void 트레일링_슬래시_없이_쿼리스트링만_붙어도_shortcode를_추출한다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/p/ABC123?igshid=xyz");

		MonitoringInput.Post post = (MonitoringInput.Post) result;
		assertThat(post.shortCode()).isEqualTo("ABC123");
	}

	@Test
	void 대문자_URL도_파싱되고_shortcode_대소문자는_보존된다() {
		MonitoringInput result = MonitoringInput.parsePost("HTTPS://WWW.INSTAGRAM.COM/REEL/AbC123/");

		assertThat(result).isInstanceOf(MonitoringInput.Post.class);
		MonitoringInput.Post post = (MonitoringInput.Post) result;
		// shortcode는 인스타그램이 대소문자를 구분해 발급하는 식별자라 소문자화하면 다른 게시물을
		// 가리키게 된다 — 스킴·도메인·경로 타입(REEL)은 대소문자 무관 매칭이어도 shortcode 값 자체는
		// 입력 그대로 보존해야 한다.
		assertThat(post.shortCode()).isEqualTo("AbC123");
		assertThat(post.canonicalUrl()).isEqualTo("https://www.instagram.com/reel/AbC123/");
	}

	@Test
	void share_링크는_해소_없이_원문_그대로_ShareLink로_넘긴다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/share/abcDEF123/");

		assertThat(result).isInstanceOf(MonitoringInput.ShareLink.class);
		assertThat(((MonitoringInput.ShareLink) result).originalUrl())
				.isEqualTo("https://www.instagram.com/share/abcDEF123/");
	}

	@Test
	void 빈_문자열은_invalid_format이다() {
		MonitoringInput result = MonitoringInput.parsePost("");

		assertThat(result).isInstanceOf(MonitoringInput.Invalid.class);
		MonitoringInput.Invalid invalid = (MonitoringInput.Invalid) result;
		assertThat(invalid.reasonCode()).isEqualTo("invalid_format");
		assertThat(invalid.reason()).isEqualTo("링크 형식이 올바르지 않아요.");
	}

	@Test
	void 타_도메인_링크는_invalid_format이다() {
		MonitoringInput result = MonitoringInput.parsePost("https://twitter.com/reel/ABC123/");

		assertThat(result).isInstanceOf(MonitoringInput.Invalid.class);
	}

	@Test
	void 게시물_경로가_아닌_인스타그램_링크는_invalid_format이다() {
		MonitoringInput result = MonitoringInput.parsePost("https://www.instagram.com/glowdeep.official/");

		assertThat(result).isInstanceOf(MonitoringInput.Invalid.class);
	}

	@Test
	void 대문자_핸들은_소문자로_정규화된_Account를_반환한다() {
		MonitoringInput result = MonitoringInput.parseAccount("GlowDeep.Official");

		assertThat(result).isInstanceOf(MonitoringInput.Account.class);
		assertThat(((MonitoringInput.Account) result).handle()).isEqualTo("glowdeep.official");
	}

	@Test
	void 빈_핸들은_invalid_format이다() {
		MonitoringInput result = MonitoringInput.parseAccount("");

		assertThat(result).isInstanceOf(MonitoringInput.Invalid.class);
		assertThat(((MonitoringInput.Invalid) result).reason()).isEqualTo("핸들 형식이 올바르지 않아요.");
	}

	@Test
	void _31자_핸들은_invalid_format이다() {
		String tooLong = "a".repeat(31);

		MonitoringInput result = MonitoringInput.parseAccount(tooLong);

		assertThat(result).isInstanceOf(MonitoringInput.Invalid.class);
	}

	@Test
	void _30자_핸들은_통과한다() {
		String maxLength = "a".repeat(30);

		MonitoringInput result = MonitoringInput.parseAccount(maxLength);

		assertThat(result).isInstanceOf(MonitoringInput.Account.class);
	}

	@Test
	void 특수문자가_섞인_핸들은_invalid_format이다() {
		MonitoringInput result = MonitoringInput.parseAccount("glow!deep");

		assertThat(result).isInstanceOf(MonitoringInput.Invalid.class);
	}
}
