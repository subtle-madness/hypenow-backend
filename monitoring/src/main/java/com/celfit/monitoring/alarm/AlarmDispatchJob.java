package com.celfit.monitoring.alarm;

import com.celfit.monitoring.mail.MailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 알람 발송 틱(기본 5분) — due 행을 유저별로 묶어 **한 통**으로 보내고 행 단위로 종결한다.
 *
 * <p>워터마크가 없다: 무엇을 보냈는지는 행의 email_status가 말한다. 유실은 구조적으로 없고,
 * 발송 성공~상태 갱신 사이 크래시 시 재발송 가능(at-least-once — 중복이 유실보다 낫다.
 * FAILED는 다음 틱에 그 행만 다시 집는다).
 * 재시도는 email_attempts 상한(기본 5)으로 끊는다 — 없으면 영구 실패 수신자 하나가 무한히 돈다.
 *
 * <p>트랜잭션을 걸지 않는다 — 발송(외부 HTTP)이 트랜잭션 안에 들어가면 커넥션을 쥔 채 수 초를
 * 기다리고, 커밋 직전 실패가 "메일은 나갔는데 SENT는 안 찍힌" 상태(= 다음 틱 재발송)를 만든다.
 */
@Component
public class AlarmDispatchJob {

	private static final Logger log = LoggerFactory.getLogger(AlarmDispatchJob.class);

	private final AlarmEventRepository events;
	private final AlarmRecipientReader recipients;
	private final AlarmMailComposer composer;
	private final MailSender mailSender;
	private final Duration debounce;
	private final Duration debounceCap;
	private final int maxAttempts;
	/** 비어 있으면 무제한(운영 기본) — dev/test에서 실사용자 이메일 유출을 막는 안전판(§7-2). */
	private final Set<String> allowedRecipients;
	private final Clock clock;

	public AlarmDispatchJob(AlarmEventRepository events, AlarmRecipientReader recipients,
			AlarmMailComposer composer, MailSender mailSender,
			@Value("${monitoring.alarm.debounce:10m}") Duration debounce,
			@Value("${monitoring.alarm.debounce-cap:30m}") Duration debounceCap,
			@Value("${monitoring.alarm.max-attempts:5}") int maxAttempts,
			@Value("${monitoring.alarm.allowed-recipients:}") String allowedRecipients, Clock clock) {
		this.events = events;
		this.recipients = recipients;
		this.composer = composer;
		this.mailSender = mailSender;
		this.debounce = debounce;
		this.debounceCap = debounceCap;
		this.maxAttempts = maxAttempts;
		this.allowedRecipients = parseAllowlist(allowedRecipients);
		this.clock = clock;
	}

