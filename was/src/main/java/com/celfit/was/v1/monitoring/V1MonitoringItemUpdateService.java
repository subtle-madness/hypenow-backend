package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.KeywordRule;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.ProfileSnapshotRow;
import com.celfit.was.monitoring.TargetRow;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 행 수정(6.29)·모니터링 취소(6.30) 비즈니스 로직. 진행 중 판정은 {@link ItemStatus}(유도 헬퍼)를
 * 공용으로 쓴다. target 확정 행의 완전 조립(post·recentComments·profileImageUrl 등)은 6.26
 * 어셈블러 후속 태스크 몫이라, 여기서는 현재 조회 표면(target·profile_snapshot)에서 바로 얻을 수
 * 있는 필드만 채운다({@link TrackingItemResponse#interim}, post는 항상 null — TODO).
 *
 * <p>{@link MonitoringReadRepository}·{@link MonitoringCommandClient}는 monitoring.enabled=false면
 * 빈 자체가 없다(MonitoringConfig 조건부) — 이 서비스는 항상 뜨는 컨트롤러 배선을 지원해야 하므로
 * Optional 주입으로 받는다. 실제로 비어 있는 채 target 확정 행(targetId != null)에 접근하는 경우는
 * "한때 활성이었다가 disable된" 운영상 불가능에 가까운 경합뿐이라 방어적으로 예외만 던진다.
 */
@Service
public class V1MonitoringItemUpdateService {

	private static final int MIN_TRACKING_DAYS = 1;
	private static final int MAX_TRACKING_DAYS = 90;
	private static final String MODE_ACCOUNT = "account";

	/** 6.29 trackingDays 변경 허용 상태 — "진행 중" = 종결(not_uploaded/ended/hidden) 아닌 4종. */
	private static final Set<String> IN_PROGRESS_STATUSES =
			Set.of(ItemStatus.COLLECTING, ItemStatus.DETECTING, ItemStatus.TRACKING, ItemStatus.ERROR);
	/** 6.30 cancel 허용 상태 — collecting은 제외(계약 표 그대로: detecting/tracking/error만). */
	private static final Set<String> CANCELABLE_STATUSES =
			Set.of(ItemStatus.DETECTING, ItemStatus.TRACKING, ItemStatus.ERROR);

	private final MonitoringItemRepository itemRepository;
	private final CampaignRepository campaignRepository;
	private final V1CampaignService campaignService;
	private final Optional<MonitoringReadRepository> readRepository;
	private final Optional<MonitoringCommandClient> commandClient;
	private final ObjectMapper objectMapper;

	public V1MonitoringItemUpdateService(MonitoringItemRepository itemRepository, CampaignRepository campaignRepository,
			V1CampaignService campaignService, Optional<MonitoringReadRepository> readRepository,
			Optional<MonitoringCommandClient> commandClient, ObjectMapper objectMapper) {
		this.itemRepository = itemRepository;
		this.campaignRepository = campaignRepository;
		this.campaignService = campaignService;
		this.readRepository = readRepository;
		this.commandClient = commandClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * 편집 대상은 기간(trackingDays)과 캠페인(campaignId/campaignName) 둘뿐(스펙 6.29). 캠페인은
	 * 모든 상태에서 허용, 기간은 진행 중 상태 + 미래 종료일만 허용. campaignName으로 캠페인이 새로
	 * 생성된 경우에만 응답에 campaign을 동봉한다(6.27과 동일 규약).
	 *
	 * <p><b>순서 불변식(2026-07-30 리뷰 픽스)</b>: 되돌릴 수 없는 원격 부수효과(monitoring extend)는
	 * 이 요청의 검증·해석이 전부 끝난 뒤에만 낸다. trackingDays·campaign 두 필드가 함께 오는 정상
	 * 경로(수정 모달)에서, trackingDays를 먼저 원격 확정해 버리면 뒤이은 campaign 검증 실패(404·400)로
	 * 트랜잭션이 롤백돼도 monitoring 쪽 연장은 이미 반영된 채라 로컬·원격이 결정론적으로 어긋난다.
	 * 그래서 1단계(validateTrackingDays·resolveCampaignChange)는 전부 순수 검증/조회이거나 롤백
	 * 안전한 로컬 쓰기(resolveOrCreate의 캠페인 insert — 같은 트랜잭션이라 실패 시 함께 롤백)만 하고,
	 * 2단계에서만 extend 호출 → app 갱신 순서로 실제 부수효과를 낸다.
	 */
	@Transactional
	public MonitoringItemPatchResponse patch(long userId, long itemId, Map<String, Object> body) {
		MonitoringItemRow item = itemRepository.findByIdAndUser(itemId, userId)
				.orElseThrow(() -> V1ApiException.notFound("대상을 찾을 수 없습니다."));

		boolean hasCampaignId = body.containsKey("campaignId");
		boolean hasCampaignName = body.containsKey("campaignName");
		if (hasCampaignId && hasCampaignName) {
			throw V1ApiException.validation("campaignId와 campaignName을 동시에 지정할 수 없어요.");
		}

		// 1단계 — 검증·해석만(원격 호출 없음). 여기서 던지는 예외는 아직 아무 부수효과도 없어 안전하다.
		Integer newTrackingDays = body.containsKey("trackingDays")
				? validateTrackingDays(item, body.get("trackingDays"))
				: null;
		CampaignChange campaignChange = resolveCampaignChange(userId, hasCampaignId, hasCampaignName,
				body.get("campaignId"), body.get("campaignName"));

		// 2단계 — 검증 통과 확정. 이제부터 원격 extend(되돌릴 수 없음) → 로컬 갱신 순서로 부수효과를 낸다.
		int trackingDays = item.trackingDays();
		if (newTrackingDays != null) {
			trackingDays = newTrackingDays;
			if (item.targetId() != null) {
				OffsetDateTime expiresAt = MonitoringExpiry.computeExpiresAt(item.registeredOn(), trackingDays);
				requireCommandClient().extend(item.targetId(), expiresAt);
			}
			itemRepository.updateTrackingDays(item.id(), trackingDays);
		}

		Long campaignId = item.campaignId();
		String campaignName = resolveCampaignName(item.campaignId(), userId);
		CampaignResponse newlyCreated = null;
		if (campaignChange != null) {
			campaignId = campaignChange.campaignId();
			campaignName = campaignChange.campaignName();
			newlyCreated = campaignChange.newlyCreated();
			itemRepository.updateCampaign(itemId, campaignId);
		}

		TrackingItemResponse response = assembleAfterPatch(item, trackingDays, campaignId, campaignName);
		return MonitoringItemPatchResponse.of(response, newlyCreated);
	}

	/**
	 * 허용 상태(유도) ∈ {detecting, tracking, error}. target 확정 행은 monitoring cancel(멱등) 성공
	 * 후 markCanceled, pending 행(target 미확정 — detecting만 도달 가능)은 monitoring 호출 없이
	 * markCanceled만 한다(계약: pending url 모드의 유도 상태 collecting은 애초에 취소 대상 밖).
	 */
	@Transactional
	public TrackingItemResponse cancel(long userId, long itemId) {
		MonitoringItemRow item = itemRepository.findByIdAndUser(itemId, userId)
				.orElseThrow(() -> V1ApiException.notFound("대상을 찾을 수 없습니다."));

		TargetRow target = fetchTarget(item);
		String status = ItemStatus.derive(item, target);
		if (!CANCELABLE_STATUSES.contains(status)) {
			throw V1ApiException.validation("현재 상태(" + ItemStatus.label(status) + ")에서는 취소할 수 없어요.");
		}

		if (item.targetId() != null) {
			requireCommandClient().cancel(item.targetId());
		}
		itemRepository.markCanceled(itemId, status, OffsetDateTime.now(KstTimestamps.KST));

		String newStatus = ItemStatus.DETECTING.equals(status) ? ItemStatus.NOT_UPLOADED : ItemStatus.ENDED;
		String campaignName = resolveCampaignName(item.campaignId(), userId);
		return assembleInterim(item, target, newStatus, item.trackingDays(), item.campaignId(), campaignName);
	}

	/**
	 * trackingDays 검증만(범위·미래 종료일·진행 중 상태) — 원격 호출·로컬 쓰기 없음(순서 불변식,
	 * patch() 클래스 javadoc 참조). 호출부가 검증 통과를 확인한 뒤에야 extend·updateTrackingDays를 낸다.
	 */
	private int validateTrackingDays(MonitoringItemRow item, Object raw) {
		int trackingDays = parseTrackingDays(raw);
		LocalDate endDate = item.registeredOn().plusDays(trackingDays);
		LocalDate today = LocalDate.now(KstTimestamps.KST);
		if (!endDate.isAfter(today)) {
			throw V1ApiException.validation("모니터링 종료일은 오늘 이후여야 해요.");
		}
		String status = ItemStatus.derive(item, fetchTarget(item));
		if (!IN_PROGRESS_STATUSES.contains(status)) {
			throw V1ApiException.validation("현재 상태(" + ItemStatus.label(status) + ")에서는 기간을 변경할 수 없어요.");
		}
		return trackingDays;
	}

	/**
	 * 캠페인 필드 검증·해석만 — campaignId 부재는 404(즉시 던짐), campaignName은 resolveOrCreate까지
	 * 마친다(신규 insert는 로컬 트랜잭션 안이라 이후 다른 검증이 실패해도 함께 롤백돼 안전, 순서
	 * 불변식 javadoc 참조). null 반환은 "캠페인 필드 미지정 — 기존 값 유지"를 뜻한다(호출부가
	 * item.campaignId() 폴백).
	 */
	private CampaignChange resolveCampaignChange(long userId, boolean hasCampaignId, boolean hasCampaignName,
			Object campaignIdRaw, Object campaignNameRaw) {
		if (hasCampaignName) {
			if (campaignNameRaw == null) {
				return new CampaignChange(null, null, null);
			}
			V1CampaignService.Resolved resolved = campaignService.resolveOrCreate(userId, campaignNameRaw.toString());
			CampaignResponse newlyCreated = resolved.created() ? CampaignResponse.from(resolved.row()) : null;
			return new CampaignChange(resolved.row().id(), resolved.row().name(), newlyCreated);
		}
		if (hasCampaignId) {
			if (campaignIdRaw == null) {
				return new CampaignChange(null, null, null);
			}
			CampaignRow campaign = campaignRepository.findByIdAndUser(parseCampaignId(campaignIdRaw), userId)
					.orElseThrow(() -> V1ApiException.notFound("캠페인을 찾을 수 없습니다."));
			return new CampaignChange(campaign.id(), campaign.name(), null);
		}
		return null;
	}

	/** resolveCampaignChange 결과 — campaignId==null && campaignName==null은 "해제"와 "미확정" 둘 다 표현 가능하므로 이 레코드 자체의 존재(null 아님)로 "변경 요청 있음"을 구분한다. */
	private record CampaignChange(Long campaignId, String campaignName, CampaignResponse newlyCreated) {
	}

	private TrackingItemResponse assembleAfterPatch(MonitoringItemRow item, int trackingDays, Long campaignId,
			String campaignName) {
		if (item.canceledAt() == null && item.targetId() == null) {
			// PATCH는 취소를 유발하지 않으므로 pending 행의 유도 상태는 항상 mode 그대로
			// (url→collecting/account→detecting) — 기존 pending 팩토리를 그대로 재사용한다.
			OffsetDateTime nextCheckAt = OffsetDateTime.now(KstTimestamps.KST).plusMinutes(5);
			if (MODE_ACCOUNT.equals(item.mode())) {
				return TrackingItemResponse.pendingAccount(item.id(), item.inputValue(),
						readKeywordRule(item.keywords()), campaignId, campaignName, item.registeredOn(), trackingDays,
						nextCheckAt);
			}
			return TrackingItemResponse.pendingPost(item.id(), item.sourceUrl(), campaignId, campaignName,
					item.registeredOn(), trackingDays, nextCheckAt);
		}
		TargetRow target = fetchTarget(item);
		String status = ItemStatus.derive(item, target);
		return assembleInterim(item, target, status, trackingDays, campaignId, campaignName);
	}

	/** 취소로 종결된 pending 행(status가 not_uploaded — pendingAccount의 고정 status로 표현 불가) 포함, 공용 조립. */
	private TrackingItemResponse assembleInterim(MonitoringItemRow item, TargetRow target, String status,
			int trackingDays, Long campaignId, String campaignName) {
		String handle;
		String displayName;
		Long followers = null;
		if (target != null) {
			handle = target.username().toLowerCase(Locale.ROOT);
			displayName = handle;
			followers = latestFollowers(target.username());
		} else if (MODE_ACCOUNT.equals(item.mode())) {
			handle = item.inputValue();   // 등록 입력 그대로 정규화된 소문자 핸들(registration 단계에서 이미 정규화)
			displayName = handle;
		} else {
			handle = "";
			displayName = "";
		}
		TrackingItemResponse.Keywords keywords =
				MODE_ACCOUNT.equals(item.mode()) ? TrackingItemResponse.Keywords.from(readKeywordRule(item.keywords())) : null;
		// TODO(6.26 어셈블러): profileImageUrl·lastUploadedAt은 profile_meta 조회 표면이 붙으면 채운다.
		OffsetDateTime nextCheckAt = ItemStatus.DETECTING.equals(status) ? nextSweepAt() : null;
		return TrackingItemResponse.interim(item.id(), item.mode(), status, handle, displayName, null, followers,
				null, campaignId, campaignName, item.sourceUrl(), item.registeredOn(), trackingDays, keywords,
				nextCheckAt);
	}

	private TargetRow fetchTarget(MonitoringItemRow item) {
		if (item.targetId() == null || readRepository.isEmpty()) {
			return null;
		}
		List<TargetRow> rows = readRepository.get().findTargets(List.of(item.targetId()));
		return rows.isEmpty() ? null : rows.get(0);
	}

	private Long latestFollowers(String username) {
		if (readRepository.isEmpty()) {
			return null;
		}
		List<ProfileSnapshotRow> rows = readRepository.get().profileTimeseries(username);
		return rows.isEmpty() ? null : rows.get(rows.size() - 1).followers();
	}

	private MonitoringCommandClient requireCommandClient() {
		return commandClient.orElseThrow(
				() -> new IllegalStateException("monitoring 서브시스템 비활성 상태에서 target 확정 행에 접근했습니다."));
	}

	private String resolveCampaignName(Long campaignId, long userId) {
		if (campaignId == null) {
			return null;
		}
		return campaignRepository.findByIdAndUser(campaignId, userId).map(CampaignRow::name).orElse(null);
	}

	/** 다음 일일 스윕(KST 02:00) 예정 시각 — detecting 상태의 nextCheckAt 근사값. */
	private static OffsetDateTime nextSweepAt() {
		ZonedDateTime now = ZonedDateTime.now(KstTimestamps.KST);
		ZonedDateTime today2am = now.toLocalDate().atTime(2, 0).atZone(KstTimestamps.KST);
		ZonedDateTime next = now.isBefore(today2am) ? today2am : today2am.plusDays(1);
		return next.toOffsetDateTime();
	}

	private static int parseTrackingDays(Object raw) {
		if (raw == null) {
			throw V1ApiException.validation("모니터링 기간을 입력해 주세요.");
		}
		if (!(raw instanceof Integer) && !(raw instanceof Long)) {
			throw V1ApiException.validation("모니터링 기간은 정수로 입력해 주세요.");
		}
		long value = ((Number) raw).longValue();
		if (value < MIN_TRACKING_DAYS || value > MAX_TRACKING_DAYS) {
			throw V1ApiException.validation("모니터링 기간은 1일 이상 90일 이하로 입력해 주세요.");
		}
		return (int) value;
	}

	private static long parseCampaignId(Object raw) {
		try {
			return Long.parseLong(raw.toString());
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("캠페인을 찾을 수 없습니다.");
		}
	}

	/** 저장 키는 or지만 계약·모니터링 어휘는 any다(MonitoringRegistrationExecutor.readKeywordRule와 동일 변환). */
	private KeywordRule readKeywordRule(String keywordsJson) {
		Map<?, ?> map = objectMapper.readValue(keywordsJson, Map.class);
		return new KeywordRule(castList(map.get("and")), castList(map.get("or")), castList(map.get("exclude")));
	}

	@SuppressWarnings("unchecked")
	private static List<String> castList(Object raw) {
		return raw == null ? List.of() : (List<String>) raw;
	}
}
