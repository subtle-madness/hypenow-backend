package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.mail.MailSendException;
import com.celfit.monitoring.mail.MailSender;
import com.celfit.monitoring.testsupport.TestDb;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 5분 틱 발송 — 디바운스·옵트아웃·유저당 1통·행 단위 재시도. */
class AlarmDispatchJobTest {

	/** 발송 fake — 수신 기록을 남기고, fail=true면 실패로 전환한다(Mockito 대신 monitoring 관례). */
	static final class FakeMailSender implements MailSender {

		record Sent(String to, String subject, String text) {}

		final List<Sent> sent = new ArrayList<>();
		/** 성공·실패를 가리지 않은 호출 횟수 — 재시도 상한 검증에 쓴다(sent는 성공분만 담는다). */
		int attempts;
		boolean fail;

		@Override
		public void send(String to, String subject, String text) {
			attempts++;
			if (fail) {
				throw new MailSendException("발송 실패(테스트)", null);
			}
			sent.add(new Sent(to, subject, text));
		}
	}

	private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

	JdbcTemplate db;
	AlarmEventRepository events;
	FakeMailSender mail;
	AlarmDispatchJob job;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		TestDb.resetAppFixture(db);
		events = new AlarmEventRepository(db);
		mail = new FakeMailSender();
		job = new AlarmDispatchJob(events, new AlarmRecipientReader(ds), new AlarmMailComposer(),
				mail, Duration.ofMinutes(10), 5, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private long user(long id, String email) {
		db.update("INSERT INTO app.users (id, email) VALUES (?, ?)", id, email);
		return id;
	}

	private void optOut(long userId, AlarmEventType type) {
		db.update("INSERT INTO app.monitoring_email_opt_outs (user_id, event_type) VALUES (?, ?)",
				userId, type.name());
	}

	/** occurredAt을 직접 지정한다 — 디바운스 판정이 "얼마나 최근인가"에 달려 있어서. */
	private long event(long userId, AlarmEventType type, Instant occurredAt, Instant dispatchAfter) {
		return db.queryForObject("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
				VALUES (1, ?, ?, '{"username":"acct_a","shortCode":"SC1"}'::jsonb, ?, ?)
				RETURNING id""",
				Long.class, userId, type.name(), Timestamp.from(occurredAt), Timestamp.from(dispatchAfter));
	}

	private String statusOf(long eventId) {
		return db.queryForObject("SELECT email_status FROM alarm_event WHERE id=?", String.class, eventId);
	}

	private static final Instant SETTLED = NOW.minusSeconds(3600);   // 디바운스 창 밖

	@Test
	void 유저당_한_통으로_묶어_보내고_행을_SENT로_닫는다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		long e2 = event(7, AlarmEventType.METRICS_HIDDEN, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.getFirst().to()).isEqualTo("a@test.io");
		assertThat(statusOf(e1)).isEqualTo("SENT");
		assertThat(statusOf(e2)).isEqualTo("SENT");
		assertThat(db.queryForObject("""
				SELECT email_sent_at IS NOT NULL FROM alarm_event WHERE id=?""", Boolean.class, e1))
				.isTrue();
	}

	@Test
	void 유저가_다르면_통도_다르다() {
		user(7, "a@test.io");
		user(9, "b@test.io");
		event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		event(9, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).hasSize(2);
	}

	/**
	 * 디바운스 — 시딩 수십 건을 연속 등록하면 즉시 레인 이벤트가 몰아친다.
	 * 대기 없이 보내면 등록할 때마다 메일이 한 통씩 나가 받은편지함이 찢어진다.
	 */
	@Test
	void 방금_들어온_이벤트가_있으면_이번_틱은_건너뛴다() {
		user(7, "a@test.io");
		event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		long fresh = event(7, AlarmEventType.COLLECTION_STARTED, NOW.minusSeconds(60), NOW.minusSeconds(60));

		job.run();

		assertThat(mail.sent).isEmpty();
		assertThat(statusOf(fresh)).isEqualTo("PENDING");   // 다음 틱에 함께 나간다
	}

	@Test
	void 발송_시각이_안_된_행은_대상이_아니다() {
		user(7, "a@test.io");
		long future = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, NOW.plusSeconds(3600));

		job.run();

		assertThat(mail.sent).isEmpty();
		assertThat(statusOf(future)).isEqualTo("PENDING");
	}

	/** 옵트아웃은 메일만 끈다 — 대장 행은 남아 앱 내 알림으로 계속 서빙된다(스펙 §3-3). */
	@Test
	void 옵트아웃_이벤트는_SKIPPED_OPTOUT으로_닫히고_나머지만_발송된다() {
		user(7, "a@test.io");
		optOut(7, AlarmEventType.METRICS_HIDDEN);
		long muted = event(7, AlarmEventType.METRICS_HIDDEN, SETTLED, SETTLED);
		long live = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(statusOf(muted)).isEqualTo("SKIPPED_OPTOUT");
		assertThat(statusOf(live)).isEqualTo("SENT");
		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.getFirst().text()).doesNotContain("일부 지표 비공개");
	}

	@Test
	void 전부_옵트아웃이면_메일을_보내지_않는다() {
		user(7, "a@test.io");
		optOut(7, AlarmEventType.COLLECTION_STARTED);
		long muted = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).isEmpty();
		assertThat(statusOf(muted)).isEqualTo("SKIPPED_OPTOUT");
	}

	/** 옵트아웃 행이 없으면 켜짐이 기본 — 빈 테이블로 전원 on(설정 화면과 1:1). */
	@Test
	void 옵트아웃_행이_없으면_기본_발송이다() {
		user(7, "a@test.io");
		event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).hasSize(1);
	}

	/** 유저 삭제·이메일 부재는 재시도해도 보낼 곳이 없다 — FAILED로 두면 매 틱 헛돈다. */
	@Test
	void 수신자가_없으면_SKIPPED_NO_RECIPIENT로_종결한다() {
		user(7, null);
		long orphan = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		long ghost = event(8, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);   // app.users에 없는 유저

		job.run();

		assertThat(statusOf(orphan)).isEqualTo("SKIPPED_NO_RECIPIENT");
		assertThat(statusOf(ghost)).isEqualTo("SKIPPED_NO_RECIPIENT");
		assertThat(mail.sent).isEmpty();
	}

	/** 발송 실패는 행 단위 FAILED — 다음 틱이 그 행만 다시 보낸다(전체 재발송 없음). */
	@Test
	void 발송_실패는_FAILED로_남고_다음_틱에_다시_시도된다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		mail.fail = true;

		job.run();
		assertThat(statusOf(e1)).isEqualTo("FAILED");
		assertThat(db.queryForObject("""
				SELECT email_sent_at IS NULL FROM alarm_event WHERE id=?""", Boolean.class, e1)).isTrue();

		mail.fail = false;
		job.run();

		assertThat(statusOf(e1)).isEqualTo("SENT");
		assertThat(mail.sent).hasSize(1);
	}

	/**
	 * 재시도 상한 — 영구 실패 수신자(주소 폐기·도메인 거부) 하나가 5분마다 무한히 Resend를 때리는 걸 막는다.
	 * 별도 "포기" 상태를 두지 않는다: FAILED + attempts >= 상한이면 due 조회에서 자연히 빠진다.
	 */
	@Test
	void 상한에_도달한_행은_더_이상_시도되지_않는다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		mail.fail = true;

		for (int tick = 0; tick < 6; tick++) {
			job.run();
		}

		// 상한 5 — 6번째 틱은 조회 단계에서 걸러져 발송기를 부르지도 않는다.
		assertThat(mail.attempts).isEqualTo(5);
		assertThat(statusOf(e1)).isEqualTo("FAILED");
		assertThat(db.queryForObject("SELECT email_attempts FROM alarm_event WHERE id=?",
				Integer.class, e1)).isEqualTo(5);
	}

	/** 한 유저의 실패가 다른 유저의 발송을 막으면 장애 하나가 알람 전체를 멈춘다. */
	@Test
	void 이미_종결된_행은_다시_보내지_않는다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();
		job.run();

		assertThat(mail.sent).hasSize(1);
		assertThat(statusOf(e1)).isEqualTo("SENT");
	}
}
