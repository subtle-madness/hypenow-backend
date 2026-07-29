package com.celfit.was.monitoring;

import com.celfit.was.mail.MailSender;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 이메일 알람 크론(계약 §4) — 게시물 감지(POST_DETECTED) 1종, 매일 09:00 KST.
 * 중복 발송 방지는 전적으로 was 책임: 이벤트별 워터마크로 관리하고, 발송 실패가 하나라도
 * 있으면 워터마크를 전진하지 않는다(다음 회차 재발송 — 유실보다 중복이 낫다는 결정, 스펙 §3).
 * 실패를 밖으로 던지지 않는다 — 스케줄 스레드 보호, 관측은 로그로.
 */
public class MonitoringAlarmJob {

	public static final String EVENT_POST_DETECTED = "POST_DETECTED";

	private static final Logger log = LoggerFactory.getLogger(MonitoringAlarmJob.class);

	private final MonitoringReadRepository readRepository;
	private final MonitoringCampaignMappingRepository mappings;
	private final MonitoringAlarmRepository alarmRepository;
	private final MonitoringAlarmMailComposer composer;
	private final MailSender mailSender;

	public MonitoringAlarmJob(MonitoringReadRepository readRepository,
			MonitoringCampaignMappingRepository mappings, MonitoringAlarmRepository alarmRepository,
			MonitoringAlarmMailComposer composer, MailSender mailSender) {
		this.readRepository = readRepository;
		this.mappings = mappings;
		this.alarmRepository = alarmRepository;
		this.composer = composer;
		this.mailSender = mailSender;
	}

	@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
	public void sendPostDetectedAlarms() {
		OffsetDateTime watermark = alarmRepository.watermark(EVENT_POST_DETECTED);
		List<PendingCandidate> fresh = readRepository.findPendingCandidatesSince(watermark.toInstant());
		if (fresh.isEmpty()) {
			log.info("모니터링 알람: 신규 감지 후보 없음 (워터마크 {})", watermark);
			return;
		}

		Map<Long, Long> userByTarget = new LinkedHashMap<>();
		mappings.findByTargetIds(fresh.stream().map(PendingCandidate::targetId).distinct().toList())
				.forEach(mapping -> userByTarget.put(mapping.targetId(), mapping.userId()));

		Map<Long, List<PendingCandidate>> byUser = new LinkedHashMap<>();
		for (PendingCandidate candidate : fresh) {
			Long userId = userByTarget.get(candidate.targetId());
			if (userId == null) {
				// 매핑 없음 — 탈퇴(CASCADE) 등. 발송 불가이므로 스킵, 관측만 남긴다
				log.warn("모니터링 알람: 매핑 없는 후보 스킵 targetId={} candidateId={}",
						candidate.targetId(), candidate.id());
				continue;
			}
			byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(candidate);
		}

		Set<Long> optedOut = alarmRepository.optedOutUserIds(EVENT_POST_DETECTED, byUser.keySet());
		byUser.keySet().removeAll(optedOut);
		Map<Long, String> emails = alarmRepository.emailsByUserIds(byUser.keySet());

		int failures = 0;
		for (Map.Entry<Long, List<PendingCandidate>> entry : byUser.entrySet()) {
			try {
				mailSender.send(emails.get(entry.getKey()),
						composer.subject(entry.getValue().size()), composer.body(entry.getValue()));
			} catch (RuntimeException e) {
				failures++;
				log.error("모니터링 알람 발송 실패 userId={}", entry.getKey(), e);
			}
		}

		if (failures == 0) {
			// now()가 아니라 처리분 기준 전진 — 실행 중 새로 감지된 행은 다음 회차가 줍는다
			OffsetDateTime maxDetected = fresh.stream().map(PendingCandidate::detectedAt)
					.max(Comparator.naturalOrder()).orElseThrow();
			alarmRepository.advanceWatermark(EVENT_POST_DETECTED, maxDetected);
			log.info("모니터링 알람: {}명 발송(옵트아웃 {}명 제외), 워터마크 {} 전진",
					byUser.size(), optedOut.size(), maxDetected);
		} else {
			log.error("모니터링 알람: 발송 실패 {}건 — 워터마크 유지(다음 회차 재발송, 중복 수신 가능)",
					failures);
		}
	}
}
