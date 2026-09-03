package com.celfit.monitoring.web;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.PostShapeUnsupportedException;
import com.celfit.instagram.source.SubjectNotFoundException;
import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.service.BrandDirectCollectService;
import com.celfit.monitoring.service.BrandHashtagRunStateResolver;
import com.celfit.monitoring.service.BrandHashtagSuggestionService;
import com.celfit.monitoring.service.BrandHashtagTags;
import com.celfit.monitoring.service.BrandRegistrationService;
import com.celfit.monitoring.service.ValidationException;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandLegacyHistoryCopier;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 추가). 저장은 전부 tombstone(deleted_at) — 하드 삭제하면 새 사용자 등록의 유도 태그 push(was
 * 책임, 2026-08-28~)가 그 자리를 재활성으로 되살리기 때문.
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
	 * brandName·collectionMonths·accountType은 하위 호환용 nullable — 기존 요청 바디(필드 없음)는
	 * null로 들어와도 문제없다. brandName은 2026-08-17부터 태그 시드에 쓰이지 않는다(계정명 태그
	 * 1종만 유도, {@link BrandRegistrationService} 참조) — 값을 보내도 무해하게 무시될 뿐이다.
	 * collectionMonths 미상은 수집 창이 기본 12개월로 접힌다. accountType(2026-08-19 경쟁사 판정
	 * 제거 설계 §2)은 {@code "competitor"}가 아니면 전부 own 취급 — has_own_link 초기화·승격에만
	 * 쓰인다({@link BrandRegistrationService#register(String, String, Integer, String)} 참조).
	 */
	public record BrandRegisterRequest(String username, String brandName, Integer collectionMonths,
			String accountType) {}

	public record BrandRegisterResponse(long brandId, String username, Long followers, String status) {}

	/** own-link 절대값 설정 요청(PUT own-link, 2026-08-19 경쟁사 판정 제거 설계 §2) — 멱등. */
	public record OwnLinkRequest(boolean hasOwnLink) {}

	/**
	 * 태그 셋(유저 관리 API, 2026-08-12) — GET 응답·PUT 요청 바디 공용. tags는 정규화(trim·선행 #
	 * 제거·소문자·blank 제거·중복 제거) 후 저장하되, 무효 문자를 포함한 항목은 절삭하지 않고
	 * 통째로 거부한다(was의 자동 유도와 의도적으로 다른 규칙 — 유저 입력이라 잘라내면 유저가
	 * 입력한 문자열과 실제 저장된 태그가 어긋난다).
	 */
	public record HashtagTagsBody(List<String> tags) {}

	/**
	 * 태그별 스윕 실행 상태(FE 요청, 2026-08-31) — GET run-state 응답 원소. status는
	 * {@link BrandHashtagRunStateResolver}가 조회 시점에 계산한 값(collecting|done|failed, 저장
	 * 안 함), lastRunAt·lastFoundCount는 직전 완료 실행의 값(status가 collecting이어도 노출).
	 */
	public record TagRunState(String tag, String status, OffsetDateTime lastRunAt, Integer lastFoundCount) {}

	/** run-state 응답 바디 — was가 사용자 장부 태그와 병합해 FE 계약(tags: 객체 배열)을 만든다. */
	public record HashtagRunStateBody(List<TagRunState> tags) {}

	/**
	 * direct 등록 요청(2026-08-18 direct 통합 §2-2·§4-2). registeredAt·importLegacyHistory는 이관
	 * 잡(mode=import) 전용 — 일반 유저 등록(was 실행기)은 둘 다 비운다(등록 시각은 now(), 레거시
	 * 이력 없음).
	 */
	public record DirectPostRegisterRequest(String shortCode, OffsetDateTime registeredAt,
			Boolean importLegacyHistory) {}

	/** direct 등록 API 응답(201 신규·200 멱등 공용 셰이프). */
	public record DirectPostResponse(String shortCode, String authorUsername, Instant takenAt, String contentType) {}

	private static final Logger log = LoggerFactory.getLogger(BrandController.class);

	private final BrandRegistrationService service;
	private final BrandRepository brands;
	private final BrandHashtagRepository hashtags;
	private final TaggedPostRepository taggedPosts;
	private final BrandDirectCollectService directCollect;
	private final BrandLegacyHistoryCopier legacyHistoryCopier;
	/** 해시태그 제안 계산(2026-09-03 자동 시드 재설계 §3) — 쓰기 없음, 순수 계산. */
	private final BrandHashtagSuggestionService suggestions;

	public BrandController(BrandRegistrationService service, BrandRepository brands,
			BrandHashtagRepository hashtags, TaggedPostRepository taggedPosts,
			BrandDirectCollectService directCollect, BrandLegacyHistoryCopier legacyHistoryCopier,
			BrandHashtagSuggestionService suggestions) {
		this.service = service;
		this.brands = brands;
		this.hashtags = hashtags;
		this.taggedPosts = taggedPosts;
		this.directCollect = directCollect;
		this.legacyHistoryCopier = legacyHistoryCopier;
		this.suggestions = suggestions;
	}

	@PostMapping
	public ResponseEntity<BrandRegisterResponse> register(@RequestBody BrandRegisterRequest req) {
		BrandRegistrationService.Result result = service.register(req.username(), req.brandName(),
				req.collectionMonths(), req.accountType());
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

	/**
	 * own-link 플래그 절대값 설정(2026-08-19 경쟁사 판정 제거 설계 §2) — was가 연결 변이(changeType
	 * 양방향·부분 해지) 커밋 후 원장(app.brand_monitorings 활성 연결)에서 재계산한 값을 그대로 민다.
	 * 멱등(같은 값 재전송 무해) — 브랜드 미존재는 204로 삼킨다(was best-effort push 컨벤션과
	 * 짝 — {@link BrandRepository#setHasOwnLink}가 0-row UPDATE를 조용히 받아들이는 것과 동형이라
	 * 여기서도 존재 여부를 굳이 확인하지 않는다).
	 */
	@PutMapping("/{username}/own-link")
	public ResponseEntity<Void> setOwnLink(@PathVariable String username, @RequestBody OwnLinkRequest req) {
		brands.setHasOwnLink(username, req.hasOwnLink());
		return ResponseEntity.noContent().build();
	}

	// ---------- direct 게시물 명령(2026-08-18 direct 통합 §2-2·§2-4·§4-2, was 실행기 진입점) ----------

	/**
	 * direct 게시물 등록 — 단건 콜로 즉시 수집·보강하는 동기 경로(설계 §2-2). 경로 변수를
	 * {@code {username}}이 아니라 {@code {brandId}}로 두는 이유: was는 {@code
	 * app.brand_monitorings.brand_id}를 들고 있고 username은 브랜드 계정명 변경 시 흔들린다 — 기존
	 * 해시태그 API가 {@code {username}}을 쓰는 것과 의도적으로 다르다.
	 *
	 * <p>이미 {@code direct_registered_at}이 있는 행이면(멱등 재요청) 단건 콜을 다시 내지 않고 저장된
	 * 값으로 200을 돌려준다 — was 실행기의 stale 복구 재시도가 매번 새 콜을 유발하면 안 된다.
	 * {@code importLegacyHistory=true}면 신규 수집 전에 레거시 이력을 먼저 복사한다(설계 §4-3,
	 * 이관 잡 전용 — {@link BrandLegacyHistoryCopier} 참조). 처리는 동기다(최대 5콜 ≈ 7초).
	 *
	 * <p>에러 바디는 계약 §2 어휘({@code {code, message}})를 반드시 채운다 — 비우면 was
	 * {@code MonitoringCommandClient.exchange}가 코드 없는 응답으로 오인해
	 * {@code MonitoringUnavailableException}(503)으로 잘못 승격한다(08-11 실측, 클래스 주석 참조
	 * 관용구 재사용).
	 */
	@PostMapping("/{brandId}/direct-posts")
	public ResponseEntity<?> registerDirectPost(@PathVariable long brandId,
			@RequestBody(required = false) DirectPostRegisterRequest req) {
		Optional<BrandRow> row = activeBrandById(brandId);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		String shortCode = req == null ? null : req.shortCode();
		if (shortCode == null || shortCode.isBlank()) {
			throw new ValidationException("shortCode는 필수입니다.");
		}
		BrandRow brand = row.get();

		Optional<TaggedPostRepository.DirectSnapshot> existing = taggedPosts.findDirectSnapshot(brand.id(), shortCode);
		if (existing.isPresent()) {
			return ResponseEntity.ok(toResponse(existing.get()));
		}

		if (Boolean.TRUE.equals(req.importLegacyHistory())) {
			legacyHistoryCopier.copy(shortCode);
		}
		Instant registeredAt = req.registeredAt() != null ? req.registeredAt().toInstant() : Instant.now();
		try {
			PostInfo post = directCollect.collectAndEnrich(brand, shortCode, registeredAt);
			return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(post));
		} catch (SubjectNotFoundException e) {
			// 전역 SubjectNotFoundException 핸들러는 SUBJECT_NOT_FOUND를 쓴다 — 여기는 계획 계약대로
			// POST_NOT_FOUND로 더 구체화해야 해서 로컬에서 먼저 잡는다.
			log.info("direct 게시물 부재: {}", e.getMessage());
			return postNotFound();
		} catch (PostShapeUnsupportedException e) {
			log.info("direct 게시물 셰이프 이상: {}", e.getMessage());
			return postUnsupported();
		}
		// PrivateAccountException은 잡지 않는다 — 전역 핸들러가 이미 422 PRIVATE_ACCOUNT를 내려
		// 계획 계약과 그대로 일치한다.
	}

	/**
	 * direct 게시물 취소 — 매핑 삭제가 아니라 direct 표식 해제(설계 §2-4). 3분기 전부 204(멱등):
	 * 행 없음(둘 다 no-op) / 겹침(tag_detected_at 있음 → direct 표식만 해제, tagged로 잔존) /
	 * 순수 direct(tag_detected_at 없음 → 행 삭제, 목록에서 즉시 제거). {@code brand_post_snapshot}·
	 * {@code brand_post_meta}·{@code brand_post_comment}는 게시물 전역 자산이라 지우지 않는다
	 * ("윈도우 이탈 후에도 영구 보존" 규칙 — 재등록 시 이력이 그대로 되살아난다).
	 */
	@DeleteMapping("/{brandId}/direct-posts/{shortCode}")
	public ResponseEntity<Void> deleteDirectPost(@PathVariable long brandId, @PathVariable String shortCode) {
		if (!taggedPosts.deleteIfDirectOnly(brandId, shortCode)) {
			taggedPosts.clearDirect(brandId, shortCode);
		}
		return ResponseEntity.noContent().build();
	}

	private static DirectPostResponse toResponse(PostInfo post) {
		return new DirectPostResponse(post.shortCode(), post.username(),
				post.takenAt() == null ? null : Instant.ofEpochSecond(post.takenAt()), post.contentType());
	}

	private static DirectPostResponse toResponse(TaggedPostRepository.DirectSnapshot snapshot) {
		return new DirectPostResponse(snapshot.shortCode(), snapshot.authorUsername(), snapshot.takenAt(),
				snapshot.contentType());
	}

	private Optional<BrandRow> activeBrandById(long brandId) {
		return brands.findById(brandId).filter(row -> row.status() == BrandStatus.ACTIVE);
	}

	private static ResponseEntity<ApiError> postNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("POST_NOT_FOUND", "게시물을 찾을 수 없습니다."));
	}

	private static ResponseEntity<ApiError> postUnsupported() {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
				.body(new ApiError("POST_UNSUPPORTED", "이 게시물은 등록할 수 없습니다."));
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
	 * 해시태그 자동 시드 제안(2026-09-03 자동 시드 재설계 §3-1) — 이 브랜드에 심을 태그 1개를
	 * <b>계산해서 돌려주기만</b> 한다. monitoring은 {@code brand_hashtag}에 아무것도 쓰지 않는다
	 * (08-28 "태그 생성 권한 was 일원화" 유지) — 저장·중복 방지·사용자 장부 반영은 전부 was 몫이다.
	 *
	 * <p>{@code tag}는 항상 비어 있지 않다(FREQ → AI → FALLBACK 3단). 상태를 저장하지 않고 AI가
	 * temperature 0이라 같은 입력엔 같은 답이 나온다. <b>백필 완료 여부는 검사하지 않는다</b> —
	 * 호출 시점 게이트는 was 책임이다(§4-2). 브랜드 미존재·비ACTIVE는 404(태그 GET과 동형).
	 */
	@GetMapping("/{username}/hashtag-suggestion")
	public ResponseEntity<?> hashtagSuggestion(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		return ResponseEntity.ok(suggestions.suggest(row.get().id(), row.get().username()));
	}

	/**
	 * 태그별 스윕 실행 상태(FE 요청, 2026-08-31, was GET hashtag-tags 병합 재료) — 브랜드 미존재·
	 * 비ACTIVE는 404(태그 GET과 동형). 활성 태그 전체를 돌려준다(deleted_at 있는 태그는 빠진다 —
	 * was는 이걸 "monitoring에 없음"으로 보고 collecting/lastRunAt=null로 접는다).
	 */
	@GetMapping("/{username}/hashtag-tags/run-state")
	public ResponseEntity<?> hashtagRunState(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		OffsetDateTime now = OffsetDateTime.now();
		List<TagRunState> states = hashtags.findRunStates(row.get().id()).stream()
				.map(r -> {
					BrandHashtagRunStateResolver.RunState resolved = BrandHashtagRunStateResolver.resolve(
							r.lastRunStartedAt(), r.lastRunFinishedAt(), r.lastRunFoundCount(), r.lastRunFailed(),
							now);
					return new TagRunState(r.tag(), resolved.status(), resolved.lastRunAt(),
							resolved.lastFoundCount());
				})
				.toList();
		return ResponseEntity.ok(new HashtagRunStateBody(states));
	}

	/**
	 * 태그 셋 전체 교체(유저 관리 API, 2026-08-12) — 정규화 후 저장(tombstone 의미론은
	 * {@link BrandHashtagRepository#replaceTags} 참조). 브랜드 미존재·비ACTIVE는 404가 이 가드보다
	 * 우선한다.
	 *
	 * <p>유효 문자 검증은 유저 입력이므로 was의 자동 유도처럼 절삭하지 않고 통째로 거부한다 —
	 * 무효 문자 포함 항목이 하나라도 있으면 422(문제 태그를 메시지에 명시).
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
