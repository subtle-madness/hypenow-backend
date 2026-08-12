package com.celfit.monitoring.web;

import com.celfit.monitoring.service.ShareResolveService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * share 단축 링크 해소 API — 계약 §2-6. 등록 플로우와 무관한 별도 전처리라 RegistrationService를
 * 호출하지 않는다(인증 없음 — 접근 통제는 TargetController와 같은 네트워크 소속 전제).
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

	private final ShareResolveService resolver;

	public ShareController(ShareResolveService resolver) {
		this.resolver = resolver;
	}

	@PostMapping("/resolve")
	public ShareResolveResponse resolve(@RequestBody ShareResolveRequest request) {
		var ref = resolver.resolve(request.url(), request.userId());
		return new ShareResolveResponse(ref.shortCode(), ref.username(), ref.contentType());
	}
}
