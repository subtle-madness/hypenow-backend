package com.celfit.was.v1.perfdashboard;

import com.celfit.was.auth.AppUserDetails;
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
 * + 브랜드 90일 윈도우라 페이지네이션 없이 전량을 조립한 뒤 자른다).
 *
 * <p>브랜드 표면(§5·§6)과 달리 {@code monitoring.enabled} 조건부가 <b>아니다</b> — 대시보드는 브랜드
 * 연동 없는 유저(레거시 개인 추적만)도 쓰는 화면이고, 어셈블러가 monitoring 비활성 환경에서 브랜드
 * 계열을 건너뛰도록 이미 설계돼 있다.
 */
@RestController
@RequestMapping("/v1/performance-dashboard")
public class V1PerformanceDashboardController {

	/** 목록 상한(FE 명세 meta.limit) — 레거시 아이템 + 브랜드 윈도우 105건 규모라 실사용에선 도달하지 않는다. */
	private static final int CONTENT_LIMIT = 250;

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

	public V1PerformanceDashboardController(PerformanceContentAssembler assembler) {
		this.assembler = assembler;
	}

	/**
	 * 통합 목록 — {@code data}는 전 필터 적용, {@code meta.statusCounts}는 <b>업로드 기간 필터만</b>
	 * 빼고 같은 필터를 적용한다(스펙 §7-1). 기간을 좁혀도 상태 뱃지가 흔들리지 않게 하려는 규칙이다.
	 *
	 * <p><b>주의</b> — 그 "같은 필터"에는 {@code status} 자신도 포함된다. {@code status=ended}로
	 * 조회하면 statusCounts의 나머지 6키가 0이 된다. 브랜드 목록(§6-1)의 {@code meta.counts}가
	 * "필터 적용 전 전량"인 것과는 다른 규칙이라, FE가 statusCounts를 상태 탭 뱃지로 쓴다면
	 * status만 모수에서 빼는 재검토가 필요하다(스펙 §7-1은 적용 대상으로 출처·캠페인·brandAccountId만
	 * 열거하고 status·sponsorship은 언급하지 않는다).
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
			@RequestParam(required = false) String brandAccountId) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String statusFilter = normalizeFilter(status, "status", STATUS_VALUES);
		// campaignId는 all(필터 없음)·none(캠페인 없음)·캠페인 id 문자열 셋을 받는다 — none 판정은
		// matchesCampaign에서 한다.
		String campaignFilter = normalizeFilter(campaignId);
		String brandFilter = normalizeFilter(brandAccountId);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");

		Predicate<PerformanceContentResponse> attributes = c ->
				(sourceFilter == null || sourceFilter.equals(c.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(c.sponsorship()))
						&& (statusFilter == null || statusFilter.equals(c.item().status()))
						&& matchesCampaign(c, campaignFilter)
						&& (brandFilter == null || brandFilter.equals(c.brandAccountId()));

		PerformanceContentAssembler.Assembled assembled = assembler.assemble(principal.getUserId());
		// 기간 외 필터만 적용한 모수 — statusCounts와 data가 같은 모수에서 갈라져 나온다.
		List<PerformanceContentResponse> beforeWindow = assembled.contents().stream().filter(attributes).toList();
		List<PerformanceContentResponse> data = beforeWindow.stream()
				.filter(c -> withinUploadWindow(c, from, to))
				.limit(CONTENT_LIMIT)
				.toList();

		return ApiResponse.ok(data, meta(data.size(), beforeWindow, assembled.lastCollectedAt()));
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

	// ---------- meta ----------

	/** @param beforeWindow 업로드 기간 필터만 빼고 필터링한 모수 — statusCounts의 모수다(§7-1). */
	private static Map<String, Object> meta(int total, List<PerformanceContentResponse> beforeWindow,
			OffsetDateTime lastCollectedAt) {
		Map<String, Long> statusCounts = new LinkedHashMap<>();
		// 7종 키는 0건이어도 전부 존재해야 한다(FE 탭 뱃지가 키 부재를 다루지 않는다).
		STATUSES.forEach(s -> statusCounts.put(s, 0L));
		for (PerformanceContentResponse content : beforeWindow) {
			// 방어적으로 merge다 — 값 공간 밖 상태가 생겨도 뭉개지 않고 키를 늘린다.
			statusCounts.merge(content.item().status(), 1L, Long::sum);
		}

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", total);
		meta.put("limit", CONTENT_LIMIT);
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
