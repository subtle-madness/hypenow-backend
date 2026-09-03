package com.celfit.was.v2.influencer;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.ConcurrencyLimiter;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.influencer.InfluencerCard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스펙 6.22·6.23 발굴 리포트 v2 — influencerId는 handle 그대로(6.4와 동일 설계).
 * 6.5(v1)는 기존 패널이 소비 중이라 병존 — 프론트 v2 전환 후 별도 PR로 폐기.
 * 인증은 둘 다 공개(SecurityConfig permitAll) — 잠금 표현은 프론트 처리(스펙 7절 15번).
 * ai-report·similar 조립·캐시는 각 서비스(V2InfluencerReportService·V2SimilarInfluencerService,
 * Redis TTL 6h, 09-03) — v1 6.5와 같은 구조. 컨트롤러는 레이트리밋·동시성 permit만 담당.
 *
 * 비로그인 무제한 접근으로 리포트가 대량 수집되는 걸 막기 위해 IP 레이트리밋을 둔다(기존 RateLimiter
 * 재사용 — V1AuthController·V1GateEventController와 동일 관용구, V1InfluencerDiscoveryController와
 * 동일 설계). 익명은 IP, 로그인 사용자는 user_id 단위로 키를 분리해 사무실 NAT 오탐을 피한다.
 * 한 계정 상세 진입 시 ai-report·similar가 함께 호출되는 게 정상 패턴이라 두 엔드포인트 상한은 독립
 * (같은 분당 상한을 공유하면 실질 상한이 반으로 준다). 익명 30/분은 email-availability(30/분,
 * 디바운스 타이핑)과 동급 — 상세 페이지를 분당 30회 넘게 여는 건 정상 열람으로 보기 어렵다.
 * 로그인 60/분은 개별 사용자 키라 더 느슨하게 둔다.
 *
 * RateLimiter(분당 고정 윈도우)와 별개로 ConcurrencyLimiter(동시 실행 수 제한, 벌크헤드)를 둔다 —
 * 분당 상한 내에서도 순간적으로 몰리는 동시 요청은 막지 못해(07-30 장애: 동시성 40 버스트로
 * HikariCP 풀 고갈, 무관한 엔드포인트까지 66초 500) 진입 자체를 제한하는 별도 방어가 필요하다.
 */
@RestController
public class V2InfluencerReportController {

	private static final int ANON_PER_MINUTE = 30;
	private static final int AUTH_PER_MINUTE = 60;

	/** 동시성 제한 초과 시 재시도 유도 — ConcurrencyLimiter 기본 대기(2초)보다 짧게 잡아 빠른 재시도를 유도. */
	private static final int CONCURRENCY_RETRY_AFTER_SECONDS = 1;

	private final V2InfluencerReportService reportService;
	private final V2SimilarInfluencerService similarService;
	private final RateLimiter rateLimiter;
	private final ConcurrencyLimiter concurrencyLimiter;

	public V2InfluencerReportController(V2InfluencerReportService reportService,
			V2SimilarInfluencerService similarService, RateLimiter rateLimiter,
			ConcurrencyLimiter concurrencyLimiter) {
		this.reportService = reportService;
		this.similarService = similarService;
		this.rateLimiter = rateLimiter;
		this.concurrencyLimiter = concurrencyLimiter;
	}

	@GetMapping("/v2/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReportV2> aiReport(@PathVariable String influencerId,
			@AuthenticationPrincipal AppUserDetails principal, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire(rateLimitKey("ai-report", principal, httpRequest),
				principal == null ? ANON_PER_MINUTE : AUTH_PER_MINUTE)) {
			throw V1ApiException.rateLimited();
		}
		if (!concurrencyLimiter.tryAcquire()) {
			throw V1ApiException.rateLimited(CONCURRENCY_RETRY_AFTER_SECONDS);
		}
		try {
			// 조립·404 판정·Redis 캐시는 서비스(V2InfluencerReportService). 동시성 permit은 캐시 히트도
			// 잡지만 히트는 ms 단위라 점유가 짧다 — 벌크헤드 의미(DB 보호)는 미스 경로에서만 실효.
			return ApiResponse.ok(reportService.report(influencerId));
		} finally {
			concurrencyLimiter.release();
		}
	}

	/** 6.23 — 응답은 6.21 InfluencerCard 재사용, 서버 고정 최대 10(유사도 내림차순, 07-28 유사도 v2 — 9→10 변경). */
	@GetMapping("/v2/influencers/{influencerId}/similar")
	public ApiResponse<List<InfluencerCard>> similar(@PathVariable String influencerId,
			@AuthenticationPrincipal AppUserDetails principal, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire(rateLimitKey("similar", principal, httpRequest),
				principal == null ? ANON_PER_MINUTE : AUTH_PER_MINUTE)) {
			throw V1ApiException.rateLimited();
		}
		if (!concurrencyLimiter.tryAcquire()) {
			throw V1ApiException.rateLimited(CONCURRENCY_RETRY_AFTER_SECONDS);
		}
		try {
			// 조립·404 판정·Redis 캐시는 서비스(V2SimilarInfluencerService) — 응답 계약은 카드 배열 그대로.
			return ApiResponse.ok(similarService.similar(influencerId).cards());
		} finally {
			concurrencyLimiter.release();
		}
	}

	/** 익명은 IP, 로그인 사용자는 user_id로 키를 분리(사무실 NAT 오탐 방지 — 클래스 주석 참고). */
	private static String rateLimitKey(String prefix, AppUserDetails principal, HttpServletRequest httpRequest) {
		return principal == null
				? prefix + ":ip:" + httpRequest.getRemoteAddr()
				: prefix + ":user:" + principal.getUserId();
	}
}
