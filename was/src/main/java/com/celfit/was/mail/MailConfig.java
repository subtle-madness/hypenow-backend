package com.celfit.was.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 발송 구현 선택 — was.mail.resend-api-key가 비어 있으면 LoggingMailSender(로컬·테스트),
 * 있으면 ResendMailSender. 프로파일 분기 대신 키 유무 단일 기준(설정 실수여도 부팅은 된다).
 *
 * 07-29 이메일 인증 제거(cc14c717) 때 send 엔드포인트 남용 표면과 함께 철거됐다가,
 * 모니터링 이메일 알람(크론 내부 전용 — 공개 발송 엔드포인트 없음)을 위해 인프라만 복구(07-29).
 */
@Configuration
public class MailConfig {

	private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

	@Bean
	MailSender mailSender(@Value("${was.mail.resend-api-key:}") String apiKey,
			@Value("${was.mail.from:hypenow <no-reply@hypenow.io>}") String from) {
		if (apiKey == null || apiKey.isBlank()) {
			log.warn("RESEND_API_KEY 미설정 — 메일을 실발송하지 않고 로그로만 출력한다(운영이라면 설정 누락)");
			return new LoggingMailSender();
		}
		// 동기 가입 경로에서 호출되므로 무한 블록 금지 — connect 5초·read 10초
		// (analytics JobConfig/AnthropicContentAttributeAnalyzer의 JDK HttpClient 타임아웃 관용구를 RestClient에 이식)
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		RestClient restClient = RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl("https://api.resend.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
		log.info("Resend 메일 발송 활성 from={}", from);
		return new ResendMailSender(restClient, from);
	}
}
