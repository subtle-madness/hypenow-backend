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
import org.springframework.web.bind.annotation.PutMapping;
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

	/**
	 * 목록 + meta. {@code total}은 <b>반환 목록</b>의 크기지만 {@code counts}는 <b>연결 행</b>에서
	 * 온다(08-12 리뷰) — 한도의 모수가 연결이라 brand_account 부재로 목록에서 빠진 건도 자리를
	 * 차지한다. 둘이 어긋날 수 있는 건 의도된 것이다(각자 다른 질문에 답한다).
	 *
	 * <p>{@code meta}·{@code limits}·{@code counts}는 전부 {@link LinkedHashMap}이다 —
	 * {@code Map.of}는 JVM 실행마다 순회 순서가 달라져 응답 키 순서가 흔들린다(08-12 리뷰).
	 */
	@GetMapping
	public ApiResponse<List<BrandAccountResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		V1BrandAccountService.Listing listing = service.list(principal.getUserId());
		Map<String, Object> limits = new LinkedHashMap<>();
		limits.put(BrandAccountType.OWN, BrandAccountType.ownLimit());
		limits.put(BrandAccountType.COMPETITOR, BrandAccountType.competitorLimit());

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", listing.accounts().size());
		// limit은 호환용으로 남긴 합산 최대다(타입별로 갈려 단일 값이 의미를 잃었다) — 실제 게이트는
		// limits·counts고, 강제 지점은 BrandLinkTransaction이다.
		meta.put("limit", BrandAccountType.ownLimit() + BrandAccountType.competitorLimit());
		meta.put("limits", limits);
		meta.put("counts", listing.counts());
		return ApiResponse.ok(listing.accounts(), meta);
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
		Integer collectionMonths = body == null ? null : body.collectionMonths();
		BrandAccountResponse response = service.register(principal.getUserId(), username, accountType,
				collectionMonths);
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

	/** 해시태그 태그 셋 조회(태그 관리 API, 2026-08-12, monitoring BrandController 프록시) — 소유 브랜드만. */
	@GetMapping("/{accountId}/hashtag-tags")
	public ApiResponse<BrandHashtagTagsResponse> getHashtagTags(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		List<String> tags = service.getHashtagTags(principal.getUserId(), parseAccountId(accountId));
		return ApiResponse.ok(new BrandHashtagTagsResponse(tags));
	}

	/** 해시태그 태그 셋 전체 교체 — 204(monitoring PUT 계약과 동형). 빈 목록도 허용(2026-08-12부터). */
	@PutMapping("/{accountId}/hashtag-tags")
	public ResponseEntity<Void> putHashtagTags(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) HashtagTagsRequest body) {
		List<String> tags = body == null ? null : body.tags();
		service.putHashtagTags(principal.getUserId(), parseAccountId(accountId), tags);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 해시태그 태그 셋 단건·다건 추가(POST 계약, 2026-08-12) — 204(monitoring POST와 동형).
	 * 빈 입력·무효 문자는 monitoring이 422로 거부 → 공용 매핑이 400 VALIDATION_FAILED로 내린다.
	 */
	@PostMapping("/{accountId}/hashtag-tags")
	public ResponseEntity<Void> addHashtagTags(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) HashtagTagsRequest body) {
		List<String> tags = body == null ? null : body.tags();
		service.addHashtagTags(principal.getUserId(), parseAccountId(accountId), tags);
		return ResponseEntity.noContent().build();
	}

	/** 해시태그 태그 단건 삭제(DELETE {tag} 계약, 2026-08-12) — 204(없어도 멱등). */
	@DeleteMapping("/{accountId}/hashtag-tags/{tag}")
	public ResponseEntity<Void> deleteHashtagTag(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @PathVariable String tag) {
		service.deleteHashtagTag(principal.getUserId(), parseAccountId(accountId), tag);
		return ResponseEntity.noContent().build();
	}

	/** 해시태그 태그 전체 삭제(DELETE 계약, 2026-08-12) — 204. 브랜드 태그 감지를 완전히 끈다. */
	@DeleteMapping("/{accountId}/hashtag-tags")
	public ResponseEntity<Void> deleteAllHashtagTags(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId) {
		service.deleteAllHashtagTags(principal.getUserId(), parseAccountId(accountId));
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 조회(스펙 §6, monitoring BrandController 프록시) — 소유 브랜드만. */
	@GetMapping("/{accountId}/seeded-accounts")
	public ApiResponse<SeededAccountsResponse> getSeededAccounts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		List<String> usernames = service.getSeededAccounts(principal.getUserId(), parseAccountId(accountId));
		return ApiResponse.ok(new SeededAccountsResponse(usernames));
	}

	/** 시딩 계정 전체 교체 — 204(monitoring PUT 계약과 동형). */
	@PutMapping("/{accountId}/seeded-accounts")
	public ResponseEntity<Void> putSeededAccounts(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) SeededAccountsRequest body) {
		List<String> usernames = body == null ? null : body.usernames();
		service.putSeededAccounts(principal.getUserId(), parseAccountId(accountId), usernames);
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 단건·다건 추가(POST 계약) — 204(monitoring POST와 동형, 빈 입력도 no-op으로 허용). */
	@PostMapping("/{accountId}/seeded-accounts")
	public ResponseEntity<Void> addSeededAccounts(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) SeededAccountsRequest body) {
		List<String> usernames = body == null ? null : body.usernames();
		service.addSeededAccounts(principal.getUserId(), parseAccountId(accountId), usernames);
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 단건 삭제(DELETE {seededUsername} 계약) — 204(없어도 멱등). */
	@DeleteMapping("/{accountId}/seeded-accounts/{seededUsername}")
	public ResponseEntity<Void> deleteSeededAccount(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @PathVariable String seededUsername) {
		service.deleteSeededAccount(principal.getUserId(), parseAccountId(accountId), seededUsername);
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 전체 삭제(DELETE 계약) — 204. */
	@DeleteMapping("/{accountId}/seeded-accounts")
	public ResponseEntity<Void> deleteAllSeededAccounts(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId) {
		service.deleteAllSeededAccounts(principal.getUserId(), parseAccountId(accountId));
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

	/** 등록 요청 본문 — 계정명·타입(생략 시 own)·수집 창(생략 시 12, 값 공간은 BrandCollectionMonths). */
	public record BrandAccountRegisterRequest(String username, String accountType, Integer collectionMonths) {
	}

	/** 타입 변경 요청 본문 — own|competitor. */
	public record BrandAccountTypeRequest(String accountType) {
	}

	/** 태그 셋 교체 요청 본문 — tags null은 서비스에서 빈 목록으로 접는다(monitoring이 422로 거부). */
	public record HashtagTagsRequest(List<String> tags) {
	}

	/** 시딩 계정 교체 요청 본문 — usernames null은 서비스에서 빈 목록으로 접는다. */
	public record SeededAccountsRequest(List<String> usernames) {
	}
}