	/** 콤마 목록 → 정규화(트림·소문자) 집합. 빈 값이면 빈 집합(=무제한). */
	private static Set<String> parseAllowlist(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(raw.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(s -> s.toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
	}

	public void run() {
		if (!recipients.configured()) {
			// 개통 전에는 크론이 꺼져 있어 여기 오지 않는다 — 크론만 켜고 DSN을 빠뜨린 오배선 방어.
			log.warn("alarm_reader DSN 미설정 — 알람 발송을 건너뛴다");
			return;
		}
		Instant now = clock.instant();
		// 상한 판정은 DB가 한다 — 상한에 닿은 행은 여기 오지도 않으므로 별도 "포기" 분기가 필요 없다.
		List<AlarmEvent> due = events.findDue(now, maxAttempts);
		if (due.isEmpty()) {
			return;
		}
		Map<Long, List<AlarmEvent>> byUser = due.stream().collect(
				Collectors.groupingBy(AlarmEvent::userId, LinkedHashMap::new, Collectors.toList()));
		for (var entry : byUser.entrySet()) {
			try {
				dispatchUser(entry.getKey(), entry.getValue(), now);
			} catch (RuntimeException e) {
				// 한 유저의 실패가 나머지를 막으면 장애 하나가 알람 전체를 멈춘다.
				log.warn("알람 발송 실패(격리) — user {}: {}", entry.getKey(), e.toString());
			}
		}
	}

	private void dispatchUser(long userId, List<AlarmEvent> rows, Instant now) {
		if (stillArriving(rows, now)) {
			// 디바운스 — 시딩 수십 건 연속 등록을 흡수한다. 잦아들고 나서 1통으로 나간다.
			return;
		}
		String email = recipients.findEmail(userId).orElse(null);
		if (email == null) {
			// 유저 삭제·이메일 부재 — 재시도해도 보낼 곳이 없다(FAILED로 두면 매 틱 헛돈다).
			log.info("수신 이메일 없음 — user {} 알람 {}건 종결", userId, rows.size());
			events.updateStatus(ids(rows), AlarmEmailStatus.SKIPPED_NO_RECIPIENT, null);
			return;
		}
		Set<AlarmEventType> optOuts = recipients.findOptOuts(userId);
		List<AlarmEvent> muted = rows.stream().filter(r -> optOuts.contains(r.eventType())).toList();
		List<AlarmEvent> sendable = rows.stream().filter(r -> !optOuts.contains(r.eventType())).toList();
		// 꺼진 종류도 대장엔 남는다 — 앱 내 알림으로는 계속 서빙된다(스펙 §3-3).
		events.updateStatus(ids(muted), AlarmEmailStatus.SKIPPED_OPTOUT, null);
		if (sendable.isEmpty()) {
			return;
		}
		if (!allowedRecipients.isEmpty()
				&& !allowedRecipients.contains(email.trim().toLowerCase(Locale.ROOT))) {
			// dev/test 안전판 — 허용목록이 설정된 환경(실사용자 이메일이 있는 test DB)에서
			// 승인된 검증 주소 밖으로는 무엇도 나가지 않는다. 새 상태값을 만들지 않는다(CHECK 제약 마이그레이션 회피).
			log.info("허용목록 밖 수신자라 발송 생략 — user {} 알람 {}건 종결", userId, sendable.size());
			events.updateStatus(ids(sendable), AlarmEmailStatus.SKIPPED_NO_RECIPIENT, null);
			return;
		}
		AlarmMailComposer.Mail mail = composer.compose(sendable);
		try {
			mailSender.send(email, mail.subject(), mail.text());
		} catch (RuntimeException e) {
			log.warn("알람 메일 발송 실패 — user {} {}건: {}", userId, sendable.size(), e.toString());
			events.updateStatus(ids(sendable), AlarmEmailStatus.FAILED, null);
			return;
		}
		events.updateStatus(ids(sendable), AlarmEmailStatus.SENT, clock.instant());
	}

	/**
	 * 아직 이벤트가 몰아치는 중인지 — due 행 중 가장 최근 발생이 디바운스 창 안이면 이번 틱을 넘긴다.
	 * "즉시 레인만" 보지 않고 due 전체를 보는 건 의도다: 09:00 레인 이벤트는 새벽 스윕에서 나와
	 * occurred_at이 몇 시간 전이라 애초에 창에 걸리지 않고, 레인 구분을 위해 컬럼을 하나 더 두는 것보다
	 * "최근에 뭔가 들어왔으면 잠깐 기다린다"가 더 단순하고 틀릴 여지가 적다.
	 *
	 * <p>다만 디바운스만으로는 유입이 끊이지 않는 유저의 due가 무기한 밀릴 수 있다(즉시 레인이
	 * 계속 들어오면 아침 레인 실패 알림 같은 다른 행까지 덩달아 갇힌다) — debounceCap이 상한이다.
	 */
	private boolean stillArriving(List<AlarmEvent> rows, Instant now) {
		Instant oldestDispatchAfter = rows.stream().map(AlarmEvent::dispatchAfter)
				.min(Comparator.naturalOrder()).orElseThrow();
		if (oldestDispatchAfter.isBefore(now.minus(debounceCap))) {
			// 디바운스는 묶음 최적화지 지연 보장이 아니다 — 가장 오래된 due가 캡을 넘기면 유입 중이어도 발송
			return false;
		}
		Instant newest = rows.stream().map(AlarmEvent::occurredAt).max(Comparator.naturalOrder())
				.orElseThrow();
		return newest.isAfter(now.minus(debounce));
	}

	private static List<Long> ids(List<AlarmEvent> rows) {
		return rows.stream().map(AlarmEvent::id).toList();
	}
}
