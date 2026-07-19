package com.celfit.was.mail;

/** 트랜잭션 메일 발송 포트 — 실패는 MailSendException(호출측이 502 EMAIL_SEND_FAILED로 변환). */
public interface MailSender {

	void send(String to, String subject, String text);
}
