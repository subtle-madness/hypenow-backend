package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.AuthorInfo;
import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.HikerBadRequestException;
import com.celfit.instagram.source.HikerFetchException;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.instagram.source.MediaRef;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.PrivateAccountException;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.instagram.source.ShareLinkUnresolvedException;
import com.celfit.instagram.source.ShortCodes;
import com.celfit.instagram.source.SubjectNotFoundException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	/**
	 * 코드별 관측 지표 — igPlays는 IG 전용, fbPlays는 null(키 부재)과 0(관측된 0)을 구분한다.
	 * saves·shares·reposts도 함께 나른다(08-04): 저장·리포스트 키는 세션 복권(콜 단위 전부/전무,
	 * clips 존재율 ~45%)이라 clips 관측을 버리면 medias(~30%)보다 좋은 공급원을 매일 흘리게 된다.
	 */
	public record ClipCounts(Long igPlays, Long fbPlays, Long saves, Long shares, Long reposts) {

		/** 저장·공유·리포스트 중 하나라도 실렸는가 — 세션 복권 당첨 판정(재시도 중단 기준). */
		public boolean hasMetricKeys() {
			return saves != null || shares != null || reposts != null;
		}
	}

	/** 클립 보강 결과 — complete=false면 조회수 null이 "부재"가 아니라 "미취득"이다(오탐 방지 근거). */
	private record ClipPlays(Map<String, ClipCounts> plays, Map<String, ClipItem> items, boolean complete) {}

	/** clips 열거의 media 노드 원형 — 그리드 숨김 릴스를 게시물로 승격할 때 파싱 재료가 된다. */
	private record ClipItem(JsonNode media) {}

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
				firstLong(user, "media_count"),
				user.path("full_name").asString(null), user.path("profile_pic_url").asString(null),
				user.path("biography").asString(null),
				// 인증뱃지·외부링크(was 계약 §3-2) — 추가 콜 없이 같은 응답에서.
				nullableBoolean(user, "is_verified"), user.path("external_url").asString(null));
	}

	/**
	 * 게시자 프로필 — /v2/user/by/id?id=(브랜드 태그 모니터링 스펙 §2). 쿼리 파라미터명은 id다 —
	 * user_id로 보내면 Hiker가 422 {"loc":["query","id"],"msg":"Field required"}를 던진다
	 * (08-07 운영 첫 백필 실측 — woodiv.nature 게시자 4명 전원 실패로 표면화). fetchProfile과 달리
	 * 비공개를 예외로 승격하지 않는다(게시자 비공개는 관측값 — author_profile.is_private).
	 * 응답 셰이프는 by/username과 동일한 {user:{...}}로 가정(경로만 실측 — 셰이프는 미확인 유지).
	 */
	public AuthorInfo fetchAuthorProfile(String userId) {
		String body = http.get("/v2/user/by/id?id=" + enc(userId));
		JsonNode user = root(body).path("user");
		if (user.isMissingNode() || user.isNull()) {
			throw new HikerFetchException("게시자 프로필 응답에 user 없음: " + userId);
		}
		// user.pk는 JSON number(findings §2-①) — 응답의 pk를 정본으로 쓰되 없으면 요청값 유지.
		String igUserId = user.path("pk").isNumber() ? user.path("pk").asString() : userId;
		return new AuthorInfo(igUserId, user.path("username").asString(null),
				user.path("full_name").asString(null),
				firstLong(user, "follower_count"), firstLong(user, "following_count"),
				firstLong(user, "media_count"),
				user.path("biography").asString(null), user.path("profile_pic_url").asString(null),
				user.path("is_private").asBoolean(false),
				// 인증뱃지(was 계약 §3-2) — 키 부재는 null(미인증 false와 구분).
				nullableBoolean(user, "is_verified"));
	}

	/**
	 * 게시물 열거 — /v2/user/medias(릴스+피드 전체, 1페이지 12건).
	 * 이 엔드포인트는 릴스여도 play_count를 안 주므로(findings §2-③) /v2/user/clips 열거로 조회수를 머지한다.
	 */
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		int wanted = Math.max(1, pages);
		ClipPlays clips = fetchClipPlays(userId, wanted);
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		String cursor = null;
		for (int page = 0; page < wanted; page++) {
			String body;
			try {
				body = http.get("/v2/user/medias?user_id=" + enc(userId) + pageParam(cursor));
			} catch (SubjectNotFoundException e) {
				// Hiker는 열거할 엔트리가 없으면 200 빈 배열이 아니라 404 {"detail":"Entries not found"}를
				// 준다(릴스 0건 계정의 /v2/user/clips와 동일 규칙 — fetchClipPlays는 이미 이걸 삼킨다).
				// collectAccount는 fetchProfile이 200으로 계정 존재를 확인한 직후에만 이 경로를 타므로,
				// 여기서의 404는 "계정 부재"가 아니라 "열거할 게시물이 없음"이다. 계정 삭제·개명은
				// fetchProfile 단계에서 이미 SubjectNotFoundException으로 걸러진다.
				//
				// page 구분 없이 동일하게 처리한다: page==0 404는 "게시물 0건", page>0 404는 커서가
				// 끝에 도달했다는 신호일 수 있다 — 어느 쪽이든 지금까지 모은 결과(0건 또는 이전 페이지분)를
				// 그대로 반환하고 열거만 조용히 중단하는 것이 안전하다(커서 미전진 가드와 같은 성격의
				// "조용한 종료" — 예외로 계정 전체 등록·스윕을 실패시키지 않는다).
				log.info("게시물 열거 404 — user_id {} {}페이지, 게시물 없음/커서 종료로 간주하고 중단", userId, page + 1);
				break;
			}
			JsonNode root = root(body);
			int before = byCode.size();
			for (JsonNode item : items(root)) {
				PostInfo post = toPost(item, username, clips.plays(), clips.complete());
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
		// 그리드 숨김 릴스 합류(08-07) — "프로필에 공유"를 끈 릴스는 medias에 영영 안 실리고
		// clips에만 실린다(운영 실측 rran.e_ DbdA0j4SDUd: 7일 연속 clips 단독). clips를 조회수
		// 머지에만 쓰면 이런 릴스는 감지·수집 양쪽의 구조적 사각지대가 되므로 게시물로 승격한다.
		// medias와 겹치는 코드는 medias 파싱본이 이미 byCode에 있어 그대로 이긴다.
		for (var entry : clips.items().entrySet()) {
			if (!byCode.containsKey(entry.getKey())) {
				byCode.put(entry.getKey(),
						toPost(entry.getValue().media(), username,
								clips.plays(), true));   // clips 응답은 재생수 인라인 — 조회수 신뢰 가능
			}
		}
		// 핀 고정 게시물이 배열 맨 앞에 옴(taken_at 2023년 사례 — findings §3) → 게시 시각 내림차순 재정렬
		List<PostInfo> out = new ArrayList<>(byCode.values());
		out.sort(Comparator.comparing(PostInfo::takenAt,
				Comparator.nullsLast(Comparator.reverseOrder())));
		return out;
	}

	/**
	 * 클립 콜만 따로 다시 부르는 재시도 경로용(최초 1회 재시도 — CollectService) — 코드별 IG·FB 몫.
	 * 실패는 fetchClipPlays와 같은 규칙으로 삼킨다(재시도는 최선 노력, 빈 맵이면 머지가 안 일어날 뿐).
	 */
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return fetchClipPlays(userId, pages).plays();
	}

	/** 릴스 재생수 보강 — /v2/user/clips는 items[].media로 한 겹 더 감싼다. 실패해도 스윕은 계속(조회수만 null). */
	private ClipPlays fetchClipPlays(String userId, int pages) {
		Map<String, ClipCounts> plays = new HashMap<>();
		Map<String, ClipItem> items = new LinkedHashMap<>();   // 응답 순서 유지(그리드 숨김 릴스 승격 재료)
		try {
			String cursor = null;
			for (int page = 0; page < pages; page++) {
				String body = http.get("/v2/user/clips?user_id=" + enc(userId) + pageParam(cursor));
				JsonNode root = root(body);
				int before = plays.size();
				for (JsonNode item : root.path("response").path("items")) {
					JsonNode m = item.path("media");
					String code = m.path("code").asString(null);
					if (code != null && !code.isBlank()) {
						items.putIfAbsent(code, new ClipItem(m));
					}
					ClipCounts counts = playCounts(m);
					// 재생수 없는 셰이프여도 저장·리포스트 관측(세션 복권 당첨분)은 버리지 않는다.
					if (counts.igPlays() != null || counts.hasMetricKeys()) {
						plays.put(code, counts);
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
			// 삼키되 실패 사실은 남긴다 — 이 플래그가 없으면 하류가 "조회수 비공개"로 오탐한다.
			log.warn("클립 재생수 보강 실패 — user_id {}: {}", userId, e.getMessage());
			return new ClipPlays(plays, items, false);
		}
		return new ClipPlays(plays, items, true);
	}

	/** 태그 열거 1페이지 — posts는 응답 순서 그대로(태그된 시점 순 — 중단 판정은 호출자가 페이지 단위로 한다). */
	public record TaggedPage(List<PostInfo> posts, String nextPageId) {}

	/**
	 * 계정에 태그된 게시물 열거 — /v2/user/tag/medias(findings §11). 1페이지 1콜만 하고 커서를
	 * 그대로 반환한다: 감지(매일 1콜)·트래킹(105개 깊이)·백필(90일 컷)의 중단 규칙이 서로 달라
	 * 페이지네이션은 호출자(BrandCollectService)가 몬다. 페이지당 건수는 IG 소관 값(실측 21)이라
	 * 여기서 어떤 개수도 가정하지 않는다(스펙 §6 하드코딩 금지).
	 *
	 * <p>릴스 조회수(ig_play_count)는 이 열거에 상시 인라인이다(§11-2 — 프로필 열거와 결정적 차이)
	 * → clips 보강 없이 viewsTrusted=true. 정렬은 태그된 시점 순이라 taken_at 비단조(소급 태그 혼입)
	 * — 재정렬하지 않고 응답 순서를 유지한다(페이지 단위 중단 판정이 순서에 의존).
	 */
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		String body;
		try {
			body = http.get("/v2/user/tag/medias?user_id=" + enc(userId) + pageParam(pageId));
		} catch (SubjectNotFoundException e) {
			// 태그된 게시물이 0건이면 Hiker는 200 빈 배열이 아니라 404를 준다(fetchRecentPosts와
			// 동일 규칙) — 브랜드 계정에 태그가 아직 없는 건 정상 상태라 조용히 빈 페이지로 넘긴다.
			log.info("태그 열거 404 — user_id {} page_id {}, 태그 게시물 없음/커서 종료로 간주", userId, pageId);
			return new TaggedPage(List.of(), null);
		}
		JsonNode root = root(body);
		List<PostInfo> posts = new ArrayList<>();
		for (JsonNode item : items(root)) {
			posts.add(toPost(item, null, Map.of(), true));
		}
		String cursor = moreAvailable(root) ? nextPageId(root) : null;
		return new TaggedPage(posts, cursor);
	}

	/** 해시태그 recent 스트림 게시물 + 사진 태그된 계정 목록(소문자 정규화). */
	public record HashtagPost(PostInfo post, List<String> taggedUsernames) {}

	public record HashtagPage(List<HashtagPost> posts, String nextPageId) {}

	/**
	 * 해시태그 recent 열거 1페이지(스펙 2026-08-11 §3) — 섹션 셰이프
	 * {response:{sections:[{layout_content:{medias:[{media}]}}]}}를 우선 파싱하고,
	 * 평탄 items 셰이프는 폴백. usertags는 직접태그 제외 판정 재료(추가 콜 없음).
	 */
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		String body;
		try {
			body = http.get("/v2/hashtag/medias/recent?name=" + enc(tag) + pageParam(pageId));
		} catch (SubjectNotFoundException e) {
			log.info("해시태그 열거 404 — tag {} page_id {}, 게시물 없음/커서 종료로 간주", tag, pageId);
			return new HashtagPage(List.of(), null);
		}
		JsonNode root = root(body);
		List<HashtagPost> posts = new ArrayList<>();
		for (JsonNode item : hashtagItems(root)) {
			posts.add(new HashtagPost(toPost(item, null, Map.of(), true), taggedUsernames(item)));
		}
		String cursor = moreAvailable(root) ? nextPageId(root) : null;
		return new HashtagPage(posts, cursor);
	}

	public PostInfo fetchPost(String shortCode) {
		// /v2/media/info/by/code — share 해소(§2-6)와 같은 media_or_ad 셰이프. 구 /v2/media/by/code와
		// 미디어 노드 동등성은 실측 대조로 확인됨(14게시물 짝 비교 — 차이는 전부 세션 편차, 08-04).
		String body = http.get("/v2/media/info/by/code?code=" + enc(shortCode));
		JsonNode media = root(body).path("media_or_ad");
		if (media.isMissingNode() || media.isNull()) {
			// 실존 부재는 전송 계층 404가 정상 경로 — 200인데 media_or_ad가 없는 건 부재로 강등한다.
			throw new SubjectNotFoundException("게시물 응답에 media_or_ad 없음: " + shortCode);
		}
		// 단건 응답에는 play_count가 그대로 실린다 — clips 보강 경로를 타지 않으므로 조회수는 항상 신뢰 가능하다.
		PostInfo post = toPost(media, null, Map.of(), true);
		// 단건 응답에는 usernameHint가 없어 소유 계정을 user.username에서만 얻는다.
		// 없으면 스냅샷 적재(post_snapshot.username NOT NULL)도 target 등록도 불가 → 셰이프 이상으로 본다.
		if (post.username() == null) {
			throw new HikerFetchException("단건 응답에 소유 계정(user.username)이 없음: " + shortCode);
		}
		return post;
	}

	/**
	 * 댓글 수집 결과 — complete=false면 중간 페이지 콜 실패로 뒤 페이지를 못 받은 부분 결과다.
	 * 받은 페이지분은 그대로 저장 가능하지만, 브랜드 워터마크처럼 "이 게시물 댓글을 다 봤다"를
	 * 전제하는 갱신은 하면 안 된다(다음 스윕이 재시도할 근거를 지운다).
	 */
	public record CommentsFetch(List<CommentInfo> comments, boolean complete) {}

	/**
	 * 추적 게시물 댓글 — /v2/media/comments?id=<media pk>(findings §10-1). media pk는 저장 없이
	 * shortcode에서 산술 유도한다({@link ShortCodes}). 결손 필드(pk·text·좋아요·작성 시각·작성자) 댓글은
	 * 저장 대상이 아니라 리스트에서 제외한다(계약 §3 post_comment).
	 */
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return fetchComments(shortCode, postUsername, pages, Set.of());
	}

	/**
	 * knownCommentIds가 주어지면(브랜드 태그 모니터링 경로) 페이지 처리 후 그 페이지의 유효 댓글이
	 * 1건 이상 전부 기지일 때 다음 페이지를 부르지 않는다 — "최신부터 읽다 기지 댓글에서 중단"
	 * (태그 스펙 §3). 정렬이 IG 랭킹 혼합이라 건 단위 중단은 신규를 놓칠 수 있어 페이지 단위로 본다.
	 * 기지 댓글도 반환 목록에는 담는다(upsert가 body·like_count를 갱신).
	 */
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		int wanted = Math.max(1, pages);
		long mediaId = ShortCodes.toMediaId(shortCode);
		List<CommentInfo> out = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < wanted; page++) {
			String body;
			try {
				body = http.get("/v2/media/comments?id=" + mediaId + pageParam(cursor));
			} catch (RuntimeException e) {
				if (page == 0) {
					throw e;   // 보존할 것이 없다 — 기존 실패 의미 유지(호출자 격리 catch가 처리)
				}
				// 중간 페이지 실패 — 받은 페이지분을 버리지 않는다(08-10 운영 실측: 첫 백필 병렬
				// 부하에서 27건 수신 후 3페이지 실패로 전량 폐기 24게시물). 미완주 표시로 브랜드
				// 워터마크 전진을 막아 다음 스윕이 재시도한다.
				log.warn("댓글 {}페이지 실패 — 받은 {}건은 보존(미완주): media {} {}",
						page + 1, out.size(), mediaId, e.toString());
				return new CommentsFetch(out, false);
			}
			JsonNode root = root(body);
			int before = out.size();
			for (JsonNode c : root.path("response").path("comments")) {
				CommentInfo comment = toComment(c, postUsername);
				if (comment != null) {
					out.add(comment);
				}
			}
			if (page > 0 && out.size() == before) {
				log.warn("댓글 커서 미전진 의심 — media {} {}페이지에서 새 댓글 0건, 수집 중단", mediaId, page + 1);
				break;
			}
			List<CommentInfo> pageComments = out.subList(before, out.size());
			if (!knownCommentIds.isEmpty() && !pageComments.isEmpty()
					&& pageComments.stream().allMatch(c -> knownCommentIds.contains(c.id()))) {
				break;   // 페이지 전체 기지 — 더 내려가도 신규가 없다고 본다(기지 중단, 태그 스펙 §3)
			}
			// response.has_more_comments는 신뢰하지 않는다 — 운영 실측(media 3929190553799320931,
			// 댓글 2,325건): 1페이지가 has_more_comments=false를 주면서도 next_page_id를 들고 있었고,
			// 그 커서로 2페이지를 재요청하면 1페이지와 중복 0건인 신규 댓글 15건이 나왔다(플래그가 거짓).
			// 그래서 종료 조건은 커서 유무만 본다 — 무한 루프 위험은 위의 "커서 미전진" 가드가 막는다
			// (page>0에서 신규 0건이면 경고 로그 남기고 break, 이미 여기서 실질적 안전장치 역할).
			cursor = nextPageId(root);
			if (cursor == null) {
				break;
			}
		}
		return new CommentsFetch(out, true);
	}

	private static CommentInfo toComment(JsonNode c, String postUsername) {
		JsonNode pk = c.path("pk");
		JsonNode text = c.path("text");
		JsonNode likeCount = c.path("comment_like_count");
		JsonNode createdAt = c.path("created_at_utc");
		JsonNode username = c.path("user").path("username");
		// 결손 필드 댓글은 프론트에 부분 결손 렌더 경로가 없어 저장하지 않는다(계약 §3).
		if (pk.isMissingNode() || pk.isNull() || text.isMissingNode() || text.isNull()
				|| !likeCount.isNumber() || !createdAt.isNumber()
				|| username.isMissingNode() || username.isNull() || username.asString("").isBlank()) {
			return null;
		}
		return new CommentInfo(pk.asString(), username.asString(), text.asString(), likeCount.asLong(),
				Instant.ofEpochSecond(createdAt.asLong()), ownerReplyText(c, postUsername));
	}

	/**
	 * 작성자 본인 답글 판정 — preview_child_comments 중 is_created_by_media_owner==true 이거나
	 * 답글 작성자 username이 게시물 소유 계정과 대소문자 무시 일치하는 첫 건(findings §10-1).
	 * 협업(coauthor) 게시물은 media owner ≠ 추적 계정일 수 있어 두 조건을 or로 본다.
	 */
	private static String ownerReplyText(JsonNode c, String postUsername) {
		for (JsonNode child : c.path("preview_child_comments")) {
			boolean isOwner = child.path("is_created_by_media_owner").asBoolean(false);
			String childUsername = child.path("user").path("username").asString(null);
			boolean usernameMatches = postUsername != null && childUsername != null
					&& childUsername.equalsIgnoreCase(postUsername);
			if (isOwner || usernameMatches) {
				return child.path("text").asString(null);
			}
		}
		return null;
	}

	/**
	 * share 단축 링크 해소 — /v2/media/info/by/url(계약 §2-6). 404는 게시물 부재로 전송 계층이 이미
	 * SubjectNotFoundException을 던진다. 400(URL 형식 불량)만 여기서 ShareLinkUnresolvedException으로 바꾼다.
	 */
	public MediaRef resolveMediaByUrl(String url) {
		String body;
		try {
			body = http.get("/v2/media/info/by/url?url=" + enc(url));
		} catch (HikerBadRequestException e) {
			throw new ShareLinkUnresolvedException("URL 해소 실패: " + url);
		}
		JsonNode media = root(body).path("media_or_ad");
		if (media.isMissingNode() || media.isNull()) {
			throw new HikerFetchException("share 해소 응답에 media_or_ad 없음: " + url);
		}
		String code = media.path("code").asString(null);
		String username = media.path("user").path("username").asString(null);
		if (code == null || username == null) {
			throw new HikerFetchException("share 해소 응답 셰이프 이상(code·username 없음): " + url);
		}
		String contentType = "clips".equals(media.path("product_type").asString("")) ? "REELS" : "FEED";
		return new MediaRef(code, username, contentType);
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

	/**
	 * 해시태그 recent 응답의 medias 노드 — sections→layout_content→medias→media 중첩을 걷는다.
	 * 섹션에 medias가 하나도 없으면(미실측 셰이프 방어) 평탄 items 셰이프로 폴백한다.
	 * {@link #items(JsonNode)}를 재사용하지 않는다 — 그쪽은 response 바로 아래 items/medias 배열
	 * 1단만 언랩하고, 여기는 sections→layout_content를 두 겹 더 내려가야 media 노드에 닿는다.
	 */
	private static List<JsonNode> hashtagItems(JsonNode root) {
		JsonNode res = root.has("response") ? root.path("response") : root;
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode section : res.path("sections")) {
			for (JsonNode media : section.path("layout_content").path("medias")) {
				JsonNode m = media.path("media");
				if (!m.isMissingNode() && !m.isNull()) {
					out.add(m);
				}
			}
		}
		if (out.isEmpty()) {
			for (JsonNode item : res.path("items")) {
				out.add(item.has("media") ? item.path("media") : item);
			}
		}
		return out;
	}

	/**
	 * 사진 태그된(usertags) 계정 목록 — 소문자 정규화(직접태그 제외 판정 재료).
	 * 같은 계정이 여러 태그 위치에 찍힐 수 있어(캐러셀 등) LinkedHashSet으로 중복을 접되
	 * 응답 순서는 유지한다.
	 */
	private static List<String> taggedUsernames(JsonNode media) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (JsonNode in : media.path("usertags").path("in")) {
			String username = in.path("user").path("username").asString(null);
			if (username != null && !username.isBlank()) {
				out.add(username.toLowerCase(java.util.Locale.ROOT));
			}
		}
		return new ArrayList<>(out);
	}

	private static PostInfo toPost(JsonNode node, String usernameHint,
			Map<String, ClipCounts> clipPlays, boolean viewsTrusted) {
		JsonNode m = node.has("media") ? node.path("media") : node;   // clips 열거는 한 겹 더 감쌈
		String code = m.path("code").asString();
		String username = usernameHint != null ? usernameHint : m.path("user").path("username").asString(null);
		// user 노드에서 같이 뽑는다(제로 콜 원칙, 트랙 II) — 단건 응답에만 실값, 열거 경로는 소비처 없음.
		String ownerFullName = m.path("user").path("full_name").asString(null);
		String ownerProfilePicUrl = m.path("user").path("profile_pic_url").asString(null);
		// 소유 계정 IG pk — 프로필의 user.pk처럼 JSON number라 asString 코어션(findings §2-①).
		// POST 등록만 있는 계정의 clips 재시도(저장·리포스트 보강)가 user_id로 쓴다.
		String ownerUserId = m.path("user").path("pk").isNumber() ? m.path("user").path("pk").asString() : null;
		// media_type==2는 일반 비디오 피드도 포함 → 릴스 판별은 product_type(findings §4)
		String contentType = "clips".equals(m.path("product_type").asString("")) ? "REELS" : "FEED";
		// v2는 caption.text, v1은 caption_text — caption 자체가 null일 수 있다
		String caption = m.path("caption_text").isMissingNode()
				? m.path("caption").path("text").asString(null) : m.path("caption_text").asString(null);
		// view_count 키는 v2 응답에 부재 → 후보에서 제외. 열거 응답엔 play_count가 없어 clips 머지로 보강.
		// views는 IG 몫(ig_play_count 우선), fbPlays는 FB 몫 — play_count는 세션 따라 FB 합산 여부가
		// 바뀌어 역행하므로(findings §2 결론 4) 정본이 아니다. 화면 합산은 저장 계층이 조립한다.
		ClipCounts own = playCounts(m);
		ClipCounts clip = clipPlays.get(code);
		Long views = own.igPlays() != null ? own.igPlays() : clip != null ? clip.igPlays() : null;
		Long fbPlays = own.fbPlays() != null ? own.fbPlays() : clip != null ? clip.fbPlays() : null;
		// 좋아요 숨김(like_and_view_counts_disabled) 시 like_count는 실측이 아니라 프리뷰 잔여값이다
		// (운영 실측 08-03: 서로 다른 두 게시물이 똑같이 3) — 취득 불가로 null 처리해야 값→null 전이가
		// METRICS_HIDDEN 감지에 걸린다. 댓글·조회수는 숨김과 무관하게 실측이 계속 온다.
		boolean likesHidden = m.path("like_and_view_counts_disabled").asBoolean(false);
		Long likes = likesHidden ? null : firstLong(m, "like_count");
		// 공유 숨김 — share_count_disabled 토글이거나, 좋아요 숨김이 공유 노출도 함께 끈다
		// (IG 앱 문구 "좋아요 수 및 공유 횟수는 회원님만", 08-05 실측: lvcd=true 전원 reshare 영구 부재).
		boolean sharesHidden = m.path("share_count_disabled").asBoolean(false) || likesHidden;
		// 저장·공유·리포스트는 세션 복권(콜 단위 전부/전무, 08-04 실측) — 이 응답이 꽝이어도
		// 같은 스윕의 clips 콜이 당첨이면 그 관측으로 채운다(조회수 머지와 같은 상호보완).
		// 피드는 저장·공유 키가 전 세션 부재라 own·clip 모두 null → 기존 영구 null 규칙 그대로.
		Long saves = own.saves() != null ? own.saves() : clip != null ? clip.saves() : null;
		Long shares = own.shares() != null ? own.shares() : clip != null ? clip.shares() : null;
		Long reposts = own.reposts() != null ? own.reposts() : clip != null ? clip.reposts() : null;
		// brand_post_meta 표시 메타(was 계약 §3-2) — 전부 같은 노드, 추가 콜 0.
		// 영상 URL·길이는 릴스·비디오에만 실린다(피드·캐러셀은 키 부재 → null).
		String videoUrl = m.path("video_versions").path(0).path("url").asString(null);
		Double videoDuration = m.path("video_duration").isNumber() ? m.path("video_duration").asDouble() : null;
		// 유료협찬은 키 부재(null=판정 unknown)와 관측된 false를 구분한다
		// — 태그 열거 응답에는 키가 없다(합성 픽스처 기준, 라이브 미실측).
		Boolean isPaidPartnership = nullableBoolean(m, "is_paid_partnership");
		return new PostInfo(code, username, ownerFullName, ownerProfilePicUrl, ownerUserId, contentType, caption,
				thumbnailUrl(m), firstLong(m, "taken_at"),
				likes, firstLong(m, "comment_count"),
				views, fbPlays,
				saves, shares, reposts,
				videoUrl, videoDuration, isPaidPartnership,
				viewsTrusted, likesHidden, sharesHidden);
	}

	/**
	 * media 노드에서 IG·FB 재생수 몫 추출. ig_play_count가 없는 응답(미실측 셰이프 방어)에서는
	 * play_count - fb_play_count로 IG 몫을 복원한다 — play_count를 그대로 views에 두면 저장 계층의
	 * fb 합산과 겹쳐 FB 몫이 이중 계상된다.
	 *
	 * <p>FB 몫은 play > ig면 play - ig로 유도한다(fb 키보다 우선) — fb 키 없이 합산 play만 주는
	 * 세션(실측 DUrj0iGEn6G)과 fb 키가 0인데 play > ig인 모순 세션(DPQoGI1APa_)이 실존하고,
	 * fb 키가 정상일 때는 play - ig == fb라 결과가 같다(실측 검산). 화면 표시값이 play이므로
	 * 유도값이 화면 기준에 더 충실하다.
	 */
	private static ClipCounts playCounts(JsonNode m) {
		Long ig = firstLong(m, "ig_play_count");
		Long fb = firstLong(m, "fb_play_count");
		Long play = firstLong(m, "play_count");
		// Long.valueOf 명시 박싱 — primitive(play-fb)와 Long(play) 혼합 삼항은 null 언박싱 NPE를 낸다
		Long igPlays = ig != null ? ig : play != null && fb != null ? Long.valueOf(play - fb) : play;
		Long fbPlays = igPlays != null && play != null && play > igPlays ? Long.valueOf(play - igPlays) : fb;
		return new ClipCounts(igPlays, fbPlays,
				firstLong(m, "save_count"), firstLong(m, "reshare_count"), firstLong(m, "media_repost_count"));
	}

	/** post_meta 썸네일(계약 §3) — image_versions2.candidates[0].url. 픽스처 실측(2026-07-30): 전 게시물 존재, 없으면 null. */
	private static String thumbnailUrl(JsonNode m) {
		JsonNode candidates = m.path("image_versions2").path("candidates");
		return candidates.isArray() && !candidates.isEmpty() ? candidates.get(0).path("url").asString(null) : null;
	}

	/**
	 * 키 부재·null을 Java null로 보존하는 boolean 파싱 — 관측된 false와 "확인 못 함"을 구분해야
	 * 하는 필드(is_verified·is_paid_partnership)용. 존재 여부가 정보인 필드에만 쓴다
	 * (is_private처럼 부재를 기본값으로 봐도 되는 필드는 기존대로 asBoolean(false)).
	 */
	private static Boolean nullableBoolean(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asBoolean();
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
