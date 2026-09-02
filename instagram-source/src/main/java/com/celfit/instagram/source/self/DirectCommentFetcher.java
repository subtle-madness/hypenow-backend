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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자체 댓글 fetcher(crawler DirectCommentFetcher 이식) — 게시물 페이지 GET 한 번으로 LSD·CSRF
 * 토큰·1페이지 댓글까지 전부 얻는다(SSR 인라인 {@code xig_polaris_media.comments_connection},
 * doc_id 불필요 — 실측: 스크래치패드 page_source.html). 2페이지부터는 로그아웃 GraphQL을 커서로
 * 페이지네이션한다. 실측(스크래치패드 debug2_result.json)으로 확인된 핵심 사실: 페이징 POST는
 * {@code X-CSRFToken} 헤더가 없으면 "Unauthorized logged out query"(code 1675002)로 거부된다 —
 * 값은 부트스트랩 GET 응답의 {@code Set-Cookie: csrftoken=...}에서 얻는다.
 *
 * <p>결손 필드(id·text·username·created_at) 댓글은 HikerBackend.toComment와 같은 필수 필드 계약으로
 * 제외한다 — 저장 계층이 백엔드에 무관하게 같은 셰이프를 받도록. 자체 GraphQL 응답에는 작성자 본인
 * 답글(preview_child_comments) 데이터가 없어 ownerReplyText는 항상 null이다(postUsername 파라미터는
 * 시그니처 동형성 유지용).
 *
 * <p>doc_id·friendlyName은 Supplier로 받는다 — IG doc_id는 2~4주 주기로 회전하는데(운영 실측), 호출자가
 * app_setting 기반 값을 매 fetch()마다 재조회해 재배포 없이 회전에 대응할 수 있게 한다(SelfCrawlBackend의
 * profileSurface Supplier 관용구와 동형).
 */
public class DirectCommentFetcher {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
	private static final Pattern CSRF_COOKIE = Pattern.compile("csrftoken=([^;]+)");
	private static final Map<String, String> GET_HEADERS = Map.of(
			"Accept", "text/html",
			"Accept-Language", "en-US,en;q=0.9",
			"Sec-Fetch-Mode", "navigate",
			"Upgrade-Insecure-Requests", "1");

	private final SelfTransport transport;
	private final Supplier<String> docId;
	private final Supplier<String> friendlyName;

	public DirectCommentFetcher(SelfTransport transport, Supplier<String> docId,
			Supplier<String> friendlyName) {
		this.transport = transport;
		this.docId = docId;
		this.friendlyName = friendlyName;
	}

	public CommentsFetch fetch(String shortCode, String postUsername, int pages) {
		// 1) 부트스트랩 GET — LSD·CSRF 토큰·1페이지 댓글을 한 응답에서 전부 얻는다.
		SelfResponse page = transport.get("https://www.instagram.com/p/" + shortCode + "/",
				ProxyTier.RESIDENTIAL, GET_HEADERS);
		if (page.status() != 200) {
			throw new SelfCrawlException(SelfErrorClassifier.ofStatus(page.status(), page.body()),
					"게시물 페이지 실패 status=" + page.status() + " code=" + shortCode);
		}
		String lsd = HandshakeExtractor.lsdFrom(page.body());
		String csrfToken = csrfTokenFrom(page);
		long mediaId = HandshakeExtractor.mediaIdFromShortCode(shortCode);

		int wanted = Math.max(1, pages);
		List<CommentInfo> out = new ArrayList<>();

		// 2) 1페이지 — 추가 요청 없이 SSR HTML에서 바로 파싱.
		JsonNode connection;
		try {
			connection = MAPPER.readTree(HandshakeExtractor.commentsConnectionFrom(page.body()));
		} catch (SelfCrawlException e) {
			throw e;
		} catch (RuntimeException e) {
			// 블록은 찾았는데 JSON이 아니다 — 있을 수 없는 경로지만 잭슨 예외 누출은 막는다.
			throw new SelfCrawlException(SelfErrorClass.LOGIN_WALL,
					"comments_connection JSON 파스 실패 code=" + shortCode, e);
		}
		appendComments(connection, out);
		String cursor = endCursor(connection);
		boolean hasNext = hasNextPage(connection);

		// 3) 2페이지부터 — GraphQL 커서 페이지네이션(X-CSRFToken 필수).
		for (int p = 1; p < wanted && hasNext; p++) {
			SelfResponse r = transport.post(GRAPHQL_URL, graphqlBody(lsd, mediaId, cursor),
					ProxyTier.RESIDENTIAL, postHeaders(lsd, csrfToken));
			// 비200뿐 아니라 200 로그인벽 HTML도 분류해 폴백망에 태운다(ofStatus의 LOGIN_WALL 분기).
			SelfErrorClass ec = SelfErrorClassifier.ofStatus(r.status(), r.body());
			if (ec != SelfErrorClass.OK) {
				// 중간 페이지 실패 — 받은 페이지분은 보존하되 미완주로 표시.
				return new CommentsFetch(out, false);
			}
			JsonNode root;
			try {
				root = MAPPER.readTree(r.body());
			} catch (RuntimeException e) {
				// 중간 페이지 파스 실패 — 비200 중간 실패와 같은 규칙으로 받은 분을 보존한다.
				return new CommentsFetch(out, false);
			}
			if (isGraphqlFailure(root)) {
				// 200 + 유효 JSON이지만 data 부재/null 또는 최상위 errors(doc_id 만료 등, code
				// 1675002 "Unauthorized logged out query" 실측) — 파싱은 성공하므로 위 catch에
				// 안 걸리고, comments_connection 체인이 MissingNode로 흘러 빈 페이지(0건)로 오독되면
				// break 후 complete=true가 나가버린다. 그러면 워터마크가 닫혀 영구 재수집 불가가
				// 되므로, 기존 부분 실패 처리(비200·파스실패)와 동일하게 받은 분을 보존한 미완주로 반환.
				return new CommentsFetch(out, false);
			}
			JsonNode pageConnection = root.path("data").path("xig_polaris_media").path("comments_connection");
			int before = out.size();
			appendComments(pageConnection, out);
			if (out.size() == before) {
				break;   // 빈 페이지 — 더 내려갈 이유가 없다.
			}
			String next = endCursor(pageConnection);
			if (!hasNextPage(pageConnection) || next == null || next.equals(cursor)) {
				break;   // 마지막 페이지 또는 커서 미전진(무한 루프 가드).
			}
			cursor = next;
		}
		return new CommentsFetch(out, true);
	}

