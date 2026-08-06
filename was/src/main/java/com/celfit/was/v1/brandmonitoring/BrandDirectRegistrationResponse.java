package com.celfit.was.v1.brandmonitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 직접 등록 접수·상태 응답(스펙 §6-4) — POST /accounts/{id}/direct-posts(202)와
 * GET /direct-registrations/{registrationId}가 같은 셰이프를 쓴다.
 *
 * <p>{@code registrationId}는 레거시 {@code app.monitoring_registrations.id}를 그대로 노출한 값이다
 * (별도 상태 저장소를 신설하지 않는다 — §6-4). 입력이 전부 "이미 브랜드 목록에 있는 게시물"이라
 * 레거시에 위임할 것이 하나도 없으면 등록 행 자체가 생기지 않아 null이다(그때는 폴링할 것도 없다).
 *
 * <p>nullable 필드는 계약 무결성 규칙 #1(1.8)에 따라 키를 생략하지 않고 명시적 null로 직렬화한다.
 */
public record BrandDirectRegistrationResponse(String registrationId, String requestedAt, List<Entry> entries) {

	/**
	 * 입력 1건의 처리 결과 — 요청의 postUrls 순서를 그대로 보존한다(부분 성공 계약).
	 *
	 * <p>{@code result} 어휘는 레거시 entry 어휘의 부분집합 4종이다. 레거시에만 있는 {@code canceled}는
	 * FE 유니언에 없어 {@code failed}로 접고 사유(reasonCode=canceled)로 구분한다.
	 *
	 * <p>{@code brandPostId}는 게시물 shortcode(브랜드 게시물 API의 postId와 같은 키)다 — 링크를
	 * 파싱하지 못했거나(share 단축 링크·형식 오류) 아직 해소 전이면 null. {@code monitoringItemId}는
	 * 매핑이 가리키는 레거시 추적 행 id로, 아직 행이 만들어지지 않았으면 null이다.
	 */
	public record Entry(
			String input,
			@Schema(allowableValues = {"pending", "success", "failed", "duplicate"}) String result,
			String reasonCode,
			String reason,
			String brandPostId,
			String monitoringItemId) {
	}
}
