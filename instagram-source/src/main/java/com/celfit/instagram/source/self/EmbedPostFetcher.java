package com.celfit.instagram.source.self;

import com.celfit.instagram.source.PostInfo;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * embed 단건 fetcher — /p/{code}/embed/captioned/ 서버렌더 HTML에서 지표를 파싱한다(로그인·doc_id 불필요).
 *
 * <p>이미지와 릴스의 공통분모는 <b>렌더된 텍스트</b>다: gql_data JSON 블롭은 릴스에만 실리므로
 * 좋아요·댓글·소유자·캡션은 렌더 텍스트에서, 조회수(video_view_count)만 릴스의 이스케이프된
 * JSON에서 뽑는다. 삭제·비공개 게시물은 302 리다이렉트(빈 본문) 또는 200 "빈 셸"(소유자·좋아요
 * 부재)로 온다 — 하지만 IP 소프트블록/게이트 응답도 같은 모양으로 온다(09-03 운영 오탐 실측,
 * 평시 17배). <b>부재 확정은 Hiker의 결정론적 404만</b>이라는 설계 불변식(BrandCollectService
 * javadoc)에 따라 이 둘은 {@link SelfErrorClass#OTHER}로 강등해 FailoverInstagramSource가 Hiker로
 * 재확인하게 한다 — 진짜 삭제면 Hiker가 404를 줘서 최종 판정은 동일하고 오탐만 사라진다.
 *
 * <p><b>좋아요 숨김 판정(데이터 보호 결함 수정)</b> — Hiker는 IG의 명시 플래그
 * (like_and_view_counts_disabled)로 숨김을 판정하지만, self는 그런 신호가 없다. 좋아요 카운트
 * 렌더 텍스트({@link #LIKES})가 안 잡히면 "진짜 숨김"과 "정규식 파싱 실패"(로케일 변경 등)를
 * 신뢰 가능하게 구분할 신호가 없다(실 픽스처·라이브 조사로도 확인 못 함 — 결함 보고서 참조).
 * 그래서 좋아요 카운트가 안 잡히면 likesHidden을 {@code null}(미확정)로 남긴다 — 카운트가
 * 잡혔을 땐(렌더된 숫자를 실제로 봤으니) {@code false}(확정 비숨김)를 준다. 과거엔 파싱 성공
 * 여부와 무관하게 항상 {@code false}를 반환해, self 기원 관측이 Hiker의 확정 false와 구분되지
 * 않는 결함이 있었다(S9, 2026-09-03 감사 수정 — PostInfo.likesHidden javadoc 참조). 저장
 * 계층(SnapshotRepository.upsertPost)은 여전히 false를 "미확정 보호" 신호로 쓰므로, 이 null은
 * 저장 직전에 false로 접힌다 — 바뀐 건 재시도·0 간주 등 인메모리 판단이 이제 진짜 미확정과
 * Hiker의 확정 false를 구분할 수 있다는 점이다.
 *
 * <p><b>공유 숨김은 구조적으로 판정 불가</b> — embed HTML에는 공유 횟수 자체가 안 실린다(shares는
 * 항상 null). "숨김"과 "이 표면이 원래 못 주는 값"을 구분할 신호가 전혀 없으므로 sharesHidden은
 * 항상 {@code null}(미확정)을 반환한다(S9). 과거엔 여기도 항상 {@code false}를 반환해, 공유 숨김
 * 릴스가 self 관측 위에서 매일 재시도 상한까지 헛돌고 소진 시 공유 0으로 오기록되는 결함을 냈다
 * (CollectService#assumeZeroForOmittedKeys 참조) — 단건 재시도(항상 Hiker 직결)가 진짜 확정값을
 * 관측하면 {@link com.celfit.instagram.source.PostInfo#mergedMetrics(Long, Long, Long, Boolean)}가
 * 그 값을 되싣는다.
 */
public class EmbedPostFetcher {

	private static final Logger log = LoggerFactory.getLogger(EmbedPostFetcher.class);

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
	// S4 — Hiker와 같은 신호(product_type)를 값 전체를 캡처해 뽑는다. 과거엔 리터럴 부분문자열
	// "product_type\":\"clips"의 존재 여부만 봐서, 실값이 "clips_v2"처럼 "clips"로 시작만 하고
	// 실제로는 다른 product_type이어도 접두 일치로 REELS 오판정됐다. gql_data JSON 블롭 자체가
	// 릴스에만 실리므로(클래스 javadoc) product_type이 아예 안 잡히는 건 파싱 실패가 아니라
	// "릴스가 아니다"라는 구조적 신호다 — null 강등 대상이 아니다.
	private static final Pattern PRODUCT_TYPE = Pattern.compile("product_type\\\\\":\\\\\"([a-z_0-9]+)");
	// 헤더 소유자: <span class="UsernameText">nasa</span> — 정확히 이 클래스만.
	// 콜라보 공동작성자는 "UsernameText CollabUsernameText"라 닫는 따옴표 즉시 매칭으로
	// 배제된다(매치 순서 의존 제거).
	private static final Pattern USERNAME =
			Pattern.compile("class=\"UsernameText\"[^>]*>([^<]+)<");
	// 캡션: <div class="Caption">…(중첩 div는 CaptionComments뿐 — 그 앞까지가 캡션 본문)
	private static final Pattern CAPTION =
			Pattern.compile("class=\"Caption\">(.*?)(?:<div |</div>)", Pattern.DOTALL);
	// S7 — 캡션 본문 맨 앞의 <a class="CaptionUsername">…</a> 앵커(작성자 username, 내용 포함) —
	// 태그 스트립 전에 통째로 제거해야 한다. 문자열 매치(예: 본문 첫 단어가 우연히 username과
	// 같은 경우)가 아니라 앵커 태그 자체를 기준으로 제거해 오삭제를 막는다.
	private static final Pattern CAPTION_USERNAME_ANCHOR =
			Pattern.compile("<a class=\"CaptionUsername\"[^>]*>.*?</a>", Pattern.DOTALL);
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
			// S1 — 3xx는 삭제·비공개 게시물뿐 아니라 IP 소프트블록/게이트 응답에서도 온다. 부재
			// 확정은 Hiker 404만이므로 NOT_FOUND로 단정하지 않고 OTHER로 강등해 재확인을 거치게 한다.
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"embed 리다이렉트(" + status + ") — 게이트/소프트블록 의심(부재 미확정): " + shortCode);
		}
		String body = res.body() == null ? "" : res.body();
		if (status != 200) {
			throw new SelfCrawlException(SelfErrorClassifier.ofStatus(status, body),
					"embed 실패 status=" + status + " code=" + shortCode);
		}

		String username = first(USERNAME, body);
		Long likes = number(first(LIKES, body));
		if (username == null && likes == null) {
			// S1 — 200인데 소유자·좋아요 둘 다 없는 빈 셸. 삭제·비공개 게시물의 응답 셰이프이기도
			// 하지만 게이트 응답도 같은 모양이라(운영 오탐 실측) NOT_FOUND로 단정하지 않고 OTHER로
			// 강등한다.
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"embed 200 빈 셸(부재 미확정): " + shortCode);
		}
		if (likes == null) {
			// 소유자는 있는데 좋아요 카운트만 안 잡힘 — 진짜 숨김인지 파싱 실패인지 구분할 신호가
			// 없다(클래스 주석 참조). 숨김 단정 금지 — likesHidden을 null(미확정)로 남겨 저장
			// 계층이 보호하도록 한다(S9 — 과거엔 false 하드코딩이라 Hiker의 확정 false와 안 구분됐다).
			log.warn("embed 좋아요 카운트 파싱 실패(숨김 여부 미확정) shortCode={}", shortCode);
		}
		Long comments = number(first(COMMENTS, body));
		Long views = number(first(VIEWS, body));
		String caption = caption(body);
		String productType = first(PRODUCT_TYPE, body);
		boolean reels = views != null || "clips".equals(productType);
		String contentType = reels ? "REELS" : "FEED";
		// likes가 잡혔으면 렌더된 숫자를 실제로 봤으니 확정 비숨김(false), 안 잡혔으면 미확정(null).
		Boolean likesHidden = likes == null ? null : false;

		return new PostInfo(shortCode, username, null, null, null, contentType, caption,
				null, null, likes, comments, views, null, null, null, null, null, null, null,
				views != null, likesHidden, null);
	}

	private static String first(Pattern p, String body) {
		Matcher m = p.matcher(body);
		return m.find() ? m.group(1) : null;
	}

	private static Long number(String s) {
		return s == null ? null : Long.valueOf(s.replace(",", ""));
	}

	/**
	 * Caption div 본문 → 태그 제거 + 엔티티 디코드. 원 HTML은
	 * {@code <a class="CaptionUsername">작성자</a><br/><br/>본문} 셰이프라, 그 앵커를 내용째
	 * 먼저 제거하지 않으면(S7) 작성자 username이 캡션 본문 앞에 섞여 들어간다.
	 */
	private static String caption(String body) {
		String raw = first(CAPTION, body);
		if (raw == null) {
			return null;
		}
		String withoutUsername = CAPTION_USERNAME_ANCHOR.matcher(raw).replaceFirst("");
		String text = withoutUsername.replaceAll("<br ?/?>", "\n").replaceAll("<[^>]*>", "");
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
