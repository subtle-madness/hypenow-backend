package com.celfit.instagram.source.self;

import com.celfit.instagram.source.PrivateAccountException;
import com.celfit.instagram.source.ProfileInfo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * og 프로필 fetcher — 로그아웃 프로필 문서(https://www.instagram.com/{username}/)의 서버렌더
 * HTML에서 프로필 통계를 파싱한다(로그인·doc_id 불필요, 문서 표면).
 *
 * <p>문서에 실린 JSON 블롭에서 follower_count·following_count·full_name·is_verified·biography·
 * profile_pic_url을 뽑고, 게시물 수만 og:description 메타("4,900 Posts")에서 뽑는다 — 문서 JSON의
 * media_count는 실리지 않는다. userId는 {@code profilePage_(\d+)} 마커(400계정 5개 전부·
 * web_profile_info 400에 안 걸리는 문서표면)에서 채택한다 — wpi가 STRUCTURAL_400인 계정도 og는
 * 이 마커를 실어 pk를 준다(feed/user posts fetcher의 pk 공급원, 콜 추가 없음). 최상위
 * {@code "id":null}은 로그아웃 PolarisViewer의 것이라 프로필 id로 쓰면 안 된다 — 반드시
 * profilePage_ 마커에서만 채택한다.
 *
 * <p>오류 분류: 정상 응답도 HTML이라 SelfErrorClassifier의 200-HTML 휴리스틱(LOGIN_WALL)을 그대로
 * 던질 수 없다 — 파싱까지 해보고 통계가 전무한 빈 셸일 때만 LOGIN_WALL(HTML 셸)/NOT_FOUND로
 * 확정한다. 3xx 리다이렉트는 계정 부재/게이트로 NOT_FOUND.
 *
 * <p>비공개 계정(S15): 문서 JSON 블롭에도 wpi(web_profile_info)와 동일하게 {@code "is_private"}
 * 마커가 실린다(실측 픽스처 og_profile.html에 {@code "is_private":false} 확인) — wpi와 동일 계약으로
 * PrivateAccountException을 던진다. 프로필 표면 토글(og↔wpi)이 예외 계약을 바꾸면 안 되기 때문이다.
 */
public class OgProfileFetcher {

	private static final Logger log = LoggerFactory.getLogger(OgProfileFetcher.class);

	private static final Map<String, String> HEADERS = Map.of(
			"Accept", "text/html",
			"Accept-Language", "en-US,en;q=0.9",
			"Sec-Fetch-Mode", "navigate",
			"Upgrade-Insecure-Requests", "1");

	private static final Pattern FOLLOWERS = Pattern.compile("\"follower_count\":(\\d+)");
	private static final Pattern FOLLOWING = Pattern.compile("\"following_count\":(\\d+)");
	// 게시물 수는 og:description 메타에서만 — "104M Followers, 95 Following, 4,900 Posts - …"
	private static final Pattern OG_DESCRIPTION =
			Pattern.compile("<meta property=\"og:description\" content=\"([^\"]*)\"");
	private static final Pattern POSTS_IN_DESCRIPTION = Pattern.compile("([\\d,]+) Posts");
	private static final Pattern FULL_NAME = Pattern.compile("\"full_name\":\"([^\"]*)\"");
	private static final Pattern IS_VERIFIED = Pattern.compile("\"is_verified\":(true|false)");
	private static final Pattern BIOGRAPHY =
			Pattern.compile("\"biography\":\"((?:\\\\.|[^\"\\\\])*)\"");
	private static final Pattern USERNAME = Pattern.compile("\"username\":\"([^\"]*)\"");
	private static final Pattern PROFILE_PIC_URL =
			Pattern.compile("\"profile_pic_url\":\"((?:\\\\.|[^\"\\\\])*)\"");
	private static final Pattern EXTERNAL_URL = Pattern.compile("\"external_url\":\"([^\"]*)\"");
	private static final Pattern IS_PRIVATE = Pattern.compile("\"is_private\":(true|false)");
	private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
	private static final Pattern PROFILE_PAGE_ID = Pattern.compile("profilePage_(\\d+)");

	private final EmbedPostFetcher.SelfFetch fetch;

	public OgProfileFetcher(EmbedPostFetcher.SelfFetch fetch) {
		this.fetch = fetch;
	}

	public ProfileInfo fetchProfile(String username) {
		String url = "https://www.instagram.com/"
				+ URLEncoder.encode(username, StandardCharsets.UTF_8) + "/";
		SelfResponse res = fetch.fetch(url, ProxyTier.RESIDENTIAL, HEADERS);
		int status = res.status();
		if (status >= 300 && status < 400) {
			// 프로필 부재·게이트는 리다이렉트로 온다 — 폴백 없이 스킵.
			throw new SelfCrawlException(SelfErrorClass.NOT_FOUND,
					"og 리다이렉트(" + status + ") — 계정 부재/게이트: " + username);
		}
		String body = res.body() == null ? "" : res.body();
		SelfErrorClass ec = SelfErrorClassifier.ofStatus(status, body);
		if (ec != SelfErrorClass.OK && ec != SelfErrorClass.LOGIN_WALL) {
			// 비200 분류는 그대로 — LOGIN_WALL(200 HTML)만은 정상 셰이프이기도 하니 파싱으로 확정.
			throw new SelfCrawlException(ec,
					"og 프로필 실패 status=" + status + " username=" + username);
		}

		Long followers = number(first(FOLLOWERS, body));
		String pageUsername = first(USERNAME, body);
		// "username":"" 같은 공백 매치는 부재와 동일 취급 — 빈 셸 가드 우회를 막는다.
		if (followers == null && (pageUsername == null || pageUsername.isBlank())) {
			// 통계 없는 빈 셸: HTML 셸(ofStatus=LOGIN_WALL)은 로그인 벽으로 표면 소진,
			// 그 외(비HTML 200 빈 본문)는 게이트/삭제로 NOT_FOUND.
			throw new SelfCrawlException(
					ec == SelfErrorClass.LOGIN_WALL
							? SelfErrorClass.LOGIN_WALL : SelfErrorClass.NOT_FOUND,
					"og 빈 셸(통계 부재): " + username);
		}

		// 통계가 실린 정상 응답으로 확정된 뒤에만 비공개 판정 — wpi와 동일 계약(폴백 대상 아님).
		if (Boolean.TRUE.equals(nullableBoolean(first(IS_PRIVATE, body)))) {
			throw new PrivateAccountException("비공개 계정: " + username);
		}

		Long following = number(first(FOLLOWING, body));
		Long mediaCount = mediaCountFromOgDescription(body);
		String fullName = unescape(first(FULL_NAME, body));
		Boolean isVerified = nullableBoolean(first(IS_VERIFIED, body));
		String biography = unescape(first(BIOGRAPHY, body));
		String profilePicUrl = unescape(first(PROFILE_PIC_URL, body));
		String externalUrl = unescape(first(EXTERNAL_URL, body));
		// profilePage_ 마커에서 pk 채택 — 최상위 "id":null(로그아웃 뷰어)은 쓰지 않는다.
		String userId = first(PROFILE_PAGE_ID, body);

		return new ProfileInfo(pageUsername != null && !pageUsername.isBlank()
						? pageUsername : username, userId,
				followers, following, mediaCount, fullName, profilePicUrl, biography,
				isVerified, externalUrl);
	}

	private static Long mediaCountFromOgDescription(String body) {
		String description = first(OG_DESCRIPTION, body);
		if (description == null) {
			// og:description 메타 자체가 없다 — 문서 셰이프 이탈(빈 셸 가드는 이미 통과한 뒤이므로 이례적).
			log.debug("og:description 메타 부재 — media_count 관측 불가");
			return null;
		}
		Long count = number(first(POSTS_IN_DESCRIPTION, description));
		if (count == null) {
			// "N Posts" 패턴은 영어 로케일 의존이라 다른 언어 응답에서 조용히 null이 될 수 있다(S15) —
			// 관측 가능하도록 최소 로그만 남긴다(로케일 확장은 이번 범위 밖).
			log.debug("og:description 게시물 수 파싱 실패(로케일 불일치 의심): {}", description);
		}
		return count;
	}

	private static String first(Pattern p, String body) {
		Matcher m = p.matcher(body);
		return m.find() ? m.group(1) : null;
	}

	private static Long number(String s) {
		return s == null ? null : Long.valueOf(s.replace(",", ""));
	}

	private static Boolean nullableBoolean(String s) {
		return s == null ? null : Boolean.valueOf(s);
	}

	/** JSON 문자열 이스케이프 해제 — \\uXXXX 먼저, 그다음 \\n·\\"·\\/·\\\\ 등 단문자. */
	private static String unescape(String s) {
		if (s == null) {
			return null;
		}
		Matcher m = UNICODE_ESCAPE.matcher(s);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(
					String.valueOf((char) Integer.parseInt(m.group(1), 16))));
		}
		m.appendTail(sb);
		return sb.toString()
				.replace("\\n", "\n")
				.replace("\\t", "\t")
				.replace("\\\"", "\"")
				.replace("\\/", "/")
				.replace("\\\\", "\\");
	}
}
