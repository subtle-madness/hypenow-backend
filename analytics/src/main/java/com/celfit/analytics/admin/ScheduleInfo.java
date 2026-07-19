package com.celfit.analytics.admin;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;

/** 스케줄 가시화 — ScheduleRunner와 같은 프로퍼티를 읽어 잡별 다음 발화 시각(KST)을 계산. */
public class ScheduleInfo {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final boolean enabled;
	private final Map<JobName, CronExpression> crons = new EnumMap<>(JobName.class);

	public ScheduleInfo(@Value("${analytics.schedule.enabled:false}") boolean enabled,
			@Value("${analytics.schedule.mirror-cron:-}") String mirrorCron,
			@Value("${analytics.schedule.classify-cron:-}") String classifyCron,
			@Value("${analytics.schedule.analyze-cron:-}") String analyzeCron,
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron) {
		this.enabled = enabled;
		put(JobName.MIRROR, mirrorCron);
		put(JobName.CLASSIFY, classifyCron);
		put(JobName.ANALYZE, analyzeCron);
		put(JobName.ACCOUNT_ANALYZE, accountCron);
	}

	private void put(JobName job, String cron) {
		// "-"는 스케줄러 비활성 컨벤션(@Scheduled와 동일). 파싱 실패는 미표시로 강등 — 화면은 살아야 한다.
		if (cron == null || "-".equals(cron.strip())) return;
		try {
			crons.put(job, CronExpression.parse(cron.strip()));
		} catch (IllegalArgumentException ignored) {
		}
	}

	public boolean enabled() {
		return enabled;
	}

	/** base 시점 이후 첫 발화를 KST로. 스케줄 off·크론 미지정이면 empty. */
	public Optional<ZonedDateTime> next(JobName job, ZonedDateTime base) {
		if (!enabled || !crons.containsKey(job)) return Optional.empty();
		ZonedDateTime next = crons.get(job).next(base);
		return Optional.ofNullable(next).map(t -> t.withZoneSameInstant(KST));
	}
}
