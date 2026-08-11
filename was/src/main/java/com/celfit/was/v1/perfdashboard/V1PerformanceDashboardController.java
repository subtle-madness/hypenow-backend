package com.celfit.was.v1.perfdashboard;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifier;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.ItemStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 성과 대시보드 표면(스펙 §7-1) — 3계열(individual·direct·tagged) 통합 목록과 단건 조회. 인증 필수.
 *
 * <p>조립·중복 제거·정렬은 전부 {@link PerformanceContentAssembler}가 끝낸다 — 이 컨트롤러는 HTTP
 * 표면(쿼리 값 공간 검증·필터·meta)만 담당한다. 필터는 전부 메모리다(대상이 유저 1명의 레거시 아이템
 * + 브랜드 90일 윈도우라 페이지네이션 없이 전량을 조립해 전량 반환한다 — 08-10 250건 상한 철폐).
 *
 * <p>브랜드 표면(§5·§6)과 달리 {@code monitoring.enabled} 조건부가 <b>아니다</b> — 대시보드는 브랜드
 * 연동 없는 유저(레거시 개인 추적만)도 쓰는 화면이고, 어셈블러가 monitoring 비활성 환경에서 브랜드
 * 계열을 건너뛰도록 이미 설계돼 있다.
 */
@RestController
@RequestMapping("/v1/performance-dashboard")
public class V1PerformanceDashboardController {

	private static final String FILTER_ALL = "all";
	/** campaignId 전용 값 — "캠페인에 묶이지 않은 콘텐츠만"(값이 아니라 부재를 고르는 필터라 별도 어휘). */
	private static final String CAMPAIGN_NONE = "none";

	/**
	 * statusCounts 키 순서(FE 탭 순서) — 레거시 {@link ItemStatus} 어휘 그대로다. tagged-only 합성
	 * 아이템은 항상 {@code tracking}이라 값 공간이 늘지 않는다.
	 */
	private static final List<String> STATUSES = List.of(ItemStatus.TRACKING, ItemStatus.COLLECTING,
			ItemStatus.DETECTING, ItemStatus.NOT_UPLOADED, ItemStatus.ENDED, ItemStatus.HIDDEN, ItemStatus.ERROR);

	/** {@code status} 필터의 허용 값(가변인자 검증용) — 요청마다 배열을 만들지 않게 미리 굳힌다. */
	private static final String[] STATUS_VALUES = STATUSES.toArray(String[]::new);

	private final PerformanceContentAssembler assembler;
	private final PerformanceComparisonAssembler comparisonAssembler;

	public V1PerformanceDashboardController(PerformanceContentAssembler assembler,
			PerformanceComparisonAssembler comparisonAssembler) {
		this.assembler = assembler;
		this.comparisonAssembler = comparisonAssembler;
	}

