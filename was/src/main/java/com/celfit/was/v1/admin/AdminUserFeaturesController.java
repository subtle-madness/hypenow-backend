package com.celfit.was.v1.admin;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.FeatureOverridesCodec;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * PUT /v1/admin/users/{id}/features(2026-08-31 유저별 기능 플래그) — 대상 유저의 기능 플래그를
 * <b>전체 교체</b>한다(PATCH 병합 아님). 인가는 SecurityConfig(/v1/admin/** hasRole ADMIN)와
 * AdminRoleFreshnessFilter(DB role 재확인)가 처리하므로 여기엔 별도 가드가 없다 —
 * AdminUsersController와 같은 자리다.
 *
 * <p>키 문자열은 검증하지 않는다(기능 목록·기본값의 정본은 프론트). 값 타입만 boolean | string[]로
 * 좁히고, 그 외는 400 VALIDATION_FAILED — 검증·직렬화는 {@link FeatureOverridesCodec}이 소유해
 * 조회 표면(/v1/me, GET /v1/admin/users/{id})과 규칙을 공유한다.
 */
@RestController
public class AdminUserFeaturesController {

	/** 본문 상한 8KB — 기능 키 수십 개 규모를 한참 넘는 값이면 오용이라 계약으로 자른다. */
	private static final int MAX_BODY_BYTES = 8 * 1024;

	private final AdminUserFeatureService featureService;
	private final FeatureOverridesCodec featureOverridesCodec;

	public AdminUserFeaturesController(AdminUserFeatureService featureService,
			FeatureOverridesCodec featureOverridesCodec) {
		this.featureService = featureService;
		this.featureOverridesCodec = featureOverridesCodec;
	}

	@PutMapping("/v1/admin/users/{id}/features")
	public ApiResponse<AdminUserFeaturesResponse> replace(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest httpRequest) {
		long targetUserId = parseId(id);
		requireWithinBodyLimit(httpRequest, body);

		if (body == null || !body.isObject() || !body.has("overrides")) {
			throw V1ApiException.validation("overrides가 필요해요.");
		}
		String overridesJson = featureOverridesCodec.validateAndSerialize(body.get("overrides"));

		String stored = featureService
				.replaceOverrides(principal.getUserId(), targetUserId, overridesJson, httpRequest.getRequestURI())
				.orElseThrow(() -> V1ApiException.notFound("유저를 찾을 수 없습니다."));
		return ApiResponse.ok(new AdminUserFeaturesResponse(featureOverridesCodec.read(stored)));
	}

	/**
	 * 상한 검사(계약 가드이지 DoS 방어가 아니다 — 본문은 이 메서드에 닿기 전에 이미 읽혀 파싱된다.
	 * 이 표면은 ADMIN 전용이라 그 정도로 충분하다). Content-Length가 정본이고, chunked 전송처럼
	 * 길이를 모르는 경우를 위해 파싱된 본문의 직렬화 크기로 한 번 더 본다.
	 */
	private static void requireWithinBodyLimit(HttpServletRequest httpRequest, JsonNode body) {
		long declared = httpRequest.getContentLengthLong();
		if (declared > MAX_BODY_BYTES) {
			throw tooLarge();
		}
		if (body != null && body.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
			throw tooLarge();
		}
	}

	private static V1ApiException tooLarge() {
		return V1ApiException.validation("요청 본문이 너무 커요(최대 8KB).");
	}

	/** id는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(AdminUsersController와 동일 관용구). */
	private static long parseId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("유저를 찾을 수 없습니다.");
		}
	}
}
