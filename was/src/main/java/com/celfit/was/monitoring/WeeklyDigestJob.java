package com.celfit.was.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 주간 다이제스트 생성 크론(2026-08-27 주간 개편 §2·§4·§8) — 매주 월요일 09:00 KST에 <b>지난주
 * (월~일)</b>를 집계해 app.monitoring_digests에 (user_id, 주 시작일) 멱등 upsert한다.
 * 일일 다이제스트(구 DigestJob)를 대체한다.
 *
 * <h2>이벤트 원장 없이 "주간 조회"</h2>
 * 주간 리듬에서는 실시간 이벤트 적재가 대부분 불필요하다(설계 §4). 이 잡이 정본 테이블을 기간
 * 조회한다: 브랜드 발견분은 brand_tagged_post·brand_hashtag_post, 미표기 판정은 brand_post_meta,
 * 콘텐츠 알림 4종만 기존 alarm_event 원장이다(지표 숨김 같은 상태 전이는 소급 조회가 불가능해
 * 적재를 유지하고 소비 리듬만 주간으로 옮겼다).
 *
 * <h2>명시적 창 — 벽시계 유도를 쓰지 않는다</h2>
 * {@link #runFor(WeekWindow)}가 본체이고 스케줄 진입점 둘은 같은 창을 계산해 넘긴다. 창 계산이
 * "그 주 어느 요일에 불러도 직전 주"라 09:00 정시 실행과 따라잡기 틱이 전부 같은 구간을 다시
 * 집계한다 — 다이제스트 자정 경계 유실(트랙 GG)과 같은 계열의 사고를 구조적으로 막는다(설계 §8).
 *
 * <h2>멱등 — 워터마크가 없다</h2>
 * {@link DigestRepository#upsert}가 (user_id, digest_date) 유니크로 재실행을 안전하게 만든다.
 * 같은 주를 몇 번 다시 돌려도 행이 늘지 않고 items만 최신 집계로 덮인다(created_at·read_at은
 * SET 절에 없어 보존). 유일한 부작용 기록은 미표기 알림 이력인데, 그것도 "이번 주가 아닌 주에
 * 알린 것만 제외"라 같은 주 재실행이 자기 기록에 걸리지 않는다.
 *
 * <h2>이벤트 0건이면 미생성</h2>
 * 조립 결과가 빈 목록이면 upsert 자체를 하지 않는다(설계 §2 "이벤트 0건이면 그 주는 알림 미생성").
 * 브랜드 연결만 있고 소식이 없는 유저에게 빈 알림이 매주 가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class WeeklyDigestJob {

	private static final Logger log = LoggerFactory.getLogger(WeeklyDigestJob.class);
	private static final String COLLECTION_STARTED = "COLLECTION_STARTED";
	private static final String COLLECTION_ENDED = "COLLECTION_ENDED";

	private final MonitoringReadRepository monitoringRead;
	private final BrandReadRepository brandRead;
	private final BrandLinkRepository brandLinks;
	private final BrandDirectPostRepository brandDirectPosts;
	private final MonitoringItemRepository monitoringItems;
	private final AdDisclosureNoticeRepository adNotices;
	private final DigestRepository digests;
	private final WeeklyDigestAssembler assembler;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	/** 광고 표기 판정 노출 킬 스위치 — FE와 같은 키를 본다(설계 §8 "킬 스위치 정합"). */
	private final boolean exposeAdDisclosure;

	public WeeklyDigestJob(MonitoringReadRepository monitoringRead, BrandReadRepository brandRead,
			BrandLinkRepository brandLinks, BrandDirectPostRepository brandDirectPosts,
			MonitoringItemRepository monitoringItems, AdDisclosureNoticeRepository adNotices,
			DigestRepository digests, WeeklyDigestAssembler assembler, ObjectMapper objectMapper, Clock clock,
			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		this.monitoringRead = monitoringRead;
		this.brandRead = brandRead;
		this.brandLinks = brandLinks;
		this.brandDirectPosts = brandDirectPosts;
		this.monitoringItems = monitoringItems;
		this.adNotices = adNotices;
		this.digests = digests;
		this.assembler = assembler;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.exposeAdDisclosure = exposeAdDisclosure;
	}

	/** 월요일 09:00 KST 정시 실행. */
	@Scheduled(cron = "${monitoring.digest.weekly-cron:0 0 9 * * MON}", zone = "Asia/Seoul")
	public void run() {
		runFor(currentWindow());
	}

	/**
	 * 따라잡기 틱(월요일 09:10~23:50, 매 10분) — {@link #run()}과 완전히 같은 재계산을 반복한다.
	 * 09:00 실행 이후 도착한 이벤트(늦게 끝난 스윕 등)를 같은 주 행으로 흡수하고, 메일 발송
	 * 실패분을 재시도할 창이기도 하다(Task 10에서 발송이 붙는다).
	 */
	@Scheduled(cron = "${monitoring.digest.weekly-catchup-cron:0 10,20,30,40,50 9-23 * * MON}", zone = "Asia/Seoul")
	public void catchUp() {
		runFor(currentWindow());
	}

	private WeekWindow currentWindow() {
		return WeekWindow.previousWeekOf(Instant.now(clock).atZone(KstTimestamps.KST).toLocalDate());
	}

	/**
	 * 창을 명시적으로 받는 본체. 대상 유저는 "지난주 알람 이벤트가 있는 유저" ∪ "활성 브랜드 연결이
	 * 있는 유저"다 — 전자만 보면 브랜드 소식·미표기만 있는 유저가 통째로 빠진다.
	 */
	public void runFor(WeekWindow window) {
		Map<Long, List<AlarmEventRow>> eventsByUser = monitoringRead
				.findAlarmEventsBetween(window.startDate(), window.endDateInclusive()).stream()
				.collect(Collectors.groupingBy(AlarmEventRow::userId, LinkedHashMap::new, Collectors.toList()));
		Map<Long, List<Long>> brandIdsByUser = brandLinks.findAllActive().stream()
				.collect(Collectors.groupingBy(BrandLinkRow::userId, LinkedHashMap::new,
						Collectors.mapping(BrandLinkRow::brandId, Collectors.toList())));
		Set<Long> userIds = new LinkedHashSet<>(eventsByUser.keySet());
		userIds.addAll(brandIdsByUser.keySet());

		int upserted = 0;
		for (long userId : userIds) {
			try {
				if (upsertWeekly(userId, window, eventsByUser.getOrDefault(userId, List.of()),
						brandIdsByUser.getOrDefault(userId, List.of()))) {
					upserted++;
				}
			} catch (RuntimeException e) {
				// 한 유저의 실패가 나머지를 막으면 장애 하나가 주간 알림 전체를 멈춘다(AlarmRecorder와 동일 정책).
				log.error("주간 다이제스트 생성 실패(격리) — user {}, week {}", userId, window.startDate(), e);
			}
		}
		log.info("주간 다이제스트 생성 완료 — 창 {}~{} KST, 대상 유저 {}명, upsert {}건",
				window.startDate(), window.endDateInclusive(), userIds.size(), upserted);
	}

	/**
	 * @return 다이제스트를 만들었으면 true, 내용이 없어 건너뛰었으면 false
	 *
	 * <p>불변식(품질 리뷰 이월 확인 #1): eventCounts와 endedPosts는 <b>같은 userEvents 목록</b>에서
	 * 유도된다 — eventCounts는 전체 그룹핑, endedPosts는 COLLECTION_ENDED로 필터링한 부분집합이다.
	 * 따라서 endedPosts가 비어있지 않으면 그 안에 최소 한 건의 COLLECTION_ENDED 이벤트가 실재했다는
	 * 뜻이고, eventCounts의 "collection_ended" 카운트도 같은 이벤트에서 나와 반드시 0보다 크다 —
	 * 두 값이 서로 다른 창을 보고 있어서 하이라이트 후보와 이벤트 카운트가 어긋나는 사고는
	 * 구조적으로 발생하지 않는다(brandNewPosts는 별도 조회원이지만 그 카운트도 같은 리스트
	 * (brandNewPosts.size())를 쓰므로 동일한 논리가 적용된다).
	 */
	private boolean upsertWeekly(long userId, WeekWindow window, List<AlarmEventRow> userEvents,
			List<Long> brandIds) {
		Map<String, Long> eventCounts = userEvents.stream().collect(Collectors.groupingBy(
				event -> MonitoringEventTypes.toFront(event.eventType()), Collectors.counting()));
		List<String> adShortCodes = adNotDisclosed(userId, window);
		List<DigestItem> items = assembler.assemble(new WeeklyDigestInput(eventCounts,
				brandNewPosts(brandIds, window), endedPosts(userEvents), adShortCodes,
				campaignNames(userId, userEvents)));
		if (items.isEmpty()) {
			return false;
		}
		// 이력은 실제로 알림에 실릴 때만 남긴다 — 조립 결과가 비면(도달 불가하지만 방어) 다음 주에 다시 기회를 준다.
		adNotices.markNotified(userId, adShortCodes, window.startDate());
		digests.upsert(userId, window.startDate(), objectMapper.writeValueAsString(items));
		return true;
	}

	/** 태그 발견분 + 해시태그 발견분(shortcode 중복 제거) — direct 등록분은 조회 단계에서 이미 빠졌다. */
	private List<WeeklyPostMetrics> brandNewPosts(List<Long> brandIds, WeekWindow window) {
		if (brandIds.isEmpty()) {
			return List.of();
		}
		Map<String, WeeklyPostMetrics> byShortCode = new LinkedHashMap<>();
		for (WeeklyPostMetrics post : brandRead.findTaggedPostsDiscoveredBetween(
				brandIds, window.from(), window.toExclusive())) {
			byShortCode.putIfAbsent(post.shortCode(), post);
		}
		for (WeeklyPostMetrics post : brandRead.findHashtagPostsDiscoveredBetween(
				brandIds, window.from(), window.toExclusive())) {
			// 태그 풀에 이미 있는 게시물이면 지표가 더 풍부한 태그 쪽(스냅샷 기반)을 남긴다.
			byShortCode.putIfAbsent(post.shortCode(), post);
		}
		return List.copyOf(byShortCode.values());
	}

	/** 수집 종료 이벤트의 target을 되짚어 추적 게시물의 최신 스냅샷 지표를 모은다. */
	private List<WeeklyPostMetrics> endedPosts(List<AlarmEventRow> userEvents) {
		List<Long> targetIds = userEvents.stream()
				.filter(event -> COLLECTION_ENDED.equals(event.eventType()))
				.map(AlarmEventRow::targetId)
				.distinct()
				.toList();
		if (targetIds.isEmpty()) {
			return List.of();
		}
		Map<String, String> authorByShortCode = new LinkedHashMap<>();
		for (TargetRow target : monitoringRead.findTargets(targetIds)) {
			if (target.trackedShortCode() != null) {
				authorByShortCode.putIfAbsent(target.trackedShortCode(), target.username());
			}
		}
		if (authorByShortCode.isEmpty()) {
			return List.of();
		}
		return monitoringRead.findLatestSnapshots(authorByShortCode.keySet()).stream()
				.map(snapshot -> new WeeklyPostMetrics(snapshot.shortCode(),
						authorByShortCode.get(snapshot.shortCode()), snapshot.contentType(),
						snapshot.views(), snapshot.likes(), snapshot.comments()))
				.toList();
	}

	/**
	 * 지난주 미표기 판정된 <b>등록(시딩) 게시물</b>. 스윕이 발견한 제3자 게시물은 대응 불가능한
	 * 소음이라 제외한다(설계 §2 "미표기 범위"). 등록 원장의 정본은 app.brand_direct_posts다.
	 * 킬 스위치가 꺼져 있으면 아예 조회하지 않는다 — FE 미노출 정보가 알림으로 새는 사고 방지(설계 §8).
	 */
	private List<String> adNotDisclosed(long userId, WeekWindow window) {
		if (!exposeAdDisclosure) {
			return List.of();
		}
		Set<String> registered = brandDirectPosts.shortCodesByUser(userId);
		if (registered.isEmpty()) {
			return List.of();
		}
		List<String> judged = brandRead.findNotDisclosedJudgedBetween(
				registered, window.from(), window.toExclusive());
		if (judged.isEmpty()) {
			return List.of();
		}
		Set<String> alreadyNotified = adNotices.findNotifiedInOtherWeek(userId, judged, window.startDate());
		return judged.stream().filter(shortCode -> !alreadyNotified.contains(shortCode)).toList();
	}

	/** 모니터링 진행 섹션 문안에 붙일 캠페인 이름(설계 §3). 유저 스코프는 조회가 건다. */
	private List<String> campaignNames(long userId, List<AlarmEventRow> userEvents) {
		List<Long> targetIds = userEvents.stream()
				.filter(event -> COLLECTION_STARTED.equals(event.eventType())
						|| COLLECTION_ENDED.equals(event.eventType()))
				.map(AlarmEventRow::targetId)
				.distinct()
				.toList();
		return monitoringItems.findCampaignNamesByTargetIds(userId, targetIds);
	}
}