	/**
	 * 통합 목록 — {@code data}는 전 필터 적용, {@code meta.statusCounts}는 <b>분류 필터</b>(출처·협찬·
	 * 캠페인·brandAccountId·accountType)만 적용한 모수에서 센다(스펙 §7-1, accountType은 08-12 추가).
	 *
	 * <p>statusCounts 모수에서 빠지는 필터는 둘이다:
	 * <ul>
	 *   <li><b>업로드 기간</b>({@code uploadedFrom}·{@code uploadedTo}) — 기간을 좁혀도 상태 뱃지가
	 *       흔들리지 않아야 한다(§7-1 명문).</li>
	 *   <li><b>{@code status} 자신</b> — statusCounts는 상태 축 뱃지라 자기 필터를 적용하면
	 *       {@code status=ended} 조회에서 나머지 6키가 전부 0이 되어 탭 바가 무력화된다. 형제
	 *       엔드포인트 §6-1의 {@code meta.counts}가 "필터 적용 전 전량"인 것과 같은 취지다.</li>
	 * </ul>
	 *
	 * <p>{@code sponsorship}은 <b>적용한다</b> — 카운트 키 축(상태)과 직교라 자기 0화가 없고,
	 * §7-1이 statusCounts에 적용한다고 본 "분류 범위" 필터에 속한다.
	 *
	 * <p><b>{@code brandAccountId}를 명시하면 {@code accountType=all}이 함의된다</b>(08-12 리뷰):
	 * 유저가 그 브랜드를 콕 집어 물었으므로 "경쟁사 제외" 기본값까지 겹쳐 걸면 안 된다. 겹쳐 걸면
	 * 경쟁사 브랜드를 지정한 조회가 오류도 힌트도 없이 빈 {@code data} + 전 상태 0이 되는데,
	 * 브랜드 칩은 경쟁사까지 내려주는 {@code /v1/brand-monitoring/accounts}로 만들고 §6의
	 * {@code /comparison}은 같은 계정의 막대를 정상적으로 그리므로 두 표면이 서로 어긋난다.
	 * <b>{@code accountType}을 명시하면 그쪽이 이긴다</b> — {@code brandAccountId=X&accountType=own}은
	 * 문자 그대로 "X가 경쟁사면 빈 결과"다(명시한 값의 의미를 함의가 덮지 않는다).
	 */
	@GetMapping("/contents")
	public ApiResponse<List<PerformanceContentResponse>> contents(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String brandAccountId,
			@RequestParam(required = false) String accountType) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String statusFilter = normalizeFilter(status, "status", STATUS_VALUES);
		// campaignId는 all(필터 없음)·none(캠페인 없음)·캠페인 id 문자열 셋을 받는다 — none 판정은
		// matchesCampaign에서 한다.
		String campaignFilter = normalizeFilter(campaignId);
		String brandFilter = normalizeFilter(brandAccountId);
		// 공용 normalizeFilter를 쓰지 않는다 — 이 파라미터만 미지정과 all이 다르다(아래 javadoc).
		// brandFilter를 같이 넘기는 이유는 "브랜드 명시 = accountType=all 함의"다(위 javadoc).
		String accountTypeFilter = normalizeAccountType(accountType, brandFilter);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");

		PerformanceContentAssembler.Assembled assembled = assembler.assemble(principal.getUserId());
		Set<String> competitorIds = assembled.competitorBrandAccountIds();

		// 분류 필터 — statusCounts 모수의 술어다(status·업로드 기간은 여기 없다, 위 javadoc).
		Predicate<PerformanceContentResponse> classification = c ->
				(sourceFilter == null || sourceFilter.equals(c.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(c.sponsorship()))
						&& matchesCampaign(c, campaignFilter)
						&& (brandFilter == null || brandFilter.equals(c.brandAccountId()))
						&& matchesAccountType(c, accountTypeFilter, competitorIds);

		// statusCounts 모수 — data는 여기서 status·기간을 더 걸어 갈라져 나온다(같은 모수 출신).
		List<PerformanceContentResponse> counted = assembled.contents().stream().filter(classification).toList();
		List<PerformanceContentResponse> data = counted.stream()
				.filter(c -> statusFilter == null || statusFilter.equals(c.item().status()))
				.filter(c -> withinUploadWindow(c, from, to))
				.toList();

		return ApiResponse.ok(data, meta(data.size(), counted, assembled.lastCollectedAt()));
	}

	/**
	 * 단건 — {@code contentId}는 {@code canonicalPostId}(순수 shortcode)다. 내 소유 범위 밖이면 존재
	 * 여부를 흘리지 않고 404(§6-2 관용구와 동일).
	 *
	 * <p>같은 shortcode의 콘텐츠가 둘 이상일 수 있다 — 종결 후 재등록처럼 레거시 아이템이 두 행인
	 * 경우다(각자 캠페인·기간이 다른 별개 등록이라 어셈블러가 접지 않는다, Task 9 판단). 그때는
	 * <b>첫 매치</b>를 돌려준다: 어셈블러 정렬이 업로드 최신순·동률은 item id로 고정돼 있어 첫 매치가
	 * 요청마다 흔들리지 않고, 목록의 첫 등장 순서와도 일치한다.
	 */
	@GetMapping("/contents/{contentId}")
	public ApiResponse<PerformanceContentResponse> content(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String contentId) {
		return assembler.assemble(principal.getUserId()).contents().stream()
				.filter(c -> contentId.equals(c.canonicalPostId()))
				.findFirst()
				.map(ApiResponse::ok)
				.orElseThrow(() -> V1ApiException.notFound("게시물을 찾을 수 없습니다."));
	}

