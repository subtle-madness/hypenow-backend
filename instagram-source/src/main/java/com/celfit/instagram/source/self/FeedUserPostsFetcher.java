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
		int mediaType = item.path("media_type").asInt(0);
		String contentType = mediaType == 2 ? "REELS" : "FEED";

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
				item.path("caption").path("text").asString(null),
				null,
				item.path("taken_at").isNumber() ? item.path("taken_at").asLong() : null,
				likes, comments, views,
				null, null, null, null, null, null, null,
				views != null, likesHidden, false);
	}
}
