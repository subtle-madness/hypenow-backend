package com.celfit.was.mail;

import java.util.function.Predicate;

/**
 * 수신자 게이트 발송 — 허용된 수신자만 실발송하고 나머지는 폴백(로깅)으로 보낸다.
 * test 환경 전용 배선(admin 계정만 실발송 — 08-12): test DB에 실사용자 이메일이 있어
 * 전원 실발송을 켤 수 없고, 전원 로깅이면 발송 경로를 검증할 수 없다는 딜레마의 절충.
 */
public class RecipientGatedMailSender implements MailSender {

	private final MailSender real;
	private final MailSender fallback;
	private final Predicate<String> allowRealSend;

	public RecipientGatedMailSender(MailSender real, MailSender fallback, Predicate<String> allowRealSend) {
		this.real = real;
		this.fallback = fallback;
		this.allowRealSend = allowRealSend;
	}

	@Override
	public void send(String to, String subject, String text) {
		(allowRealSend.test(to) ? real : fallback).send(to, subject, text);
	}
}
