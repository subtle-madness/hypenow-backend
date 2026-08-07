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
 * monitoring 내부 명령 API — 등록·연장·해지 3종 + share 해소 1종(계약 v2.1 §2). 인증 없음 —
 * 도커 내부망 전용(07-28 토큰 제거 결정). 승인·기각(v1)은 v2에서 감지 즉시 자동 추적으로 폐지돼
 * was 클라이언트에서도 제거됐다(호출 시 monitoring이 404).
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

	/** 공유 단축 링크 해소(계약 §2-6) — 등록과 분리된 전처리 API. 등록 전 shortcode를 얻을 때 호출한다. */
	public ShareResolveResult resolveShare(String url) {
		return exchange(() -> restClient.post().uri("/api/share/resolve")
				.body(new ShareResolveRequest(url)).retrieve().body(ShareResolveResult.class));
	}

	public ExtendResult extend(long targetId, OffsetDateTime expiresAt) {
		return exchange(() -> restClient.patch().uri("/api/targets/{id}", targetId)
				.body(new ExtendRequest(expiresAt)).retrieve().body(ExtendResult.class));
	}

	public CancelResult cancel(long targetId) {
		return exchange(() -> restClient.delete().uri("/api/targets/{id}", targetId)
				.retrieve().body(CancelResult.class));
	}

	/**
	 * 브랜드 태그 모니터링 등록(monitoring BrandController §2 — 동기 프로필 검증 + 비동기 백필 시작).
	 * 201 신규 / 200 replay 모두 같은 바디라 was는 둘을 구분하지 않는다(멱등 재등록 안전).
	 * 404(IG 계정 부재)·422(비공개 계정)는 에러 바디 code 그대로 MonitoringApiException으로 승격된다.
	 */
	public BrandRegisterResult registerBrand(String username) {
		return exchange(() -> restClient.post().uri("/api/brands")
				.body(new BrandRegisterRequest(username)).retrieve().body(BrandRegisterResult.class));
	}

	/**
	 * 브랜드 탈퇴 — 404(monitoring에 미등록)는 목적 달성이라 삼킨다(삭제 재시도 안전).
	 *
	 * <p>404를 예외로 받아 걸러내지 않고 onStatus로 미리 삼키는 이유: monitoring의 탈퇴 404는
	 * {@code ResponseEntity.notFound()}라 바디가 비어 있어 {@code {code, message}}가 없다 —
	 * 그대로 두면 {@link #exchange}의 "응답 해석 불가" 규칙에 걸려 MonitoringApiException이 아니라
	 * MonitoringUnavailableException이 된다. 그 계열을 catch로 삼키면 진짜 접속 실패까지 같이
	 * 삼켜지므로, 상태 코드 단계에서 404만 정확히 흡수한다. 나머지 4xx·5xx·전송 실패는 그대로 승격.
	 */
	public void deregisterBrand(String username) {
		exchange(() -> restClient.delete().uri("/api/brands/{username}", username)
				.retrieve()
				.onStatus(status -> status.value() == 404,
						(request, response) -> log.info("브랜드 탈퇴 — monitoring에 미등록(이미 정리됨): {}", username))
				.toBodilessEntity());
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

	record ShareResolveRequest(String url) {
	}

	record BrandRegisterRequest(String username) {
	}

	/** monitoring BrandController.BrandRegisterResponse와 동형 — followers는 등록 시점 관측값(null 가능). */
	public record BrandRegisterResult(long brandId, String username, Long followers, String status) {
	}
}
