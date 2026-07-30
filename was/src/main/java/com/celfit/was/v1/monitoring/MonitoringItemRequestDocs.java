package com.celfit.was.v1.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * POST /v1/monitoring/items(등록, 6.27)·PATCH /v1/monitoring/items/{itemId}(행 수정, 6.29)의
 * Swagger 문서 전용 스키마 — <b>런타임 역직렬화에는 쓰지 않는다.</b> 실제 컨트롤러 파라미터 타입은
 * 여전히 {@code Map<String,Object>}이며(V1MonitoringItemsController 참조, PATCH의 "키 부재 vs
 * 명시적 null" 구분을 위해 유지), 이 레코드들은 {@code @RequestBody(content=@Content(schema=
 * @Schema(implementation=...)))}로 springdoc이 뽑는 필드 스키마만 대체한다.
 *
 * <p>필드 정의의 정본은 {@link V1MonitoringRegistrationService}(등록)와
 * {@link V1MonitoringItemUpdateService}(행 수정)의 파싱·검증 분기다.
 */
public final class MonitoringItemRequestDocs {

	private MonitoringItemRequestDocs() {
	}

	@Schema(name = "MonitoringRegisterRequest", description = "POST /v1/monitoring/items 요청 본문(계약 6.27). "
			+ "posts·accounts 중 최소 1개 항목 필요, 합산 최대 100개(초과 시 400).")
	public record Register(
			@Schema(description = "게시물 링크 목록. `https://www.instagram.com/{p|reel|reels}/{shortcode}/` 형태로 "
					+ "프론트가 정규화해 보낸다. 공유 단축 링크(instagram.com/share/...)는 원문 그대로 허용(서버가 리다이렉트 해소).")
			List<String> posts,
			@Schema(description = "계정 핸들 목록. 형식 `^[a-z0-9._]{1,30}$`(대문자 입력도 서버가 소문자로 정규화).")
			List<String> accounts,
			@Schema(description = "감지 조건. accounts가 비어있지 않을 때만 유효성 검사 대상(and·or 합쳐 최소 1개). "
					+ "게시물 전용 등록은 빈 규칙도 통과한다.")
			Keywords keywords,
			@Schema(description = "모니터링 기간(일). 1 이상 90 이하 정수(상한 3개월).",
					requiredMode = Schema.RequiredMode.REQUIRED)
			Integer trackingDays,
			@Schema(description = "캠페인 이름 — 동명 캠페인이 있으면 연결, 없으면 이름만 가진 캠페인을 자동 생성해 연결. "
					+ "campaignId와 동시 전달 불가.", nullable = true)
			String campaignName,
			@Schema(description = "캠페인 ID 직접 지정. campaignName과 동시 전달 불가.", nullable = true)
			String campaignId) {
	}

	@Schema(name = "MonitoringItemKeywords", description = "감지 조건(계약 6.25·6.27): "
			+ "(and 전부 포함) AND (or 중 1개 이상 포함) AND NOT (exclude 중 1개라도 포함). "
			+ "대소문자 무시·부분일치, or가 비어 있으면 or 조건은 생략.")
	public record Keywords(
			@Schema(description = "전부 포함해야 하는 키워드. 최대 5개.")
			List<String> and,
			@Schema(description = "1개 이상 포함해야 하는 키워드. 최대 5개. and·or 합쳐 최소 1개 필요(accounts 등록 시).")
			List<String> or,
			@Schema(description = "포함하면 안 되는 키워드. 최대 5개.")
			List<String> exclude) {
	}

	@Schema(name = "MonitoringItemPatchRequest", description = "PATCH /v1/monitoring/items/{itemId} 요청 본문"
			+ "(계약 6.29). 세 필드 모두 선택. 편집 대상은 기간과 캠페인뿐이다. campaignName·campaignId는 동시 전달"
			+ " 불가, 키가 있고 값이 null이면 캠페인 연결 해제(모든 상태에서 허용). trackingDays는 진행 중 상태"
			+ "(collecting/detecting/tracking/error)에서만 허용하고 null로 해제할 수 없다"
			+ "(V1MonitoringItemUpdateService.patch 참조).")
	public record Patch(
			@Schema(description = "변경할 모니터링 기간(일). 1 이상 90 이하이고, 변경 후 종료일"
					+ "(registeredAt+trackingDays)이 오늘(KST) 이후여야 한다. 진행 중 상태에서만 허용.", nullable = true)
			Integer trackingDays,
			@Schema(description = "캠페인 이름으로 변경(없으면 자동 생성). 키가 있고 null이면 연결 해제. "
					+ "campaignId와 동시 전달 불가.", nullable = true)
			String campaignName,
			@Schema(description = "캠페인 ID로 변경. 키가 있고 null이면 연결 해제. campaignName과 동시 전달 불가.",
					nullable = true)
			String campaignId) {
	}
}
