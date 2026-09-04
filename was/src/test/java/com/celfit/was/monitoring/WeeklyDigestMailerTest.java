package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.PiiTestSeed;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import com.celfit.was.mail.MailSendException;
import com.celfit.was.mail.MailSender;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 주간 리포트 메일 발송(설계 §6) — 옵트아웃·at-least-once·시도 상한·중복 발송 방지. */
class WeeklyDigestMailerTest extends IntegrationTest {

	private static final WeekWindow WEEK = new WeekWindow(LocalDate.of(2026, 8, 17));
	private static final List<DigestItem> ITEMS = List.of(
			new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 1, null));

	/** 발송 기록만 남기는 스텁 — 실패 모드는 생성자 플래그로 켠다. */
	private static final class RecordingMailSender implements MailSender {
		private final List<String> sent = new ArrayList<>();
		private boolean failing;

		@Override
		public void send(String to, String subject, String text) {
			if (failing) {
				throw new MailSendException("발송 실패(테스트)");
			}
			sent.add(to + "|" + subject);
		}
	}

	@Autowired
	DigestRepository digestRepository;
	@Autowired
	WeeklyEmailOptOutRepository optOutRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	FieldCipher fieldCipher;

	RecordingMailSender mailSender;
	WeeklyDigestMailer mailer;
	long userId;
	long digestId;

	@BeforeEach
	void setUp() {
		mailSender = new RecordingMailSender();
		mailer = new WeeklyDigestMailer(digestRepository, optOutRepository, userRepository,
				new WeeklyDigestMailComposer("https://hypenow.io"), mailSender, 3, Duration.ZERO);
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "weekly-mail-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		PiiTestSeed.backfill(jdbcClient, fieldCipher);
		digestId = digestRepository.upsert(userId, WEEK.startDate(), "[]");
	}

	private long attempts() {
		return jdbcClient.sql("SELECT email_attempts FROM app.monitoring_digests WHERE id = :id")
				.param("id", digestId).query(Long.class).single();
	}

	private boolean sentMarked() {
		return Boolean.TRUE.equals(jdbcClient
				.sql("SELECT email_sent_at IS NOT NULL FROM app.monitoring_digests WHERE id = :id")
				.param("id", digestId).query(Boolean.class).single());
	}

	@Test
	void 메일을_보내고_발송_시각을_찍는다() {
		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(mailSender.sent).hasSize(1);
		assertThat(mailSender.sent.get(0)).contains("[hypenow] 지난주 모니터링 요약 (8월 17일 - 8월 23일)");
		assertThat(sentMarked()).isTrue();
	}

	@Test
	void 이미_보낸_다이제스트는_다시_보내지_않는다() {
		mailer.send(userId, digestId, WEEK, ITEMS);
		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(mailSender.sent).hasSize(1);
	}

	@Test
	void 옵트아웃_유저에게는_보내지_않고_시도도_올리지_않는다() {
		optOutRepository.optOut(userId);

		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(mailSender.sent).isEmpty();
		assertThat(sentMarked()).isFalse();
		assertThat(attempts()).isZero();
	}

	@Test
	void 발송_실패는_시도만_올리고_다음_틱에_재시도된다() {
		mailSender.failing = true;
		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(sentMarked()).isFalse();
		assertThat(attempts()).isEqualTo(1);

		mailSender.failing = false;
		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(mailSender.sent).hasSize(1);
		assertThat(sentMarked()).isTrue();
	}

	@Test
	void 시도_상한에_닿으면_더_이상_시도하지_않는다() {
		mailSender.failing = true;
		mailer.send(userId, digestId, WEEK, ITEMS);
		mailer.send(userId, digestId, WEEK, ITEMS);
		mailer.send(userId, digestId, WEEK, ITEMS);
		assertThat(attempts()).isEqualTo(3);

		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(attempts()).isEqualTo(3);   // 상한(3)에 닿아 더 집지 않는다
	}

	@Test
	void 수신_이메일이_없으면_헛돌지_않게_종결한다() {
		// 읽기 전환(09-04) 이후 수신 주소는 email_enc에서 복호화한다 — 평문만 비우면 아무 일도 안 일어난다
		jdbcClient.sql("UPDATE app.users SET email = '', email_enc = :enc WHERE id = :id")
				.param("enc", fieldCipher.encrypt(""))
				.param("id", userId)
				.update();

		mailer.send(userId, digestId, WEEK, ITEMS);

		assertThat(mailSender.sent).isEmpty();
		assertThat(sentMarked()).isTrue();
	}
}
