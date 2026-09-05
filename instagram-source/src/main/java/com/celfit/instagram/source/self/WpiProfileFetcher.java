package com.celfit.instagram.source.self;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.PrivateAccountException;
import com.celfit.instagram.source.ProfileInfo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * web_profile_info 프로필 fetcher — 프로필 스냅샷 + 최근 12건을 JSON 한 방으로 뽑는다(로그인 불필요).
 *
 * <p>이 표면은 401-민감이라 MOBILE 티어로 나간다(모바일 IP 예산 — ProxyTier 주석). 비공개 계정은
 * HikerBackend 프로필 계약과 동일하게 PrivateAccountException으로 승격한다 — 비공개는 관측값이지
 * 자체크롤 실패가 아니므로 폴백 라우팅에 태우지 않는다.
 */
public class WpiProfileFetcher {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	// x-ig-app-id 없이는 web_profile_info가 로그인 벽 HTML로 응답한다(웹앱 공개 식별자).
	private static final Map<String, String> HEADERS = Map.of(
			"x-ig-app-id", "936619743392459",
			"Accept", "*/*",
			"Accept-Language", "en-US,en;q=0.9");

	private final EmbedPostFetcher.SelfFetch http;

	public WpiProfileFetcher(EmbedPostFetcher.SelfFetch http) {
		this.http = http;
	}

	public ProfileInfo fetchProfile(String username) {
		JsonNode user = fetchUser(username);
		return new ProfileInfo(user.path("username").asString(null), user.path("id").asString(null),
				count(user, "edge_followed_by"), count(user, "edge_follow"),
				count(user, "edge_owner_to_timeline_media"),
				user.path("full_name").asString(null), user.path("profile_pic_url").asString(null),
				user.path("biography").asString(null),
				nullableBoolean(user, "is_verified"), user.path("external_url").asString(null));
	}

	public List<PostInfo> fetchRecentPosts(String username) {
		JsonNode user = fetchUser(username);
		String profileUsername = user.path("username").asString(username);
		List<PostInfo> posts = new ArrayList<>();
		for (JsonNode edge : user.path("edge_owner_to_timeline_media").path("edges")) {
			posts.add(toPost(edge.path("node"), profileUsername));
		}
		return posts;
	}

	private JsonNode fetchUser(String username) {
		String url = "https://www.instagram.com/api/v1/users/web_profile_info/?username="
				+ URLEncoder.encode(username, StandardCharsets.UTF_8);
		SelfResponse res = http.fetch(url, ProxyTier.MOBILE, HEADERS);
		String body = res.body() == null ? "" : res.body();
		// 비200뿐 아니라 200 로그인벽 HTML도 분류해 폴백망에 태운다(ofStatus의 LOGIN_WALL 분기).
		SelfErrorClass ec = SelfErrorClassifier.ofStatus(res.status(), body);
		if (ec != SelfErrorClass.OK) {
			throw new SelfCrawlException(ec,
					"web_profile_info 실패 status=" + res.status() + " username=" + username);
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		} catch (RuntimeException e) {
			// 200인데 JSON이 아니다 — 게이트 응답으로 보고 폴백망에 태운다(잭슨 예외 누출 차단).
			throw new SelfCrawlException(SelfErrorClass.LOGIN_WALL,
					"web_profile_info JSON 파스 실패(로그인벽 의심) username=" + username, e);
		}
		JsonNode user = root.path("data").path("user");
		if (user.isMissingNode() || user.isNull() || user.isEmpty()) {
			// V7 — 09-02부터 IG가 로그아웃 wpi 요청에 401 대신 200 + user:null(또는 빈 객체)을
			// 주는 사례가 실측됐다(08-18 crawler 실측: 이런 계정도 Hiker로는 수집 가능). 이걸
			// NOT_FOUND로 확정하면 FailoverInstagramSource가 Hiker 재확인 없이 SubjectNotFoundException으로
			// 끝내버려 존재하는 계정을 부재로 오판정한다 — HTTP 404(진짜 확정 부재, ofStatus 분기)와
			// 달리 이 경로는 비확정이라 OTHER로 던져 Hiker 재확인을 유도한다. Hiker가 404를 주면
			// 그때 비로소 SubjectNotFoundException으로 확정된다(Hiker 경로 계약).
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"web_profile_info user 부재(200, 비확정 — Hiker 재확인 필요): " + username);
		}
		if (user.path("is_private").asBoolean(false)) {
			throw new PrivateAccountException("비공개 계정: " + username);
		}
		return user;
	}

	private static PostInfo toPost(JsonNode node, String username) {
		boolean video = node.path("is_video").asBoolean(false);
		// -1은 IG의 명시적 좋아요 숨김 센티널(확정 true). 숫자를 실제로 봤으면 확정 false. 엣지
		// 자체가 없으면(구조적 부재) 미확정(null, S9 보완, 2026-09-03 리뷰 지적) — embed·
		// FeedUserPostsFetcher와 같은 규칙이다. 과거엔 "likes == null"만 보고 부재를 확정 true로
		// 단정해, mergedWith의 OR 병합에서 정본(embed)의 진짜 확정 false를 덮어 likes를 null로
		// 강제하는 결함을 냈다.
		JsonNode likeCountNode = node.path("edge_media_preview_like").path("count");
		Boolean likesHidden;
		Long likes;
		if (!likeCountNode.isNumber()) {
			likesHidden = null;
			likes = null;
		} else if (likeCountNode.asLong() < 0) {
			likesHidden = true;
			likes = null;
		} else {
			likesHidden = false;
			likes = likeCountNode.asLong();
		}
		Long views = node.path("video_view_count").isNumber()
				? node.path("video_view_count").asLong() : null;
		return new PostInfo(node.path("shortcode").asString(null), username, null, null, null,
				video ? "REELS" : "FEED", null, null,
				node.path("taken_at_timestamp").isNumber()
						? node.path("taken_at_timestamp").asLong() : null,
				likes, count(node, "edge_media_to_comment"), views,
				null, null, null, null, null, null, null,
				// sharesHidden=null(미확정, S9) — web_profile_info 응답에도 공유 횟수가 안 실려
				// EmbedPostFetcher·FeedUserPostsFetcher와 같은 구조적 한계다.
				views != null, likesHidden, null);
	}

	/** edge_* 래퍼의 count — 부재·비숫자는 null(HikerBackend firstLong과 같은 규칙). */
	private static Long count(JsonNode node, String edge) {
		JsonNode v = node.path(edge).path("count");
		return v.isNumber() ? v.asLong() : null;
	}

	private static Boolean nullableBoolean(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asBoolean();
	}
}
