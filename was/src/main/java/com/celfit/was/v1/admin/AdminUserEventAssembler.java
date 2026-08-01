package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.monitoring.TargetRow;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.monitoring.MonitoringInput;
import com.celfit.was.v1.saved.V1SavedRepository;
import com.celfit.was.v1.saved.V1SavedRepository.SavedContentRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * GET /v1/admin/users/{id} events[] 조립(설계 2026-08-01 §4·§0 A5·B3) — 5종 이벤트를 한 타임라인으로
 * 합쳐 at 내림차순 정렬 후 최근 100건에서 절단한다. app·분석 결과·monitoring 세 갈래를 각각 조회해
 * Java에서 결합한다(§4-4 크로스 스키마 조인 금지).
 */
@Component
public class AdminUserEventAssembler {

	private static final int EVENT_LIMIT = 100;   // A5
	private static final String KIND_ACCOUNT = "account";
	private static final String KIND_POST = "post";

	private final CampaignRepository campaignRepository;
	private final RegistrationRepository registrationRepository;
	private final MonitoringItemRepository itemRepository;
	private final Optional<MonitoringReadRepository> readRepository;
	private final V1SavedRepository savedRepository;

	public AdminUserEventAssembler(CampaignRepository campaignRepository,
			RegistrationRepository registrationRepository, MonitoringItemRepository itemRepository,
			Optional<MonitoringReadRepository> readRepository, V1SavedRepository savedRepository) {
		this.campaignRepository = campaignRepository;
		this.registrationRepository = registrationRepository;
		this.itemRepository = itemRepository;
		this.readRepository = readRepository;
		this.savedRepository = savedRepository;
	}

	public List<AdminUserEvent> assemble(AdminUserRow user, String signupCode) {
		List<RawEvent> raw = new ArrayList<>();
		raw.add(new RawEvent("signup", signupLabel(signupCode, user.signupRoute()), user.createdAt()));

		for (CampaignRow campaign : campaignRepository.findByUser(user.id())) {
			raw.add(new RawEvent("campaign_created", "캠페인 '" + campaign.name() + "' 생성", campaign.createdAt()));
		}

		raw.addAll(monitoringRegisteredEvents(user.id()));
		raw.addAll(contentSavedEvents(user.id()));
		raw.addAll(influencerSavedEvents(user.id()));

		return raw.stream()
				.sorted(Comparator.comparing(RawEvent::at).reversed())
				.limit(EVENT_LIMIT)
				.map(r -> new AdminUserEvent(r.type(), r.label(), KstTimestamps.toKstIso(r.at())))
				.toList();
	}

	// --- signup ---

	private static String signupLabel(String signupCode, String signupRoute) {
		if (signupCode != null) {
			return "가입 (초대코드 " + signupCode + ")";
		}
		if (signupRoute != null) {
			return "가입 (" + signupRouteLabel(signupRoute) + ")";
		}
		return "가입";
	}

	private static String signupRouteLabel(String signupRoute) {
		return switch (signupRoute) {
			case "portal_search" -> "포털 검색";
			case "blog_community" -> "블로그·커뮤니티";
			case "pr_article" -> "기사";
			case "social_media" -> "소셜미디어";
			case "offline_event" -> "오프라인 행사";
			case "referral" -> "레퍼럴";
			case "other" -> "기타";
			default -> signupRoute;
		};
	}

	// --- monitoring_registered (설계 §0 B3) ---

	/**
	 * 등록 요청 헤더 단위 1이벤트 — entries의 kind·input·resolved(item_id → target.username)로
	 * 핸들을 최선 표기한다(§4). post 항목은 등록 시점엔 핸들을 모르지만, 조회 시점엔 감지가 끝나
	 * target이 확정돼 있을 수 있어 item_id를 거쳐 target까지 역추적한다.
	 */
	private List<RawEvent> monitoringRegisteredEvents(long userId) {
		List<RegistrationRow> registrations = registrationRepository.findRecentByUser(userId, EVENT_LIMIT);
		if (registrations.isEmpty()) {
			return List.of();
		}
		Set<Long> itemIds = registrations.stream().flatMap(r -> r.entries().stream())
				.map(RegistrationEntryRow::itemId).filter(Objects::nonNull).collect(Collectors.toSet());
		Map<Long, MonitoringItemRow> itemsById = itemIds.isEmpty() ? Map.of()
				: itemRepository.findByIds(itemIds).stream().collect(Collectors.toMap(MonitoringItemRow::id, i -> i));
		Set<Long> targetIds = itemsById.values().stream().map(MonitoringItemRow::targetId)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		Map<Long, TargetRow> targetsById = readRepository.map(r -> r.findTargets(targetIds)).orElseGet(List::of)
				.stream().collect(Collectors.toMap(TargetRow::id, t -> t));

		return registrations.stream()
				.map(r -> new RawEvent("monitoring_registered",
						monitoringRegisteredLabel(r.entries(), itemsById, targetsById), r.requestedAt()))
				.toList();
	}

