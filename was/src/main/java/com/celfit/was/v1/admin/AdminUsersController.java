package com.celfit.was.v1.admin;

import com.celfit.was.v1.account.SignupCodeRepository;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.FeatureOverridesCodec;
import com.celfit.was.v1.common.V1ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 유저 조회(설계 2026-08-01 §4) — GET /v1/admin/users(목록)·GET /v1/admin/users/{id}(상세).
 * 인가는 SecurityConfig(hasRole ADMIN)가 처리, 이 컨트롤러는 조회 로직만 담당한다.
 */
@RestController
public class AdminUsersController {

	private final AdminUserRepository userRepository;
	private final AdminUserAssembler assembler;
	private final AdminUserEventAssembler eventAssembler;
	private final SignupCodeRepository signupCodeRepository;
	private final FeatureOverridesCodec featureOverridesCodec;

	public AdminUsersController(AdminUserRepository userRepository, AdminUserAssembler assembler,
			AdminUserEventAssembler eventAssembler, SignupCodeRepository signupCodeRepository,
			FeatureOverridesCodec featureOverridesCodec) {
		this.userRepository = userRepository;
		this.assembler = assembler;
		this.eventAssembler = eventAssembler;
		this.signupCodeRepository = signupCodeRepository;
		this.featureOverridesCodec = featureOverridesCodec;
	}

	/**
	 * 목록(§4) — query는 email·name 부분일치. sort 파라미터는 받되 의도적으로 무시한다(계약:
	 * 미지원 값은 무시하고 항상 created_at DESC — 실제로 지원값 자체가 없다).
	 */
	@GetMapping("/v1/admin/users")
	public ApiResponse<List<AdminUserSummary>> list(@RequestParam(required = false) String query,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer limit) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, limit);
		AdminUserRepository.Page result = userRepository.findPage(query, pageRequest.limit(), pageRequest.offset());
		List<AdminUserSummary> summaries = assembler.assemble(result.rows());

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", result.total());
		meta.put("limit", pageRequest.limit());
		meta.put("offset", pageRequest.offset());
		return ApiResponse.ok(summaries, meta);
	}

	/** 상세(§4) — 부재 유저는 404 NOT_FOUND. */
	@GetMapping("/v1/admin/users/{id}")
	public ApiResponse<AdminUserDetail> detail(@PathVariable String id) {
		long userId = parseId(id);
		AdminUserRow row = userRepository.findById(userId)
				.orElseThrow(() -> V1ApiException.notFound("유저를 찾을 수 없습니다."));

		AdminUserSummary summary = assembler.assemble(List.of(row)).get(0);
		String signupCode = signupCodeRepository.findCodeByUsedBy(userId).orElse(null);
		List<AdminUserEvent> events = eventAssembler.assemble(row, signupCode);

		AdminUserDetail detail = AdminUserDetail.from(summary, blankToEmpty(row.jobTitle()), signupCode, events,
				featureOverridesCodec.read(row.featureOverrides()));
		return ApiResponse.ok(detail);
	}

	private static String blankToEmpty(String value) {
		return value == null ? "" : value;
	}

	/** id는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(V1MonitoringItemsController와 동일 관용구). */
	private static long parseId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("유저를 찾을 수 없습니다.");
		}
	}
}
