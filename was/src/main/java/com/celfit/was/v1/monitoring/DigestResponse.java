package com.celfit.was.v1.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 알림 다이제스트 응답(스펙 6.32, 2026-08-27 주간 개편 §3·§5). 순수 DTO - jsonb 파싱·조립은
 * {@link DigestAssembler} 몫이다(리포 관용구: DTO record는 순수, jsonb 파싱은 @Component
 * Assembler가 소유).
 *
 * <p>주간 개편으로 <b>알림 단위가 하루에서 한 주로</b> 바뀌었다. {@code date}는 이제 주 시작일
 * (월요일)이고 목록·읽음 구조는 그대로다(설계 §5 "계약 유지"). 개편 전에 쌓인 일일 행도 그대로
 * 조회되므로 items[] 원소는 {@code metrics} 없이도 파싱돼야 한다.
 *
 * <p>readAt은 계약 무결성 규칙 #1(1.8)이 짚은 그 사례다 - 키를 생략하면 프론트가 전 알림을
 * 읽음으로 오판해 안읽음 배지가 영구히 0이 된다. record 기본 동작(NON_NULL 미적용)으로 키를
 * 항상 유지한다.
 */
public record DigestResponse(String id, String date, String createdAt, String readAt, List<Item> items) {

	public record Item(
			// 섹션 구분(설계 §3). 개편 전 일일 행은 전부 "content"였다 - 그 값도 계속 유효하다.
			@Schema(allowableValues = {"action_needed", "brand", "content", "highlight"})
			String category,
			// 항목 종류. 정본은 WeeklyDigestAssembler - 컴파일 상수 참조가 불가해 문자열로 표기한다.
			@Schema(allowableValues = {"ad_not_disclosed", "content_issue", "metrics_private",
					"brand_new_posts", "collection_started", "collection_ended", "top_post"})
			String type,
			String summary, int count,
			// 섹션 합산 지표. 지표가 붙지 않는 항목은 null이다(개편 전 일일 행도 전부 null).
			Metrics metrics) {

		/**
		 * 합산 지표. views는 <b>릴스만</b> 집계된다 - 피드 게시물의 조회수는 항상 NULL이라는 관측
		 * 규칙 때문에, 그 주에 릴스가 없으면 views 자체가 null이다(설계 §3).
		 */
		public record Metrics(Long views, Long likes, Long comments) {
		}
	}
}
