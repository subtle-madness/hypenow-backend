package com.celfit.was.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 주간 다이제스트 생성 크론(2026-08-27 주간 개편 §2·§4·§8, 2026-08-28 품질 리뷰 반영) — 매주
 * 월요일 09:00 KST에 <b>지난주(월~일)</b>를 집계해 app.monitoring_digests에 (user_id, 주 시작일)
 * 멱등 upsert한다. 일일 다이제스트(구 DigestJob)를 대체한다.
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
 * <h2>따라잡기는 요일 제한이 없다(2026-08-28 품질 리뷰 C1)</h2>
 * {@link #catchUp()}의 기본 크론은 월요일 한정이 아니라 <b>매일</b> 09:10~23:50 매 10분이다.
 * {@link #currentWindow()}가 주중 어느 요일에 불러도 같은 "직전 주" 창을 계산하므로, was가
 * 월요일 하루 종일 죽어 있다가 화~일 아무 때나 되살아나도 그 주의 다이제스트를 복구할 수 있어야
 * 한다 — 월요일로만 한정하면 정확히 그 시나리오(가장 복구가 필요한 순간)를 놓친다. 멱등 upsert라
 * 매일 재계산해도 안전하다.
 *
 * <h2>멱등 — 워터마크가 없다</h2>
 * {@link DigestRepository#upsertWeekly}가 (user_id, digest_date) 유니크로 재실행을 안전하게
 * 만든다. 같은 주를 몇 번 다시 돌려도 행이 늘지 않고 items만 최신 집계로 덮인다(created_at·
 * read_at은 원칙적으로 보존). 유일한 부작용 기록은 미표기 알림 이력인데, 그것도 "이번 주가 아닌
 * 주에 알린 것만 제외"라 같은 주 재실행이 자기 기록에 걸리지 않는다.
 *
 * <h2>구 일일 잡과의 digest_date 충돌(2026-08-28 품질 리뷰 C2)</h2>
 * 주간 전환 첫 주의 창 시작 월요일은 구 일일 DigestJob이 이미 그 날짜로 행을 만들어 뒀을 수
 * 있다(digest_date 컬럼을 "달력일"과 "주 시작일" 둘 다로 썼던 값 공간이 겹친다). {@link
 * DigestRepository#upsertWeekly}가 이 충돌을 감지해 리셋한다 — 조건은 그 메서드 참조.
 *
 * <h2>이벤트 0건이면 items만 비운다(2026-08-28 품질 리뷰 I4, 재리뷰로 delete→clearItems 교체)</h2>
 * 조립 결과가 빈 목록이면 upsert 대신 그 (user, 주 시작일) 행의 {@code items}만
 * {@link DigestRepository#clearItems}로 비운다(행이 없으면 no-op) — <b>행 자체·read_at·
 * email_sent_at·email_attempts는 보존</b>한다. 최초 구현은 행을 통째로 delete했는데, 그러면
 * "메일 발송됨 → 킬 스위치 off·브랜드 연결 해제로 행 삭제 → 복구 → 같은 주 재생성" 경로에서
 * email_sent_at까지 함께 사라져 같은 주 메일이 중복 발송되고 read_at도 부활한다. clearItems는
 * items만 비우므로 FE 노출은 {@link DigestRepository#findVisibleRecentByUser}·
 * {@link DigestRepository#countVisibleByUser}가 {@code items='[]'} 행을 걸러 자연히 사라지고,
 * 같은 주에 다시 채워지면(같은 행이 창 마감 이후에 만들어졌으므로 upsertWeekly의 리셋 조건이
 * 발동하지 않아) read_at·email_sent_at이 그대로 보존된 채 노출만 되살아난다.
 *
 * <h2>배치 조회로 N+1 해소(2026-08-28 품질 리뷰 I3)</h2>
 * 브랜드 발견분·미표기 판정은 유저 수만큼 왕복하지 않는다 — {@link #runFor}가 대상 유저 전체의
 * 브랜드id·등록shortcode를 한 번에 모아 조회한 뒤 메모리에서 userId로 그룹핑해 각 유저의
 * upsertWeekly 호출에 넘긴다. 유저 단위 실패 격리(장애 하나가 전체를 막지 않는 정책)는 조립·
 * upsert가 일어나는 {@link #upsertWeekly} 호출 자체에 남아 있다 — 배치 조회 단계가 실패하면
 * (공유 입력이라) 그 실행 전체가 실패하는 게 맞다.
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
	private final WeeklyDigestMailer mailer;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	/** 광고 표기 판정 노출 킬 스위치 — FE와 같은 키를 본다(설계 §8 "킬 스위치 정합"). */
	private final boolean exposeAdDisclosure;
	/**
	 * 중첩 실행 가드(품질 리뷰 Important #2) — 발송 루프의 {@code Thread.sleep}(pace)이
	 * was 기본 스케줄러 스레드를 분 단위로 점유할 수 있어, run·catchUp이 겹쳐 들어오면 같은
	 * 유저 집합을 두 스레드가 동시에 upsertWeekly하는 경합이 생긴다. 실행 중에 새 트리거가
	 * 들어오면 스킵하고 경고만 남긴다 — 다음 따라잡기 틱(10분 뒤)이 다시 시도하므로 유실이 아니다.
	 */
	private final AtomicBoolean running = new AtomicBoolean(false);

	public WeeklyDigestJob(MonitoringReadRepository monitoringRead, BrandReadRepository brandRead,
			BrandLinkRepository brandLinks, BrandDirectPostRepository brandDirectPosts,
			MonitoringItemRepository monitoringItems, AdDisclosureNoticeRepository adNotices,
			DigestRepository digests, WeeklyDigestAssembler assembler, WeeklyDigestMailer mailer,
			ObjectMapper objectMapper, Clock clock,
			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		this.monitoringRead = monitoringRead;
		this.brandRead = brandRead;
		this.brandLinks = brandLinks;
		this.brandDirectPosts = brandDirectPosts;
		this.monitoringItems = monitoringItems;
		this.adNotices = adNotices;
		this.digests = digests;
		this.assembler = assembler;
		this.mailer = mailer;
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
	 * 따라잡기 틱(품질 리뷰 C1 — 요일 제한 없이 매일 09:10~23:50, 매 10분) — {@link #run()}과
	 * 완전히 같은 재계산을 반복한다. 09:00 실행 이후 도착한 이벤트(늦게 끝난 스윕 등)를 같은 주
	 * 행으로 흡수하고, was가 월요일 내내 죽어 있다가 다른 요일에 되살아나도 {@link
	 * WeekWindow#previousWeekOf}가 같은 "직전 주" 창을 주므로 그 주를 복구한다. 메일 발송 실패분을
	 * 재시도할 창이기도 하다(Task 10에서 발송이 붙는다).
	 */
	@Scheduled(cron = "${monitoring.digest.weekly-catchup-cron:0 10,20,30,40,50 9-23 * * *}", zone = "Asia/Seoul")
	public void catchUp() {
		runFor(currentWindow());
	}

	private WeekWindow currentWindow() {
		return WeekWindow.previousWeekOf(Instant.now(clock).atZone(KstTimestamps.KST).toLocalDate());
	}

	/**
	 * 창을 명시적으로 받는 본체. 대상 유저는 "지난주 알람 이벤트가 있는 유저" ∪ "활성 브랜드 연결이
	 * 있는 유저"다 — 전자만 보면 브랜드 소식·미표기만 있는 유저가 통째로 빠진다.
	 *
	 * <p><b>계약(2026-08-28 재리뷰 nit)</b>: 반드시 <b>이미 닫힌</b> 창만 넘길 것 — 이번 주(진행
	 * 중인, 아직 끝나지 않은 창)를 넘기면 {@link DigestRepository#upsertWeekly}의 리셋 조건
	 * (기존 행 created_at < windowCloseAt)이 오발동해 정당한 행의 read_at·created_at까지 리셋될
	 * 수 있다 — 그 조건은 "정상적인 주간 행은 창이 닫힌 뒤에만 생성된다"는 전제 위에 서 있다.
	 * {@link #run()}·{@link #catchUp()} 둘 다 {@link WeekWindow#previousWeekOf}로 항상 직전
	 * (이미 닫힌) 주를 계산해 넘기므로 스케줄 진입점은 이 계약을 자동으로 지킨다 — 명시적 창을
	 * 받는 이 메서드를 직접 호출할 때만(운영 재처리 등) 주의가 필요하다.
	 */
	public void runFor(WeekWindow window) {
		if (!running.compareAndSet(false, true)) {
			// 이미 도는 중 — 발송 루프의 pace()가 스케줄러 스레드를 오래 쥐고 있을 수 있어 run·
			// catchUp이 겹치면 같은 창을 두 스레드가 동시에 upsert하는 경합이 생긴다. 다음 따라잡기
			// 틱이 10분 뒤 다시 시도하므로 이번 트리거는 건너뛰어도 유실이 아니다.
			log.warn("주간 다이제스트 실행이 이미 진행 중 — 이번 트리거는 건너뜀 (창 {}~{})",
					window.startDate(), window.endDateInclusive());
			return;
		}
		try {
			Map<Long, List<AlarmEventRow>> eventsByUser = monitoringRead
					.findAlarmEventsBetween(window.startDate(), window.endDateInclusive()).stream()
					.collect(Collectors.groupingBy(AlarmEventRow::userId, LinkedHashMap::new, Collectors.toList()));
			List<BrandLinkRow> activeLinks = brandLinks.findAllActive();
			Map<Long, List<Long>> brandIdsByUser = activeLinks.stream()
					.collect(Collectors.groupingBy(BrandLinkRow::userId, LinkedHashMap::new,
							Collectors.mapping(BrandLinkRow::brandId, Collectors.toList())));
			Map<Long, List<Long>> userIdsByBrand = activeLinks.stream()
					.collect(Collectors.groupingBy(BrandLinkRow::brandId, LinkedHashMap::new,
							Collectors.mapping(BrandLinkRow::userId, Collectors.toList())));
			Set<Long> userIds = new LinkedHashSet<>(eventsByUser.keySet());
			userIds.addAll(brandIdsByUser.keySet());

			// 품질 리뷰 I3 — 유저 수만큼 왕복하지 않고 전 유저분을 한 번에 모아 조회한 뒤 그룹핑한다.
			Map<Long, List<WeeklyPostMetrics>> brandNewPostsByUser = brandNewPostsByUser(userIdsByBrand, window);
			Map<Long, List<String>> adNotDisclosedByUser = adNotDisclosedByUser(userIds, window);

			int upserted = 0;
			for (long userId : userIds) {
				try {
					if (upsertWeekly(userId, window, eventsByUser.getOrDefault(userId, List.of()),
							brandNewPostsByUser.getOrDefault(userId, List.of()),
							adNotDisclosedByUser.getOrDefault(userId, List.of()))) {
						upserted++;
					}
				} catch (RuntimeException e) {
					// 한 유저의 실패가 나머지를 막으면 장애 하나가 주간 알림 전체를 멈춘다(AlarmRecorder와 동일 정책).
					log.error("주간 다이제스트 생성 실패(격리) — user {}, week {}", userId, window.startDate(), e);
				}
			}
			log.info("주간 다이제스트 생성 완료 — 창 {}~{} KST, 대상 유저 {}명, upsert {}건",
					window.startDate(), window.endDateInclusive(), userIds.size(), upserted);
		} finally {
			running.set(false);
		}
	}

	/**
	 * @return 다이제스트를 만들었으면 true, 내용이 없어 삭제(또는 애초에 없어 no-op)했으면 false
	 *
	 * <p>불변식(Task 8 품질 리뷰 이월 확인 #1): eventCounts와 endedPosts는 <b>같은 userEvents
	 * 목록</b>에서 유도된다 — eventCounts는 toFront 매핑 후 그룹핑, endedPosts는 COLLECTION_ENDED
	 * 원본 유형으로 필터링한 부분집합이다. COLLECTION_ENDED는 toFront가 항상 "collection_ended"로
	 * 매핑하는 기지(旣知) 유형이라(toFront의 null 반환은 <em>미지</em> 유형에만 발생), endedPosts가
	 * 비어있지 않으면 eventCounts의 collection_ended도 반드시 0보다 크다 — 두 값이 서로 다른 창을
	 * 보고 있어서 하이라이트 후보와 이벤트 카운트가 어긋나는 사고는 구조적으로 발생하지 않는다.
	 */
	private boolean upsertWeekly(long userId, WeekWindow window, List<AlarmEventRow> userEvents,
			List<WeeklyPostMetrics> brandNewPosts, List<String> adShortCodes) {
		// 미지 이벤트 유형은 조용히 건너뛴다(MonitoringEventTypes.toFront 참조, 품질 리뷰 nit) —
		// alarm_event에 5번째 유형이 추가돼도 이 잡 전체가 예외로 죽지 않는다.
		Map<String, Long> eventCounts = userEvents.stream()
				.map(event -> MonitoringEventTypes.toFront(event.eventType()))
				.filter(Objects::nonNull)
				.collect(Collectors.groupingBy(frontType -> frontType, Collectors.counting()));
		List<DigestItem> items = assembler.assemble(new WeeklyDigestInput(eventCounts, brandNewPosts,
				endedPosts(userEvents), adShortCodes,
				campaignNamesFor(userId, userEvents, COLLECTION_STARTED),
				campaignNamesFor(userId, userEvents, COLLECTION_ENDED)));
		if (items.isEmpty()) {
			// 품질 리뷰 I4(재리뷰로 delete→clearItems 교체) — 행은 보존하고 items만 비운다.
			// email_sent_at·email_attempts·read_at을 지키기 위해서다(클래스 Javadoc 참조).
			digests.clearItems(userId, window.startDate());
			return false;
		}
		long digestId = digests.upsertWeekly(userId, window.startDate(), window.toExclusive(),
				objectMapper.writeValueAsString(items));
		// 품질 리뷰 I1 — upsert가 실제로 성공한 뒤에만 이력을 남긴다. 순서를 뒤집으면(이력 먼저)
		// upsert 실패 시 "알림은 못 갔는데 이력엔 통보됨으로 찍힌" 무음 유실이 생긴다 — 이 순서면
		// 실패 방향이 "다음 주 중복 통지"(자가 복구, 성가시지만 안전)로 뒤집힌다.
		adNotices.markNotified(userId, adShortCodes, window.startDate());
		// 발송은 같은 try 블록(유저 단위 격리) 안이다 — 한 유저의 메일 실패가 다음 유저의 다이제스트를 막지 않는다.
		mailer.send(userId, digestId, window, items);
		return true;
	}

	/**
	 * 브랜드 발견분 배치 조회(품질 리뷰 I3) — 유저 수만큼 왕복하지 않고 전 브랜드를 한 번에 조회해
	 * userId로 되짚는다. 브랜드 풀은 유저 간 공유라 같은 발견분이 그 브랜드를 건 유저 전원에게
	 * 뿌려진다. 유저 내부 dedup(shortcode 기준, 태그 발견이 해시태그 발견보다 우선)은 여기서
	 * 그대로 재현한다 — 예전에 유저마다 호출하던 {@code brandNewPosts(brandIds, window)}와 동치.
	 */
	private Map<Long, List<WeeklyPostMetrics>> brandNewPostsByUser(Map<Long, List<Long>> userIdsByBrand,
			WeekWindow window) {
		if (userIdsByBrand.isEmpty()) {
			return Map.of();
		}
		List<Long> allBrandIds = List.copyOf(userIdsByBrand.keySet());
		List<WeeklyPostMetrics> tagged = brandRead.findTaggedPostsDiscoveredBetween(
				allBrandIds, window.from(), window.toExclusive());
		List<WeeklyPostMetrics> hashtag = brandRead.findHashtagPostsDiscoveredBetween(
				allBrandIds, window.from(), window.toExclusive());

		Map<Long, Map<String, WeeklyPostMetrics>> byUserThenShortCode = new LinkedHashMap<>();
		for (WeeklyPostMetrics post : tagged) {
			for (long userId : userIdsByBrand.getOrDefault(post.brandId(), List.of())) {
				byUserThenShortCode.computeIfAbsent(userId, key -> new LinkedHashMap<>())
						.putIfAbsent(post.shortCode(), post);
			}
		}
		for (WeeklyPostMetrics post : hashtag) {
			// 태그 풀에 이미 있는 게시물이면 지표가 더 풍부한 태그 쪽(스냅샷 기반)을 남긴다.
			for (long userId : userIdsByBrand.getOrDefault(post.brandId(), List.of())) {
				byUserThenShortCode.computeIfAbsent(userId, key -> new LinkedHashMap<>())
						.putIfAbsent(post.shortCode(), post);
			}
		}
		Map<Long, List<WeeklyPostMetrics>> result = new LinkedHashMap<>();
		byUserThenShortCode.forEach((userId, byShortCode) -> result.put(userId, List.copyOf(byShortCode.values())));
		return result;
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
				// brandId 0L = 해당 없음 — 수집 종료분은 target 기원이라 브랜드 태그와 무관하다
				// (WeeklyPostMetrics#brandId 참조).
				.map(snapshot -> new WeeklyPostMetrics(0L, snapshot.shortCode(),
						authorByShortCode.get(snapshot.shortCode()), snapshot.contentType(),
						snapshot.views(), snapshot.likes(), snapshot.comments()))
				.toList();
	}

	/**
	 * 지난주 미표기 판정된 등록(시딩) 게시물을 유저별로 배치 조회(품질 리뷰 I3) —
	 * {@code shortCodesByUser}·{@code findNotDisclosedJudgedBetween}을 유저마다 왕복하던 것을
	 * 전 유저 registered shortcode를 한 번에 모아 조회 1회로 줄인다. 스윕이 발견한 제3자 게시물은
	 * 대응 불가능한 소음이라 제외한다(설계 §2 "미표기 범위"). 등록 원장의 정본은
	 * app.brand_direct_posts다. 킬 스위치가 꺼져 있으면 아예 조회하지 않는다 — FE 미노출 정보가
	 * 알림으로 새는 사고 방지(설계 §8).
	 *
	 * <p>{@link BrandReadRepository#findNotDisclosedJudgedBetween}은 2026-08-28 재리뷰 nit로
	 * shortcode 바인드를 받지 않고 창만으로 걸러 읽는다 — 등록 원장과의 교집합은 여기서(자바)
	 * {@code allRegistered}로 먼저 한 번 걸러낸다(전 유저 무관 후보 배제), 그 다음 유저별
	 * {@code registered}로 다시 좁힌다.
	 *
	 * <p>{@code findNotifiedInOtherWeek}(유저별 통지 이력 확인)는 배치할 수 없다 — 유저마다 자기
	 * 이력만 봐야 하는 스칼라 userId 파라미터라서다. 다만 judged 후보가 있는 유저에게만 호출하므로
	 * 브랜드 연결만 있고 등록·판정이 없는 대다수 유저는 이 호출 자체가 생기지 않는다.
	 */
	private Map<Long, List<String>> adNotDisclosedByUser(Set<Long> userIds, WeekWindow window) {
		if (!exposeAdDisclosure) {
			return Map.of();
		}
		List<BrandDirectPostRepository.UserShortCodeRow> registrations = brandDirectPosts.findAllUserShortCodes();
		if (registrations.isEmpty()) {
			return Map.of();
		}
		Map<Long, Set<String>> registeredByUser = registrations.stream()
				.collect(Collectors.groupingBy(BrandDirectPostRepository.UserShortCodeRow::userId, LinkedHashMap::new,
						Collectors.mapping(BrandDirectPostRepository.UserShortCodeRow::shortCode,
								Collectors.toCollection(LinkedHashSet::new))));
		Set<String> allRegistered = registrations.stream()
				.map(BrandDirectPostRepository.UserShortCodeRow::shortCode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		List<String> judgedRegistered = brandRead.findNotDisclosedJudgedBetween(window.from(), window.toExclusive())
				.stream()
				.filter(allRegistered::contains)
				.toList();
		if (judgedRegistered.isEmpty()) {
			return Map.of();
		}

		Map<Long, List<String>> result = new LinkedHashMap<>();
		for (long userId : userIds) {
			Set<String> registered = registeredByUser.getOrDefault(userId, Set.of());
			if (registered.isEmpty()) {
				continue;
			}
			List<String> judgedForUser = judgedRegistered.stream().filter(registered::contains).toList();
			if (judgedForUser.isEmpty()) {
				continue;
			}
			Set<String> alreadyNotified = adNotices.findNotifiedInOtherWeek(userId, judgedForUser, window.startDate());
			List<String> remaining = judgedForUser.stream().filter(sc -> !alreadyNotified.contains(sc)).toList();
			if (!remaining.isEmpty()) {
				result.put(userId, remaining);
			}
		}
		return result;
	}

	/**
	 * 모니터링 진행 섹션 문안에 붙일 캠페인 이름(설계 §3) — 품질 리뷰 I5로 이벤트 유형별로
	 * 나뉜다(시작 캠페인 이름이 종료 문안에도 섞여 나가는 오귀속을 없앤다). 유저 스코프는 조회가 건다.
	 */
	private List<String> campaignNamesFor(long userId, List<AlarmEventRow> userEvents, String eventType) {
		List<Long> targetIds = userEvents.stream()
				.filter(event -> eventType.equals(event.eventType()))
				.map(AlarmEventRow::targetId)
				.distinct()
				.toList();
		return monitoringItems.findCampaignNamesByTargetIds(userId, targetIds);
	}
}
