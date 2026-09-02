package com.celfit.was.v1.brandmonitoring;

import java.util.List;

/**
 * 브랜드 태그 셋 응답(태그 관리 API, 2026-08-12 — <b>2026-08-31 태그별 실행 상태 확장</b>) — 활성 태그
 * 전체. GET 응답 셰이프. PUT 요청 바디는 컨트롤러의 {@code HashtagTagsRequest}(문자열 배열, 태그
 * 조작은 여전히 이름만 받는다 — status는 조회 전용 파생값).
 *
 * <p>이전엔 {@code tags}가 문자열 배열이었다(FE는 문자열/객체 배열 둘 다 받게 준비했다는 회신,
 * 필드명 {@code tags}는 유지) — 태그를 추가한 뒤 백그라운드 스윕이 끝났는지 알 길이 없어 FE가
 * /posts meta.total 폴링 + 3분 타임아웃으로 우회하던 문제(status만 있어도 폴링 종료 가능) 해소.
 */
public record BrandHashtagTagsResponse(List<TagStatus> tags) {

	/**
	 * 태그 1건의 실행 상태 — status 값 공간은 collecting|done|failed(monitoring
	 * BrandHashtagRunStateResolver가 계산, was는 그대로 통과). lastRunAt은 KST 오프셋 ISO 문자열
	 * (KstTimestamps 관용구, 계약 1.5), 실행 이력이 없으면 null. lastFoundCount는 직전 완료 실행이
	 * 신규 편입한 게시물 수(collecting이어도 직전 값을 그대로 보여준다) — 실행 이력이 없으면 null.
	 */
	public record TagStatus(String tag, String status, String lastRunAt, Integer lastFoundCount) {
	}
}
