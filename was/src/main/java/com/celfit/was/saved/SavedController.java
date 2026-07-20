package com.celfit.was.saved;

import com.celfit.was.auth.AppUserDetails;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 인플루언서 후보·콘텐츠 북마크 저장 API — 전부 인증 필수(SecurityConfig가 /api/saved/** authenticated로
 * 막는다). handle·short_code는 존재 검증 없이 그대로 저장한다(분석 결과 조회·조인 금지 §4-4, 프론트가
 * 화면에 뜬 값만 보낸다는 전제).
 */
@RestController
public class SavedController {

	/** 상태 어휘는 was가 확정: reviewing(검토중)·contact_planned(컨택 예정)·collaborating(협업 중). */
	private static final Set<String> ALLOWED_STATUSES = Set.of("reviewing", "contact_planned", "collaborating");

	private final SavedRepository repository;

	public SavedController(SavedRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/api/saved/influencers")
	public SavedInfluencersResponse listInfluencers(@AuthenticationPrincipal AppUserDetails principal) {
		return SavedInfluencersResponse.of(repository.findInfluencers(principal.getUserId()));
	}

	/**
	 * 부분 갱신 upsert — body에 없는 필드는 유지된다. memo는 null 지정(비우기)과 미지정(유지)을
	 * 구분해야 해서 Map으로 받아 key 존재 여부(containsKey)로 판별한다(record는 이 구분이 불가능).
	 */
	@PutMapping("/api/saved/influencers/{handle}")
	public SavedInfluencer upsertInfluencer(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String handle, @RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;

		Object statusValue = fields.get("status");
		String status = statusValue == null ? null : statusValue.toString();
		if (status != null && !ALLOWED_STATUSES.contains(status)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 어휘 밖: " + status);
		}

		boolean memoGiven = fields.containsKey("memo");
		Object memoValue = fields.get("memo");
		String memo = memoValue == null ? null : memoValue.toString();

		return repository.upsertInfluencer(principal.getUserId(), handle, status, memoGiven, memo);
	}

	@DeleteMapping("/api/saved/influencers/{handle}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteInfluencer(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String handle) {
		repository.deleteInfluencer(principal.getUserId(), handle);
	}

	@GetMapping("/api/saved/contents")
	public SavedContentsResponse listContents(@AuthenticationPrincipal AppUserDetails principal) {
		return SavedContentsResponse.of(repository.findContents(principal.getUserId()));
	}

	@PutMapping("/api/saved/contents/{shortCode}")
	public SavedContent upsertContent(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String shortCode) {
		return repository.upsertContent(principal.getUserId(), shortCode);
	}

	@DeleteMapping("/api/saved/contents/{shortCode}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteContent(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String shortCode) {
		repository.deleteContent(principal.getUserId(), shortCode);
	}
}