	/**
	 * doc_id 만료 등 GraphQL 레벨 실패 감지 — 최상위 {@code errors} 배열 존재, 또는
	 * {@code data.xig_polaris_media.comments_connection}이 부재/null인 경우. 진짜로 댓글이 0건인
	 * 정상 응답은 {@code comments_connection}이 존재하고 {@code edges}만 빈 배열이라 구분된다.
	 */
	private static boolean isGraphqlFailure(JsonNode root) {
		JsonNode errors = root.path("errors");
		if (errors.isArray() && !errors.isEmpty()) {
			return true;
		}
		JsonNode connection = root.path("data").path("xig_polaris_media").path("comments_connection");
		return connection.isMissingNode() || connection.isNull();
	}

	private static void appendComments(JsonNode connection, List<CommentInfo> out) {
		for (JsonNode edge : connection.path("edges")) {
			CommentInfo comment = toComment(edge.path("node"));
			if (comment != null) {
				out.add(comment);
			}
		}
	}

	private static String endCursor(JsonNode connection) {
		return connection.path("page_info").path("end_cursor").asString(null);
	}

	private static boolean hasNextPage(JsonNode connection) {
		return connection.path("page_info").path("has_next_page").asBoolean(false);
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

	/** 부트스트랩 GET 응답의 Set-Cookie들에서 csrftoken 값을 뽑는다. 없으면 null(POST에서 헤더 생략). */
	private static String csrfTokenFrom(SelfResponse response) {
		for (String setCookie : response.header("set-cookie")) {
			Matcher m = CSRF_COOKIE.matcher(setCookie);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
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
		form.put("fb_api_req_friendly_name", friendlyName.get());
		form.put("doc_id", docId.get());
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

	/**
	 * Sec-Fetch-Site: same-origin 필수 — 없으면 IG가 JSON 대신 HTML 셸을 돌려준다.
	 * X-CSRFToken 필수 — 없으면 IG가 "Unauthorized logged out query"(code 1675002)로 거부한다(실측,
	 * 스크래치패드 debug2_result.json). csrfToken이 없으면(부트스트랩 Set-Cookie 누락) 헤더를 생략하고
	 * 그대로 시도한다 — 실패하면 기존 비200/파스실패 분기가 부분 결과로 처리한다.
	 */
	private static Map<String, String> postHeaders(String lsd, String csrfToken) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("x-ig-app-id", "936619743392459");
		headers.put("x-fb-lsd", lsd);
		headers.put("Sec-Fetch-Site", "same-origin");
		headers.put("Sec-Fetch-Mode", "cors");
		headers.put("Sec-Fetch-Dest", "empty");
		if (csrfToken != null && !csrfToken.isBlank()) {
			headers.put("X-CSRFToken", csrfToken);
		}
		return headers;
	}
}
