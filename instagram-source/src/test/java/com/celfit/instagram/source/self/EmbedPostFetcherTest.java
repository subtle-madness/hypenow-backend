package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.PostInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** embed 단건 파서 — 커밋된 실응답 픽스처(en-US) 기준 정확값 검증. */
class EmbedPostFetcherTest {

	private static String fixture(String name) {
		try (var in = EmbedPostFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static EmbedPostFetcher fetcher(String body, int status) {
		return new EmbedPostFetcher((url, tier, headers) -> new SelfResponse(status, body));
	}

	@Test
	void 이미지_게시물_정확_지표를_파싱한다() {
		PostInfo p = fetcher(fixture("embed_image_en.html"), 200).fetch("DcOX3hWFiey");
		assertThat(p.shortCode()).isEqualTo("DcOX3hWFiey");
		assertThat(p.likes()).isEqualTo(485_263L);
		assertThat(p.comments()).isEqualTo(3_359L);
		assertThat(p.views()).isNull();
		assertThat(p.username()).isEqualTo("nasa");
		assertThat(p.contentType()).isEqualTo("FEED");
		assertThat(p.caption()).contains("With your powers combined");
		assertThat(p.viewsTrusted()).isFalse();
		assertThat(p.likesHidden()).isFalse();
		// S9 — 공유 횟수는 embed HTML에 구조적으로 안 실려 "숨김"과 "이 표면이 원래 못 주는 값"을
		// 구분할 신호가 없다 — 확정 false(비숨김)로 단정하지 않고 미확정(null)을 반환한다.
		assertThat(p.sharesHidden()).isNull();
	}

	/**
	 * S7 — 캡션 div가 `<a class="CaptionUsername">nasa</a><br/><br/>본문` 구조라 태그를 통째로
	 * 벗기면 작성자 username이 캡션 앞에 "nasa\n\n"으로 섞여 들어간다(09-03 운영 실측 23건, 캡션
	 * 해시 요동→광고 재판정 반복 유발). CaptionUsername 앵커를 내용째 제거한 뒤 본문만 남아야 한다.
	 */
	@Test
	void 캡션에_작성자_username_접두가_섞이지_않는다() {
		PostInfo p = fetcher(fixture("embed_image_en.html"), 200).fetch("DcOX3hWFiey");
		assertThat(p.caption()).doesNotStartWith("nasa");
		assertThat(p.caption()).startsWith("With your powers combined");
	}

	@Test
	void 릴스_영상_조회수까지_파싱한다() {
		PostInfo p = fetcher(fixture("embed_reel_en.html"), 200).fetch("DcMXl1IPNtB");
		assertThat(p.likes()).isEqualTo(95_971L);
		assertThat(p.comments()).isEqualTo(789L);
		assertThat(p.views()).isEqualTo(560_365L);
		assertThat(p.username()).isEqualTo("nasajohnson");
		assertThat(p.contentType()).isEqualTo("REELS");
		assertThat(p.caption()).contains("Soothing spacewalk");
		assertThat(p.viewsTrusted()).isTrue();
		assertThat(p.likesHidden()).isFalse();   // 좋아요 숫자를 실제로 봤으니 확정 비숨김
		assertThat(p.sharesHidden()).isNull();   // S9 — 공유는 embed에 구조적으로 안 실려 항상 미확정
	}

	/**
	 * 좋아요 카운트 렌더 텍스트가 없으면(정규식 파싱 실패 — 로케일 변경 등) "숨김"으로 단정하면 안
	 * 된다(수정 2 — 파싱 실패를 숨김으로 오분류하면 저장 계층에 영구 오염을 남긴다, findings 참조).
	 * self는 숨김 여부를 확정할 신뢰 가능한 신호가 없으므로 likesHidden을 null(미확정)로 남긴다
	 * (S9, 2026-09-03 감사 수정 — 과거엔 항상 false를 반환해 Hiker의 확정 false와 안 구분됐다).
	 * "미확정" 보호는 여전히 저장 계층(SnapshotRepository)이 담당하지만, 이제 인메모리 재시도·0
	 * 간주 판단도 진짜 미확정과 확정 false를 구분할 수 있다.
	 */
	@Test
	void 좋아요_파싱_실패는_미확정_null로_반환된다() {
		// 좋아요 카운트 패턴이 없는 렌더 텍스트 — username은 있어 빈 셸(NOT_FOUND)로는 분류되지 않는다.
		String body = "<html><body><span class=\"UsernameText\">nasa</span>"
				+ "<a>View all 12 comments</a></body></html>";
		PostInfo p = fetcher(body, 200).fetch("SC1");

		assertThat(p.likes()).isNull();
		assertThat(p.likesHidden()).isNull();
	}

	/**
	 * S1 — 3xx 리다이렉트는 게이트·소프트블록 응답에서도 나온다(09-03 운영 오탐 실측). 부재 확정은
	 * Hiker의 결정론적 404만(BrandCollectService 불변식) — NOT_FOUND로 확정하지 않고 OTHER로 강등해
	 * FailoverInstagramSource가 Hiker로 재확인하게 한다.
	 */
	@Test
	void 삭제_리다이렉트는_부재_미확정_OTHER로_강등된다() {
		assertThatThrownBy(() -> fetcher("", 302).fetch("Bt_A-8dgHKW"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}

	/**
	 * S1 — 200인데 소유자·좋아요 둘 다 없는 "빈 셸"도 게이트 응답에서 나올 수 있다. 3xx와 동일하게
	 * OTHER로 강등해 Hiker 재확인을 거치게 한다(폴백 없이 확정하지 않는다).
	 */
	@Test
	void 빈_셸_200은_부재_미확정_OTHER로_강등된다() {
		String body = "<html><body>로그인/게이트 추정 셸 — 소유자·좋아요 신호 없음</body></html>";
		assertThatThrownBy(() -> fetcher(body, 200).fetch("SC2"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}

	/** S1 — 진짜 HTTP 404만 NOT_FOUND로 남는다(부재 확정 유일 경로). */
	@Test
	void 진짜_HTTP_404는_NOT_FOUND_유지() {
		assertThatThrownBy(() -> fetcher("", 404).fetch("Bt_A-8dgHKW"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.NOT_FOUND));
	}

	/**
	 * S4 — 과거 판정은 {@code body.contains("product_type\":\"clips")}로 접두사만 봤다. 실값이
	 * "clips_v2"처럼 "clips"로 시작만 하고 다른 product_type이어도 접두 일치로 REELS 오판정됐다.
	 * 구조적 파싱(값 전체를 캡처해 "clips"와 완전 일치 비교)이면 이 오탐이 사라진다.
	 */
	@Test
	void product_type_접두사만_같고_실값이_다르면_REELS로_오판정하지_않는다() {
		// 런타임 텍스트: <script>{\"product_type\":\"clips_v2\"}</script> — embed 실 HTML의
		// 이스케이프된 JSON 블롭(VIEWS 패턴 주석 참조)과 같은 셰이프.
		String escapedJson = "{\\\"product_type\\\":\\\"clips_v2\\\"}";
		String body = "<html><body><span class=\"UsernameText\">nasa</span>"
				+ "<a data-log-event=\"likeCountClick\">100 likes</a>"
				+ "<script>" + escapedJson + "</script></body></html>";

		PostInfo p = fetcher(body, 200).fetch("SC3");

		assertThat(p.contentType()).isEqualTo("FEED");
		assertThat(p.views()).isNull();
	}

	/** product_type이 정확히 "clips"면(views 신호 없이도) REELS로 확정한다 — 구조적 추출의 정상 경로. */
	@Test
	void product_type이_정확히_clips면_views_없이도_REELS로_확정한다() {
		String escapedJson = "{\\\"product_type\\\":\\\"clips\\\"}";
		String body = "<html><body><span class=\"UsernameText\">nasa</span>"
				+ "<a data-log-event=\"likeCountClick\">100 likes</a>"
				+ "<script>" + escapedJson + "</script></body></html>";

		PostInfo p = fetcher(body, 200).fetch("SC4");

		assertThat(p.contentType()).isEqualTo("REELS");
	}
}
