package com.celfit.monitoring.web;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.service.BrandHashtagTags;
import com.celfit.monitoring.service.BrandRegistrationService;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.BrandSeededAccountRepository;
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
 * 브랜드 태그 모니터링 등록/탈퇴 + 태그 셋 관리 API(수집 파이프라인 진입점 — was 조회 API·FE 계약은
 * 범위 밖). 태그는 GET(조회)·PUT(전체 교체)·POST(단건·다건 추가)·DELETE {item}(단건 삭제)·
 * DELETE(전체 삭제) 표준 REST 5종을 제공한다(2026-08-12 확장 — 유저 결정: 표준 REST 단건 조작
 * 추가). 저장은 전부 tombstone(deleted_at) — 하드 삭제하면 등록 replay의 자동 시드가 되살리기 때문.
 * <b>제외 문자열 관리 API는 2026-08-17 FE 협의로 폐기됐다</b>(프론트는 이미 UI·호출 제거) — 이
 * 컨트롤러에서 5종 엔드포인트를 걷어냈다({@code brand_hashtag_exclusion} 테이블 자체는
 * expand-contract 원칙상 DROP하지 않고 남아 있다).
 * 201 신규 / 200 replay / 204 탈퇴(이미 닫힘 포함, 멱등)·교체·추가·삭제 / 404 미등록·비ACTIVE·
 * IG 계정 부재 / 400 형식 위반 / 422 비공개 계정·태그 무효 문자·태그 추가 빈 입력 — 예외 매핑은
 * ApiExceptionHandler 공용. <b>PUT 빈 목록은 이제 허용된다</b>(2026-08-12) — 단건 삭제·전체 삭제
 * API가 생겨 "전체 비우기"가 더 이상 실수로만 일어나는 상태가 아니다(구 하한 가드는 폐지).
 *
 * <p>태그 PUT(전체 교체)·POST(추가)가 저장 결과 태그 셋을 비우지 않으면, 그 브랜드의 해시태그
 * 스윕을 비동기로 1회 트리거한다(2026-08-17 — "해시태그를 등록한 당시에 조회해서 당일 게시물을
 * 즉시 추가한다"는 합의된 동작, {@link BrandRegistrationService#triggerHashtagSweepIfNonEmpty}
 * 참조). DELETE 계열은 트리거하지 않는다 — 태그를 줄이는 조작에서 즉시 조회할 이유가 없다.
 */
@RestController
@RequestMapping("/api/brands")
public class BrandController {

	/**
	 * brandName·collectionMonths는 하위 호환용 nullable — 기존 요청 바디(필드 없음)는 null로 들어와도
	 * 문제없다. brandName은 2026-08-17부터 태그 시드에 쓰이지 않는다(계정명 태그 1종만 유도,
	 * {@link BrandRegistrationService} 참조) — 값을 보내도 무해하게 무시될 뿐이다. collectionMonths
	 * 미상은 수집 창이 기본 12개월로 접힌다.
	 */
	public record BrandRegisterRequest(String username, String brandName, Integer collectionMonths) {}

	public record BrandRegisterResponse(long brandId, String username, Long followers, String status) {}

	/**
	 * 태그 셋(유저 관리 API, 2026-08-12) — GET 응답·PUT 요청 바디 공용. tags는 정규화(trim·선행 #
	 * 제거·소문자·blank 제거·중복 제거) 후 저장하되, 무효 문자를 포함한 항목은 절삭하지 않고
	 * 통째로 거부한다(자동 유도 BrandHashtagTags.derive와 의도적으로 다른 규칙 — 유저 입력이라
	 * 잘라내면 유저가 입력한 문자열과 실제 저장된 태그가 어긋난다).
	 */
	public record HashtagTagsBody(List<String> tags) {}

	/**
	 * 시딩(협업) 계정 목록(유저 관리 API, 스펙 §6) — GET 응답·PUT 요청 바디 공용, 태그·해시태그와 같은
	 * 계약 모양. usernames는 정규화(trim·소문자·blank 제거·중복 제거) 후 저장한다 — 태그와 달리
	 * 선행 {@code #} 제거는 하지 않는다(인스타그램 username 규칙에 무관한 문자라 절삭 대상이 아니다).
	 */
	public record SeededAccountsBody(List<String> usernames) {}

	private final BrandRegistrationService service;
	private final BrandRepository brands;
	private final BrandHashtagRepository hashtags;
	private final BrandSeededAccountRepository seededAccounts;

