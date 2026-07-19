package com.celfit.was.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RESEND_API_KEY 미설정 시 대체 — 발송 대신 내용을 로그로 출력(로컬 개발·통합 테스트용). */
public class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	@Override
	public void send(String to, String subject, String text) {
		log.info("메일 발송(로깅 모드) to={} subject={} text={}", to, subject, text);
	}
}
