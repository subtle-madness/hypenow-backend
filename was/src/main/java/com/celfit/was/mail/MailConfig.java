package com.celfit.was.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 발송 구현 선택 — was.mail.resend-api-key가 비어 있으면 LoggingMailSender(로컬·테스트),
 * 있으면 ResendMailSender. 프로파일 분기 대신 키 유무 단일 기준(설정 실수여도 부팅은 된다).
 */
@Configuration
public class MailConfig {

	@Bean
	MailSender mailSender(@Value("${was.mail.resend-api-key:}") String apiKey,
			@Value("${was.mail.from:hypenow <no-reply@hypenow.io>}") String from) {
		if (apiKey == null || apiKey.isBlank()) {
			return new LoggingMailSender();
		}
		RestClient restClient = RestClient.builder()
				.baseUrl("https://api.resend.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
		return new ResendMailSender(restClient, from);
	}
}
