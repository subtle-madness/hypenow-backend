package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * monitoring 내부 명령 API 5개(계약 §2). 인증 없음 — 도커 내부망 전용(07-28 토큰 제거 결정).
 * 에러는 2계열로 승격: 에러 바디 {code, message} → MonitoringApiException(code 그대로),
 * 전송 실패·해석 불가 → MonitoringUnavailableException(같은 멱등키 재시도 가능 신호).
 */
public class MonitoringCommandClient {

	private static final Logger log = LoggerFactory.getLogger(MonitoringCommandClient.class);

	private final RestClient restClient;

	public MonitoringCommandClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public RegisterResult register(RegisterRequest request) {
		return exchange(() -> restClient.post().uri("/api/targets")
				.body(request).retrieve().body(RegisterResult.class));
	}

	public ApproveResult approve(long targetId, long candidateId) {
		return exchange(() -> restClient.post()
				.uri("/api/targets/{id}/candidates/{cid}/approve", targetId, candidateId)
				.retrieve().body(ApproveResult.class));
	}

	public RejectResult reject(long targetId, long candidateId) {
		return exchange(() -> restClient.post()
				.uri("/api/targets/{id}/candidates/{cid}/reject", targetId, candidateId)
				.retrieve().body(RejectResult.class));
	}

	public ExtendResult extend(long targetId, OffsetDateTime expiresAt) {
		return exchange(() -> restClient.patch().uri("/api/targets/{id}", targetId)
				.body(new ExtendRequest(expiresAt)).retrieve().body(ExtendResult.class));
	}

	public CancelResult cancel(long targetId) {
		return exchange(() -> restClient.delete().uri("/api/targets/{id}", targetId)
				.retrieve().body(CancelResult.class));
	}

	private <T> T exchange(Supplier<T> call) {
		try {
			return call.get();
		} catch (RestClientResponseException e) {
			ErrorBody body = parseErrorBody(e);
			if (body == null || body.code() == null) {
				throw new MonitoringUnavailableException(
						"monitoring 응답 해석 불가 HTTP " + e.getStatusCode().value(), e);
			}
			throw new MonitoringApiException(body.code(), body.message(), e.getStatusCode().value(), e);
		} catch (ResourceAccessException e) {
			throw new MonitoringUnavailableException("monitoring 접속 실패: " + e.getMessage(), e);
		} catch (RestClientException e) {
			// 2xx인데 바디 파싱 실패 등 — 성공 여부 불명이므로 같은 키 재시도 가능 계열로 승격
			throw new MonitoringUnavailableException("monitoring 응답 처리 실패: " + e.getMessage(), e);
		}
	}

	private ErrorBody parseErrorBody(RestClientResponseException e) {
		try {
			return e.getResponseBodyAs(ErrorBody.class);
		} catch (RuntimeException parseFailure) {
			log.debug("monitoring 에러 바디 해석 실패 — 전송 계열로 처리 (HTTP {}): {}",
					e.getStatusCode().value(), parseFailure.getMessage());
			return null;   // JSON 아님·빈 바디 — 전송 계열로 처리
		}
	}

	record ErrorBody(String code, String message) {
	}
}
