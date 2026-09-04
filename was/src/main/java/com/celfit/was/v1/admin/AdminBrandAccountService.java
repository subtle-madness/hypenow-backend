package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.AdminBrandReadRepository;
import com.celfit.was.monitoring.AdminBrandReadRepository.BrandCallSumRow;
import com.celfit.was.monitoring.AdminBrandReadRepository.PostCountRow;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.brandmonitoring.BrandAccountAssembler;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 어드민 "등록된 브랜드 목록"(GET /v1/admin/brand-monitoring/accounts, 2026-09-03) 집계 — 행 단위는
 * app.brand_monitorings 활성 연결 1개다. app 스키마(유저·링크)와 monitoring DB(브랜드 계정·게시물·콜
 * 집계)는 별도 DataSource라 SQL 조인이 불가능(시스템 경계 원칙과도 정합) — 링크 전체를 읽어 유저·
 * 브랜드 id 집합을 뽑고, 그 id들로 각각 배치 조회한 뒤 Java에서 합친다. 규모는 활성 링크 수(운영
 * 기준 수천 행대)라 전량 인메모리 조립이 부담이 아니다({@link AdminMonitoringRegistrationsController}·
 * {@link AdminCrawlingUsageService}와 같은 관용구).
 *
 * <p>monitoring이 꺼져 있으면({@code monitoring.enabled=false}) {@link AdminBrandReadRepository} 빈이
 * 없다 — 그 경우 행은 링크·유저만으로 나가고 monitoring 유래 필드(postCount·crawlingCalls·
 * collectionStatus·backfillCompletedAt·lastCollectedAt)는 전부 0/null이다. username도 그때만 링크의
 * 스냅샷 값(등록 시점 관측)으로 대체한다 — monitoring이 살아 있으면 계정 최신 관측값이 정본이다.
 *
 * <p><b>60초 캐시는 없다(2026-09-04 제거)</b>. 원래(PR #766) 조립 결과를 60초 인메모리 캐시해
 * monitoring-ro 풀 경합을 막았지만, 등록·해지 직후 새로고침해도 최대 60초 옛 목록이 보이는 신선도
 * 결함으로 판정됐다. 대신 원인 두 가지를 직접 잡는다.
 * <ol>
 *   <li><b>전용 읽기 풀</b> — 계정 메타(findAccountsByIds, PK IN이라 가볍다)와 게시물 수·콜 합계
 *       집계(전량 GROUP BY라 무겁다)는 {@link AdminBrandReadRepository}를 통해 monitoring-admin
 *       풀({@link com.celfit.was.monitoring.MonitoringConfig})로 조회한다 — 실사용자 브랜드
 *       대시보드·AI 어시스턴트가 쓰는 monitoring-ro 풀과 완전히 분리돼 경합하지 않는다.
 *   <li><b>집계는 페이지 분량만</b> — 정렬 키가 postCount·crawlingCalls가 아니면(user·username·
 *       collectionStatus·registeredAt) 두 집계 쿼리를 페이지에 남은 ≤limit개 brandId로만 좁혀
 *       부른다({@link #mergeAggregates}). postCount·crawlingCalls 정렬은 순서를 정하려면 불가피하게
 *       필터링된 전체 brandId로 집계한다.
 * </ol>
 * 링크·유저·계정 메타 조회는 여전히 전량이다(비집계·PK IN이라 가볍다) — q 검색·기본 정렬·페이지
 * 컷은 이 전량 조회 결과로 수행한다.
 */
@Service
public class AdminBrandAccountService {

	private static final Set<String> ALLOWED_SORT_KEYS =
			Set.of("user", "username", "postCount", "crawlingCalls", "collectionStatus", "registeredAt");
	private static final Set<String> AGGREGATE_SORT_KEYS = Set.of("postCount", "crawlingCalls");
	private static final String DEFAULT_SORT = "registeredAt:desc";

	private final BrandLinkRepository linkRepository;
	private final AdminUserRepository userRepository;
	private final Optional<AdminBrandReadRepository> adminReads;
	private final Clock clock;

	public AdminBrandAccountService(BrandLinkRepository linkRepository, AdminUserRepository userRepository,
			Optional<AdminBrandReadRepository> adminReads, Clock clock) {
		this.linkRepository = linkRepository;
		this.userRepository = userRepository;
		this.adminReads = adminReads;   // monitoring.enabled=false면 비어 있다 — monitoring 필드는 전부 0/null
		this.clock = clock;
	}

	public Result list(AdminPageRequest pageRequest, String sort, String q) {
		SortSpec sortSpec = parseSort(sort);
		Comparator<Assembled> comparator = buildComparator(sortSpec);

		List<Assembled> base = loadBase();
		if (base.isEmpty()) {
			return new Result(List.of(), 0);
		}

		String normalizedQ = normalize(q);
		List<Assembled> filtered = normalizedQ == null ? base
				: base.stream().filter(a -> matches(a, normalizedQ)).toList();

		boolean fullAggregation = AGGREGATE_SORT_KEYS.contains(sortSpec.key());
		List<Assembled> ordered = fullAggregation ? mergeAggregates(filtered, brandIdsOf(filtered)) : filtered;

		List<Assembled> sorted = ordered.stream().sorted(comparator).toList();
		long total = sorted.size();
		List<Assembled> page = sorted.stream().skip(pageRequest.offset()).limit(pageRequest.limit()).toList();
		// postCount·crawlingCalls 정렬이면 sorted 단계에서 이미 실값이 채워져 있다 — 그 외 정렬은 여기서
		// 처음이자 유일하게 집계를 부른다(페이지에 남은 ≤limit개 brandId로만).
		List<Assembled> pageWithAggregates = fullAggregation ? page : mergeAggregates(page, brandIdsOf(page));

		List<AdminBrandAccountRow> rows = pageWithAggregates.stream()
				.map(AdminBrandAccountService::toResponse)
				.toList();
		return new Result(rows, total);
	}

	/**
	 * 링크·유저·계정 메타(monitoring PK IN — 가벼움)만으로 조립한 목록. postCount·crawlingCalls는
	 * 전부 0 자리표시자다 — 실값은 {@link #mergeAggregates}가 필요한 범위에만 채운다.
	 */
	private List<Assembled> loadBase() {
		List<BrandLinkRow> links = linkRepository.findAllActive();
		if (links.isEmpty()) {
			return List.of();
		}

		Set<Long> userIds = links.stream().map(BrandLinkRow::userId).collect(Collectors.toSet());
		Map<Long, AdminUserRow> usersById = userRepository.findByIds(userIds).stream()
				.collect(Collectors.toMap(AdminUserRow::id, Function.identity()));

		Set<Long> brandIds = links.stream().map(BrandLinkRow::brandId).collect(Collectors.toSet());
		Map<Long, BrandAccountRow> accountsById = adminReads.isPresent()
				? adminReads.get().findAccountsByIds(brandIds).stream()
						.collect(Collectors.toMap(BrandAccountRow::id, Function.identity()))
				: Map.of();

		return List.copyOf(links.stream()
				.map(link -> assembleBase(link, usersById.get(link.userId()), accountsById.get(link.brandId())))
				.toList());
	}

	private static Assembled assembleBase(BrandLinkRow link, AdminUserRow user, BrandAccountRow account) {
		// user는 app.brand_monitorings.user_id가 app.users(id) FK(ON DELETE CASCADE)라 활성 링크에
		// 짝이 없을 수 없다 — 그래도 못 찾으면 데이터 정합 위반이니 조용히 삼키지 않고 바로 던진다.
		if (user == null) {
			throw new IllegalStateException("브랜드 연결의 유저를 찾을 수 없어요(userId=" + link.userId() + ")");
		}
		String username = account != null ? account.username() : link.username();
		String collectionStatus = account != null ? BrandAccountAssembler.collectionStatus(account) : null;
		OffsetDateTime backfillCompletedAt = account != null ? account.backfillCompletedAt() : null;
		OffsetDateTime lastCollectedAt = account != null ? account.lastSweptAt() : null;

		return new Assembled(link.brandId(), username, link.accountType(), user.id(), user.email(),
				blankToNull(user.name()), blankToNull(user.companyName()), 0L, 0L, 0L,
				collectionStatus, link.collectionMonths(), backfillCompletedAt, link.createdAt(), lastCollectedAt);
	}

	/**
	 * 주어진 brandId 집합에 대해서만 postCount·crawlingCalls 실값을 채운 새 목록을 돌려준다(원본은
	 * 불변이라 record를 새로 만든다). monitoring 비활성이면(adminReads 없음) 그대로 돌려준다 — 이미
	 * 0 자리표시자다.
	 */
	private List<Assembled> mergeAggregates(List<Assembled> rows, Set<Long> brandIds) {
		if (rows.isEmpty() || adminReads.isEmpty()) {
			return rows;
		}
		AdminBrandReadRepository reads = adminReads.get();
		LocalDate today = LocalDate.now(clock.withZone(KstTimestamps.KST));
		LocalDate monthStart = today.withDayOfMonth(1);
		Map<Long, Long> postCountsByBrand = reads.countPostsByBrand(brandIds).stream()
				.collect(Collectors.toMap(PostCountRow::brandId, PostCountRow::postCount));
		Map<Long, BrandCallSumRow> callSumsByBrand = reads.sumCallCountsByBrand(brandIds, monthStart).stream()
				.collect(Collectors.toMap(BrandCallSumRow::brandId, Function.identity()));
		return rows.stream()
				.map(a -> withAggregates(a, postCountsByBrand.getOrDefault(a.accountId(), 0L),
						callSumsByBrand.get(a.accountId())))
				.toList();
	}

	private static Assembled withAggregates(Assembled a, long postCount, BrandCallSumRow callSum) {
		long callsTotal = callSum != null ? callSum.total() : 0L;
		long callsMonth = callSum != null ? callSum.month() : 0L;
		return new Assembled(a.accountId(), a.username(), a.mode(), a.userId(), a.userEmail(), a.userName(),
				a.userOrgName(), postCount, callsTotal, callsMonth, a.collectionStatus(), a.collectionMonths(),
				a.backfillCompletedAt(), a.registeredAt(), a.lastCollectedAt());
	}

	private static Set<Long> brandIdsOf(List<Assembled> rows) {
		return rows.stream().map(Assembled::accountId).collect(Collectors.toSet());
	}

	private static AdminBrandAccountRow toResponse(Assembled a) {
		return new AdminBrandAccountRow(String.valueOf(a.accountId()), a.username(), a.mode(),
				new AdminBrandAccountRow.User(a.userId(), a.userEmail(), a.userName(), a.userOrgName()),
				a.postCount(), new AdminBrandAccountRow.CrawlingCalls(a.callsTotal(), a.callsMonth()),
				a.collectionStatus(), a.collectionMonths(), KstTimestamps.toKstIso(a.backfillCompletedAt()),
				KstTimestamps.toKstIso(a.registeredAt()), KstTimestamps.toKstIso(a.lastCollectedAt()));
	}

	private static boolean matches(Assembled a, String normalizedQ) {
		return a.username().toLowerCase(Locale.ROOT).contains(normalizedQ)
				|| a.userEmail().toLowerCase(Locale.ROOT).contains(normalizedQ);
	}

	private static String normalize(String q) {
		if (q == null) {
			return null;
		}
		String trimmed = q.trim();
		return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	/**
	 * sort=<key>:<asc|desc> 파싱(계약 문서 §쿼리 파라미터) — 미지정은 registeredAt:desc, 그 외 형식·
	 * 키·방향 오류는 전부 400 VALIDATION_FAILED.
	 */
	private static SortSpec parseSort(String sort) {
		String raw = (sort == null) ? DEFAULT_SORT : sort;
		int colon = raw.indexOf(':');
		if (colon < 0) {
			throw invalidSort(raw);
		}
		String key = raw.substring(0, colon);
		String direction = raw.substring(colon + 1);
		if (!ALLOWED_SORT_KEYS.contains(key)) {
			throw invalidSort(raw);
		}
		boolean ascending;
		if ("asc".equals(direction)) {
			ascending = true;
		} else if ("desc".equals(direction)) {
			ascending = false;
		} else {
			throw invalidSort(raw);
		}
		return new SortSpec(key, ascending);
	}

	/**
	 * 타이브레이크(registeredAt desc → accountId asc → user id asc)는 정렬 키와 무관하게 항상 마지막에
	 * 붙어 페이지 경계에서 순서가 흔들리지 않게 한다.
	 */
	private static Comparator<Assembled> buildComparator(SortSpec spec) {
		Comparator<Assembled> base = switch (spec.key()) {
			case "user" -> Comparator.comparing(a -> a.userEmail().toLowerCase(Locale.ROOT));
			case "username" -> Comparator.comparing(a -> a.username().toLowerCase(Locale.ROOT));
			case "postCount" -> Comparator.comparingLong(Assembled::postCount);
			case "crawlingCalls" -> Comparator.comparingLong(Assembled::callsTotal);
			// null(monitoring 비활성·계정 미확인)은 가장 작은 값으로 취급 — asc면 맨 앞, desc면 맨 끝.
			case "collectionStatus" ->
					Comparator.comparing(Assembled::collectionStatus, Comparator.nullsFirst(Comparator.naturalOrder()));
			case "registeredAt" -> Comparator.comparing(Assembled::registeredAt);
			default -> throw invalidSort(spec.key() + ":" + (spec.ascending() ? "asc" : "desc"));   // 도달 불가 방어
		};
		if (!spec.ascending()) {
			base = base.reversed();
		}
		return base.thenComparing(Comparator.comparing(Assembled::registeredAt).reversed())
				.thenComparingLong(Assembled::accountId)
				.thenComparingLong(Assembled::userId);
	}

	private static V1ApiException invalidSort(String raw) {
		return V1ApiException.validation("정렬 형식이 올바르지 않아요: " + raw);
	}

	public record Result(List<AdminBrandAccountRow> rows, long total) {
	}

	private record SortSpec(String key, boolean ascending) {
	}

	/** 필터·정렬·조립 중간 표현 — 원시 타입을 그대로 들고 있어 정렬·검색이 문자열 변환 없이 돈다. */
	private record Assembled(long accountId, String username, String mode, long userId, String userEmail,
			String userName, String userOrgName, long postCount, long callsTotal, long callsMonth,
			String collectionStatus, int collectionMonths, OffsetDateTime backfillCompletedAt,
			OffsetDateTime registeredAt, OffsetDateTime lastCollectedAt) {
	}
}
