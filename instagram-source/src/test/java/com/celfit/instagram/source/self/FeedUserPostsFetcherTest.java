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
		// S9 — feed/user 응답에도 공유 횟수 자체가 안 실려 embed와 같은 구조적 한계다: 확정
		// false(비숨김)로 단정하지 않고 미확정(null)을 반환한다.
		assertThat(image.sharesHidden()).isNull();
		assertThat(image.takenAt()).isEqualTo(1_787_148_707L);
		assertThat(image.ownerUserId()).isEqualTo("528817151");
		assertThat(image.caption()).isEqualTo("A stunning view of Earth from orbit.");
		// S14 — image_versions2.candidates[0].url(HikerBackend와 동일 필드, media 노드 공통 셰이프).
		assertThat(image.thumbnailUrl())
				.isEqualTo("https://scontent-gmp1-1.cdninstagram.com/v/t51.29350-15/img_1080.jpg?oe=6A9B20B6");

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
		// product_type "carousel_container"(clips 아님)은 FEED로 분류된다.
		assertThat(carousel.contentType()).isEqualTo("FEED");
		assertThat(carousel.likes()).isEqualTo(12_000L);
	}

	/**
	 * S4 — media_type==2는 일반 비디오 피드도 포함해 릴스 단독 판별 신호가 아니다(HikerBackend와
	 * 동일 결론, findings §4). Hiker와 같은 신호(product_type == "clips")로 판정해야 media_type==2인
	 * 일반 비디오 게시물을 REELS로 오판정하지 않는다.
	 */
	@Test
	void media_type가_2여도_product_type이_clips가_아니면_REELS가_아니다() {
		String body = """
				{"items":[{"code":"VID1","media_type":2,"product_type":"feed",
				"like_count":10,"comment_count":2,"taken_at":1700000000,
				"caption":{"text":"일반 비디오"},"user":{"username":"nasa"}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).contentType()).isEqualTo("FEED");
	}

	/**
	 * S4 — product_type 필드 자체가 없으면(예상외 셰이프) media_type만으로 REELS/FEED를 단정하지
	 * 않고 null(판별 불가)로 남긴다 — 저장 계층(PostMetaRepository)이 기존 값을 COALESCE로 보존한다.
	 */
	@Test
	void product_type이_없으면_콘텐츠_타입은_null이다() {
		String body = """
				{"items":[{"code":"NOPT","media_type":2,
				"like_count":10,"comment_count":2,"taken_at":1700000000,
				"caption":{"text":"셰이프 이상"},"user":{"username":"nasa"}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).contentType()).isNull();
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

	/**
	 * S9 보완(2026-09-03 리뷰 지적) — like_count 키 자체가 없는 경우(-1 센티널이 아니라 구조적
	 * 부재)를 과거엔 -1과 뭉뚱그려 확정 숨김(true)으로 단정했다. 이는 embed·wpi의 "구조적으로
	 * 판정 불가면 null" 규칙과 어긋난다 — CollectService#assumeZeroForOmittedKeys는 확정
	 * false만 0 간주 대상으로 삼지만, mergedWith의 OR 병합은 폴백(feed/user)의 근거 없는 true가
	 * 정본(embed)의 진짜 확정 false를 덮어버려 likes가 null로 강제되는 결함을 냈다. 키 부재는
	 * null(미확정)이어야 한다 — 이 표면이 좋아요를 아예 못 줬는지, 진짜 숨겨졌는지 구분 못 한다.
	 */
	@Test
	void 좋아요_키_부재는_숨김_미확정_null이다() {
		String body = """
				{"items":[{"code":"NOKEYLIKE","media_type":1,"comment_count":5,
				"taken_at":1700000000,"caption":null,"user":{"username":"nasa"}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).likes()).isNull();
		assertThat(posts.get(0).likesHidden()).isNull();
	}

	/** S14 — image_versions2 자체가 없으면(예상외 셰이프) 예외 없이 thumbnailUrl을 null로 남긴다. */
	@Test
	void image_versions2가_없으면_썸네일은_null이다() {
		String body = """
				{"items":[{"code":"NOIMG","media_type":1,"product_type":"feed","like_count":10,
				"comment_count":2,"taken_at":1700000000,"caption":null,"user":{"username":"nasa"}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).thumbnailUrl()).isNull();
	}

	/** S14 — candidates가 빈 배열이면 마찬가지로 null(첫 후보 부재). */
	@Test
	void candidates가_빈_배열이면_썸네일은_null이다() {
		String body = """
				{"items":[{"code":"EMPTYIMG","media_type":1,"product_type":"feed","like_count":10,
				"comment_count":2,"taken_at":1700000000,"caption":null,"user":{"username":"nasa"},
				"image_versions2":{"candidates":[]}}]}
				""";
		List<PostInfo> posts = fetcher(body, 200).fetchRecentPosts("nasa", "528817151");

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).thumbnailUrl()).isNull();
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
