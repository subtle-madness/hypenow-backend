package com.celfit.instagram.source.self;

import com.celfit.instagram.source.PostInfo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * feed/user posts fetcher — 최근 12건 + 지표를 1콜에(로그인 불필요, pk 입력, page1 고정 — 딥페이징 없음).
 *
 * <p>wpi(web_profile_info)는 대형 공개계정 ~40%에서 계정별 STRUCTURAL_400(IG web_profile_info
 * 버그)에 걸려 항상 실패하는데, feed/user는 다른 엔드포인트라 그 400을 안 받는다. 이 표면도
 * 401-민감이라(레지던셜보다 크게 낮지만) MOBILE 티어로 나간다. 영상 조회수(play_count)가 아이템에
 * 이미 실려 오므로 clips 보강 콜이 불요하다.
 */
public class FeedUserPostsFetcher {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final Map<String, String> HEADERS = Map.of(
			"x-ig-app-id", "936619743392459",
			"Sec-Fetch-Site", "same-origin",
			"Accept", "*/*",
			"Accept-Language", "en-US,en;q=0.9");

	private final EmbedPostFetcher.SelfFetch http;

	public FeedUserPostsFetcher(EmbedPostFetcher.SelfFetch http) {
		this.http = http;
	}

	public List<PostInfo> fetchRecentPosts(String username, String userId) {
		String url = "https://www.instagram.com/api/v1/feed/user/"
				+ URLEncoder.encode(userId, StandardCharsets.UTF_8) + "/?count=12";
		SelfResponse res = http.fetch(url, ProxyTier.MOBILE, HEADERS);
		String body = res.body() == null ? "" : res.body();
		// 비200뿐 아니라 200 로그인벽 HTML도 분류해 폴백망에 태운다(ofStatus의 LOGIN_WALL 분기).
		SelfErrorClass ec = SelfErrorClassifier.ofStatus(res.status(), body);
		if (ec != SelfErrorClass.OK) {
			throw new SelfCrawlException(ec,
					"feed/user 실패 status=" + res.status() + " userId=" + userId);
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		} catch (RuntimeException e) {
			// 200인데 JSON이 아니다 — 게이트 응답으로 보고 폴백망에 태운다(잭슨 예외 누출 차단).
			throw new SelfCrawlException(SelfErrorClass.LOGIN_WALL,
					"feed/user JSON 파스 실패(로그인벽 의심) userId=" + userId, e);
		}
		JsonNode items = root.path("items");
		if (!items.isArray()) {
			// 계정 부재가 아니라 예상외 셰이프 — NOT_FOUND가 아닌 OTHER로 Hiker 폴백을 유도한다.
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"feed/user items 부재(예상외 셰이프) userId=" + userId);
		}
		List<PostInfo> posts = new ArrayList<>();
		for (JsonNode item : items) {
			posts.add(toPost(item, username, userId));
		}
		return posts;
	}

	private static PostInfo toPost(JsonNode item, String username, String userId) {
		String contentType = contentType(item);

		JsonNode likeCount = item.path("like_count");
		// -1은 IG의 좋아요 숨김 센티널 — 부재도 동일 취급.
		boolean likesHidden = !likeCount.isNumber() || likeCount.asLong() < 0;
		Long likes = likesHidden ? null : likeCount.asLong();

		JsonNode commentCount = item.path("comment_count");
		Long comments = commentCount.isNumber() ? commentCount.asLong() : null;

		JsonNode playCount = item.path("play_count");
		Long views = playCount.isNumber() ? playCount.asLong() : null;

		return new PostInfo(
				item.path("code").asString(null),
				item.path("user").path("username").asString(username),
				null, null,
				userId,
				contentType,
				extractCaption(item, userId),
				null,
				item.path("taken_at").isNumber() ? item.path("taken_at").asLong() : null,
				likes, comments, views,
				null, null, null, null, null, null, null,
				// sharesHidden=null(미확정, S9) — feed/user 응답에는 공유 횟수 자체가 안 실려
				// "숨김"과 "이 표면이 원래 못 주는 값"을 구분할 신호가 없다(EmbedPostFetcher와 동일
				// 구조적 한계). 과거 false 하드코딩은 Hiker의 확정 false와 안 구분돼, 공유 숨김
				// 릴스가 재시도 상한까지 헛돌고 소진 시 공유 0으로 오기록되는 결함을 냈다.
				views != null, likesHidden, null);
	}

	/**
	 * S4 — HikerBackend와 같은 신호(product_type == "clips")로 판정한다. media_type==2는 일반
	 * 비디오 피드도 포함해 REELS 단독 판별 신호가 아니다(HikerBackend 주석 "media_type==2는 일반
	 * 비디오 피드도 포함 → 릴스 판별은 product_type" 동일 결론, findings §4). product_type 필드
	 * 자체가 없으면(예상외 셰이프) media_type만으로 단정하지 않고 null(판별 불가)을 반환한다 —
	 * 저장 계층(PostMetaRepository)이 COALESCE로 기존 값을 보존하므로, 콜 전체를 실패시켜 Hiker
	 * 폴백을 강제할 필요가 없는 항목이다(캡션과 달리 결손이 저장 계층에서 안전하게 흡수된다).
	 */
	private static String contentType(JsonNode item) {
		JsonNode productType = item.path("product_type");
		if (!productType.isString()) {
			return null;
		}
		return "clips".equals(productType.asString()) ? "REELS" : "FEED";
	}

	/**
	 * 캡션 3-상태 구분(트랙 HH 계약, 데이터 보호 결함 수정) — IG 응답의 caption 노드는 명시적으로
	 * null이면(실제 확인된 무캡션 셰이프) ""로 매핑하고, 키 자체가 없거나 text 필드가 없으면
	 * (예상외 셰이프) 파싱 실패로 보고 콜 전체를 실패시켜 Hiker 폴백을 유도한다 — 전자를 후자로
	 * 오분류해도 저장 계층(SnapshotWriter/PostMetaRepository)이 안전하게 흡수하지만, 후자를
	 * 전자로 오분류하면(과거 {@code asString(null)} 단일 처리) 캡션 결손이 조용히 지나간다.
	 */
	private static String extractCaption(JsonNode item, String userId) {
		if (!item.has("caption")) {
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"feed/user caption 키 부재(예상외 셰이프) userId=" + userId);
		}
		JsonNode captionNode = item.path("caption");
		if (captionNode.isNull()) {
			return "";
		}
		JsonNode textNode = captionNode.path("text");
		if (!textNode.isString()) {
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"feed/user caption.text 부재(예상외 셰이프) userId=" + userId);
		}
		return textNode.asString();
	}
}
