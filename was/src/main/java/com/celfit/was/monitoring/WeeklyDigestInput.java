package com.celfit.was.monitoring;

import java.util.List;
import java.util.Map;

/**
 * 주간 다이제스트 1건을 조립하는 데 필요한 유저 1명분 입력 전부(설계 §3). 잡이 여러 산지에서
 * 모아 채우고, {@link WeeklyDigestAssembler}가 이것만 보고 항목을 만든다 - 조립기가 DB를
 * 모르게 갈라 두면 문안·합산·하이라이트 규칙을 컨테이너 없이 검증할 수 있다.
 *
 * @param eventCounts   프론트 어휘(collection_started 등) → 지난주 alarm_event 건수
 * @param brandNewPosts 지난주 새로 발견된 브랜드 게시물(태그 + 해시태그, direct 제외)
 * @param endedPosts    지난주 수집이 끝난 콘텐츠의 최신 지표
 * @param adNotDisclosedShortCodes 지난주 미표기 판정된 등록 게시물(이미 알린 것은 제외된 뒤)
 * @param campaignNames 모니터링 진행 섹션 문안에 붙일 캠페인 이름(이름순, 중복 없음)
 */
public record WeeklyDigestInput(
		Map<String, Long> eventCounts,
		List<WeeklyPostMetrics> brandNewPosts,
		List<WeeklyPostMetrics> endedPosts,
		List<String> adNotDisclosedShortCodes,
		List<String> campaignNames) {
}
