package com.celfit.was.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendMailSenderTest {

	@Test
	void 성공_응답이면_예외없이_발송된다() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
				.defaultHeader("Authorization", "Bearer test-key");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
				.andExpect(header("Authorization", "Bearer test-key"))
				.andExpect(jsonPath("$.to[0]").value("a@b.c"))
				.andExpect(jsonPath("$.subject").value("제목"))
				.andRespond(withSuccess("{\"id\":\"re_1\"}", MediaType.APPLICATION_JSON));

		ResendMailSender sender = new ResendMailSender(builder.build(), "hypenow <no-reply@hypenow.io>");
		assertThatCode(() -> sender.send("a@b.c", "제목", "본문")).doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void 비2xx_응답이면_MailSendException() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		ResendMailSender sender = new ResendMailSender(builder.build(), "hypenow <no-reply@hypenow.io>");
		assertThatThrownBy(() -> sender.send("a@b.c", "제목", "본문"))
				.isInstanceOf(MailSendException.class);
	}
}