	/**
	 * 성과 비교 집계(스펙 2026-08-10) — 브랜드 계정 × 5구간. 기간 파라미터는 없다(5구간 항상 전부).
	 * 모수는 목록과 같은 조립 전량에 분류 필터(source·sponsorship·campaignId)만 건 것 — 목록·비교
	 * 막대의 숫자가 정의상 일치한다. individual은 계정 귀속이 불가능해 집계에서 빠진다
	 * (source=individual이면 전 구간이 빈다 — 의도된 동작).
	 *
	 * <p>{@code accountType} 파라미터는 <b>없다</b>(08-12) — own·competitor를 나란히 놓는 것이 이
	 * 화면의 존재 이유라 타입으로 축을 걸러낼 여지가 없다. 계정별 타입은 응답 필드로 내린다(스펙 §6).
	 */
	@GetMapping("/comparison")
	public ApiResponse<PerformanceComparisonResponse> comparison(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String campaignFilter = normalizeFilter(campaignId);

		List<PerformanceContentResponse> filtered = assembler.assemble(principal.getUserId()).contents().stream()
				.filter(c -> (sourceFilter == null || sourceFilter.equals(c.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(c.sponsorship()))
						&& matchesCampaign(c, campaignFilter))
				.toList();
		return ApiResponse.ok(comparisonAssembler.assemble(principal.getUserId(), filtered));
	}

	// ---------- meta ----------

	/** @param counted 분류 필터만 적용한 모수 — statusCounts의 모수다(status·기간 미적용, §7-1). */
	private static Map<String, Object> meta(int total, List<PerformanceContentResponse> counted,
			OffsetDateTime lastCollectedAt) {
		Map<String, Long> statusCounts = new LinkedHashMap<>();
		// 7종 키는 0건이어도 전부 존재해야 한다(FE 탭 뱃지가 키 부재를 다루지 않는다).
		STATUSES.forEach(s -> statusCounts.put(s, 0L));
		for (PerformanceContentResponse content : counted) {
			// 방어적으로 merge다 — 값 공간 밖 상태가 생겨도 뭉개지 않고 키를 늘린다.
			statusCounts.merge(content.item().status(), 1L, Long::sum);
		}

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", total);
		// 목록 상한 철폐(08-10) — 전량 반환이라 잘림이 없다. limit 키는 응답 형태 호환용으로 남기되
		// 반환 건수와 같게 둔다(FE가 total > limit로 잘림을 판정해도 오탐이 없다).
		meta.put("limit", total);
		meta.put("lastCollectedAt", KstTimestamps.toKstIso(lastCollectedAt));
		meta.put("statusCounts", statusCounts);
		return meta;
	}

	// ---------- 필터 ----------

	private static boolean withinUploadWindow(PerformanceContentResponse content, LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return true;
		}
		// 산지별로 날짜·타임스탬프가 섞여 있어 직접 파싱하지 않는다(어셈블러 공용 키).
		LocalDate uploadedOn = PerformanceContentAssembler.uploadedOn(content);
		if (uploadedOn == null) {
			// 업로드일을 모르는 콘텐츠(collecting·detecting 등 post 없는 아이템)는 기간 판정이 불가라
			// data에서 제외한다 — 다만 statusCounts 모수에는 그대로 남는다(§7-1).
			return false;
		}
		return (from == null || !uploadedOn.isBefore(from)) && (to == null || !uploadedOn.isAfter(to));
	}

