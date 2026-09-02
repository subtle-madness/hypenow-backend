package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.PostInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** feed/user posts fetcher — 커밋된 최소 셰이프 픽스처(nasa, 이미지·릴스·캐러셀) 기준 정확값 검증. */
class FeedUserPostsFetcherTest {

	private static String fixture(String name) {
		try (var in = FeedUserPostsFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static FeedUserPostsFetcher fetcher(String body, int status) {
		return new FeedUserPostsFetcher((url, tier, headers) -> new SelfResponse(status, body));
	}

	@Test
	void 최근_게시물_3건을_파싱한다() {
		List<PostInfo> posts =
				fetcher(fixture("feed_user.json"), 200).fetchRecentPosts("nasa", "528817151");
		assertThat(posts).hasSize(3);

		PostInfo image = posts.get(0);
		assertThat(image.shortCode()).isEqualTo("DImageAAAAA");
		assertThat(image.username()).isEqualTo("nasa");
		assertThat(image.contentType()).isEqualTo("FEED");
		assertThat(image.likes()).isEqualTo(485_267L);
		assertThat(image.comments()).isEqualTo(3_359L);
		assertThat(image.views()).isNull();
		assertThat(image.viewsTrusted()).isFalse();
		assertThat(image.likesHidden()).isFalse();
		assertThat(image.takenAt()).isEqualTo(1_787_148_707L);
		assertThat(image.ownerUserId()).isEqualTo("528817151");
		assertThat(image.caption()).isEqualTo("A stunning view of Earth from orbit.");

		PostInfo reel = posts.stream()
				.filter(p -> "DReelBBBBBB".equals(p.shortCode()))
				.findFirst()
				.orElseThrow();
		assertThat(reel.contentType()).isEqualTo("REELS");
		assertThat(reel.views()).isEqualTo(560_359L);
		assertThat(reel.likes()).isEqualTo(95_971L);
		assertThat(reel.viewsTrusted()).isTrue();

		PostInfo carousel = posts.stream()
				.filter(p -> "DCaroCCCCCC".equals(p.shortCode()))
				.findFirst()
				.orElseThrow();
		// media_type 8(캐러셀)은 2가 아니라 FEED로 분류된다.
		assertThat(carousel.contentType()).isEqualTo("FEED");
		assertThat(carousel.likes()).isEqualTo(12_000L);
	}

	@Test
	void MOBILE_티어로_나간다() {
		AtomicReference<ProxyTier> seen = new AtomicReference<>();
		FeedUserPostsFetcher fetcher = new FeedUserPostsFetcher((url, tier, headers) -> {
			seen.set(tier);
			return new SelfResponse(200, fixture("feed_user.json"));
		});

		fetcher.fetchRecentPosts("nasa", "528817151");

		assertThat(seen.get()).isEqualTo(ProxyTier.MOBILE);
	}

	@Test
	void 비200은_SelfCrawlException() {
		assertThatThrownBy(() -> fetcher("", 401).fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.RECOVERABLE_401));
	}

	@Test
	void 로그인벽_200_HTML은_LOGIN_WALL_예외() {
		String html = "<!DOCTYPE html><html><body>Login • Instagram</body></html>";
		assertThatThrownBy(() -> fetcher(html, 200).fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void 비JSON_200은_잭슨_예외가_아닌_SelfCrawlException() {
		// 파스 실패가 unchecked Jackson 예외로 새면 폴백망(Failover 라우팅)을 우회한다.
		assertThatThrownBy(() -> fetcher("not json {{{", 200).fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class);
	}

	@Test
	void items_부재는_OTHER() {
		assertThatThrownBy(() -> fetcher("{\"status\":\"fail\",\"message\":\"login_required\"}", 200)
				.fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));

		assertThatThrownBy(() -> fetcher("{\"foo\":1}", 200).fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}

	@Test
	void 좋아요_숨김은_likes_null_likesHidden_true() {
		String body = """
				{"items":[{"code":"HID","media_type":1,"like_count":-1,"comment_count":5,
				"taken_at":1700000000,"user":{"username":"nasa"}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).likes()).isNull();
		assertThat(posts.get(0).likesHidden()).isTrue();
	}

	@Test
	void 빈_items는_빈_리스트() {
		String body = "{\"items\":[],\"num_results\":0,\"status\":\"ok\"}";
		assertThat(fetcher(body, 200).fetchRecentPosts("nasa", "528817151")).isEmpty();
	}
}
