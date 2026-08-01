package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.PostMetaRow;
import com.celfit.was.monitoring.TargetRow;
import com.celfit.was.monitoring.TrackedSnapshotRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 어드민 모니터링 상태 공유 서비스(설계 2026-08-01 §4) — GET /v1/admin/users의 health와
 * GET /v1/admin/monitoring/registrations의 rows·counts가 반드시 같은 모집단·같은 상태 유도를
 * 쓰도록 {@link #computeActiveRows()} 하나로 합류시킨다. 조립은 TrackingItemAssembler 관용구와
 * 동일(app 행 + monitoring target/post_meta/snapshot을 각각 배치 조회해 Java에서 결합, 크로스 DB
 * 조인 없음) — 다만 유저 스코프가 없다(전체 유저 대상, 어드민 전용).
 */
@Service
public class AdminMonitoringHealthService {

	private static final String MODE_URL = "url";
	private static final String MODE_ACCOUNT = "account";
	private static final String CONTENT_TYPE_REELS = "REELS";

	private final MonitoringItemRepository itemRepository;
	private final Optional<MonitoringReadRepository> readRepository;

	public AdminMonitoringHealthService(MonitoringItemRepository itemRepository,
			Optional<MonitoringReadRepository> readRepository) {
		this.itemRepository = itemRepository;
		this.readRepository = readRepository;
	}

	/**
	 * 활성 행 전체(전 유저) — monitoring 미기동(readRepository 비어있음)이면 target 배치 조회가
	 * 전부 빈 결과로 폴백돼 모든 행이 pending 규칙만으로 판정된다(§4 "monitoring 미기동 → 활성 행
	 * 판정은 전부 pending 규칙, 상태는 healthy" 요건이 자연히 성립).
	 */
	public List<AdminMonitoringRow> computeActiveRows() {
		List<MonitoringItemRow> items = itemRepository.findAllNotCanceled();
		Set<Long> targetIds =
				items.stream().map(MonitoringItemRow::targetId).filter(Objects::nonNull).collect(Collectors.toSet());
		Map<Long, TargetRow> targetsById = readRepository.map(r -> r.findTargets(targetIds)).orElseGet(List::of)
				.stream().collect(Collectors.toMap(TargetRow::id, t -> t));

		LocalDate today = LocalDate.now(KstTimestamps.KST);
		List<MonitoringItemRow> active = items.stream()
				.filter(item -> AdminMonitoringStatus.isActive(item, targetsById.get(item.targetId()), today))
				.toList();
		if (active.isEmpty()) {
			return List.of();
		}

		Set<String> shortCodes = active.stream()
				.map(item -> targetsById.get(item.targetId()))
				.filter(Objects::nonNull)
				.map(TargetRow::trackedShortCode)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<String, PostMetaRow> postMetaByCode = readRepository.map(r -> r.findPostMeta(shortCodes)).orElseGet(List::of)
				.stream().collect(Collectors.toMap(PostMetaRow::shortCode, r -> r));
		Map<String, LocalDate> latestSnapshotByCode =
				readRepository.map(r -> r.findLatestSnapshots(shortCodes)).orElseGet(List::of).stream()
						.collect(Collectors.toMap(TrackedSnapshotRow::shortCode, TrackedSnapshotRow::capturedOn));

		OffsetDateTime now = OffsetDateTime.now(KstTimestamps.KST);
		return active.stream()
				.map(item -> assembleRow(item, targetsById.get(item.targetId()), today, now, postMetaByCode,
						latestSnapshotByCode))
				.toList();
	}

	/** counts(§4) — 5키 전부 항상 포함(값 없으면 0). 호출부가 status 필터 반영 전 rows에 대해 불러야 한다. */
	public Map<String, Long> counts(List<AdminMonitoringRow> rows) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String key : AdminMonitoringStatus.STATUS_ORDER) {
			counts.put(key, 0L);
		}
		for (AdminMonitoringRow row : rows) {
			counts.merge(row.status(), 1L, Long::sum);
		}
		return counts;
	}

	/**
	 * GET /v1/admin/users의 health(§4) — 유저별 error 행 ≥1 → "error", 아니면 hidden_or_deleted
	 * ≥1 → "hidden", 그 외 "ok". detect_stalled·collect_stalled는 반영하지 않는다(계약 명시).
	 * 활성 행이 하나도 없는 유저는 이 맵에 없으므로 호출부가 getOrDefault(id, "ok")로 마무리한다.
	 */
	public Map<Long, String> healthByUser(List<AdminMonitoringRow> rows) {
		Map<Long, List<AdminMonitoringRow>> byUser =
				rows.stream().collect(Collectors.groupingBy(AdminMonitoringRow::userId));
		Map<Long, String> result = new HashMap<>();
		for (Map.Entry<Long, List<AdminMonitoringRow>> entry : byUser.entrySet()) {
			boolean hasError =
					entry.getValue().stream().anyMatch(r -> AdminMonitoringStatus.ERROR.equals(r.status()));
			boolean hasHidden = entry.getValue().stream()
					.anyMatch(r -> AdminMonitoringStatus.HIDDEN_OR_DELETED.equals(r.status()));
			result.put(entry.getKey(), hasError ? "error" : hasHidden ? "hidden" : "ok");
		}
		return result;
	}

	private AdminMonitoringRow assembleRow(MonitoringItemRow item, TargetRow target, LocalDate today,
			OffsetDateTime now, Map<String, PostMetaRow> postMetaByCode, Map<String, LocalDate> latestSnapshotByCode) {
		String shortCode = target == null ? null : target.trackedShortCode();
		LocalDate latestSnapshotDate = shortCode == null ? null : latestSnapshotByCode.get(shortCode);
		String status = AdminMonitoringStatus.deriveStatus(item, target, today, now, latestSnapshotDate);
		String contentType = resolveContentType(target, postMetaByCode);
		String handle = resolveHandle(item, target);
		String postUrl = resolvePostUrl(item, target, contentType);
		OffsetDateTime lastCollectedAt = toKstMidnight(latestSnapshotDate);
		return new AdminMonitoringRow(item.id(), item.userId(), item.campaignId(), status, handle, contentType,
				lastCollectedAt, postUrl);
	}

	/** handle(§4) — target 있으면 "@"+username, account pending이면 "@"+input_value, url pending이면 "". */
	private static String resolveHandle(MonitoringItemRow item, TargetRow target) {
		if (target != null) {
			return "@" + target.username().toLowerCase(Locale.ROOT);
		}
		if (MODE_ACCOUNT.equals(item.mode())) {
			return "@" + item.inputValue().toLowerCase(Locale.ROOT);
		}
		return "";
	}

	/** contentType(설계 B1) — post_meta 기준, 감지 전·불명(post_meta 없음)은 "reels" 잠정. */
	private static String resolveContentType(TargetRow target, Map<String, PostMetaRow> postMetaByCode) {
		if (target == null || target.trackedShortCode() == null) {
			return "reels";
		}
		PostMetaRow meta = postMetaByCode.get(target.trackedShortCode());
		if (meta != null && !CONTENT_TYPE_REELS.equalsIgnoreCase(meta.contentType())) {
			return "feed";
		}
		return "reels";
	}

	/**
	 * postUrl(§4) — TrackingItemAssembler.buildPostUrl과 같은 조립(그쪽은 private이라 복제, 단순
	 * 문자열 조합이라 중복 비용이 작다): url 모드는 source_url 원문, 그 외는 세그먼트+shortCode 조립.
	 * tracked_short_code가 없으면(게시물 미특정) null — hidden_or_deleted도 이 조건 하나로 단순화(§4).
	 */
	private static String resolvePostUrl(MonitoringItemRow item, TargetRow target, String contentType) {
		if (target == null || target.trackedShortCode() == null) {
			return null;
		}
		if (MODE_URL.equals(item.mode()) && item.sourceUrl() != null) {
			return item.sourceUrl();
		}
		String segment = "reels".equals(contentType) ? "reel" : "p";
		return "https://www.instagram.com/" + segment + "/" + target.trackedShortCode() + "/";
	}

	private static OffsetDateTime toKstMidnight(LocalDate date) {
		return date == null ? null : date.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}
}
