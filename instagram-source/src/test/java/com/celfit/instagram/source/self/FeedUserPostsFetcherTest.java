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
		// caption은 이 테스트의 관심사가 아니므로 명시적 null(실제 캡션 없음 셰이프)로 채워 캡션
		// 셰이프 가드(아래 테스트들)에 걸리지 않게 한다.
		String body = """
				{"items":[{"code":"HID","media_type":1,"like_count":-1,"comment_count":5,
				"taken_at":1700000000,"caption":null,"user":{"username":"nasa"}}]}
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

	// ── 캡션 3-상태 구분(데이터 보호 결함 수정 — 수정 3, 사용자 요구: 캡션 결손 제로) ──
	// IG 응답의 caption 노드는 "명시적으로 null"(실제 캡션 없음)과 "노드 자체 실종"(셰이프 이상 —
	// 파싱 실패로 간주해 Hiker 폴백)을 구분해야 한다. 전자를 후자로 오분류하면 무해하지만(둘 다
	// 저장 계층에서 안전하게 처리됨), 후자를 전자로 오분류하면 캡션 결손이 조용히 지나간다.

	/** caption 노드가 명시적으로 null이면(IG 응답의 실제 무캡션 셰이프) ""로 매핑한다. */
	@Test
	void 캡션이_명시적_null이면_빈_문자열이다() {
		String body = """
				{"items":[{"code":"NOCAP","media_type":1,"like_count":10,"comment_count":2,
				"taken_at":1700000000,"caption":null,"user":{"username":"nasa"}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).caption()).isEqualTo("");
	}

	/** caption 키 자체가 없으면(예상외 셰이프) 파싱 실패로 보고 콜 전체를 Hiker 폴백으로 유도한다. */
	@Test
	void 캡션_키_부재는_셰이프_이상으로_전체_콜을_실패시킨다() {
		String body = """
				{"items":[{"code":"NOKEY","media_type":1,"like_count":10,"comment_count":2,
				"taken_at":1700000000,"user":{"username":"nasa"}}]}
				""";
		assertThatThrownBy(() -> fetcher(body, 200).fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}

	/** caption 노드는 있는데 text 필드가 없으면(예상외 셰이프) 마찬가지로 파싱 실패 처리한다. */
	@Test
	void 캡션_text_필드_부재도_셰이프_이상이다() {
		String body = """
				{"items":[{"code":"NOTEXT","media_type":1,"like_count":10,"comment_count":2,
				"taken_at":1700000000,"caption":{"pk":"123"},"user":{"username":"nasa"}}]}
				""";
		assertThatThrownBy(() -> fetcher(body, 200).fetchRecentPosts("nasa", "528817151"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}
}