	private String monitoringRegisteredLabel(List<RegistrationEntryRow> entries, Map<Long, MonitoringItemRow> itemsById,
			Map<Long, TargetRow> targetsById) {
		int n = entries.size();
		List<String> tokens = entries.stream().map(e -> tokenFor(e, itemsById, targetsById)).toList();
		Set<String> distinctNonNull = tokens.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
		boolean allSame = distinctNonNull.size() == 1
				&& tokens.stream().allMatch(t -> t != null && t.equals(tokens.get(0)));

		if (allSame) {
			String handle = tokens.get(0);
			String typeWord = commonTypeWord(entries);
			return typeWord != null
					? "모니터링 등록 (%s %s %d건)".formatted(handle, typeWord, n)
					: "모니터링 등록 (%s %d건)".formatted(handle, n);
		}

		Optional<String> anchor = tokens.stream().filter(Objects::nonNull).findFirst();
		if (anchor.isPresent()) {
			return n == 1
					? "모니터링 등록 (%s 1건)".formatted(anchor.get())
					: "모니터링 등록 (%s 외 %d건)".formatted(anchor.get(), n - 1);
		}
		// 핸들을 전혀 특정하지 못한 완전 폴백(설계서 미기재 — post 전량이 아직 감지 전인 방어적 케이스).
		return "모니터링 등록 (%d건)".formatted(n);
	}

	/** entry 표시 토큰(§4 "핸들") — account는 입력 그대로, post는 item→target 역추적으로 최선 표기, 실패하면 null. */
	private static String tokenFor(RegistrationEntryRow entry, Map<Long, MonitoringItemRow> itemsById,
			Map<Long, TargetRow> targetsById) {
		if (KIND_ACCOUNT.equals(entry.kind())) {
			return "@" + normalizeHandleText(entry.input());
		}
		Long itemId = entry.itemId();
		if (itemId == null) {
			return null;
		}
		MonitoringItemRow item = itemsById.get(itemId);
		if (item == null || item.targetId() == null) {
			return null;
		}
		TargetRow target = targetsById.get(item.targetId());
		return target == null ? null : "@" + target.username().toLowerCase(Locale.ROOT);
	}

	private static String normalizeHandleText(String raw) {
		String trimmed = raw == null ? "" : raw.trim();
		String stripped = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
		return stripped.toLowerCase(Locale.ROOT);
	}

	/** 유형(§4) — post 항목만 URL 세그먼트(reel/reels vs p)로 결정 가능, account·share·invalid는 null(생략). */
	private String commonTypeWord(List<RegistrationEntryRow> entries) {
		List<String> words = entries.stream().map(this::contentTypeWordFor).toList();
		if (words.stream().anyMatch(Objects::isNull)) {
			return null;
		}
		Set<String> distinct = new HashSet<>(words);
		return distinct.size() == 1 ? distinct.iterator().next() : null;
	}

	private String contentTypeWordFor(RegistrationEntryRow entry) {
		if (!KIND_POST.equals(entry.kind())) {
			return null;
		}
		MonitoringInput parsed = MonitoringInput.parsePost(entry.input());
		if (parsed instanceof MonitoringInput.Post post) {
			return post.canonicalUrl().contains("/reel/") ? "릴스" : "피드";
		}
		return null;
	}

	// --- content_saved ---

	private List<RawEvent> contentSavedEvents(long userId) {
		List<SavedContentRow> saved = savedRepository.findSavedContents(userId);
		if (saved.isEmpty()) {
			return List.of();
		}
		List<String> shortCodes = saved.stream().map(SavedContentRow::shortCode).toList();
		Map<String, ContentCardRow> cardsByCode = savedRepository.findCards(shortCodes).stream()
				.collect(Collectors.toMap(ContentCardRow::shortCode, c -> c));
		return saved.stream()
				.map(row -> new RawEvent("content_saved", contentSavedLabel(cardsByCode.get(row.shortCode())),
						row.createdAt()))
				.toList();
	}

	private static String contentSavedLabel(ContentCardRow card) {
		if (card == null) {
			return "콘텐츠 저장";
		}
		String typeWord = "REELS".equalsIgnoreCase(card.contentType()) ? "릴스" : "피드";
		return "콘텐츠 저장 (@%s %s)".formatted(card.handle(), typeWord);
	}

	// --- influencer_saved ---

	private List<RawEvent> influencerSavedEvents(long userId) {
		return savedRepository.findSavedInfluencers(userId).stream()
				.map(row -> new RawEvent("influencer_saved", "인플루언서 저장 (@%s)".formatted(row.handle()), row.createdAt()))
				.toList();
	}

	private record RawEvent(String type, String label, OffsetDateTime at) {
	}
}
