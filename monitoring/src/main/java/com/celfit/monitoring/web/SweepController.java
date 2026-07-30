package com.celfit.monitoring.web;

import com.celfit.monitoring.service.SweepCommandService;
import com.celfit.monitoring.service.SweepGuard;
import com.celfit.monitoring.store.SweepRunRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일일 스윕 수동 트리거 API — test 환경은 스케줄 전부 비활성(K 원칙)이라 인플루언서 감지 동작을
 * 검증하려면 스윕을 수동으로 1회 돌릴 방법이 필요하다(운영 컴포즈 크론을 임시로 당기고 재기동하던
 * 수작업을 대체). 인증 없음: TargetController와 같은 전용 도커 네트워크 소속 전제(계약 §1).
 *
 * <p><b>이 엔드포인트는 Hiker API 콜을 유발한다</b>(계정 수만큼 프로필·열거 콜 — POST /api/targets와
 * 노출 수준이 같다). monitoring은 인터넷에서 도달 불가능하지만(Caddyfile 무배선) 그 방어가 한 겹뿐이라
 * {@code monitoring.sweep.manual-trigger-enabled}로 프로퍼티 게이트를 둔다(기본 false — AdminUiController·
 * CoverageController와 동일한 {@code @ConditionalOnProperty} 관용구, 빈 자체가 등록되지 않아 비활성 시
 * 404). {@code GET /latest}는 콜을 유발하지 않는 단순 조회지만 컨트롤러를 쪼개는 대신 함께 묶었다 —
 * 별도 프로퍼티를 두면 조합이 늘어 검증 부담만 커지고, latest 단독 노출의 실이익이 없다.
 */
@RestController
@RequestMapping("/api/sweeps")
@ConditionalOnProperty(name = "monitoring.sweep.manual-trigger-enabled", havingValue = "true")
public class SweepController {

	private final SweepCommandService command;
	private final SweepRunRepository sweepRuns;
	private final SweepGuard guard;

	public SweepController(SweepCommandService command, SweepRunRepository sweepRuns, SweepGuard guard) {
		this.command = command;
		this.sweepRuns = sweepRuns;
		this.guard = guard;
	}

	/** 스윕 1회를 비동기로 시작하고 즉시 202. 이미 실행 중이면 SweepAlreadyRunningException → 409(ApiExceptionHandler). */
	@PostMapping
	public ResponseEntity<SweepStartResponse> start() {
		var started = command.start();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(SweepStartResponse.from(started));
	}

	/** 마지막 스윕 실행 상태 — 검증 루프의 폴링 대상. 이력이 없어도 200(빈 표현), 404는 쓰지 않는다. */
	@GetMapping("/latest")
	public SweepStatusResponse latest() {
		boolean running = guard.isRunning();
		return sweepRuns.latest()
				.map(row -> SweepStatusResponse.from(row, running))
				.orElseGet(() -> SweepStatusResponse.empty(running));
	}
}
