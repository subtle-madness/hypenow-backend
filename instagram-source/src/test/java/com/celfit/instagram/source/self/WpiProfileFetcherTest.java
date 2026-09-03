package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.PrivateAccountException;
import com.celfit.instagram.source.ProfileInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** web_profile_info 프로필 fetcher — 커밋된 실응답 픽스처(nasa) 기준 정확값 검증. */
class WpiProfileFetcherTest {

	private static String fixture(String name) {
		try (var in = WpiProfileFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static WpiProfileFetcher fetcher(String body, int status) {
		return new WpiProfileFetcher((url, tier, headers) -> new SelfResponse(status, body));
	}

	@Test
	void 프로필_정확_지표를_파싱한다() {
		ProfileInfo p = fetcher(fixture("wpi_profile.json"), 200).fetchProfile("nasa");
		assertThat(p.username()).isEqualTo("nasa");
		assertThat(p.userId()).isEqualTo("528817151");
		assertThat(p.followers()).isEqualTo(104_434_587L);
		assertThat(p.following()).isEqualTo(91L);
		assertThat(p.mediaCount()).isEqualTo(4_900L);
		assertThat(p.fullName()).isEqualTo("NASA");
		assertThat(p.isVerified()).isTrue();
		assertThat(p.externalUrl()).isEqualTo("https://www.nasa.gov/");
	}

	@Test
	void 최근_게시물_12건을_파싱한다() {
		List<PostInfo> posts = fetcher(fixture("wpi_profile.json"), 200).fetchRecentPosts("nasa");
		assertThat(posts).hasSize(12);

		PostInfo image = posts.get(0);
		assertThat(image.shortCode()).isEqualTo("DcOX3hWFiey");
		assertThat(image.username()).isEqualTo("nasa");
		assertThat(image.contentType()).isEqualTo("FEED");
		assertThat(image.likes()).isEqualTo(485_267L);
		assertThat(image.comments()).isEqualTo(3_359L);
		assertThat(image.views()).isNull();
		assertThat(image.takenAt()).isEqualTo(1_787_148_707L);
		assertThat(image.viewsTrusted()).isFalse();
		assertThat(image.likesHidden()).isFalse();
		// S9 — web_profile_info 응답에도 공유 횟수가 안 실려 embed·feed/user와 같은 구조적 한계다.
		assertThat(image.sharesHidden()).isNull();

		PostInfo reel = posts.stream()
				.filter(p -> "DcMXl1IPNtB".equals(p.shortCode()))
				.findFirst()
				.orElseThrow();
		assertThat(reel.contentType()).isEqualTo("REELS");
		assertThat(reel.views()).isEqualTo(560_359L);
		assertThat(reel.likes()).isEqualTo(95_971L);
		assertThat(reel.viewsTrusted()).isTrue();
	}

	@Test
	void 비200은_SelfCrawlException() {
		assertThatThrownBy(() -> fetcher("", 401).fetchProfile("nasa"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.RECOVERABLE_401));
	}

	@Test
	void 로그인벽_200_HTML은_LOGIN_WALL_예외() {
		String html = "<!DOCTYPE html><html><body>Login • Instagram</body></html>";
		assertThatThrownBy(() -> fetcher(html, 200).fetchProfile("nasa"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void 비JSON_200은_잭슨_예외가_아닌_SelfCrawlException() {
		// 파스 실패가 unchecked Jackson 예외로 새면 폴백망(Failover 라우팅)을 우회한다.
		assertThatThrownBy(() -> fetcher("not json {{{", 200).fetchProfile("nasa"))
				.isInstanceOf(SelfCrawlException.class);
	}

	@Test
	void user_부재는_NOT_FOUND() {
		assertThatThrownBy(() -> fetcher("{\"data\":{\"user\":null}}", 200).fetchProfile("ghost"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.NOT_FOUND));
	}

	@Test
	void 비공개_계정은_PrivateAccountException() {
		String body = "{\"data\":{\"user\":{\"username\":\"secret\",\"id\":\"1\",\"is_private\":true}}}";
		assertThatThrownBy(() -> fetcher(body, 200).fetchProfile("secret"))
				.isInstanceOf(PrivateAccountException.class);
	}
}