	private static boolean matchesCampaign(PerformanceContentResponse content, String filter) {
		if (filter == null) {
			return true;
		}
		String campaignId = content.item().campaignId();
		return CAMPAIGN_NONE.equals(filter) ? campaignId == null : Objects.equals(campaignId, filter);
	}

	/**
	 * accountType 필터(08-12) — {@code all}(=null)은 전량, {@code competitor}는 경쟁사 구독 소속만,
	 * <b>미지정·own은 "경쟁사만 제외"</b>다.
	 *
	 * <p>미지정이 "own 브랜드만"이 아닌 이유: 이 응답에는 브랜드에 귀속되지 않는 레거시 개인 추적
	 * 콘텐츠(brandAccountId null)가 섞여 있어, 문자 그대로 own만 남기면 경쟁사를 하나도 등록하지
	 * 않은 유저의 성과 요약 숫자까지 줄어든다. 요청서의 의도(경쟁사가 내 성과를 오염시키지 않게)는
	 * 경쟁사만 빼는 것으로 충족된다(스펙 §5).
	 */
	private static boolean matchesAccountType(PerformanceContentResponse content, String filter,
			Set<String> competitorIds) {
		boolean competitor = content.brandAccountId() != null
				&& competitorIds.contains(content.brandAccountId());
		if (BrandAccountType.COMPETITOR.equals(filter)) {
			return competitor;
		}
		if (filter == null) {
			return true;   // all — 미지정과 갈라지는 지점이다(normalizeAccountType 참고).
		}
		return !competitor;
	}

	/**
	 * accountType 전용 정규화 — 다른 필터와 달리 미지정과 {@code all}이 다르다(미지정은 경쟁사 제외가
	 * 기본, all은 전량). 그래서 공용 {@link #normalizeFilter(String, String, String...)}를 쓰지 않는다:
	 * 그쪽에 태우면 미지정이 곧 전량이 되어 경쟁사 콘텐츠가 기본 성과 요약을 오염시킨다.
	 *
	 * <p>단, <b>미지정이면서 {@code brandAccountId}가 명시된 조회</b>는 전량(all)이다 — 특정 브랜드를
	 * 집어 물은 요청에 경쟁사 제외 기본값까지 겹쳐 걸면 경쟁사 브랜드 조회가 조용히 빈다(08-12 리뷰).
	 *
	 * @param brandFilter 정규화된 brandAccountId(null = 브랜드 미지정) — 함의 판정에만 쓴다
	 * @return null = 전량(all), {@code "own"} = 경쟁사 제외, {@code "competitor"} = 경쟁사만
	 */
	private static String normalizeAccountType(String raw, String brandFilter) {
		if (raw == null || raw.isBlank()) {
			return brandFilter == null ? BrandAccountType.OWN : null;
		}
		if (FILTER_ALL.equals(raw)) {
			return null;
		}
		if (!BrandAccountType.isValid(raw)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		return raw;
	}

	/** 미지정·{@code all}은 필터 없음(null), 그 외 값은 허용 목록 밖이면 400. */
	private static String normalizeFilter(String raw, String param, String... allowed) {
		String value = normalizeFilter(raw);
		if (value == null) {
			return null;
		}
		for (String candidate : allowed) {
			if (candidate.equals(value)) {
				return value;
			}
		}
		throw V1ApiException.validation(param + " 값이 올바르지 않아요.");
	}

	/**
	 * 값 공간이 열린 파라미터(brandAccountId)용 정규화 — 미지정·빈 값·{@code all}은 필터 없음.
	 * 브랜드 id는 숫자 문자열이라 {@code all}이 실제 id와 충돌할 수 없어, FE의 "전체" 탭이 그대로
	 * 넘어와도 전량으로 받아준다.
	 */
	private static String normalizeFilter(String raw) {
		return raw == null || raw.isBlank() || FILTER_ALL.equals(raw) ? null : raw;
	}

	private static LocalDate parseDate(String raw, String param) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation(param + "은 YYYY-MM-DD 형식이어야 해요.");
		}
	}
}
