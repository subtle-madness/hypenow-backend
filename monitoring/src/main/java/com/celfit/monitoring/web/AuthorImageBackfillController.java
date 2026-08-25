package com.celfit.monitoring.web;

import com.celfit.monitoring.service.AuthorImageBackfillCommandService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 만료된 인스타 CDN 프로필 이미지 재수집 백필 수동 트리거 API({@link com.celfit.monitoring.image.AuthorImageBackfillJob}
 * 참고, 2026-08-25) — {@link SweepController}와 동형: 인증 없음(전용 도커 네트워크 소속 전제),
 * {@code monitoring.image.backfill-trigger-enabled} 프로퍼티 게이트(기본 false — 빈 자체가
 * 등록되지 않아 비활성 시 404), 202 ACCEPTED + 비동기 실행.
 *
 * <p><b>이 엔드포인트는 Hiker API 콜을 유발한다</b>(요청한 {@code limit}만큼 재조회 콜 — 계정 수만큼
 * 콜을 내는 POST /api/sweeps보다도 증폭 폭이 크다: 게시자당 1콜에 더해, 직후 이어서 도는 아카이브
 * 잡의 다운로드·업로드까지 같은 실행에서 발생한다). {@code limit}은 필수 파라미터다 — 기본값을
 * 두면 호출자가 실수로 큰 콜 볼륨을 유발할 수 있어, 매번 명시하도록 강제한다.
 */
@RestController
@RequestMapping("/api/author-image-backfill")
@ConditionalOnProperty(name = "monitoring.image.backfill-trigger-enabled", havingValue = "true")
public class AuthorImageBackfillController {

	private final AuthorImageBackfillCommandService command;

	public AuthorImageBackfillController(AuthorImageBackfillCommandService command) {
		this.command = command;
	}

	/** 백필 1회를 비동기로 시작하고 즉시 202. 이미 실행 중이면 AuthorImageBackfillAlreadyRunningException → 409(ApiExceptionHandler). */
	@PostMapping
	public ResponseEntity<AuthorImageBackfillStartResponse> start(@RequestParam int limit) {
		var started = command.start(limit);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(AuthorImageBackfillStartResponse.from(started));
	}
}
