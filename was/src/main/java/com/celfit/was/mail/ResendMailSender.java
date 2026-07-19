package com.celfit.was.mail;

import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Resend HTTPS API 발송(POST /emails) — SMTP 불사용이라 오라클 아웃바운드 25포트 차단과 무관.
 * 비2xx·네트워크 오류는 MailSendException으로 감싼다.
 */
public class ResendMailSender implements MailSender {

	private final RestClient restClient;
	private final String from;

	public ResendMailSender(RestClient restClient, String from) {
		this.restClient = restClient;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String text) {
		try {
			restClient.post().uri("/emails")
					.body(Map.of("from", from, "to", new String[] {to}, "subject", subject, "text", text))
					.retrieve()
					.toBodilessEntity();
		} catch (RuntimeException e) {
			throw new MailSendException("Resend 발송 실패: " + e.getMessage(), e);
		}
	}
}
