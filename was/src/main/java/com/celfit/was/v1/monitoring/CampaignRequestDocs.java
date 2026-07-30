package com.celfit.was.v1.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST·PATCH /v1/monitoring/campaigns의 Swagger 문서 전용 스키마(계약 6.31) — <b>런타임
 * 역직렬화에는 쓰지 않는다.</b> 실제 컨트롤러 파라미터 타입은 여전히
 * {@code Map<String,Object>}이며(V1CampaignController 클래스 문서 참조, PATCH의 "키 부재 vs
 * 명시적 null" 구분을 위해 유지), 이 레코드들은 {@code @RequestBody(content=@Content(schema=
 * @Schema(implementation=...)))}로 springdoc이 뽑는 필드 스키마만 대체한다.
 *
 * <p>필드 정의의 정본은 {@link V1CampaignService}의 파싱·검증 분기(parseBudget·parseSeedingCount 등)다.
 */
public final class CampaignRequestDocs {

	private CampaignRequestDocs() {
	}

	@Schema(name = "CampaignCreateRequest", description = "POST /v1/monitoring/campaigns 요청 본문(계약 6.31).")
	public record Create(
			@Schema(description = "캠페인 이름. 유저 내 유니크, 최대 40자(정규화·금지문자 규칙은 계약 6.25 참조).",
					requiredMode = Schema.RequiredMode.REQUIRED)
			String name,
			@Schema(description = "설명. 최대 200자.", nullable = true)
			String description,
			@Schema(description = "시작일 YYYY-MM-DD.", nullable = true)
			String startDate,
			@Schema(description = "종료일 YYYY-MM-DD. 시작일이 함께 있으면 시작일 이후여야 한다.", nullable = true)
			String endDate,
			@Schema(description = "브랜드명. 최대 30자.", nullable = true)
			String brand,
			@Schema(description = "예산(원 단위). 0 이상 정수만 허용 — 소수는 400.", nullable = true)
			Long budget,
			@Schema(description = "시딩 인원 수(이행률 모수 — 서버가 집계하지 않는 유저 선언값). 0 이상 정수만 허용.",
					nullable = true)
			Integer seedingCount) {
	}

	@Schema(name = "CampaignPatchRequest", description = "PATCH /v1/monitoring/campaigns/{campaignId} 요청 본문"
			+ "(계약 6.31). 전 필드 선택. 의미론: 키를 생략하면 기존 값 유지, 키가 있고 값이 null이면 해제한다"
			+ "(name만 예외 — 키가 있는데 null이면 400, 이름은 해제할 수 없다). 이 문서 스키마는 모든 필드를 nullable"
			+ "로 표기하지만 실제 처리는 Map의 containsKey로 '키 존재 여부'를 구분한다(V1CampaignService.patch 참조 —"
			+ " 이 구분을 record로는 표현할 수 없어 여기 설명으로만 남긴다).")
	public record Patch(
			@Schema(description = "캠페인 이름. 키가 있으면 null 불가(해제 불가 — 400).", nullable = true)
			String name,
			@Schema(description = "설명. 최대 200자. 키가 있고 null이면 해제.", nullable = true)
			String description,
			@Schema(description = "시작일 YYYY-MM-DD. 키가 있고 null이면 해제.", nullable = true)
			String startDate,
			@Schema(description = "종료일 YYYY-MM-DD. 키가 있고 null이면 해제.", nullable = true)
			String endDate,
			@Schema(description = "브랜드명. 최대 30자. 키가 있고 null이면 해제.", nullable = true)
			String brand,
			@Schema(description = "예산(원 단위). 0 이상 정수. 키가 있고 null이면 해제.", nullable = true)
			Long budget,
			@Schema(description = "시딩 인원 수. 0 이상 정수. 키가 있고 null이면 해제.", nullable = true)
			Integer seedingCount) {
	}
}
