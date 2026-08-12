package com.celfit.monitoring.web;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.service.BrandHashtagTags;
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
 * 브랜드 태그 모니터링 등록/탈퇴 + 제외 문자열 관리 + 태그 셋 관리 API(수집 파이프라인 진입점 —
 * was 조회 API·FE 계약은 범위 밖). 태그·제외 문자열 두 리소스 모두 GET(조회)·PUT(전체 교체)·
 * POST(단건·다건 추가)·DELETE {item}(단건 삭제)·DELETE(전체 삭제) 표준 REST 5종을 제공한다
 * (2026-08-12 확장 — 유저 결정: 태그·제외 문자열에 표준 REST 단건 조작 추가). 저장은 전부
 * tombstone(deleted_at) — 하드 삭제하면 등록 replay의 자동 시드가 되살리기 때문.
 * 201 신규 / 200 replay / 204 탈퇴(이미 닫힘 포함, 멱등)·교체·추가·삭제 / 404 미등록·비ACTIVE·
 * IG 계정 부재 / 400 형식 위반 / 422 비공개 계정·태그 무효 문자·태그 추가 빈 입력 — 예외 매핑은
 * ApiExceptionHandler 공용. <b>PUT 빈 목록은 이제 허용된다</b>(2026-08-12) — 단건 삭제·전체 삭제
 * API가 생겨 "전체 비우기"가 더 이상 실수로만 일어나는 상태가 아니다(구 EmptyExclusionTermsException
 * 하한 가드는 폐지).
 */
@RestController
@RequestMapping("/api/brands")
public class BrandController {

	/**
	 * brandName·collectionMonths는 하위 호환용 nullable — 기존 요청 바디(필드 없음)는 null로 들어와
	 * 계정명 유도 2종 태그만 시드되고, 수집 창은 기본 12개월로 접힌다.
	 */
	public record BrandRegisterRequest(String username, String brandName, Integer collectionMonths) {}

	public record BrandRegisterResponse(long brandId, String username, Long followers, String status) {}

	/** 자사 제외 문자열 — GET 응답·PUT 요청 바디 공용. terms는 정규화(trim·소문자·blank 제거·중복 제거) 후 저장. */
	public record HashtagExclusionsBody(List<String> terms) {}

