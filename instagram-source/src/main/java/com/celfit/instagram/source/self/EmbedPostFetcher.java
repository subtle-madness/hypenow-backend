package com.celfit.instagram.source.self;

import com.celfit.instagram.source.PostInfo;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * embed 단건 fetcher — /p/{code}/embed/captioned/ 서버렌더 HTML에서 지표를 파싱한다(로그인·doc_id 불필요).
 *
 * <p>이미지와 릴스의 공통분모는 <b>렌더된 텍스트</b>다: gql_data JSON 블롭은 릴스에만 실리므로
 * 좋아요·댓글·소유자·캡션은 렌더 텍스트에서, 조회수(video_view_count)만 릴스의 이스케이프된
 * JSON에서 뽑는다. 삭제·비공개 게시물은 302 리다이렉트(빈 본문) 또는 200 "빈 셸"(소유자·좋아요
 * 부재)로 온다 — 둘 다 NOT_FOUND로 분류해 폴백 없이 스킵시킨다.
 */
public class EmbedPostFetcher {

	/** 저수준 전송 심 — 운영은 SelfHttpClient::get, 테스트는 픽스처 반환 람다를 꽂는다. */
	@FunctionalInterface
	public interface SelfFetch {
		SelfResponse fetch(String url, ProxyTier tier, Map<String, String> headers);
	}

	private static final Map<String, String> HEADERS = Map.of(
			"Accept", "text/html",
			"Accept-Language", "en-US,en;q=0.9",
			"Sec-Fetch-Mode", "navigate",
			"Upgrade-Insecure-Requests", "1");

	// 렌더 텍스트: <a ... data-log-event="likeCountClick" ...>485,263 likes</a>
	private static final Pattern LIKES = Pattern.compile(">([\\d,]+) likes<");
	// 렌더 텍스트: >View all 3,359 comments< (댓글 적으면 "View all" 접두 없이 올 수 있음).
	// >…< 앵커 필수 — 댓글 앵커가 캡션 뒤에 렌더되므로, 비앵커 패턴은 "got 500 comments" 같은
	// 캡션 본문을 먼저 집어 오답을 낸다.
	private static final Pattern COMMENTS = Pattern.compile(">(?:View all )?([\\d,]+) comments<");
	// 릴스 전용 — 이스케이프된 JSON(video_view_count\":560365)이라 구분자를 느슨히 잡는다.
	private static final Pattern VIEWS = Pattern.compile("video_view_count[^0-9]{0,4}([0-9]+)");
	// 헤더 소유자: <span class="UsernameText">nasa</span> — 정확히 이 클래스만.
	// 콜라보 공동작성자는 "UsernameText CollabUsernameText"라 닫는 따옴표 즉시 매칭으로
	// 배제된다(매치 순서 의존 제거).
	private static final Pattern USERNAME =
			Pattern.compile("class=\"UsernameText\"[^>]*>([^<]+)<");
	// 캡션: <div class="Caption">…(중첩 div는 CaptionComments뿐 — 그 앞까지가 캡션 본문)
	private static final Pattern CAPTION =
			Pattern.compile("class=\"Caption\">(.*?)(?:<div |</div>)", Pattern.DOTALL);
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

	private final SelfFetch http;

	public EmbedPostFetcher(SelfFetch http) {
		this.http = http;
	}

	public PostInfo fetch(String shortCode) {
		String url = "https://www.instagram.com/p/" + shortCode + "/embed/captioned/";
		SelfResponse res = http.fetch(url, ProxyTier.RESIDENTIAL, HEADERS);
		int status = res.status();
		if (status >= 300 && status < 400) {
			// 삭제·비공개 게시물은 리다이렉트(빈 본문)로 온다 — 폴백 없이 스킵.
			throw new SelfCrawlException(SelfErrorClass.NOT_FOUND,
					"embed 리다이렉트(" + status + ") — 게시물 부재/비공개: " + shortCode);
		}
		String body = res.body() == null ? "" : res.body();
		if (status != 200) {
			throw new SelfCrawlException(SelfErrorClassifier.ofStatus(status, body),
					"embed 실패 status=" + status + " code=" + shortCode);
		}

		String username = first(USERNAME, body);
		Long likes = number(first(LIKES, body));
		if (username == null && likes == null) {
			// 200인데 소유자·좋아요 둘 다 없는 빈 셸 — 삭제·비공개 게시물의 또 다른 응답 셰이프.
			throw new SelfCrawlException(SelfErrorClass.NOT_FOUND, "embed 빈 셸: " + shortCode);
		}
		Long comments = number(first(COMMENTS, body));
		Long views = number(first(VIEWS, body));
		String caption = caption(body);
		boolean reels = views != null || body.contains("product_type\\\":\\\"clips");
		String contentType = reels ? "REELS" : "FEED";

		return new PostInfo(shortCode, username, null, null, null, contentType, caption,
				null, null, likes, comments, views, null, null, null, null, null, null, null,
				views != null, likes == null, false);
	}

	private static String first(Pattern p, String body) {
		Matcher m = p.matcher(body);
		return m.find() ? m.group(1) : null;
	}

	private static Long number(String s) {
		return s == null ? null : Long.valueOf(s.replace(",", ""));
	}

	/** Caption div 본문 → 태그 제거 + 엔티티 디코드. 소유자 username으로 시작하는 게 정상 셰이프다. */
	private static String caption(String body) {
		String raw = first(CAPTION, body);
		if (raw == null) {
			return null;
		}
		String text = raw.replaceAll("<br ?/?>", "\n").replaceAll("<[^>]*>", "");
		return decodeEntities(text).strip();
	}

	private static String decodeEntities(String s) {
		// 숫자 엔티티(&#064; 등) 먼저 — &amp; 치환을 나중에 해야 &amp;#064; 이중 디코드가 안 생긴다.
		Matcher m = NUMERIC_ENTITY.matcher(s);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb,
					Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
		}
		m.appendTail(sb);
		return sb.toString()
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&amp;", "&");
	}
}
