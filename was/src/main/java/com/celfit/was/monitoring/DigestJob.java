package com.celfit.was.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 다이제스트 생성 크론(6.32, 갭 문서 A-1-2 재설계) — alarm_event 대장을 유저·날짜별로 집계해
 * app.monitoring_digests에 upsert한다.
 *
 * <h2>워터마크 폐지 — 날짜 멱등 재계산</h2>
 * 구 설계(app.monitoring_alarm_state)는 "마지막으로 어디까지 봤는지"를 워터마크로 들고 있었다.
 * v2.1은 alarm_event가 유일한 원천이라 그럴 필요가 없다 — 매 실행마다 "occurred_at의 KST 날짜 =
 * 오늘"인 이벤트를 다시 전부 읽어 다이제스트를 재계산한다. {@link DigestRepository#upsert}가
 * (user_id, digest_date) 유니크로 재실행을 안전하게 만든다(같은 날짜를 몇 번 다시 돌려도 행이
 * 늘지 않고, items만 최신 집계로 덮인다). created_at·read_at은 upsert의 SET 절에 없어 그대로
 * 보존된다.
 *
 * <h2>늦은 배치 — 따라잡기 크론(6.32 "배치가 9시 이후에 끝나면 늦게라도 그날 발송")</h2>
 * 09:00 단발 실행만으로는 이 요구를 만족하지 못한다 — 스윕이 09:00을 넘겨 끝나면(재시도 라운드가
 * 겹치면 실측 가능) 그 늦은 이벤트는 09:00 실행에 안 잡히고, "다음 실행"은 내일 날짜를 집계하므로
 * 영구히 어느 다이제스트에도 반영되지 않는다. 그래서 {@link #catchUp()}이 09:10부터 매 10분(정시 포함)으로
 * 같은 날짜를 재실행한다 — 멱등 upsert 설계를 그대로 재사용(이미 생성된 다이제스트도 items를
 * 다시 계산해 늦게 도착한 이벤트를 흡수, read_at·created_at은 여전히 보존). 이벤트가 그새 없으면
 * findAlarmEventsOn이 빈 리스트를 주므로 매 틱이 조회 1번으로 저렴하다.
 *
 * <p><b>한계</b>: 이 가드도 23:50(마지막 틱)까지만 돈다 — 23:50 이후 자정 전에 도착한 이벤트는
 * (스윕이 자정을 실제로 넘기지 않았더라도) 다음날 크론이 "어제 이벤트"를 오늘 날짜로 집계하지
 * 않으므로 유실된다. sweep_run(P1 확장) 합류 시 "오늘 02:00 이후 성공한 스윕이 없으면 스킵" 완료 가드로
 * 정확히 판단할 수 있게 될 것 — 지금은 발생 확률이 낮은 장애 시나리오라 범위 밖으로 둔다.
 *
 * <h2>취소 제외 — 코드로 확인한 결과 별도 처리 불필요</h2>
 * 계약 6.30은 "취소로 종결된 행은 다음 날 다이제스트의 collection_ended에 포함하지 않는다"고
 * 규정한다. monitoring {@code TargetCommandService.cancel()}을 확인한 결과 CANCELED 전이는
 * {@code AlarmRecorder}를 전혀 호출하지 않는다 — COLLECTION_ENDED는 {@code DailySweepJob}의
 * {@code expireOverdue()}(EXPIRED 전이)에서만 적재된다. 게다가 cancel()은
 * {@code target.status().active()}(WATCHING/TRACKING)에서만 동작해 이미 EXPIRED인 target은
 * 취소할 수 없다 — 그래서 "COLLECTION_ENDED가 적재된 target이 나중에 CANCELED로도 닫히는" 경우가
 * 구조적으로 존재하지 않는다. 계약이 요구하는 제외는 이미 monitoring 쪽에서 결과적으로 성립하므로,
 * was 쪽에서 monitoring_items.canceled_at과 대조해 걸러내는 별도 로직은 두지 않는다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class DigestJob {

	private static final Logger log = LoggerFactory.getLogger(DigestJob.class);

	/** 이벤트 유형별 문안(6.32, 건수 미포함 — count는 별도 필드) — 어휘·순서 정본은 MonitoringEventTypes. */
	private static final Map<String, String> SUMMARY_BY_TYPE = Map.of(
			"collection_started", "새로 수집을 시작한 콘텐츠가 있어요",
			"collection_ended", "모니터링 기간이 끝난 콘텐츠가 있어요",
			"metrics_private", "일부 지표가 비공개로 바뀐 콘텐츠가 있어요",
			"content_issue", "게시물을 확인하지 못한 콘텐츠가 있어요");

	private final MonitoringReadRepository monitoringReadRepository;
	private final DigestRepository digestRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public DigestJob(MonitoringReadRepository monitoringReadRepository, DigestRepository digestRepository,
			ObjectMapper objectMapper, Clock clock) {
		this.monitoringReadRepository = monitoringReadRepository;
		this.digestRepository = digestRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Scheduled(cron = "${monitoring.digest.cron:0 0 9 * * *}", zone = "Asia/Seoul")
	public void run() {
		digestForToday();
	}

	/**
	 * 따라잡기 틱(09:10~23:50, 매 10분·정시 포함) — {@link #run()}과 완전히 같은 재계산을 반복한다.
	 * 09:00 실행 이후 도착한 이벤트(늦게 끝난 스윕 등)를 그날 안에 다이제스트로 흡수하기 위한
	 * 가드(클래스 Javadoc "늦은 배치" 절 참조).
	 */
	@Scheduled(cron = "${monitoring.digest.catchup-cron:0 0,10,20,30,40,50 9-23 * * *}", zone = "Asia/Seoul")
	public void catchUp() {
		digestForToday();
	}

	private void digestForToday() {
		LocalDate today = Instant.now(clock).atZone(KstTimestamps.KST).toLocalDate();
		List<AlarmEventRow> events = monitoringReadRepository.findAlarmEventsOn(today);
		if (events.isEmpty()) {
			return;   // 이벤트 0건이면 다이제스트를 만들지 않는다(6.32) — 유저별 upsert 자체를 시도하지 않는다
		}
		Map<Long, List<AlarmEventRow>> byUser = events.stream()
				.collect(Collectors.groupingBy(AlarmEventRow::userId, LinkedHashMap::new, Collectors.toList()));
		int upserted = 0;
		for (var entry : byUser.entrySet()) {
			try {
				upsertDigest(entry.getKey(), today, entry.getValue());
				upserted++;
			} catch (RuntimeException e) {
				// 한 유저의 실패가 나머지 유저의 다이제스트 생성을 막으면 안 된다(격리 정책은
				// AlarmRecorder·AlarmDispatchJob과 동일 — 부가 기능의 부분 실패는 전체를 막지 않는다).
				log.error("다이제스트 생성 실패(격리) — user {}", entry.getKey(), e);
			}
		}
		log.info("다이제스트 생성 완료 — {} KST, 이벤트 {}건, 유저 {}명 upsert", today, events.size(), upserted);
	}

	private void upsertDigest(long userId, LocalDate today, List<AlarmEventRow> events) {
		Map<String, Long> countsByFrontType = events.stream()
				.collect(Collectors.groupingBy(e -> MonitoringEventTypes.toFront(e.eventType()),
						Collectors.counting()));
		// 순서는 MonitoringEventTypes.EVENT_TYPES 고정 — 그 유저에게 없는 유형은 items에서 빠진다.
		List<Map<String, Object>> items = MonitoringEventTypes.EVENT_TYPES.stream()
				.filter(countsByFrontType::containsKey)
				.map(type -> digestItem(type, countsByFrontType.get(type)))
				.toList();
		String itemsJson = objectMapper.writeValueAsString(items);
		digestRepository.upsert(userId, today, itemsJson);
	}

	/** items[] 원소(6.32) — category는 상수 "content"뿐이다(현재 다이제스트 대상이 콘텐츠 알림뿐). */
	private static Map<String, Object> digestItem(String frontType, long count) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("category", "content");
		item.put("type", frontType);
		item.put("summary", SUMMARY_BY_TYPE.get(frontType));
		item.put("count", count);
		return item;
	}
}
