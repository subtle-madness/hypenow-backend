package com.celfit.monitoring.hiker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * monitoring의 유일한 외부 수집 경로 — HikerAPI v2 3종(프로필·열거·단건) 파싱.
 * 엔드포인트·필드 매핑의 정본은 docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md.
 * 빈 조립은 {@code HikerConfig} — 전송은 항상 RecordingHikerHttp로 감싸져 들어온다(원형 적재).
 */
public class HikerClient {

	private static final Logger log = LoggerFactory.getLogger(HikerClient.class);
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private final HikerHttp http;

	public HikerClient(HikerHttp http) {
		this.http = http;
	}

	public ProfileInfo fetchProfile(String username) {
		String body = http.get("/v2/user/by/username?username=" + enc(username));
		JsonNode user = root(body).path("user");
		if (user.isMissingNode() || user.isNull()) {
			throw new HikerFetchException("프로필 응답에 user 없음: " + username);
		}
		if (user.path("is_private").asBoolean(false)) {
			throw new PrivateAccountException("비공개 계정: " + username);
		}
		// user.pk는 JSON number라 문자열화가 필요하다(findings §2-①).
		return new ProfileInfo(username, user.path("pk").asString(),
				firstLong(user, "follower_count"), firstLong(user, "following_count"),
				firstLong(user, "media_count"), body);
	}