	public BrandController(BrandRegistrationService service, BrandRepository brands,
			BrandHashtagRepository hashtags, BrandSeededAccountRepository seededAccounts) {
		this.service = service;
		this.brands = brands;
		this.hashtags = hashtags;
		this.seededAccounts = seededAccounts;
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

	/** 활성 태그 조회(유저 관리 API) — 브랜드 미존재·비ACTIVE는 404. */
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
	 * 우선한다.
	 *
	 * <p>유효 문자 검증은 유저 입력이므로 자동 유도(BrandHashtagTags.derive)처럼 절삭하지 않고
	 * 통째로 거부한다 — 무효 문자 포함 항목이 하나라도 있으면 422(문제 태그를 메시지에 명시).
	 * 빈 목록은 허용한다(2026-08-12 — 전체 삭제 API가 생겨 "전부 지우기"가 정당한 상태이므로 구
	 * 하한 가드는 폐지, {@link #deleteAllHashtagTags} 참조. 브랜드 태그 감지가 전부 꺼지는 셈이다).
	 *
	 * <p>저장 결과(=정규화된 요청 태그 셋)가 비어 있지 않으면 즉시 스윕을 트리거한다(2026-08-17,
	 * 클래스 주석 참조) — replaceTags는 요청 태그 셋을 그대로 활성 집합으로 만들므로 별도 재조회
	 * 없이 normalized로 판단할 수 있다.
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
		service.triggerHashtagSweepIfNonEmpty(row.get(), normalized);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 단건·다건 추가(POST 계약) — 정규화·유효 문자 검증 후 저장(tombstone 재활성). PUT과 달리 빈
	 * 입력은 422로 거부한다 — "추가할 태그가 없다"는 요청 자체가 무의미해 실수일 확률이 높다
	 * (전체를 비우는 명시적 의도는 DELETE 전체가 담당). 여기 도달하면 normalized는 항상 비어있지
	 * 않으므로(위 422 가드) 즉시 스윕이 매번 트리거된다(2026-08-17, 클래스 주석 참조).
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
		service.triggerHashtagSweepIfNonEmpty(row.get(), normalized);
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

	// ---------- 시딩(협업) 계정 관리(유저 입력, 스펙 §6, 2026-08-18) ----------

	/** 시딩 계정 조회 — 브랜드 미존재·비ACTIVE는 404. */
	@GetMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> seededAccounts(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		return ResponseEntity.ok(new SeededAccountsBody(seededAccounts.findUsernames(row.get().id())));
	}

	/** 전체 교체 — 목록에 없는 기존 계정은 하드 삭제된다({@link BrandSeededAccountRepository#replace}). */
	@PutMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> replaceSeededAccounts(@PathVariable String username,
			@RequestBody SeededAccountsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		seededAccounts.replace(row.get().id(), normalizeUsernames(body.usernames()));
		return ResponseEntity.noContent().build();
	}

	/**
	 * 단건·다건 추가(POST 계약) — 정규화 후 저장. 태그 POST와 달리 빈 입력을 422로 거부하지 않는다
	 * (repository.add()가 빈 컬렉션에 no-op이라 컨트롤러가 별도로 막을 이유가 없다).
	 */
	@PostMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> addSeededAccounts(@PathVariable String username,
			@RequestBody(required = false) SeededAccountsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		seededAccounts.add(row.get().id(), normalizeUsernames(body == null ? null : body.usernames()));
		return ResponseEntity.noContent().build();
	}

	/** 단건 삭제(DELETE {username} 계약) — 정규화 후 삭제, 없어도 멱등 204. */
	@DeleteMapping("/{username}/seeded-accounts/{seededUsername}")
	public ResponseEntity<?> deleteSeededAccount(@PathVariable String username,
			@PathVariable String seededUsername) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		String normalized = normalizeUsername(seededUsername);
		if (normalized != null) {
			seededAccounts.delete(row.get().id(), normalized);
		}
		return ResponseEntity.noContent().build();
	}

	/** 전체 삭제(DELETE 계약) — 브랜드의 시딩 계정 등록을 전부 비운다. */
	@DeleteMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> deleteAllSeededAccounts(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		seededAccounts.deleteAll(row.get().id());
		return ResponseEntity.noContent().build();
	}

	/** trim → 소문자 → blank 제거 → 중복 제거(입력 순서 보존). usernames가 null이면 빈 목록. */
	private static List<String> normalizeUsernames(List<String> usernames) {
		if (usernames == null) {
			return List.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String username : usernames) {
			String cleaned = normalizeUsername(username);
			if (cleaned != null) {
				normalized.add(cleaned);
			}
		}
		return List.copyOf(normalized);
	}

	/** 단건 정규화(trim → 소문자) — null·blank는 null(호출측이 "대상 없음"으로 처리). */
	private static String normalizeUsername(String username) {
		if (username == null) {
			return null;
		}
		String cleaned = username.strip().toLowerCase();
		return cleaned.isBlank() ? null : cleaned;
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
	 * 태그 GET/PUT 등 전용 404 — 계약 §2 어휘 {@code {code, message}}를 채운 바디다.
	 * deregister의 빈 바디 404(멱등 삼킴을 위한 별개 계약, was가 onStatus로 흡수)와는 의도적으로 다르다 —
	 * 여기는 was가 그대로 흘려도 되는 조회·설정 API라 에러 바디가 없으면 exchange()가 코드 없는 응답으로
	 * 오인해 MonitoringUnavailableException(503)으로 잘못 승격한다(08-11 실측).
	 */
	private static ResponseEntity<ApiError> brandNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다."));
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
