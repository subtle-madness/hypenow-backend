package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 계정 표면(스펙 §5) — 등록(202)·목록·단건 폴링·타입 변경(200, 08-12)·삭제(204). 인증 필수(SecurityConfig
 * anyRequest().authenticated()). 레거시 /v1/monitoring/**와 완전히 분리된 신규 경로다.
 *
 * <p>monitoring 서브시스템이 꺼진 환경에서는 표면 자체가 없다(빈 미등록 → 404) —
 * 서비스가 monitoring 전용 빈(명령 클라이언트·브랜드 조회)에 전면 의존하기 때문이다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring/accounts")
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandAccountsController {

	private final V1BrandAccountService service;

	public V1BrandAccountsController(V1BrandAccountService service) {
		this.service = service;
	}

	@GetMapping
	public ApiResponse<List<BrandAccountResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		List<BrandAccountResponse> accounts = service.list(principal.getUserId());
		long own = accounts.stream().filter(a -> BrandAccountType.OWN.equals(a.accountType())).count();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", accounts.size());
		// limit은 호환용으로 남긴 합산 최대다(타입별로 갈려 단일 값이 의미를 잃었다) — 실제 게이트는
		// limits·counts고, 강제 지점은 BrandLinkTransaction이다.
		meta.put("limit", BrandAccountType.ownLimit() + BrandAccountType.competitorLimit());
		meta.put("limits", Map.of(BrandAccountType.OWN, BrandAccountType.ownLimit(),
				BrandAccountType.COMPETITOR, BrandAccountType.competitorLimit()));
		meta.put("counts", Map.of(BrandAccountType.OWN, own,
				BrandAccountType.COMPETITOR, accounts.size() - own));
		return ApiResponse.ok(accounts, meta);
	}

	@GetMapping("/{accountId}")
	public ApiResponse<BrandAccountResponse> get(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId) {
		return ApiResponse.ok(service.get(principal.getUserId(), parseAccountId(accountId)));
	}

	/**
	 * 연결은 202다 — monitoring 프로필 검증만 동기로 끝나고 수집(백필)은 뒤에서 돈다(폴링으로 확인).
	 * 이미 수집된 브랜드면 재수집 없이 연결만 하고 현재 상태(ready 등)를 그대로 돌려주며,
	 * 이미 연결된 브랜드 재요청도 같은 202(멱등 — 기존 객체)다.
	 */
	@PostMapping
	public ResponseEntity<ApiResponse<BrandAccountResponse>> register(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) BrandAccountRegisterRequest body) {
		String username = body == null ? null : body.username();
		String accountType = body == null ? null : body.accountType();
		BrandAccountResponse response = service.register(principal.getUserId(), username, accountType);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
	}

	/** 타입 변경(§2-3, 08-12) — 재수집 없이 구독 속성만 바꾼다. 200 + 갱신된 계정 객체. */
	@PatchMapping("/{accountId}")
	public ApiResponse<BrandAccountResponse> changeType(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) BrandAccountTypeRequest body) {
		String accountType = body == null ? null : body.accountType();
		return ApiResponse.ok(service.changeType(principal.getUserId(), parseAccountId(accountId), accountType));
	}

	@DeleteMapping("/{accountId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId) {
		service.delete(principal.getUserId(), parseAccountId(accountId));
		return ResponseEntity.noContent().build();
	}

	/** accountId는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(V1CampaignController 관용구). */
	private static long parseAccountId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("브랜드 계정을 찾을 수 없습니다.");
		}
	}

	/** 등록 요청 본문 — 계정명(정규화·검증은 BrandUsername)과 타입(생략 시 own). */
	public record BrandAccountRegisterRequest(String username, String accountType) {
	}

	/** 타입 변경 요청 본문 — own|competitor. */
	public record BrandAccountTypeRequest(String accountType) {
	}
}
