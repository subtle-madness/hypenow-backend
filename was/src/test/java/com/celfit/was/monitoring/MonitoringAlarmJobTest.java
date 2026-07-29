package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.celfit.was.IntegrationTest;
import com.celfit.was.mail.MailSender;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MonitoringAlarmJobTest extends IntegrationTest {

	static final OffsetDateTime BASE = OffsetDateTime.parse("2026-07-01T00:00:00+09:00");
	static final OffsetDateTime DETECTED_1 = OffsetDateTime.parse("2026-07-28T02:00:00+09:00");
	static final OffsetDateTime DETECTED_2 = OffsetDateTime.parse("2026-07-29T02:00:00+09:00");

	@Autowired
	DataSource dataSource;
	@Autowired
	MonitoringCampaignMappingRepository mappings;
	@Autowired
	MonitoringAlarmRepository alarmRepository;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	MailSender mailSender;
	MonitoringAlarmJob job;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();
		jdbcClient.sql("TRUNCATE app.monitoring_campaigns, app.monitoring_email_opt_outs").update();
		jdbcClient.sql("""
				UPDATE app.monitoring_alarm_state SET last_notified_at = :base
				WHERE event_type = 'POST_DETECTED'
				""").param("base", BASE).update();

		mailSender = mock(MailSender.class);
		job = new MonitoringAlarmJob(new MonitoringReadRepository(monitoringJdbc), mappings,
				alarmRepository, new MonitoringAlarmMailComposer(), mailSender);
	}

	long seedUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "job-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	long seedTarget(String username, String status) {
		return monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, registration_key, expires_at)
				VALUES ('ACCOUNT', :username, :status, gen_random_uuid()::text, now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username).param("status", status)
				.query(Long.class).single();
	}

	void seedCandidate(long targetId, String shortCode, OffsetDateTime detectedAt) {
		monitoringJdbc.sql("""
				INSERT INTO detected_candidate (target_id, short_code, detected_at, caption_excerpt, status)
				VALUES (:t, :sc, :at, '…샤넬…', 'PENDING')
				""")
				.param("t", targetId).param("sc", shortCode).param("at", detectedAt).update();
	}

	long linkUserToTarget(long userId, long targetId) {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		mappings.confirmTarget(key, targetId);
		return targetId;
	}

	String emailOf(long userId) {
		return jdbcClient.sql("SELECT email FROM app.users WHERE id = :id")
				.param("id", userId).query(String.class).single();
	}

	@Test
	void 유저당_한_통으로_묶어_발송하고_워터마크를_전진한다() {
		long user = seedUser();
		long t1 = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		long t2 = linkUserToTarget(user, seedTarget("acc2", "TRACKING"));
		seedCandidate(t1, "NEW1", DETECTED_1);
		seedCandidate(t2, "NEW2", DETECTED_2);

		job.sendPostDetectedAlarms();

		verify(mailSender).send(eq(emailOf(user)), contains("2건"), contains("NEW1"));
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(DETECTED_2);
	}

	@Test
	void 옵트아웃_유저는_제외되고_워터마크는_전진한다() {
		long user = seedUser();
		long t1 = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		seedCandidate(t1, "NEW1", DETECTED_1);
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:u, 'POST_DETECTED')
				""").param("u", user).update();

		job.sendPostDetectedAlarms();

		verify(mailSender, never()).send(anyString(), anyString(), anyString());
		// 발송 대상 0명 = 처리 완료 — 워터마크는 전진해야 다음 회차가 같은 후보를 재평가하지 않는다
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(DETECTED_1);
	}

	@Test
	void 발송_실패가_있으면_워터마크를_유지한다() {
		long user = seedUser();
		long t1 = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		seedCandidate(t1, "NEW1", DETECTED_1);
		doThrow(new RuntimeException("발송 실패")).when(mailSender)
				.send(anyString(), anyString(), anyString());

		job.sendPostDetectedAlarms();   // 예외를 밖으로 던지지 않는다(크론 스레드 보호)

		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(BASE);
	}

	@Test
	void 매핑_없는_후보는_스킵되고_나머지는_정상_발송된다() {
		long user = seedUser();
		long linked = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		long orphan = seedTarget("acc_orphan", "WATCHING");   // 매핑 없음(탈퇴 CASCADE 등)
		seedCandidate(linked, "NEW1", DETECTED_1);
		seedCandidate(orphan, "ORPHAN1", DETECTED_2);

		job.sendPostDetectedAlarms();

		verify(mailSender).send(eq(emailOf(user)), contains("1건"), contains("NEW1"));
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(DETECTED_2);
	}

	@Test
	void 신규_후보가_없으면_아무것도_하지_않는다() {
		job.sendPostDetectedAlarms();

		verifyNoInteractions(mailSender);
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(BASE);
	}
}
