package com.celfit.instagram.source.self;

import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.CommentsFetch;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자체 댓글 fetcher(crawler DirectCommentFetcher 이식) — 게시물 페이지 GET으로 LSD를 부트스트랩한 뒤
 * 로그아웃 GraphQL(xig_polaris_media.comments_connection)을 커서로 페이지네이션한다.
 *
 * <p>결손 필드(id·text·username·created_at) 댓글은 HikerBackend.toComment와 같은 필수 필드 계약으로
 * 제외한다 — 저장 계층이 백엔드에 무관하게 같은 셰이프를 받도록. 자체 GraphQL 응답에는 작성자 본인
 * 답글(preview_child_comments) 데이터가 없어 ownerReplyText는 항상 null이다(postUsername 파라미터는
 * 시그니처 동형성 유지용).
 */
public class DirectCommentFetcher {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
	private static final Map<String, String> GET_HEADERS = Map.of(
			"Accept", "text/html",
			"Accept-Language", "en-US,en;q=0.9",
			"Sec-Fetch-Mode", "navigate",
			"Upgrade-Insecure-Requests", "1");

	private final SelfTransport transport;
	private final String docId;
	private final String friendlyName;

	public DirectCommentFetcher(SelfTransport transport, String docId, String friendlyName) {
		this.transport = transport;
		this.docId = docId;
		this.friendlyName = friendlyName;
	}

	public CommentsFetch fetch(String shortCode, String postUsername, int pages) {
		// 1) LSD 부트스트랩 — 게시물 페이지 GET(HTML). 로그인 벽 셸이면 lsdFrom이 LOGIN_WALL로 승격.
		SelfResponse page = transport.get("https://www.instagram.com/p/" + shortCode + "/",
				ProxyTier.RESIDENTIAL, GET_HEADERS);
		if (page.status() != 200) {
			throw new SelfCrawlException(SelfErrorClassifier.ofStatus(page.status(), page.body()),
					"게시물 페이지 실패 status=" + page.status() + " code=" + shortCode);
		}
		String lsd = HandshakeExtractor.lsdFrom(page.body());
		long mediaId = HandshakeExtractor.mediaIdFromShortCode(shortCode);

		int wanted = Math.max(1, pages);
		List<CommentInfo> out = new ArrayList<>();
		String cursor = null;
		for (int p = 0; p < wanted; p++) {
			SelfResponse r = transport.post(GRAPHQL_URL, graphqlBody(lsd, mediaId, cursor),
					ProxyTier.RESIDENTIAL, postHeaders(lsd));
			if (r.status() != 200) {
				if (p == 0) {
					// 보존할 것이 없다 — 상태 분류 예외로 폴백 라우팅에 태운다.
					throw new SelfCrawlException(SelfErrorClassifier.ofStatus(r.status(), r.body()),
							"댓글 graphql 실패 status=" + r.status() + " code=" + shortCode);
				}
				// 중간 페이지 실패 — 받은 페이지분은 보존하되 미완주로 표시(HikerBackend와 동일 규칙).
				return new CommentsFetch(out, false);
			}
			JsonNode connection = MAPPER.readTree(r.body())
					.path("data").path("xig_polaris_media").path("comments_connection");
			int before = out.size();
			for (JsonNode edge : connection.path("edges")) {
				CommentInfo comment = toComment(edge.path("node"));
				if (comment != null) {
					out.add(comment);
				}
			}
			if (out.size() == before) {
				break;   // 빈 페이지 — 더 내려갈 이유가 없다.
			}
			JsonNode pageInfo = connection.path("page_info");
			String next = pageInfo.path("end_cursor").asString(null);
			if (!pageInfo.path("has_next_page").asBoolean(false) || next == null
					|| next.equals(cursor)) {
				break;   // 마지막 페이지 또는 커서 미전진(무한 루프 가드).
			}
			cursor = next;
		}
		return new CommentsFetch(out, true);
	}

	/**
	 * 결손 필드(id·text·username·created_at) 댓글은 제외 — HikerBackend.toComment의 필수 필드 계약 미러.
	 * likeCount만은 nullable(자체 응답도 대체로 실리지만 부재를 결손으로 보지 않는다).
	 */
	private static CommentInfo toComment(JsonNode node) {
		String id = node.path("id").asString(null);
		JsonNode text = node.path("text");
		String username = node.path("user").path("username").asString(null);
		JsonNode createdAt = node.path("created_at");
		if (id == null || id.isBlank() || text.isMissingNode() || text.isNull()
				|| username == null || username.isBlank() || !createdAt.isNumber()) {
			return null;
		}
		JsonNode likeCount = node.path("comment_like_count");
		return new CommentInfo(id, username, text.asString(),
				likeCount.isNumber() ? likeCount.asLong() : null,
				Instant.ofEpochSecond(createdAt.asLong()), null);
	}

	/** GraphQL form 바디 — variables는 커서에 따옴표가 실리므로 JSON 직렬화 후 URL 인코딩 필수. */
	private String graphqlBody(String lsd, long mediaId, String cursor) {
		Map<String, Object> variables = new LinkedHashMap<>();
		variables.put("media_id", String.valueOf(mediaId));
		if (cursor != null) {
			variables.put("after", cursor);
		}
		Map<String, String> form = new LinkedHashMap<>();
		form.put("lsd", lsd);
		form.put("fb_api_req_friendly_name", friendlyName);
		form.put("doc_id", docId);
		form.put("variables", MAPPER.writeValueAsString(variables));
		StringBuilder sb = new StringBuilder();
		form.forEach((k, v) -> {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
		});
		return sb.toString();
	}

	/** Sec-Fetch-Site: same-origin 필수 — 없으면 IG가 JSON 대신 HTML 셸을 돌려준다. */
	private static Map<String, String> postHeaders(String lsd) {
		return Map.of(
				"x-ig-app-id", "936619743392459",
				"x-fb-lsd", lsd,
				"Sec-Fetch-Site", "same-origin",
				"Sec-Fetch-Mode", "cors",
				"Sec-Fetch-Dest", "empty");
	}
}
