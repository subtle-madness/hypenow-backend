package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * FE 화면 필터(FE 변경요청서 2026-08-28 §5) - 프론트가 지정한 조회 범위를 서버가 강제하는 정본 표현.
 * "all"·null은 무필터로 정규화한다({@link #normalize}) - 요청 JSON({@link AiMessagesRequest.ScopeRequest})의
 * 느슨한 표현을 여기서 한 번에 정리해, 이후 필터 로직({@link BrandAiToolbox})은 null 체크만 하면 된다.
 *
 * <p>강제 지점은 {@link BrandAiToolbox}의 인덱스 필터다 - 모델이 뭐라 요청하든 scope 밖 데이터는 툴
 * 결과에 나타나지 않는다(설계 §요구). 이 레코드 자체는 순수 값 객체라 필터 로직을 갖지 않는다.
 */
public record AiScope(LocalDate dateFrom, LocalDate dateTo, String mediaType, String sponsorship,
		String source, Integer followerMin, Integer followerMax, String q) {

	private static final String ALL = "all";

	/** 필터 없음 - scope 자체가 생략된 요청(전체 조회)에 쓴다. */
	public static final AiScope EMPTY = new AiScope(null, null, null, null, null, null, null, null);

	/** 요청 DTO({@link AiMessagesRequest.ScopeRequest})를 정규화한 도메인 값으로 변환한다. 날짜 파싱
	 * 실패는 400(VALIDATION_FAILED) - 컨트롤러 검증 단계에서 여기까지 호출되면 곧장 클라이언트 오류로 던진다. */
	public static AiScope from(AiMessagesRequest.ScopeRequest req) {
		if (req == null) {
			return EMPTY;
		}
		return new AiScope(
				parseDate(req.dateFrom(), "dateFrom"),
				parseDate(req.dateTo(), "dateTo"),
				normalize(req.mediaType()),
				normalize(req.sponsorship()),
				normalize(req.source()),
				req.followerMin(),
				req.followerMax(),
				req.q() == null || req.q().isBlank() ? null : req.q().trim());
	}

	private static LocalDate parseDate(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.trim());
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation("scope." + field + "가 올바른 날짜 형식이 아니에요(YYYY-MM-DD).");
		}
	}

	/** "all"·빈 값은 무필터(null)로 접는다 - reels|feed|all|null 같은 4값 계약을 3값(구체값·null)으로 줄인다. */
	private static String normalize(String raw) {
		if (raw == null || raw.isBlank() || ALL.equalsIgnoreCase(raw)) {
			return null;
		}
		return raw.trim();
	}

	public boolean isEmpty() {
		return dateFrom == null && dateTo == null && mediaType == null && sponsorship == null
				&& source == null && followerMin == null && followerMax == null && q == null;
	}

	/**
	 * 시스템 프롬프트에 얹는 현재 화면 필터 1줄 요약(T3, 설계 §요구 "모델이 답변에 기준을 명시") -
	 * 필터가 없으면 빈 문자열(프롬프트에 아무것도 덧붙이지 않는다).
	 */
	public String summaryLine() {
		if (isEmpty()) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		if (dateFrom != null || dateTo != null) {
			parts.add("기간 " + (dateFrom == null ? "처음" : dateFrom) + "~" + (dateTo == null ? "지금" : dateTo));
		}
		if (mediaType != null) {
			parts.add("reels".equalsIgnoreCase(mediaType) ? "릴스만" : "feed".equalsIgnoreCase(mediaType) ? "피드만" : mediaType);
		}
		if (sponsorship != null) {
			parts.add(switch (sponsorship) {
				case "sponsored" -> "광고 표기만";
				case "organic" -> "오가닉만";
				case "unknown" -> "판정 미상만";
				default -> sponsorship;
			});
		}
		if (source != null) {
			parts.add(switch (source) {
				case "tagged" -> "태그 게시물만";
				case "direct" -> "직접 등록만";
				default -> source;
			});
		}
		if (followerMin != null || followerMax != null) {
			parts.add("팔로워 " + (followerMin == null ? "" : followerMin + "명") + "~"
					+ (followerMax == null ? "" : followerMax + "명"));
		}
		if (q != null) {
			parts.add("작성자 검색 \"" + q + "\"");
		}
		return "\n\n[현재 화면 필터] " + String.join(" · ", parts)
				+ "\n이 필터 범위 안에서만 조회·답변하고, 답변에도 이 기준을 명시하세요.\n";
	}
}
