package com.celfit.was.monitoring;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.mail.MailSender;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 주간 리포트 메일 발송(설계 §6) — 다이제스트 <b>생성 직후</b> 같은 루프에서 1통 보낸다.
 * 구 5분 틱 디스패처와 디바운스는 이 구조에서 의미가 없어 제거됐다(주간 리듬은 애초에 몰아치지
 * 않는다).
 *
 * <h2>워터마크가 없다 — 발송 대장은 다이제스트 행이다</h2>
 * 무엇을 보냈는지는 {@code monitoring_digests.email_sent_at}이 말한다. 실패는 시도만 올리고
 * 삼키므로 같은 주의 따라잡기 틱이 그 행만 다시 집는다(at-least-once — 중복이 유실보다 낫다).
 * 시도 상한이 없으면 영구 실패 수신자 하나가 월요일 내내 재시도를 돈다.
 *
 * <h2>트랜잭션을 걸지 않는다</h2>
 * 발송(외부 HTTP)이 트랜잭션 안에 들어가면 커넥션을 쥔 채 수 초를 기다리고, 커밋 직전 실패가
 * "메일은 나갔는데 SENT는 안 찍힌" 상태를 만든다(monitoring AlarmDispatchJob이 같은 이유로
 * 트랜잭션을 피했다).
 *
 * <h2>발송 시각 집중 완화</h2>
 * 월요일 09:00에 전 유저 발송이 몰린다(설계 §8). 발송 사이에 최소 간격을 둬 Resend 호출이
 * 순간적으로 몰리지 않게 한다 — 배치 API를 새로 붙이는 것보다 단순하고, 주간 1회라 총 소요가
 * 유저 수 × 간격으로 예측 가능하다. 테스트는 간격 0으로 조립한다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class WeeklyDigestMailer {

	private static final Logger log = LoggerFactory.getLogger(WeeklyDigestMailer.class);

	private final DigestRepository digests;
	private final WeeklyEmailOptOutRepository optOuts;
	private final UserRepository users;
	private final WeeklyDigestMailComposer composer;
	private final MailSender mailSender;
	private final int maxAttempts;
	private final Duration sendInterval;

	public WeeklyDigestMailer(DigestRepository digests, WeeklyEmailOptOutRepository optOuts,
			UserRepository users, WeeklyDigestMailComposer composer, MailSender mailSender,
			@Value("${monitoring.digest.email.max-attempts:5}") int maxAttempts,
			@Value("${monitoring.digest.email.send-interval:PT0.2S}") Duration sendInterval) {
		this.digests = digests;
		this.optOuts = optOuts;
		this.users = users;
		this.composer = composer;
		this.mailSender = mailSender;
		this.maxAttempts = maxAttempts;
		this.sendInterval = sendInterval;
	}

	/**
	 * 다이제스트 1건의 주간 리포트 메일 발송. 이미 보냈거나 시도 상한에 닿았으면 no-op이고,
	 * 옵트아웃 유저는 시도조차 올리지 않는다(나중에 다시 켜면 그 주 리포트를 받을 수 있게 남겨 둔다).
	 */
	public void send(long userId, long digestId, WeekWindow window, List<DigestItem> items) {
		if (!digests.isEmailPending(digestId, maxAttempts)) {
			return;
		}
		if (optOuts.isOptedOut(userId)) {
			return;
		}
		String email = users.findById(userId).map(AppUser::email)
				.filter(value -> !value.isBlank())
				.orElse(null);
		if (email == null) {
			// 유저 삭제·이메일 부재 — 재시도해도 보낼 곳이 없다(그냥 두면 매 틱 헛돈다).
			log.info("수신 이메일 없음 — user {} 주간 리포트 종결", userId);
			digests.markEmailSent(digestId);
			return;
		}
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, items);
		try {
			mailSender.send(email, mail.subject(), mail.text());
		} catch (RuntimeException e) {
			log.warn("주간 리포트 발송 실패 — user {}: {}", userId, e.toString());
			digests.markEmailAttempted(digestId);
			return;
		}
		digests.markEmailSent(digestId);
		pace();
	}

	private void pace() {
		if (sendInterval.isZero() || sendInterval.isNegative()) {
			return;
		}
		try {
			Thread.sleep(sendInterval.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
