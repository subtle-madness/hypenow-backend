package com.celfit.was.v2.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 콘텐츠 관계 표면(스펙 §8) — 인증 필수. 기존 캠페인 CRUD
 * ({@code /v1/monitoring/campaigns}, {@code V1CampaignController})는 그대로 두고 <b>관계 조작만</b>
 * v2로 낸다: 콘텐츠 식별자가 캠페인 id가 아니라 {@code canonicalPostId}(shortcode)라 v1 어휘와
 * 다르기 때문이다(§7-1 성과 대시보드와 같은 식별자).
 *
 * <p>판정·위임은 전부 {@link V2CampaignContentService}가 한다 — 이 컨트롤러는 HTTP 표면
 * (path 파싱·상태 코드)만 담당한다.
 */
@RestController
@RequestMapping("/v2/monitoring/campaigns")
public class V2CampaignContentsController {

	/** body 없는 POST(RequestBody required=false → null) 정규화 상수 — 검증은 서비스가 한다. */
	private static final AddContentsRequest EMPTY_REQUEST = new AddContentsRequest(null, null);

	private final V2CampaignContentService service;

	public V2CampaignContentsController(V2CampaignContentService service) {
		this.service = service;
	}

	/**
	 * 콘텐츠 추가 — <b>동기 완결이면 200, 레거시 등록으로 아이템이 새로 생겼으면 202</b>다(§8).
	 * 202는 "첫 수집이 아직 안 끝났다"는 신호이고, 그 항목의 result는 {@code pending}이다.
	 */
	@PostMapping("/{campaignId}/contents")
	public ResponseEntity<ApiResponse<V2CampaignContentsResponse>> addContents(
			@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String campaignId,
			@RequestBody(required = false) AddContentsRequest body) {
		AddContentsRequest request = body == null ? EMPTY_REQUEST : body;
		V2CampaignContentService.Added added = service.add(principal.getUserId(), parseCampaignId(campaignId),
				request.contentIds(), request.trackingDays());
		return ResponseEntity.status(added.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
				.body(ApiResponse.ok(added.body()));
	}

	/** 콘텐츠 제거 — 캠페인 연결만 끊는다(모니터링 지속, §7-2). 멱등 아님(404 있음). */
	@DeleteMapping("/{campaignId}/contents/{contentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeContent(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String campaignId, @PathVariable String contentId) {
		service.remove(principal.getUserId(), parseCampaignId(campaignId), contentId);
	}

	/**
	 * 추가 요청 본문 — {@code contentIds}는 canonicalPostId(shortcode) 목록, {@code trackingDays}는
	 * 새로 만들어질 아이템의 모니터링 기간이다(§8).
	 */
	public record AddContentsRequest(List<String> contentIds, Integer trackingDays) {
	}

	/** campaignId는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(v1 캠페인 관용구). */
	private static long parseCampaignId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("캠페인을 찾을 수 없습니다.");
		}
	}
}
