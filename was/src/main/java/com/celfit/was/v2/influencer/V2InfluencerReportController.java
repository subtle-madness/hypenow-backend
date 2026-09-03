package com.celfit.was.v2.influencer;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.ConcurrencyLimiter;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.influencer.InfluencerCard;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스펙 6.22·6.23 발굴 리포트 v2 — influencerId는 handle 그대로(6.4와 동일 설계).
 * 6.5(v1)는 기존 패널이 소비 중이라 병존 — 프론트 v2 전환 후 별도 PR로 폐기.
 * 인증은 둘 다 공개(SecurityConfig permitAll) — 잠금 표현은 프론트 처리(스펙 7절 15번).
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

	private final V2InfluencerReportRepository repository;
	private final V2InfluencerReportAssembler assembler;
	private final V1InfluencerDiscoveryRepository discoveryRepository;
	private final V1InfluencerDiscoveryAssembler discoveryAssembler;
	private final RateLimiter rateLimiter;
	private final ConcurrencyLimiter concurrencyLimiter;

	public V2InfluencerReportController(V2InfluencerReportRepository repository,
			V2InfluencerReportAssembler assembler,
			V1InfluencerDiscoveryRepository discoveryRepository,
			V1InfluencerDiscoveryAssembler discoveryAssembler, RateLimiter rateLimiter,
			ConcurrencyLimiter concurrencyLimiter) {
		this.repository = repository;
		this.assembler = assembler;
		this.discoveryRepository = discoveryRepository;
		this.discoveryAssembler = discoveryAssembler;
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
			var summary = repository.findSummary(influencerId)
					.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
			// 신 스키마 카피 없으면 "리포트 미생성" — tagline·요약 3종이 비-null 계약이라 부분 응답 불가(스펙 6.22 에러)
			var copy = repository.findLatestCopy(influencerId)
					.orElseThrow(() -> V1ApiException.notFound("리포트가 아직 생성되지 않았습니다."));
			return ApiResponse.ok(assembler.toReport(summary, copy,
					repository.findSeries(influencerId),
					repository.findCategories(influencerId),
					repository.findBrandCollabs(influencerId)));
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
			if (repository.findSummary(influencerId).isEmpty()) {
				throw V1ApiException.notFound("인플루언서를 찾을 수 없습니다.");
			}
			// 축은 대상 계정에서 파생(F&B 단독 → fnb) — 후보 풀·믹스·카드 비중이 같은 축을 따라간다.
			boolean fnbAxis = repository.findFnbAxis(influencerId);
			List<String> handles = repository.findSimilarHandles(influencerId, fnbAxis);
			List<InfluencerCard> cards = discoveryAssembler.toCards(
					discoveryRepository.findCardsByHandles(handles),
					discoveryRepository.findShares(handles, fnbAxis),
					discoveryRepository.findBrands(handles),
					discoveryRepository.findThumbs(handles),
					discoveryRepository.findEngagements(handles),
					discoveryRepository.findCaptions(handles));
			// 카드 조회는 순서 비보장 — 유사도 순(handles) 복원
			Map<String, InfluencerCard> byId = cards.stream()
					.collect(Collectors.toMap(InfluencerCard::id, Function.identity()));
			return ApiResponse.ok(handles.stream().map(byId::get).filter(Objects::nonNull).toList());
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
