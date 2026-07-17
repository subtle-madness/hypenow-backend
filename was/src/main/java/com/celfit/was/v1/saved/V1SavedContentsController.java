package com.celfit.was.v1.saved;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.content.ContentCardAssembler;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.saved.V1SavedAssembler.ContentItem;
import com.celfit.was.v1.saved.V1SavedRepository.ContentSave;
import com.celfit.was.v1.saved.V1SavedRepository.SavedContentRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 저장 콘텐츠 /v1(스펙 6.6~6.8) — 인증 필수(SecurityConfig가 /v1/saved-contents/** authenticated).
 * 저장은 contentId+memo만 보존하고, 목록·응답의 카드는 조회 시점 analysis 미러에서 조합한다(§4-4:
 * app·analysis 분리 조회 후 Java 결합). 구 /api/saved(SavedController)와 병존.
 */
@RestController
public class V1SavedContentsController {

	private final V1SavedRepository repository;
	private final ContentCardAssembler cardAssembler;
	private final V1SavedAssembler savedAssembler;

	public V1SavedContentsController(V1SavedRepository repository, ContentCardAssembler cardAssembler,
			V1SavedAssembler savedAssembler) {
		this.repository = repository;
		this.cardAssembler = cardAssembler;
		this.savedAssembler = savedAssembler;
	}

	/**
	 * 저장(upsert) — 이미 있으면 memo만 갱신. inserted면 201, 갱신이면 200(스펙 6.7).
	 * 존재 확인은 카드 조회로 겸한다: 분석 완료 콘텐츠만 카드이므로 empty → 404. upsert 전에 확인해
	 * 미분석/없는 콘텐츠가 저장되지 않게 한다.
	 */
	@PostMapping("/v1/saved-contents")
	public ResponseEntity<ApiResponse<ContentItem>> save(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		Object idValue = fields.get("contentId");
		String contentId = idValue == null ? null : idValue.toString();
		if (contentId == null || contentId.isBlank()) {
			throw V1ApiException.validation("contentId는 필수입니다.");
		}
		ContentCardRow row = repository.findCard(contentId)
				.orElseThrow(() -> V1ApiException.notFound("콘텐츠를 찾을 수 없습니다."));

		Object memoValue = fields.get("memo");
		String memo = V1SavedAssembler.normalizeMemo(memoValue == null ? null : memoValue.toString());
		ContentSave save = repository.upsertContent(principal.getUserId(), contentId, memo);

		// 저장 목록/응답 카드는 정의상 저장된 콘텐츠 → isContentsSaved=true (스펙 6.6/6.7 예시).
		ContentItem item = savedAssembler.toContentItem(cardAssembler.toCard(row, true), save.memo(), save.createdAt());
		HttpStatus status = save.inserted() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.ok(item));
	}

	/**
	 * 목록 — 최근 저장 순. app에서 저장 행을 뽑고 short_code 묶음으로 카드를 일괄 조회해 Java에서 결합한다.
	 * meta.total은 <b>조합 후 노출 건수</b>(미러 부재로 제외된 분은 미포함), limit은 표기용 100(스펙 3.3 전량 반환).
	 */
	@GetMapping("/v1/saved-contents")
	public ApiResponse<List<ContentItem>> list(@AuthenticationPrincipal AppUserDetails principal) {
		List<SavedContentRow> saved = repository.findSavedContents(principal.getUserId());
		List<ContentItem> items;
		if (saved.isEmpty()) {
			items = List.of(); // 빈 저장 목록이면 IN () 방지 위해 analysis 조회 생략
		} else {
			List<String> codes = saved.stream().map(SavedContentRow::shortCode).toList();
			Map<String, ContentCard> byCode = repository.findCards(codes).stream()
					.map(r -> cardAssembler.toCard(r, true)) // 저장 목록 카드 → isContentsSaved=true (스펙 6.6)

					.collect(Collectors.toMap(ContentCard::id, c -> c, (a, b) -> a, LinkedHashMap::new));
			items = savedAssembler.toContentItems(saved, byCode);
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", items.size());
		meta.put("limit", 100);
		return ApiResponse.ok(items, meta);
	}

	/** 저장 취소 — 멱등 204(없어도 성공, 스펙 6.8). */
	@DeleteMapping("/v1/saved-contents/{contentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String contentId) {
		repository.deleteContent(principal.getUserId(), contentId);
	}
}
