package com.celfit.was.v2.monitoring;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.RegistrationResult;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.ItemStatus;
import com.celfit.was.v1.monitoring.MonitoringRegistrationResponse;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler;
import com.celfit.was.v2.monitoring.V2CampaignContentsResponse.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 v2 콘텐츠 관계(스펙 §8) — 캠페인에 콘텐츠를 붙이고 떼는 것만 한다.
 *
 * <p><b>모니터링 자체는 전부 레거시가 정본이다</b>. 레거시 코드는 한 줄도 바꾸지 않는다:
 * <ol>
 *   <li>이미 내 추적 아이템이 있는 콘텐츠 → 슬림 경로 {@link CampaignItemLinker#link}로
 *       {@code campaignId}만 연결 — 레거시 patch의 두 소유 검증은 링커가 그대로 유지하되,
 *       v2가 버리던 응답 전량 재조립은 생략한다(아이템당 ~9쿼리 → 3쿼리. 상한 100건이 한
 *       트랜잭션에 몰리는 경로라 커넥션 점유 시간이 문제였다 — 트레이드오프는 링커 javadoc)</li>
 *   <li>추적 아이템이 없고 브랜드 태그 목록(tagged)에만 있는 콘텐츠 →
 *       {@link V1MonitoringRegistrationService#register}에 canonical URL을 위임해 아이템을 만든다</li>
 * </ol>
 *
 * <p><b>캠페인 관계는 1:1을 유지한다</b>(설계 §8) — 이미 다른 캠페인에 묶인 콘텐츠는 여기서
 * <b>옮기지 않고</b> duplicate로 돌려준다. 이동은 기존 콘텐츠 수정(PATCH /v1/monitoring/items/{id})의
 * 몫이다. 그래야 "추가" 한 번에 남의 캠페인 구성이 조용히 바뀌는 일이 없다.
 *
 * <p>콘텐츠 식별자는 {@code contentId} = shortcode(= canonicalPostId, §7-1과 같은 값)다. shortcode →
 * 아이템 대응은 레거시 전량 조립({@link TrackingItemAssembler#assembleList})에서 뽑는다 — 아이템
 * 산지가 두 가지(url 모드 등록·account 모드 감지)라 DB 조회 하나로는 같은 모수를 못 만든다.
 *
 * <p>브랜드 표면(§5·§6)과 달리 {@code monitoring.enabled} 조건부가 <b>아니다</b> — 캠페인은 브랜드
 * 연동이 없는 유저도 쓰는 기능이고(레거시 개인 추적만), 브랜드 의존은 {@link Optional}로 받아
 * 비활성 환경에서는 tagged 경로만 건너뛴다(성과 대시보드와 같은 설계).
 */
@Service
public class V2CampaignContentService {

	/** 계약 신규 코드(§9) — 같은 캠페인·다른 캠페인 둘 다 이 코드고, 구분은 reason 문장이 한다. */
	static final String REASON_CODE_ALREADY_EXISTS = "CAMPAIGN_CONTENT_ALREADY_EXISTS";
	/** 어디에도 없는 콘텐츠 — 기존 404 어휘(NOT_FOUND)를 entry 사유로 재사용한다. */
	static final String REASON_CODE_NOT_FOUND = "NOT_FOUND";

	private static final String REASON_SAME_CAMPAIGN = "이미 이 캠페인에 추가된 콘텐츠입니다.";
	private static final String REASON_NOT_FOUND = "게시물을 찾을 수 없습니다.";
	private static final String CAMPAIGN_NOT_FOUND = "캠페인을 찾을 수 없습니다.";

	/** 레거시 등록(6.27)과 같은 한 번 상한·기간 규칙 — 위임을 건너뛰는 경로에서도 필요해 여기서 본다. */
	private static final int MAX_CONTENTS = 100;
	private static final int MIN_TRACKING_DAYS = 1;
	private static final int MAX_TRACKING_DAYS = 90;

	/**
	 * 종결 3종 — 같은 shortcode의 아이템이 여럿일 때 <b>뒤로 미루는</b> 상태다(제외가 아니다).
	 * 레거시 duplicate 판정 모수에서 빠지는 상태와 같은 기준이며({@link ItemStatus#derive}),
	 * 종결 후 재등록이 허용돼 활성·종결 행이 공존할 수 있다(직접 등록 §6-4의 activeItemId 관용구).
	 *
	 * <p>단, <b>취소된 아이템은 아예 모수에서 뺀다</b>({@link #withoutCanceled}) — 자연 종결(ended)은
	 * 이미 수집이 끝난 콘텐츠라 캠페인에 담는 게 정당하지만, 취소분에 캠페인만 붙이면 "success인데
	 * 수집은 영영 없음"이 된다. 취소 여부는 레거시 응답(status가 ended/not_uploaded로 유도됨)으로는
	 * 구분이 안 돼 아이템 행(canceled_at)을 직접 본다.
	 */
	private static final Set<String> TERMINAL_STATUSES =
			Set.of(ItemStatus.NOT_UPLOADED, ItemStatus.ENDED, ItemStatus.HIDDEN);

	private final CampaignRepository campaignRepository;
	private final TrackingItemAssembler trackingItemAssembler;
	private final CampaignItemLinker linker;
	private final V1MonitoringRegistrationService registrationService;
	private final BrandLinkRepository linkRepository;
	private final MonitoringItemRepository itemRepository;
	private final Optional<BrandReadRepository> brandReadRepository;
	private final Optional<BrandPostAssembler> brandPostAssembler;

	public V2CampaignContentService(CampaignRepository campaignRepository,
			TrackingItemAssembler trackingItemAssembler, CampaignItemLinker linker,
			V1MonitoringRegistrationService registrationService, BrandLinkRepository linkRepository,
			MonitoringItemRepository itemRepository, Optional<BrandReadRepository> brandReadRepository,
			Optional<BrandPostAssembler> brandPostAssembler) {
		this.campaignRepository = campaignRepository;
		this.trackingItemAssembler = trackingItemAssembler;
		this.linker = linker;
		this.registrationService = registrationService;
		this.linkRepository = linkRepository;
		this.itemRepository = itemRepository;
		this.brandReadRepository = brandReadRepository;
		this.brandPostAssembler = brandPostAssembler;
	}

	/**
	 * 콘텐츠 추가(§8) — entry 단위 부분 성공. 레거시 등록 위임은 <b>한 번에 묶어서</b> 한다:
	 * 콘텐츠마다 register를 부르면 등록 행(registration)이 그 수만큼 생겨 폴링 대상이 흩어진다.
	 *
	 * <p>{@code @Transactional} — 레거시 등록도 {@code @Transactional}이라 이 트랜잭션에 합류한다.
	 * 캠페인 연결(슬림 경로)과 아이템 생성이 한 번에 커밋되고, 비동기 실행기 트리거도 커밋 뒤로 미뤄진다
	 * (레거시 triggerExecutor의 afterCommit 계약 유지). 부분 성공은 <b>응답 안의 entry 판정</b>이지
	 * 반쯤 커밋된 DB가 아니다.
	 */
	@Transactional
	public Added add(long userId, long campaignId, List<String> contentIds, Integer trackingDays) {
		CampaignRow campaign = requireCampaign(userId, campaignId);
		List<String> ids = validateContentIds(contentIds);
		int days = validateTrackingDays(trackingDays);
		String campaignKey = String.valueOf(campaign.id());

		Map<String, TrackingItemResponse> itemsByShortcode = indexByShortcode(withoutCanceled(userId,
				trackingItemAssembler.assembleList(userId).items()));

		// 1차 판정 — 입력 순서대로 (즉시 확정) / (위임 대기) / (앞 항목의 재판정)으로 가른다.
		List<Plan> plans = new ArrayList<>(ids.size());
		List<String> delegatedUrls = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		// tagged 조회는 실제로 필요할 때 한 번만 한다(브랜드 연동이 없으면 아예 돌지 않는다).
		Map<String, String> taggedUrls = null;

		for (String contentId : ids) {
			if (!seen.add(contentId)) {
				plans.add(Plan.repeat(contentId));
				continue;
			}
			TrackingItemResponse item = itemsByShortcode.get(contentId);
			if (item != null) {
				plans.add(Plan.settled(link(userId, campaign.id(), contentId, item)));
				continue;
			}
			if (taggedUrls == null) {
				taggedUrls = taggedPostUrls(userId);
			}
			String canonicalUrl = taggedUrls.get(contentId);
			if (canonicalUrl == null) {
				plans.add(Plan.settled(new Result(contentId, RegistrationResult.FAILED, null,
						REASON_CODE_NOT_FOUND, REASON_NOT_FOUND)));
				continue;
			}
			plans.add(new Plan(contentId, delegatedUrls.size(), false, null));
			delegatedUrls.add(canonicalUrl);
		}

		// 위임할 게 없으면 등록 행 자체를 만들지 않는다(폴링할 대상이 없다) — 전부 동기 완결이라 200이다.
		Map<String, String> createdItemIds = delegatedUrls.isEmpty() ? Map.of()
				: createdItemIdsByShortcode(registrationService.register(userId,
						body(delegatedUrls, days, campaignKey)));

		// 202는 "새로 만들어진 아이템이 있다"일 때만 — 위임했어도 레거시가 전건을 접어 0개면
		// 폴링할 등록 대상이 없으니 동기 완결(200)이다(§8).
		return new Added(new V2CampaignContentsResponse(campaignKey, resolve(plans, createdItemIds)),
				!createdItemIds.isEmpty());
	}

	/**
	 * 취소 아이템 제외 — 종결 status 후보가 있을 때만 아이템 행을 읽는다(전원 활성인 평시 경로에
	 * DB 왕복을 더하지 않는다). 취소는 반드시 종결 status로 유도되므로 이 게이트로 충분하다.
	 */
	private List<TrackingItemResponse> withoutCanceled(long userId, List<TrackingItemResponse> items) {
		boolean anyTerminal = items.stream().anyMatch(item -> TERMINAL_STATUSES.contains(item.status()));
		if (!anyTerminal) {
			return items;
		}
		Set<String> canceledIds = new LinkedHashSet<>();
		for (MonitoringItemRow row : itemRepository.findByUser(userId)) {
			if (row.canceledAt() != null) {
				canceledIds.add(String.valueOf(row.id()));
			}
		}
		if (canceledIds.isEmpty()) {
			return items;
		}
		return items.stream().filter(item -> !canceledIds.contains(item.id())).toList();
	}

	/**
	 * 2차 정산 — 위임 결과를 채우고, 같은 요청에 두 번 온 contentId는 <b>앞 판정을 그대로 잇는다</b>.
	 * 반복분을 무조건 "이미 이 캠페인에 있음"으로 접으면 앞 항목이 failed였을 때 거짓말이 된다.
	 * 앞 판정이 성공(success·pending)일 때만 duplicate로 바꾼다 — 실제로 두 번 추가되지는 않았기 때문이다.
	 */
	private static List<Result> resolve(List<Plan> plans, Map<String, String> createdItemIds) {
		Map<String, Result> firstByContentId = new LinkedHashMap<>();
		List<Result> results = new ArrayList<>(plans.size());
		for (Plan plan : plans) {
			Result result;
			if (plan.repeat()) {
				// 반복분은 항상 앞선 판정 뒤에 온다(입력 순서대로 돌기 때문) — first는 반드시 존재한다.
				Result first = firstByContentId.get(plan.contentId());
				result = isAdded(first)
						? duplicate(plan.contentId(), first.monitoringItemId(), REASON_SAME_CAMPAIGN)
						: first;
			} else {
				result = plan.delegatedIndex() == null ? plan.settled() : settle(plan, createdItemIds);
				firstByContentId.put(plan.contentId(), result);
			}
			results.add(result);
		}
		return results;
	}

	private static boolean isAdded(Result result) {
		return RegistrationResult.SUCCESS.equals(result.result())
				|| RegistrationResult.PENDING.equals(result.result());
	}

	/**
	 * 콘텐츠 제거(§8) — 캠페인 연결만 끊는다. <b>모니터링은 계속된다</b>(§7-2): 취소는 별개 동작
	 * (DELETE /v1/monitoring/items/{id})이고, 캠페인에서 뺀다고 수집을 멈추면 되돌릴 수 없는 손실이다.
	 *
	 * <p>탐색 모수는 "그 캠페인 소속인 내 아이템"이다 — 같은 shortcode의 아이템이 여럿이어도
	 * 캠페인 소속이 아닌 행은 애초에 제거 대상이 아니라 404다(남의 캠페인·비소속 구분 없이 같은 응답).
	 *
	 * <p>매칭되는 소속 아이템은 <b>전건</b> 연결을 끊는다 — 성과 대시보드는 레거시 아이템끼리
	 * 중복 제거를 하지 않아(tagged↔레거시만 병합), 대표 1건만 끊으면 204 직후 재조회에 콘텐츠가
	 * 그대로 남는다(종결 후 재등록·account 감지 중복이 같은 shortcode 행을 여럿 만들 수 있다).
	 */
	@Transactional
	public void remove(long userId, long campaignId, String contentId) {
		CampaignRow campaign = requireCampaign(userId, campaignId);
		String campaignKey = String.valueOf(campaign.id());
		String normalized = contentId == null ? "" : contentId.trim();

		List<TrackingItemResponse> targets = trackingItemAssembler.assembleList(userId).items().stream()
				.filter(item -> normalized.equals(shortcodeOf(item)))
				.filter(item -> campaignKey.equals(item.campaignId()))
				.toList();
		if (targets.isEmpty()) {
			throw V1ApiException.notFound("캠페인에서 콘텐츠를 찾을 수 없습니다.");
		}

		for (TrackingItemResponse target : targets) {
			linker.unlink(userId, Long.parseLong(target.id()));
		}
	}

	// ---------- 판정 ----------

	/** 기존 아이템 1건의 판정 — 캠페인 미연결이면 연결(슬림 경로), 이미 연결돼 있으면 duplicate(이동 금지). */
	private Result link(long userId, long campaignId, String contentId, TrackingItemResponse item) {
		String campaignKey = String.valueOf(campaignId);
		if (item.campaignId() == null) {
			linker.link(userId, campaignId, Long.parseLong(item.id()));
			return new Result(contentId, RegistrationResult.SUCCESS, item.id(), null, null);
		}
		if (campaignKey.equals(item.campaignId())) {
			return duplicate(contentId, item.id(), REASON_SAME_CAMPAIGN);
		}
		return duplicate(contentId, item.id(), otherCampaignReason(item.campaignName()));
	}

	/**
	 * 위임분 정산 — 레거시가 만든 pending 아이템 id를 shortcode로 맞춘다. 캠페인 연결은 등록 시점에
	 * 이미 걸려 있다(register 본문의 campaignId → insertPending) — 여기서 patch를 다시 부르지 않는다.
	 */
	private static Result settle(Plan plan, Map<String, String> createdItemIds) {
		// 아이템이 안 잡히는 경우는 레거시가 duplicate로 판정했다는 뜻인데, 그러려면 이 shortcode의
		// 활성 url 모드 행이 있어야 하고 그건 위 1차 판정에서 이미 걸러졌다(도달 불가). 응답을 죽이지
		// 않고 id 없는 pending으로 흘린다 — FE는 캠페인 화면을 다시 조회하면 실제 상태를 본다.
		return new Result(plan.contentId(), RegistrationResult.PENDING, createdItemIds.get(plan.contentId()),
				null, null);
	}

	private static Result duplicate(String contentId, String itemId, String reason) {
		return new Result(contentId, RegistrationResult.DUPLICATE, itemId, REASON_CODE_ALREADY_EXISTS, reason);
	}

	/** 이동 금지 사유 — 어느 캠페인인지 알려줘야 사용자가 그 캠페인에서 빼고 다시 넣을 수 있다. */
	private static String otherCampaignReason(String campaignName) {
		String where = campaignName == null || campaignName.isBlank() ? "다른 캠페인" : "'" + campaignName + "' 캠페인";
		return "이미 " + where + "에 연결된 콘텐츠입니다. 캠페인을 옮기려면 콘텐츠 수정에서 변경해 주세요.";
	}

	// ---------- shortcode 색인 ----------

	/**
	 * shortcode → 대표 아이템. 같은 shortcode의 아이템이 여럿일 수 있다(종결 후 재등록, 또는 url
	 * 모드 등록과 account 모드 감지가 같은 게시물을 가리키는 경우) — 활성 우선, 활성끼리는 id 최대다.
	 */
	private static Map<String, TrackingItemResponse> indexByShortcode(List<TrackingItemResponse> items) {
		Map<String, TrackingItemResponse> byShortcode = new LinkedHashMap<>();
		for (TrackingItemResponse item : items) {
			String shortcode = shortcodeOf(item);
			if (shortcode != null) {
				byShortcode.merge(shortcode, item, V2CampaignContentService::preferred);
			}
		}
		return byShortcode;
	}

	/** 활성(비종결) 우선 → id 최대. 직접 등록(§6-4)의 activeItemId와 같은 기준이다. */
	private static TrackingItemResponse preferred(TrackingItemResponse a, TrackingItemResponse b) {
		boolean aActive = !TERMINAL_STATUSES.contains(a.status());
		boolean bActive = !TERMINAL_STATUSES.contains(b.status());
		if (aActive != bActive) {
			return aActive ? a : b;
		}
		return Long.parseLong(a.id()) >= Long.parseLong(b.id()) ? a : b;
	}

	/**
	 * 아이템의 shortcode — 확정된 게시물 URL이 1순위고, 아직 게시물이 없는 url 모드 아이템
	 * (collecting)은 등록 원문({@code sourceUrl})으로 폴백한다. 폴백이 없으면 방금 등록한 콘텐츠가
	 * 첫 수집 전까지 "미존재"로 보여 같은 게시물의 아이템이 하나 더 생긴다.
	 */
	private static String shortcodeOf(TrackingItemResponse item) {
		String fromPost = PerformanceContentAssembler
				.shortcodeOf(item.post() == null ? null : item.post().url());
		return fromPost != null ? fromPost : PerformanceContentAssembler.shortcodeOf(item.sourceUrl());
	}

	// ---------- tagged ----------

	/**
	 * 내 브랜드 태그 목록의 shortcode → canonical URL(§6-1 어셈블러 결과의 postUrl — REELS는
	 * {@code /reel/}, FEED는 {@code /p/}). monitoring 비활성·브랜드 연결 없음·계정 행 부재면 빈 맵이라
	 * 그 콘텐츠는 그대로 failed(NOT_FOUND)가 된다.
	 */
	private Map<String, String> taggedPostUrls(long userId) {
		if (brandReadRepository.isEmpty() || brandPostAssembler.isEmpty()) {
			return Map.of();
		}
		Optional<BrandLinkRow> link = linkRepository.findActiveByUser(userId);
		if (link.isEmpty()) {
			return Map.of();
		}
		Optional<BrandAccountRow> account = brandReadRepository.get().findAccount(link.get().brandId());
		if (account.isEmpty()) {
			return Map.of();
		}
		Map<String, String> urls = new LinkedHashMap<>();
		for (BrandPostResponse post : brandPostAssembler.get().assembleTagged(account.get())) {
			urls.putIfAbsent(post.shortcode(), post.postUrl());
		}
		return urls;
	}

	// ---------- 레거시 위임 ----------

	/** 레거시 등록 본문 — posts만 담는다(accounts는 캠페인 콘텐츠의 관심사가 아니다). */
	private static Map<String, Object> body(List<String> posts, int trackingDays, String campaignId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("posts", posts);
		body.put("trackingDays", trackingDays);
		body.put("campaignId", campaignId);
		return body;
	}

	/**
	 * 레거시 등록 응답의 신규 아이템 → shortcode 색인. {@code items}는 <b>새로 만들어진 pending 행만</b>
	 * 담아서(중복·실패 입력은 빠진다) 위임 목록과 1:1이 아니다 — 그래서 순서가 아니라 sourceUrl에서
	 * 뽑은 shortcode로 맞춘다(URL 정규화 형태에 좌우되지 않는 유일한 키).
	 */
	private static Map<String, String> createdItemIdsByShortcode(MonitoringRegistrationResponse legacy) {
		Map<String, String> byShortcode = new LinkedHashMap<>();
		for (TrackingItemResponse created : legacy.items()) {
			String shortcode = PerformanceContentAssembler.shortcodeOf(created.sourceUrl());
			if (shortcode != null) {
				byShortcode.putIfAbsent(shortcode, created.id());
			}
		}
		return byShortcode;
	}

	// ---------- 검증 ----------

	private CampaignRow requireCampaign(long userId, long campaignId) {
		return campaignRepository.findByIdAndUser(campaignId, userId)
				.orElseThrow(() -> V1ApiException.notFound(CAMPAIGN_NOT_FOUND));
	}

	/** trim 정규화 포함 — 공백 섞인 id를 그대로 판정하면 조용한 NOT_FOUND가 된다(응답도 정규형 에코). */
	private static List<String> validateContentIds(List<String> contentIds) {
		if (contentIds == null || contentIds.isEmpty()) {
			throw V1ApiException.validation("추가할 콘텐츠를 입력해 주세요.");
		}
		if (contentIds.size() > MAX_CONTENTS) {
			throw V1ApiException.validation("한 번에 추가할 수 있는 항목은 최대 100개예요.");
		}
		List<String> normalized = new ArrayList<>(contentIds.size());
		for (String contentId : contentIds) {
			if (contentId == null || contentId.isBlank()) {
				throw V1ApiException.validation("contentIds 형식이 올바르지 않아요.");
			}
			normalized.add(contentId.trim());
		}
		return normalized;
	}

	/**
	 * 기간은 항상 검증한다 — 실제로 쓰이는 건 위임(아이템 생성) 경로뿐이지만, 같은 요청이 어떤
	 * 콘텐츠를 만나느냐에 따라 검증이 켜졌다 꺼졌다 하면 FE가 잘못된 값을 조용히 보내게 된다.
	 */
	private static int validateTrackingDays(Integer trackingDays) {
		if (trackingDays == null) {
			throw V1ApiException.validation("모니터링 기간을 입력해 주세요.");
		}
		if (trackingDays < MIN_TRACKING_DAYS || trackingDays > MAX_TRACKING_DAYS) {
			throw V1ApiException.validation("모니터링 기간은 1일 이상 90일 이하로 입력해 주세요.");
		}
		return trackingDays;
	}

	/**
	 * 추가 결과 — {@code accepted}는 "레거시 등록으로 아이템이 새로 생겼다"는 뜻이고, 컨트롤러가
	 * 그때만 202를 낸다(§8: 동기 완결 200, 등록 생성 섞이면 202).
	 */
	public record Added(V2CampaignContentsResponse body, boolean accepted) {
	}

	/**
	 * 입력 1건의 1차 판정 — {@code repeat}이면 같은 요청의 앞선 같은 contentId를 잇고,
	 * {@code delegatedIndex}가 null이면 위임 없이 확정된 건({@code settled}가 최종),
	 * 아니면 레거시 위임 목록에서의 인덱스다.
	 */
	private record Plan(String contentId, Integer delegatedIndex, boolean repeat, Result settled) {

		static Plan settled(Result result) {
			return new Plan(result.contentId(), null, false, result);
		}

		static Plan repeat(String contentId) {
			return new Plan(contentId, null, true, null);
		}
	}
}