	/**
	 * 태그 셋(유저 관리 API, 2026-08-12) — GET 응답·PUT 요청 바디 공용. tags는 정규화(trim·선행 #
	 * 제거·소문자·blank 제거·중복 제거) 후 저장하되, 무효 문자를 포함한 항목은 절삭하지 않고
	 * 통째로 거부한다(자동 유도 BrandHashtagTags.derive와 의도적으로 다른 규칙 — 유저 입력이라
	 * 잘라내면 유저가 입력한 문자열과 실제 저장된 태그가 어긋난다).
	 */
	public record HashtagTagsBody(List<String> tags) {}

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
		BrandRegistrationService.Result result = service.register(req.username(), req.brandName(),
				req.collectionMonths());
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
	 * 전체 교체(PUT 계약) — 정규화 후 저장(tombstone 의미론은 {@link BrandHashtagRepository#replaceExclusionTerms}
	 * 참조), 브랜드 미존재·비ACTIVE는 404. 빈 목록도 허용한다(2026-08-12 — 전체 삭제 API가 생겨
	 * "전부 지우기"가 정당한 상태이므로 구 하한 가드는 폐지, {@link #deleteAllExclusions} 참조).
	 */
	@PutMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<?> replaceExclusions(@PathVariable String username,
			@RequestBody HashtagExclusionsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		hashtags.replaceExclusionTerms(row.get().id(), normalize(body.terms()));
		return ResponseEntity.noContent().build();
	}

	/** 단건·다건 추가(POST 계약) — 정규화 후 저장(tombstone 재활성). 빈 입력은 추가할 게 없다는 뜻이라 무해한 204. */
	@PostMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<?> addExclusions(@PathVariable String username,
			@RequestBody(required = false) HashtagExclusionsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		hashtags.addExclusionTerms(row.get().id(), normalize(body == null ? null : body.terms()));
		return ResponseEntity.noContent().build();
	}

	/** 단건 삭제(tombstone, DELETE {term} 계약) — 정규화 후 삭제, 없어도 멱등 204. */
	@DeleteMapping("/{username}/hashtag-exclusions/{term}")
	public ResponseEntity<?> deleteExclusion(@PathVariable String username, @PathVariable String term) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		String normalized = normalizeItem(term);
		if (normalized != null) {
			hashtags.deleteExclusionTerm(row.get().id(), normalized);
		}
		return ResponseEntity.noContent().build();
	}

	/** 전체 삭제(tombstone, DELETE 계약) — 자사 오탐 필터를 브랜드 단위로 완전히 끈다. */
	@DeleteMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<?> deleteAllExclusions(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		hashtags.deleteAllExclusionTerms(row.get().id());
		return ResponseEntity.noContent().build();
	}

	/** 활성 태그 조회(유저 관리 API) — 브랜드 미존재·비ACTIVE는 제외 문자열과 동형으로 404. */
	@GetMapping("/{username}/hashtag-tags")
	public ResponseEntity<?> hashtagTags(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		return ResponseEntity.ok(new HashtagTagsBody(hashtags.findTags(row.get().id())));
	}

	/**
	 * 태그 셋 전체 교체(유저 관리 API, 2026-08-12) — 정규화 후 저장(tombstone 의미론은
	 * {@link BrandHashtagRepository#replaceTags} 참조). 브랜드 미존재·비ACTIVE는 404가 이 가드보다
	 * 우선한다(제외 문자열과 같은 순서).
	 *
	 * <p>유효 문자 검증은 유저 입력이므로 자동 유도(BrandHashtagTags.derive)처럼 절삭하지 않고
	 * 통째로 거부한다 — 무효 문자 포함 항목이 하나라도 있으면 422(문제 태그를 메시지에 명시).
	 * 빈 목록은 허용한다(2026-08-12 — 전체 삭제 API가 생겨 "전부 지우기"가 정당한 상태이므로 구
	 * 하한 가드는 폐지, {@link #deleteAllHashtagTags} 참조. 브랜드 태그 감지가 전부 꺼지는 셈이다).
	 */
	@PutMapping("/{username}/hashtag-tags")
	public ResponseEntity<?> replaceHashtagTags(@PathVariable String username,
			@RequestBody HashtagTagsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		List<String> normalized = normalizeTags(body.tags());
		rejectInvalidTags(normalized);
		hashtags.replaceTags(row.get().id(), normalized);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 단건·다건 추가(POST 계약) — 정규화·유효 문자 검증 후 저장(tombstone 재활성). PUT과 달리 빈
	 * 입력은 422로 거부한다 — "추가할 태그가 없다"는 요청 자체가 무의미해 실수일 확률이 높다
	 * (전체를 비우는 명시적 의도는 DELETE 전체가 담당).
	 */
	@PostMapping("/{username}/hashtag-tags")
	public ResponseEntity<?> addHashtagTags(@PathVariable String username,
			@RequestBody(required = false) HashtagTagsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		List<String> normalized = normalizeTags(body == null ? null : body.tags());
		rejectInvalidTags(normalized);
		if (normalized.isEmpty()) {
			throw new InvalidHashtagException("추가할 태그가 없습니다.");
		}
		hashtags.addTags(row.get().id(), normalized);
		return ResponseEntity.noContent().build();
	}

	/** 단건 삭제(tombstone, DELETE {tag} 계약) — 정규화 후 삭제, 없어도 멱등 204. */
	@DeleteMapping("/{username}/hashtag-tags/{tag}")
	public ResponseEntity<?> deleteHashtagTag(@PathVariable String username, @PathVariable String tag) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		String normalized = normalizeTagItem(tag);
		if (normalized != null) {
			hashtags.deleteTag(row.get().id(), normalized);
		}
		return ResponseEntity.noContent().build();
	}

	/** 전체 삭제(tombstone, DELETE 계약) — 브랜드 단위로 해시태그 감지를 일시 중지하는 것과 같다. */
	@DeleteMapping("/{username}/hashtag-tags")
	public ResponseEntity<?> deleteAllHashtagTags(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		hashtags.deleteAllTags(row.get().id());
		return ResponseEntity.noContent().build();
	}

	/** 무효 문자 포함 항목이 하나라도 있으면 422(문제 태그를 메시지에 명시) — PUT·POST 공용. */
	private static void rejectInvalidTags(List<String> normalized) {
		List<String> invalid = normalized.stream().filter(tag -> !BrandHashtagTags.isValidTag(tag)).toList();
		if (!invalid.isEmpty()) {
			throw new InvalidHashtagException("사용할 수 없는 문자가 포함된 태그입니다: " + String.join(", ", invalid));
		}
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
			String cleaned = normalizeItem(term);
			if (cleaned != null) {
				normalized.add(cleaned);
			}
		}
		return List.copyOf(normalized);
	}

	/** 단건 정규화(trim → 소문자) — null·blank는 null(호출측이 "대상 없음"으로 처리). */
	private static String normalizeItem(String term) {
		if (term == null) {
			return null;
		}
		String cleaned = term.strip().toLowerCase();
		return cleaned.isBlank() ? null : cleaned;
	}

	/** trim → 선행 # 제거 → 소문자 → blank 제거 → 중복 제거(입력 순서 보존). tags가 null이면 빈 목록. */
	private static List<String> normalizeTags(List<String> tags) {
		if (tags == null) {
			return List.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String tag : tags) {
			String cleaned = normalizeTagItem(tag);
			if (cleaned != null) {
				normalized.add(cleaned);
			}
		}
		return List.copyOf(normalized);
	}

	/** 단건 정규화(trim → 선행 # 제거 → 소문자) — null·blank는 null(호출측이 "대상 없음"으로 처리). */
	private static String normalizeTagItem(String tag) {
		if (tag == null) {
			return null;
		}
		String stripped = tag.strip();
		if (stripped.startsWith("#")) {
			stripped = stripped.substring(1);
		}
		String cleaned = stripped.strip().toLowerCase();
		return cleaned.isBlank() ? null : cleaned;
	}
}
