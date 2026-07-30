package com.celfit.monitoring.mail;

/** 발송 실패 — 재시도 가능(다음 5분 틱이 같은 행만 다시 보낸다). */
public class MailSendException extends RuntimeException {

	public MailSendException(String message, Throwable cause) {
		super(message, cause);
	}
}
