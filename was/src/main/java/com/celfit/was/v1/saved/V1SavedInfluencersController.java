package com.celfit.was.v1.saved;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.saved.V1SavedAssembler.InfluencerItem;
import com.celfit.was.v1.saved.V1SavedRepository.InfluencerSave;
import com.celfit.was.v1.saved.V1SavedRepository.SavedInfluencerRow;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 저장 인플루언서 /v1(스펙 6.9~6.11) — 인증 필수. 콘텐츠와 달리 프로필 미러(accounts) 부재 시에도
 * 목록에서 제외하지 않고 handle만 채운다(저장이 정본). 단, POST 저장 시점엔 accounts 존재를 요구한다
 * (없는 handle을 새로 만들지 않기 위함). status 필드는 v1이 다루지 않는다(구 /api/saved 전용).
 */
@RestController
public class V1SavedInfluencersController {

	private final V1SavedRepository repository;
	private final V1SavedAssembler savedAssembler;

	public V1SavedInfluencersController(V1SavedRepository repository, V1SavedAssembler savedAssembler) {
		this.repository = repository;
		this.savedAssembler = savedAssembler;
	}

	/** 저장(upsert) — memo만 갱신, status는 불변. inserted면 201, 갱신이면 200(스펙 6.10). */
	@PostMapping("/v1/saved-influencers")
	public ResponseEntity<ApiResponse<InfluencerItem>> save(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		Object idValue = fields.get("influencerId");
		String influencerId = idValue == null ? null : idValue.toString();
		if (influencerId == null || influencerId.isBlank()) {
			throw V1ApiException.validation("influencerId는 필수입니다.");
		}
		ContentCard.Influencer profile = repository.findInfluencer(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));

		Object memoValue = fields.get("memo");
		String memo = V1SavedAssembler.normalizeMemo(memoValue == null ? null : memoValue.toString());
		InfluencerSave save = repository.upsertInfluencer(principal.getUserId(), influencerId, memo);

		InfluencerItem item = savedAssembler.toInfluencerItem(profile, save.memo(), save.createdAt());
		HttpStatus status = save.inserted() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.ok(item));
	}

	/**
	 * 목록 — 최근 저장 순. 저장 handle 묶음으로 프로필을 일괄 조회해 Java에서 결합하되, 미러에 없는 handle은
	 * handle-only로 남긴다(제외 아님). meta.total은 노출 건수(=저장 건수), limit은 표기용 100.
	 */
	@GetMapping("/v1/saved-influencers")
	public ApiResponse<List<InfluencerItem>> list(@AuthenticationPrincipal AppUserDetails principal) {
		List<SavedInfluencerRow> saved = repository.findSavedInfluencers(principal.getUserId());
		List<InfluencerItem> items;
		if (saved.isEmpty()) {
			items = List.of(); // 빈 저장 목록이면 IN () 방지 위해 analysis 조회 생략
		} else {
			List<String> handles = saved.stream().map(SavedInfluencerRow::handle).toList();
			Map<String, ContentCard.Influencer> byHandle = repository.findInfluencers(handles).stream()
					.collect(Collectors.toMap(ContentCard.Influencer::handle, p -> p, (a, b) -> a, LinkedHashMap::new));
			items = savedAssembler.toInfluencerItems(saved, byHandle);
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", items.size());
		meta.put("limit", 100);
		return ApiResponse.ok(items, meta);
	}

	/**
	 * 메모 수정(편집 전용 PUT) — 이미 저장된 인플루언서의 memo만 갱신하고 200을 돌려준다. 저장 이력이 없으면
	 * 404(POST와 달리 새 저장을 만들지 않는다). 프로필 미러가 없어도 목록과 같은 정책으로 handle만 채워 남긴다
	 * (저장이 정본, 미러는 보조) — POST의 존재 요구와 다른 이유: 편집은 이미 저장된 행이 대상이라서다.
	 */
	@PutMapping("/v1/saved-influencers/{influencerId}")
	public ApiResponse<InfluencerItem> updateMemo(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String influencerId, @RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		Object memoValue = fields.get("memo");
		String memo = V1SavedAssembler.normalizeMemo(memoValue == null ? null : memoValue.toString());
		SavedInfluencerRow saved = repository.updateInfluencerMemo(principal.getUserId(), influencerId, memo)
				.orElseThrow(() -> V1ApiException.notFound("저장된 인플루언서가 아닙니다."));
		ContentCard.Influencer profile = repository.findInfluencer(influencerId)
				.orElse(new ContentCard.Influencer(influencerId, influencerId, null, null, null));
		InfluencerItem item = savedAssembler.toInfluencerItem(profile, saved.memo(), saved.createdAt());
		return ApiResponse.ok(item);
	}

	/** 저장 취소 — 멱등 204(없어도 성공, 스펙 6.11). */
	@DeleteMapping("/v1/saved-influencers/{influencerId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String influencerId) {
		repository.deleteInfluencer(principal.getUserId(), influencerId);
	}
}
