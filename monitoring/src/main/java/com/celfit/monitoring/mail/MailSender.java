package com.celfit.monitoring.mail;

/** 메일 발송 포트 — 실패는 MailSendException(호출측이 알람 행을 FAILED로 종결하고 다음 틱에 재시도). */
public interface MailSender {

	void send(String to, String subject, String text);
}
