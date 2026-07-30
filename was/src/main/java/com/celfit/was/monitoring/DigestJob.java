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
import org.springframework.beans.factory.annotation.Value;
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
 * v2.1은 alarm_event가 유일한 원천이라 그럴 필요가 없다 — 매 실행마다 최근 {@code lookbackDays}일
 * (아래 "자정 넘김 유실 해소" 절 참조)의 이벤트를 다시 전부 읽어 날짜별로 다이제스트를 재계산한다.
 * {@link DigestRepository#upsert}가 (user_id, digest_date) 유니크로 재실행을 안전하게 만든다(같은
 * 날짜를 몇 번 다시 돌려도 행이 늘지 않고, items만 최신 집계로 덮인다). created_at·read_at은
 * upsert의 SET 절에 없어 그대로 보존된다.
 *
 * <h2>늦은 배치 — 따라잡기 크론(6.32 "배치가 9시 이후에 끝나면 늦게라도 그날 발송")</h2>
 * 09:00 단발 실행만으로는 이 요구를 만족하지 못한다 — 스윕이 09:00을 넘겨 끝나면(재시도 라운드가
 * 겹치면 실측 가능) 그 늦은 이벤트는 09:00 실행에 안 잡히고, "다음 실행"은 내일 날짜를 집계하므로
 * 영구히 어느 다이제스트에도 반영되지 않는다. 그래서 {@link #catchUp()}이 09:10부터 매 10분(정시 포함)으로
 * 같은 회고 창을 재실행한다 — 멱등 upsert 설계를 그대로 재사용(이미 생성된 다이제스트도 items를
 * 다시 계산해 늦게 도착한 이벤트를 흡수, read_at·created_at은 여전히 보존). 이벤트가 그새 없으면
 * findAlarmEventsBetween이 빈 리스트를 주므로 매 틱이 조회 1번으로 저렴하다.
 *
 * <h2>자정 넘김 유실 해소 — 회고 창 재계산</h2>
 * 구 설계는 매 실행이 "오늘 KST 날짜"만 재계산했다 — 그래서 따라잡기 크론의 마지막 틱(23:50)
 * 이후 자정 전에 도착한 이벤트는 그날 어느 다이제스트에도 반영되지 못했다: D+1의 실행들은
 * 전부 D+1 날짜만 집계하므로 D 날짜는 영구히 재방문되지 않는다.
 *
 * <p>해법은 매 실행이 최근 {@code lookbackDays}일(기본 2일 = 어제·오늘)의 이벤트를 범위 조회
 * ({@link MonitoringReadRepository#findAlarmEventsBetween}) 한 번으로 읽어 KST 날짜별로 묶고,
 * 날짜마다 독립적으로 (유저, 날짜) 멱등 upsert를 반복하는 것이다. 근거: alarm_event.occurred_at은
 * {@code DEFAULT now()}이고 다이제스트 날짜 판정은 오직 occurred_at의 KST 날짜뿐이다 — 자정이
 * 지나면 그 날짜를 가진 이벤트는 더 이상 생길 수 없으므로, D 날짜 이벤트 집합은 자정에 확정된다.
 * 따라서 회고 1일(어제만 다시 봄)이면 논리적으로 완결이고, 기본값 2일의 여분 1일은 was
 * 다운타임(자정을 넘겨 죽어 있다가 뒤늦게 뜨는 경우)에 대비한 안전 마진이다.
 *
 * <p>원안이던 "오늘 02:00 이후 성공한 스윕이 없으면 스킵"(sweep_run 완료 가드)은 채택하지
 * 않았다. ① 유실 기전은 "과거 날짜를 다시 집계하는 경로의 부재"인데 그 가드는 오늘 집계를
 * 늦출 뿐 과거 날짜 재집계 경로를 만들지 않아 유실을 막지 못한다. ② sweep_run 기록이 실패하거나
 * 스윕을 수동 실행하면 다이제스트가 조용히 안 나가는 fail-closed 실패 모드를 새로 들인다.
 * sweep_run은 이번 변경에서 전혀 참조하지 않는다.
 *
 * <p>계약 관점: 23:50 이후 도착한 이벤트는 다음날 09:00 실행에서 그 이벤트의 날짜(어제)
 * 다이제스트로 반영된다 — 계약 §6.32의 {@code date} 정의(이벤트 발생 KST 날짜)는 그대로
 * 유지되고, §6.25 "이틀치가 한 알림에 섞이면 안 된다"도 날짜별 upsert가 독립이라 계속 지켜진다.
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
	/** 재계산 대상 KST 날짜 수(오늘 포함) — 클래스 Javadoc "자정 넘김 유실 해소" 절 참조. 1 미만은 1로 클램프. */
	private final int lookbackDays;

	public DigestJob(MonitoringReadRepository monitoringReadRepository, DigestRepository digestRepository,
			ObjectMapper objectMapper, Clock clock,
			@Value("${monitoring.digest.lookback-days:2}") int lookbackDays) {
		this.monitoringReadRepository = monitoringReadRepository;
		this.digestRepository = digestRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.lookbackDays = Math.max(1, lookbackDays);
	}

	@Scheduled(cron = "${monitoring.digest.cron:0 0 9 * * *}", zone = "Asia/Seoul")
	public void run() {
		digestRecentDays();
	}

	/**
	 * 따라잡기 틱(09:10~23:50, 매 10분·정시 포함) — {@link #run()}과 완전히 같은 재계산을 반복한다.
	 * 09:00 실행 이후 도착한 이벤트(늦게 끝난 스윕 등)를 회고 창 안의 해당 날짜 다이제스트로
	 * 흡수하기 위한 가드(클래스 Javadoc "늦은 배치"·"자정 넘김 유실 해소" 절 참조).
	 */
	@Scheduled(cron = "${monitoring.digest.catchup-cron:0 0,10,20,30,40,50 9-23 * * *}", zone = "Asia/Seoul")
	public void catchUp() {
		digestRecentDays();
	}

	/**
	 * 최근 {@link #lookbackDays}일(오늘 포함)의 alarm_event를 범위 조회로 읽어 KST 날짜 → 유저
	 * 순으로 묶고, (유저, 날짜) 조합마다 독립적으로 멱등 upsert한다. 날짜·유저 두 그룹핑 모두
	 * LinkedHashMap이라 순서가 결정론적이다.
	 */
	private void digestRecentDays() {
		LocalDate today = Instant.now(clock).atZone(KstTimestamps.KST).toLocalDate();
		LocalDate from = today.minusDays(lookbackDays - 1);
		List<AlarmEventRow> events = monitoringReadRepository.findAlarmEventsBetween(from, today);
		if (events.isEmpty()) {
			return;   // 이벤트 0건이면 다이제스트를 만들지 않는다(6.32) — (유저, 날짜) upsert 자체를 시도하지 않는다
		}
		// 날짜 키는 occurred_at의 KST 날짜 — (occurred_at AT TIME ZONE 'Asia/Seoul')::date와 동치.
		Map<LocalDate, Map<Long, List<AlarmEventRow>>> byDateThenUser = events.stream()
				.collect(Collectors.groupingBy(e -> KstTimestamps.toKstDate(e.occurredAt()), LinkedHashMap::new,
						Collectors.groupingBy(AlarmEventRow::userId, LinkedHashMap::new, Collectors.toList())));
		int upserted = 0;
		for (var dateEntry : byDateThenUser.entrySet()) {
			LocalDate date = dateEntry.getKey();
			for (var userEntry : dateEntry.getValue().entrySet()) {
				try {
					upsertDigest(userEntry.getKey(), date, userEntry.getValue());
					upserted++;
				} catch (RuntimeException e) {
					// 한 (유저, 날짜)의 실패가 나머지 upsert를 막으면 안 된다(격리 정책은
					// AlarmRecorder·AlarmDispatchJob과 동일 — 부가 기능의 부분 실패는 전체를 막지 않는다).
					log.error("다이제스트 생성 실패(격리) — user {}, date {}", userEntry.getKey(), date, e);
				}
			}
		}
		log.info("다이제스트 생성 완료 — 창 {}~{} KST({}일), 이벤트 {}건, upsert {}건", from, today, lookbackDays,
				events.size(), upserted);
	}

	private void upsertDigest(long userId, LocalDate date, List<AlarmEventRow> events) {
		Map<String, Long> countsByFrontType = events.stream()
				.collect(Collectors.groupingBy(e -> MonitoringEventTypes.toFront(e.eventType()),
						Collectors.counting()));
		// 순서는 MonitoringEventTypes.EVENT_TYPES 고정 — 그 유저에게 없는 유형은 items에서 빠진다.
		List<Map<String, Object>> items = MonitoringEventTypes.EVENT_TYPES.stream()
				.filter(countsByFrontType::containsKey)
				.map(type -> digestItem(type, countsByFrontType.get(type)))
				.toList();
		String itemsJson = objectMapper.writeValueAsString(items);
		digestRepository.upsert(userId, date, itemsJson);
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
