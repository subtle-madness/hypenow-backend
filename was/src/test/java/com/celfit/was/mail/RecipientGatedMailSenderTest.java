package com.celfit.was.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipientGatedMailSenderTest {

	/** 호출 인자를 그대로 기록하는 스텁 — real/fallback 중 어느 쪽이 실제로 호출됐는지 검증용. */
	private static class RecordingMailSender implements MailSender {

		private final List<String> sentTo = new ArrayList<>();

		@Override
		public void send(String to, String subject, String text) {
			sentTo.add(to);
		}
	}

	@Test
	void 허용된_수신자는_real로_발송된다() {
		RecordingMailSender real = new RecordingMailSender();
		RecordingMailSender fallback = new RecordingMailSender();
		RecipientGatedMailSender gated = new RecipientGatedMailSender(real, fallback, to -> to.equals("admin@hypenow.io"));

		gated.send("admin@hypenow.io", "제목", "본문");

		assertThat(real.sentTo).containsExactly("admin@hypenow.io");
		assertThat(fallback.sentTo).isEmpty();
	}

	@Test
	void 비허용_수신자는_fallback으로_발송된다() {
		RecordingMailSender real = new RecordingMailSender();
		RecordingMailSender fallback = new RecordingMailSender();
		RecipientGatedMailSender gated = new RecipientGatedMailSender(real, fallback, to -> to.equals("admin@hypenow.io"));

		gated.send("user@hypenow.io", "제목", "본문");

		assertThat(fallback.sentTo).containsExactly("user@hypenow.io");
		assertThat(real.sentTo).isEmpty();
	}

	@Test
	void 여러_수신자를_섞어_호출해도_각각_올바른_경로로_간다() {
		RecordingMailSender real = new RecordingMailSender();
		RecordingMailSender fallback = new RecordingMailSender();
		RecipientGatedMailSender gated = new RecipientGatedMailSender(real, fallback, to -> to.startsWith("admin"));

		gated.send("admin1@hypenow.io", "제목", "본문");
		gated.send("user1@hypenow.io", "제목", "본문");
		gated.send("admin2@hypenow.io", "제목", "본문");

		assertThat(real.sentTo).containsExactly("admin1@hypenow.io", "admin2@hypenow.io");
		assertThat(fallback.sentTo).containsExactly("user1@hypenow.io");
	}
}
