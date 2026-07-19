package com.celfit.was.mail;

/** 메일 발송 실패(Resend 비2xx·타임아웃 등). */
public class MailSendException extends RuntimeException {

	public MailSendException(String message) {
		super(message);
	}

	public MailSendException(String message, Throwable cause) {
		super(message, cause);
	}
}
