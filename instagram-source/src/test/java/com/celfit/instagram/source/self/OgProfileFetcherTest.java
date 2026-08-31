package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.ProfileInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** og 프로필(문서 표면) fetcher — 커밋된 실응답 픽스처(nasa 로그아웃 프로필 HTML) 기준 정확값 검증. */
class OgProfileFetcherTest {

	private static String fixture(String name) {
		try (var in = OgProfileFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static OgProfileFetcher fetcher(String body, int status) {
		return new OgProfileFetcher((url, tier, headers) -> new SelfResponse(status, body));
	}

	@Test
	void 프로필_정확_지표를_파싱한다() {
		ProfileInfo p = fetcher(fixture("og_profile.html"), 200).fetchProfile("nasa");
		assertThat(p.username()).isEqualTo("nasa");
		// userId는 이 표면의 채택 범위 밖(정본은 wpi) — 최상위 "id":null(로그아웃 뷰어)을 오독하지 않는다.
		assertThat(p.userId()).isNull();
		assertThat(p.followers()).isEqualTo(104_434_301L);
		assertThat(p.following()).isEqualTo(91L);
		// JSON media_count가 없어 og:description의 "4,900 Posts"에서 뽑는다.
		assertThat(p.mediaCount()).isEqualTo(4_900L);
		assertThat(p.fullName()).isEqualTo("NASA");
		assertThat(p.isVerified()).isTrue();
		assertThat(p.biography()).contains("Making the seemingly");
		// ✨(✨) 유니코드 이스케이프 디코드까지 확인.
		assertThat(p.biography()).isEqualTo("Making the seemingly impossible, possible. ✨");
		assertThat(p.profilePicUrl()).startsWith("https://scontent-")
				.doesNotContain("\\/");
		// 픽스처에 external_url 키 자체가 없다 — null.
		assertThat(p.externalUrl()).isNull();
	}

	@Test
	void 리다이렉트_3xx는_NOT_FOUND() {
		assertThatThrownBy(() -> fetcher("", 302).fetchProfile("ghost"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.NOT_FOUND));
	}

	@Test
	void 로그인벽_200_HTML_셸은_LOGIN_WALL_예외() {
		// follower_count도 username도 없는 HTML 셸 = 통계 없는 게이트 페이지.
		String html = "<!DOCTYPE html><html><body>Login • Instagram</body></html>";
		assertThatThrownBy(() -> fetcher(html, 200).fetchProfile("nasa"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void 공백_username만_실린_셸도_빈_셸_가드에_걸린다() {
		// "username":"" 매치가 가드를 우회하면 전 필드 null 프로필이 정상 결과로 새어 나간다.
		String html = "<!DOCTYPE html><html><body>login {\"username\":\"\"}</body></html>";
		assertThatThrownBy(() -> fetcher(html, 200).fetchProfile("nasa"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void 비200은_SelfCrawlException으로_분류한다() {
		assertThatThrownBy(() -> fetcher("", 429).fetchProfile("nasa"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.RATE_LIMIT_429));
	}
}
