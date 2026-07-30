package com.celfit.was.v1.monitoring;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 등록 입력(6.27) 정규화·판정 — 순수 정적 유틸(부작용 없음). 게시물 링크는
 * shortcode 추출·canonical URL 계산까지 하고, 계정 핸들은 형식 검증 후 소문자로
 * 정규화한다. 공유 단축 링크(`instagram.com/share/...`)는 해소 없이 원문만 담아
 * {@link ShareLink}로 넘긴다 — 리다이렉트 해소는 백그라운드 실행기(후속 태스크) 몫이다.
 */
public sealed interface MonitoringInput {

	String REASON_INVALID_FORMAT = "invalid_format";

	/** 게시물 링크 파싱 성공 — shortCode는 중복·저장 비교 키, canonicalUrl은 저장·표시용. */
	record Post(String shortCode, String canonicalUrl) implements MonitoringInput {
	}

	/** 공유 단축 링크(원본 URL 그대로) — 해소는 백그라운드 실행기 몫. */
	record ShareLink(String originalUrl) implements MonitoringInput {
	}

	/** 계정 핸들 파싱 성공 — 이미 소문자로 정규화됨. */
	record Account(String handle) implements MonitoringInput {
	}

	/** 파싱 실패 — reasonCode/reason은 6.28 처리 내역 어휘를 그대로 쓴다. */
	record Invalid(String input, String reasonCode, String reason) implements MonitoringInput {
	}

	// 프로필 경유 주소(instagram.com/{username}/{p|reel|reels}/{code})도 허용하므로 도메인 다음
	// 세그먼트를 선택적으로 건너뛴다. 스킴·www는 선택(프론트가 이미 정규화해서 보내지만 방어적으로 허용).
	Pattern POST_PATTERN = Pattern.compile(
			"^(?:https?://)?(?:www\\.)?instagram\\.com/(?:[^/?#]+/)?(p|reel|reels)/([A-Za-z0-9_-]+)/?(?:[?#].*)?$",
			Pattern.CASE_INSENSITIVE);

	Pattern SHARE_PATTERN = Pattern.compile(
			"^(?:https?://)?(?:www\\.)?instagram\\.com/share/.+$", Pattern.CASE_INSENSITIVE);

	Pattern HANDLE_PATTERN = Pattern.compile("^[a-z0-9._]{1,30}$");

	/** 게시물 링크 파싱 — p/reel/reels 접어 shortcode 기준으로 canonical화, share 링크는 미해소 통과. */
	static MonitoringInput parsePost(String url) {
		if (url == null || url.isBlank()) {
			return new Invalid(url, REASON_INVALID_FORMAT, "링크 형식이 올바르지 않아요.");
		}
		String trimmed = url.trim();
		if (SHARE_PATTERN.matcher(trimmed).matches()) {
			return new ShareLink(trimmed);
		}
		Matcher matcher = POST_PATTERN.matcher(trimmed);
		if (!matcher.matches()) {
			return new Invalid(url, REASON_INVALID_FORMAT, "링크 형식이 올바르지 않아요.");
		}
		String type = matcher.group(1).toLowerCase(Locale.ROOT);
		String shortCode = matcher.group(2);
		// reels → reel로 접어 canonical 표기 통일(등록 원문 표기는 프론트가 별도 보존, 여기는 저장·비교용).
		String canonicalType = "reels".equals(type) ? "reel" : type;
		String canonicalUrl = "https://www.instagram.com/" + canonicalType + "/" + shortCode + "/";
		return new Post(shortCode, canonicalUrl);
	}

	/** 계정 핸들 파싱 — 소문자 정규화 후 형식 검증(대문자 입력도 정규화해 통과시킨다). */
	static MonitoringInput parseAccount(String raw) {
		if (raw == null || raw.isBlank()) {
			return new Invalid(raw, REASON_INVALID_FORMAT, "핸들 형식이 올바르지 않아요.");
		}
		String lower = raw.trim().toLowerCase(Locale.ROOT);
		if (!HANDLE_PATTERN.matcher(lower).matches()) {
			return new Invalid(raw, REASON_INVALID_FORMAT, "핸들 형식이 올바르지 않아요.");
		}
		return new Account(lower);
	}
}
