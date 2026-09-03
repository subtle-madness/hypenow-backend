package com.celfit.was.monitoring;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
	private final RestClient directPostRestClient;

	public MonitoringCommandClient(RestClient restClient) {
		this(restClient, restClient);
	}

	/**
	 * direct 등록 전용 타임아웃 분리(2026-08-18 스테이징 실측, §T7) — {@code directPostRestClient}는
	 * registerDirectPost 호출에만 쓰인다(readTimeout 30s 기본, {@code MonitoringConfig} 참조). 다른
	 * 명령은 전부 기존 {@code restClient}(readTimeout 10s)를 그대로 쓴다 — 단일 인자 생성자는
	 * 두 클라이언트가 같은 것으로 대체돼(테스트·레거시 호출부 호환) 기존 타임아웃 동작을 그대로 보존한다.
	 */
	public MonitoringCommandClient(RestClient restClient, RestClient directPostRestClient) {
		this.restClient = restClient;
		this.directPostRestClient = directPostRestClient;
	}

	public RegisterResult register(RegisterRequest request) {
		return exchange(() -> restClient.post().uri("/api/targets")
				.body(request).retrieve().body(RegisterResult.class));
	}

	/**
	 * 공유 단축 링크 해소(계약 §2-6) — 등록과 분리된 전처리 API. 등록 전 shortcode를 얻을 때 호출한다.
	 * userId는 크롤링 콜 집계 귀속용(2026-08-12 비용 범위 확장) — monitoring이 이 콜을 그 유저의
	 * target_call_count에 계상한다. null이어도 해소는 동작한다(그 콜만 비용 미집계).
	 */
	public ShareResolveResult resolveShare(String url, Long userId) {
		return exchange(() -> restClient.post().uri("/api/share/resolve")
				.body(new ShareResolveRequest(url, userId)).retrieve().body(ShareResolveResult.class));
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
	 *
	 * <p>brandName은 해시태그 브랜드명 태그 감지의 재료(스펙 2026-08-11 §2) — 호출부가 브랜드 유형
	 * 유저의 company_name만 채워 넣고, 그 외에는 null을 보낸다.
	 *
	 * <p>collectionMonths는 수집 창(1|3|6|12) — 이미 활성인 브랜드에 더 큰 값이면 monitoring이 기간 확장으로 처리한다.
	 *
	 * <p>accountType(nullable, 2026-08-19 경쟁사 판정 제거 설계 §2)은 monitoring의 has_own_link
	 * 파생 플래그 초기화·승격 재료다 — {@link com.celfit.was.v1.brandmonitoring.BrandAccountType}의
	 * own/competitor 값을 그대로 전달한다. null은 own과 동치(monitoring 기본값).
	 */
	public BrandRegisterResult registerBrand(String username, String brandName, int collectionMonths,
			String accountType) {
		return exchange(() -> restClient.post().uri("/api/brands")
				.body(new BrandRegisterRequest(username, brandName, collectionMonths, accountType))
				.retrieve().body(BrandRegisterResult.class));
	}

	/**
	 * own-link 플래그 절대값 push(2026-08-19 경쟁사 판정 제거 설계 §2) — was가 연결 변이(changeType
	 * 양방향·부분 해지) 커밋 후 원장(app.brand_monitorings 활성 연결)에서 재계산한 값을 그대로 민다.
	 * {@code deregisterBrand}와 같은 best-effort 컨벤션으로 호출부가 감싼다(실패 시 warn 로그만,
	 * 예외 전파 금지 — 이 메서드 자체는 일반 규칙대로 예외를 던진다).
	 */
	public void pushOwnLink(String username, boolean hasOwnLink) {
		exchange(() -> restClient.put().uri("/api/brands/{username}/own-link", username)
				.body(new OwnLinkRequest(hasOwnLink)).retrieve().toBodilessEntity());
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

	/**
	 * 브랜드 태그 셋 조회(BrandController §태그 관리, 2026-08-12) — 활성 태그 전체.
	 * 404(BRAND_NOT_FOUND)는 다른 브랜드 조회 경로와 동형으로 MonitoringApiException으로 승격된다.
	 */
	public List<String> getHashtagTags(String username) {
		HashtagTagsBody body = exchange(() -> restClient.get()
				.uri("/api/brands/{username}/hashtag-tags", username)
				.retrieve().body(HashtagTagsBody.class));
		return body == null || body.tags() == null ? List.of() : body.tags();
	}

	/**
	 * 태그별 스윕 실행 상태 조회(FE 요청, 2026-08-31 — monitoring BrandController run-state) — 활성
	 * 태그 전체(deleted_at 있는 태그는 monitoring이 이미 빼고 준다). status 계산은 monitoring이
	 * 끝낸 값을 그대로 받는다(BrandHashtagRunStateResolver가 정본 — was는 재계산하지 않는다).
	 */
	public List<TagRunState> getHashtagRunStates(String username) {
		HashtagRunStateBody body = exchange(() -> restClient.get()
				.uri("/api/brands/{username}/hashtag-tags/run-state", username)
				.retrieve().body(HashtagRunStateBody.class));
		return body == null || body.tags() == null ? List.of() : body.tags();
	}

	/**
	 * 해시태그 자동 시드 제안 조회(2026-09-03 자동 시드 재설계 §3-1, monitoring BrandController
	 * hashtag-suggestion) — monitoring이 계산만 해서 돌려주는 값이다(그쪽은 아무것도 쓰지 않는다).
	 * {@code tag}는 항상 비어 있지 않다(FREQ → AI → FALLBACK 3단).
	 *
	 * <p>404(BRAND_NOT_FOUND)는 다른 브랜드 조회 경로와 동형으로 MonitoringApiException으로 승격된다 —
	 * 호출부({@code V1BrandAccountService.ensureAutoSeeded})가 best-effort로 격리한다.
	 */
	public HashtagSuggestionBody getHashtagSuggestion(String username) {
		return exchange(() -> restClient.get()
				.uri("/api/brands/{username}/hashtag-suggestion", username)
				.retrieve().body(HashtagSuggestionBody.class));
	}

	/**
	 * 태그 셋 전체 교체(PUT 계약) — tags는 monitoring이 정규화(trim·#제거·소문자·중복 제거) 후 저장.
	 * 빈 목록도 허용(2026-08-12부터 — monitoring PUT 하한 가드 폐지, 단건·전체 삭제 API 참조).
	 */
	public void putHashtagTags(String username, List<String> tags) {
		exchange(() -> restClient.put().uri("/api/brands/{username}/hashtag-tags", username)
				.body(new HashtagTagsBody(tags)).retrieve().toBodilessEntity());
	}

	/**
	 * 태그 단건·다건 추가(POST 계약, 2026-08-12) — tombstone 재활성(monitoring이 정규화·유효 문자
	 * 검증). 무효 문자 포함·빈 입력은 monitoring이 422로 거부 → MonitoringApiException으로 승격.
	 */
	public void addHashtagTags(String username, List<String> tags) {
		exchange(() -> restClient.post().uri("/api/brands/{username}/hashtag-tags", username)
				.body(new HashtagTagsBody(tags)).retrieve().toBodilessEntity());
	}

	/**
	 * 태그 단건 삭제(tombstone, DELETE {tag} 계약, 2026-08-12) — 없어도 204(멱등).
	 * tag는 URI 템플릿 변수로 넘겨 RestClient가 인코딩한다(한글 등 특수문자 왕복 안전).
	 */
	public void deleteHashtagTag(String username, String tag) {
		exchange(() -> restClient.delete().uri("/api/brands/{username}/hashtag-tags/{tag}", username, tag)
				.retrieve().toBodilessEntity());
	}

	/** 태그 전체 삭제(tombstone, DELETE 계약, 2026-08-12) — 브랜드 태그 감지를 완전히 끈다. */
	public void deleteAllHashtagTags(String username) {
		exchange(() -> restClient.delete().uri("/api/brands/{username}/hashtag-tags", username)
				.retrieve().toBodilessEntity());
	}

	/**
	 * direct 게시물 등록(2026-08-18 direct 통합 §2-2·§4-2, monitoring BrandController §direct 명령) —
	 * 단건 콜로 즉시 수집·보강하는 동기 경로다(최대 5콜 ≈ 7초). 201(신규 수집)·200(이미 풀에 있음,
	 * 멱등)은 같은 바디라 was는 둘을 구분하지 않고 둘 다 성공으로 접는다.
	 *
	 * <p>404({@code POST_NOT_FOUND})·422({@code PRIVATE_ACCOUNT}·{@code POST_UNSUPPORTED})·
	 * 404({@code BRAND_NOT_FOUND})는 에러 바디 code 그대로 {@link MonitoringApiException}으로
	 * 승격된다(계약 어긋나면 {@link MonitoringUnavailableException}(503)으로 잘못 승격 — 클래스 주석
	 * 관용구 재사용).
	 *
	 * <p>registeredAt·importLegacyHistory는 이관 잡(mode=import) 전용이다 — was 실행기의 일반 유저
	 * 등록 경로는 둘 다 비운다(등록 시각은 monitoring이 now()로 채우고, 레거시 이력 복사도 생략).
	 *
	 * <p><b>전용 타임아웃(결함 2, 2026-08-18 스테이징 실측)</b>: monitoring 동기 처리 최대 ~7초 +
	 * 콜드스타트 여유(2026-08-18 스테이징 실측 — 10s 타임아웃으로 응답 유실 1회 관찰). 공용
	 * {@code restClient}(readTimeout 10s)는 여유가 3초뿐이라 이 호출만 {@code directPostRestClient}
	 * (readTimeout 기본 30s, {@code monitoring.command.direct-post-timeout})로 분리했다.
	 */
	public DirectPostResult registerDirectPost(long brandId, String shortCode, OffsetDateTime registeredAt,
			boolean importLegacyHistory) {
		return exchange(() -> directPostRestClient.post().uri("/api/brands/{brandId}/direct-posts", brandId)
				.body(new DirectPostRegisterRequest(shortCode, registeredAt, importLegacyHistory))
				.retrieve().body(DirectPostResult.class));
	}

	/**
	 * direct 게시물 취소(2026-08-18 direct 통합 §2-4) — 매핑 삭제가 아니라 direct 표식 해제.
	 * 행이 없어도 204(멱등) — was의 재시도·이중 취소가 안전해야 한다.
	 */
	public void deleteDirectPost(long brandId, String shortCode) {
		exchange(() -> restClient.delete().uri("/api/brands/{brandId}/direct-posts/{shortCode}", brandId, shortCode)
				.retrieve().toBodilessEntity());
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

	record ShareResolveRequest(String url, Long userId) {
	}

	record BrandRegisterRequest(String username, String brandName, int collectionMonths, String accountType) {
	}

	/** monitoring BrandController.BrandRegisterResponse와 동형 — followers는 등록 시점 관측값(null 가능). */
	public record BrandRegisterResult(long brandId, String username, Long followers, String status) {
	}

	/** monitoring BrandController.OwnLinkRequest와 동형(2026-08-19 경쟁사 판정 제거 설계 §2). */
	record OwnLinkRequest(boolean hasOwnLink) {
	}

	/** monitoring BrandController.HashtagTagsBody와 동형 — GET 응답·PUT 요청 바디 공용. */
	record HashtagTagsBody(List<String> tags) {
	}

	/** monitoring BrandController.TagRunState와 동형(2026-08-31) — status는 monitoring 계산값 그대로. */
	public record TagRunState(String tag, String status, OffsetDateTime lastRunAt, Integer lastFoundCount) {
	}

	/** monitoring BrandController.HashtagRunStateBody와 동형. */
	record HashtagRunStateBody(List<TagRunState> tags) {
	}

	/**
	 * 제안 응답(§3-1) — path는 FREQ|AI|FALLBACK, tag는 항상 비어 있지 않다.
	 * topCount·candidatePosts는 운영 판단 재료(FALLBACK 비율이 높으면 AI 경로가 죽은 것이다)라
	 * 저장하지 않고 로그·검토용으로만 쓴다. Integer인 이유는 필드 누락 응답에서 NPE가 아니라
	 * null로 들어오게 하기 위함이다.
	 */
	public record HashtagSuggestionBody(String path, String tag, Integer topCount, Integer candidatePosts) {
	}

	/** monitoring BrandController.DirectPostRegisterRequest와 동형. */
	record DirectPostRegisterRequest(String shortCode, OffsetDateTime registeredAt, Boolean importLegacyHistory) {
	}

	/** monitoring BrandController.DirectPostResponse와 동형(201·200 공용 셰이프). */
	public record DirectPostResult(String shortCode, String authorUsername, Instant takenAt, String contentType) {
	}
}