	/**
	 * 게시물 열거 — /v2/user/medias(릴스+피드 전체, 1페이지 12건).
	 * 이 엔드포인트는 릴스여도 play_count를 안 주므로(findings §2-③) /v2/user/clips 열거로 조회수를 머지한다.
	 */
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		int wanted = Math.max(1, pages);
		Map<String, Long> plays = fetchClipPlays(userId, wanted);
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		String cursor = null;
		for (int page = 0; page < wanted; page++) {
			String body = http.get("/v2/user/medias?user_id=" + enc(userId) + pageParam(cursor));
			JsonNode root = root(body);
			int before = byCode.size();
			for (JsonNode item : items(root)) {
				PostInfo post = toPost(item, username, body, plays);
				byCode.putIfAbsent(post.shortCode(), post);   // 페이지 경계 중복 방지
			}
			// 커서 전진 가드: 커서 파라미터명이 틀리면 API가 같은 1페이지를 계속 돌려주는데
			// dedupe가 이를 조용히 흡수해 "누락 없는 정상"으로 보인다(콜만 2배 과금).
			// 새 숏코드가 0건이면 전진하지 않은 것으로 보고 중단한다.
			if (page > 0 && byCode.size() == before) {
				log.warn("커서 미전진 의심 — user_id {} {}페이지에서 새 게시물 0건, 열거 중단", userId, page + 1);
				break;
			}
			cursor = nextPageId(root);
			if (cursor == null || !moreAvailable(root)) {
				break;
			}
		}
		// 핀 고정 게시물이 배열 맨 앞에 옴(taken_at 2023년 사례 — findings §3) → 게시 시각 내림차순 재정렬
		List<PostInfo> out = new ArrayList<>(byCode.values());
		out.sort(Comparator.comparing(PostInfo::takenAt,
				Comparator.nullsLast(Comparator.reverseOrder())));
		return out;
	}

	/** 릴스 재생수 보강 — /v2/user/clips는 items[].media로 한 겹 더 감싼다. 실패해도 스윕은 계속(조회수만 null). */
	private Map<String, Long> fetchClipPlays(String userId, int pages) {
		Map<String, Long> plays = new HashMap<>();
		try {
			String cursor = null;
			for (int page = 0; page < pages; page++) {
				JsonNode root = root(http.get("/v2/user/clips?user_id=" + enc(userId) + pageParam(cursor)));
				int before = plays.size();
				for (JsonNode item : root.path("response").path("items")) {
					JsonNode m = item.path("media");
					Long play = firstLong(m, "play_count", "ig_play_count");
					if (play != null) {
						plays.put(m.path("code").asString(), play);
					}
				}
				if (page > 0 && plays.size() == before) {   // 열거와 동일한 커서 전진 가드
					log.warn("클립 커서 미전진 의심 — user_id {} {}페이지에서 새 릴스 0건, 보강 중단", userId, page + 1);
					break;
				}
				cursor = nextPageId(root);
				if (cursor == null || !moreAvailable(root)) {
					break;
				}
			}
		} catch (RuntimeException e) {
			log.warn("클립 재생수 보강 실패 — user_id {}: {}", userId, e.getMessage());
		}
		return plays;
	}

	public PostInfo fetchPost(String shortCode) {
		String body = http.get("/v2/media/by/code?code=" + enc(shortCode));
		List<JsonNode> items = items(root(body));
		if (items.isEmpty()) {
			throw new SubjectNotFoundException("게시물 응답이 비어 있음: " + shortCode);
		}
		PostInfo post = toPost(items.getFirst(), null, body, Map.of());
		// 단건 응답에는 usernameHint가 없어 소유 계정을 user.username에서만 얻는다.
		// 없으면 스냅샷 적재(post_snapshot.username NOT NULL)도 target 등록도 불가 → 셰이프 이상으로 본다.
		if (post.username() == null) {
			throw new HikerFetchException("단건 응답에 소유 계정(user.username)이 없음: " + shortCode);
		}
		return post;
	}

	private static String pageParam(String cursor) {
		return cursor == null ? "" : "&page_id=" + enc(cursor);
	}

	/** 다음 페이지 커서 — 최상위 next_page_id(findings §3). 없거나 공백이면 마지막 페이지. */
	private static String nextPageId(JsonNode root) {
		String cursor = root.path("next_page_id").asString(null);
		return cursor == null || cursor.isBlank() ? null : cursor;
	}

	/**
	 * 더 있음 플래그 — 열거는 `response.more_available`, 클립은 `response.paging_info.more_available`(findings §3).
	 * 키가 없는 셰이프에서는 true로 보고 페이지 수 상한·무진전 가드에 맡긴다.
	 */
	private static boolean moreAvailable(JsonNode root) {
		JsonNode res = root.has("response") ? root.path("response") : root;
		JsonNode flag = res.path("more_available");
		if (flag.isMissingNode() || flag.isNull()) {
			flag = res.path("paging_info").path("more_available");
		}
		return flag.isMissingNode() || flag.isNull() || flag.asBoolean(true);
	}

	private static String enc(String value) {
		return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static JsonNode root(String body) {
		return MAPPER.readTree(body);
	}

	/** {response:{items:[...]}} / {items:[...]} / 배열 / 단일 객체 — 셰이프 유연 대응. */
	private static List<JsonNode> items(JsonNode root) {
		JsonNode node = root.has("response") ? root.path("response") : root;
		JsonNode arr = node.isArray() ? node
				: node.has("items") ? node.path("items")
				: node.has("medias") ? node.path("medias") : node;
		List<JsonNode> out = new ArrayList<>();
		if (arr.isArray()) {
			arr.forEach(out::add);
		} else {
			out.add(arr);
		}
		return out;
	}

	private static PostInfo toPost(JsonNode node, String usernameHint, String rawJson,
			Map<String, Long> clipPlays) {
		JsonNode m = node.has("media") ? node.path("media") : node;   // clips 열거는 한 겹 더 감쌈
		String code = m.path("code").asString();
		String username = usernameHint != null ? usernameHint : m.path("user").path("username").asString(null);
		// media_type==2는 일반 비디오 피드도 포함 → 릴스 판별은 product_type(findings §4)
		String contentType = "clips".equals(m.path("product_type").asString("")) ? "REELS" : "FEED";
		// v2는 caption.text, v1은 caption_text — caption 자체가 null일 수 있다
		String caption = m.path("caption_text").isMissingNode()
				? m.path("caption").path("text").asString(null) : m.path("caption_text").asString(null);
		// view_count 키는 v2 응답에 부재 → 후보에서 제외. 열거 응답엔 play_count가 없어 clips 머지로 보강.
		Long views = firstLong(m, "play_count", "ig_play_count");
		return new PostInfo(code, username, contentType, caption,
				firstLong(m, "taken_at"),
				firstLong(m, "like_count"), firstLong(m, "comment_count"),
				views != null ? views : clipPlays.get(code),
				firstLong(m, "save_count"),          // 릴스 전용 — 피드·캐러셀은 키 부재 → null
				firstLong(m, "reshare_count"),       // 공유. 릴스 전용
				firstLong(m, "media_repost_count"),  // 리포스트. 전 타입 제공
				rawJson);
	}

	/** 후보 필드 중 처음 존재하는 값. 전부 없으면 null(취득 불가 지표 규칙). */
	private static Long firstLong(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.path(f);
			if (v.isNumber()) {
				return v.asLong();
			}
		}
		return null;
	}
}
