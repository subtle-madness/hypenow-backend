package com.celfit.monitoring.web;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.service.BrandRegistrationService;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 태그 모니터링 등록/탈퇴 + 제외 문자열 관리 API(수집 파이프라인 진입점 — was 조회 API·FE 계약은 범위 밖).
 * 201 신규 / 200 replay / 204 탈퇴(이미 닫힘 포함, 멱등)·제외 문자열 교체 / 404 미등록·비ACTIVE·IG 계정 부재 /
 * 400 형식 위반 / 422 비공개 계정·제외 문자열 빈 목록 거부(비소급 오염 방지) — 예외 매핑은 ApiExceptionHandler 공용.
 */
@RestController
@RequestMapping("/api/brands")
public class BrandController {

	/** brandName은 하위 호환용 nullable — 기존 요청 바디(필드 없음)는 null로 들어와 계정명 유도 2종 태그만 시드된다. */
	public record BrandRegisterRequest(String username, String brandName) {}

	public record BrandRegisterResponse(long brandId, String username, Long followers, String status) {}

	/** 자사 제외 문자열 — GET 응답·PUT 요청 바디 공용. terms는 정규화(trim·소문자·blank 제거·중복 제거) 후 저장. */
	public record HashtagExclusionsBody(List<String> terms) {}

	private final BrandRegistrationService service;
	private final BrandRepository brands;
	private final BrandHashtagRepository hashtags;

	public BrandController(BrandRegistrationService service, BrandRepository brands,
			BrandHashtagRepository hashtags) {
		this.service = service;
		this.brands = brands;
		this.hashtags = hashtags;
	}

	@PostMapping
	public ResponseEntity<BrandRegisterResponse> register(@RequestBody BrandRegisterRequest req) {
		BrandRegistrationService.Result result = service.register(req.username(), req.brandName());
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.body(new BrandRegisterResponse(result.brandId(), result.username(),
						result.followers(), "ACTIVE"));
	}

	@DeleteMapping("/{username}")
	public ResponseEntity<Void> deregister(@PathVariable String username) {
		return switch (service.deregister(username)) {
			// 이미 닫힘도 멱등 204 — was 재시도(타임아웃·크래시 복구)가 안전해야 한다.
			case CLOSED, ALREADY_CLOSED -> ResponseEntity.noContent().build();
			case NOT_FOUND -> ResponseEntity.notFound().build();
		};
	}

	@GetMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<?> exclusions(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		return ResponseEntity.ok(new HashtagExclusionsBody(hashtags.findExclusionTerms(row.get().id())));
	}

	/**
	 * 전체 교체(PUT 계약) — 정규화 후 저장, 브랜드 미존재·비ACTIVE는 404.
	 * 정규화 결과가 빈 목록이면 422로 거부한다 — 판정은 비소급(이미 저장된 verdict 불변)이라
	 * 빈 목록으로 전부 지우면 자사 게시물(스트림의 71~87%)이 RELEVANT로 저장된 뒤 term을
	 * 되돌려도 복구 불가(EmptyExclusionTermsException 참조). 브랜드 조회를 먼저 해 미등록·
	 * 비ACTIVE는 이 가드보다 우선해 여전히 404다.
	 */
	@PutMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<?> replaceExclusions(@PathVariable String username,
			@RequestBody HashtagExclusionsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		List<String> normalized = normalize(body.terms());
		if (normalized.isEmpty()) {
			throw new EmptyExclusionTermsException("제외 문자열은 최소 1개 필요합니다.");
		}
		hashtags.replaceExclusionTerms(row.get().id(), normalized);
		return ResponseEntity.noContent().build();
	}

	private Optional<BrandRow> activeBrand(String username) {
		return brands.findByUsername(username).filter(row -> row.status() == BrandStatus.ACTIVE);
	}

	/**
	 * 제외 문자열 GET/PUT 전용 404 — 계약 §2 어휘 {@code {code, message}}를 채운 바디다.
	 * deregister의 빈 바디 404(멱등 삼킴을 위한 별개 계약, was가 onStatus로 흡수)와는 의도적으로 다르다 —
	 * 여기는 was가 그대로 흘려도 되는 조회·설정 API라 에러 바디가 없으면 exchange()가 코드 없는 응답으로
	 * 오인해 MonitoringUnavailableException(503)으로 잘못 승격한다(08-11 실측).
	 */
	private static ResponseEntity<ApiError> brandNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다."));
	}

	/** trim → 소문자 → blank 제거 → 중복 제거(입력 순서 보존). terms가 null이면 빈 목록. */
	private static List<String> normalize(List<String> terms) {
		if (terms == null) {
			return List.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String term : terms) {
			if (term == null) {
				continue;
			}
			String cleaned = term.strip().toLowerCase();
			if (!cleaned.isBlank()) {
				normalized.add(cleaned);
			}
		}
		return List.copyOf(normalized);
	}
}
