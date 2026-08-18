package com.celfit.monitoring.ad;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 광고 표기 판정 오케스트레이터(스펙 §5) — Tier0(메타 규칙) → Tier1(사전, {@link AdDisclosurePatterns})
 * → Tier2(LLM 추출, {@link AdDisclosureExtractor}) → Tier3(조합, {@link AdVerdictCombiner}) 순서로
 * 실행하고, 앞 티어에서 확정되면 뒤 티어를 생략한다(Tier1 확정 시 LLM 콜 자체가 안 나간다).
 *
 * <p>후보 선정은 {@code ad_verdict IS NULL OR judged_caption_hash <> md5(caption)}(스펙 §7) — 판정
 * 상태는 {@link BrandPostMetaRepository#findAdJudgmentState}로 배치 조회하고, 해시는 이 클래스가
 * Java {@link MessageDigest}로 계산해 기록·비교 양쪽에 <b>같은 알고리즘</b>을 쓴다(Postgres md5()를
 * 별도로 호출하지 않는다 — 언어 간 해시 불일치 리스크 제거).
 *
 * <p>LLM 콜은 전용 소형 풀(worker, 동시 3~4 — 스펙 §7)로 나간다. 게시물 단위 격리: 한 건의 LLM
 * 실패·파싱 실패가 나머지 게시물 판정에 번지지 않고, verdict는 NULL로 남아 다음 스윕이 재시도한다.
 * 단, 이 "다음 스윕 재시도"는 180일 이하 게시물(추적 창) 한정이다 — 180일 초과 게시물은 크롤
 * 정책상 재열거 자체가 없어({@code BrandCrawlPolicy.due}가 무조건 false) verdict NULL이 영구
 * 잔존할 수 있었다(2026-08-18 스펙 리뷰 정정).
 *
 * <p><b>백필 단계</b>(2026-08-18 스펙 §7 개정) — 사용자 확정 원칙은 "광고 판정은 처음에 전량
 * 돌고, 이후에는 캡션 변경분만 돈다"다. {@link #judgePosts}(스윕 경유)만으로는 이 원칙이
 * 180일 추적 창 안에서만 성립한다. {@link #backfillUnjudged}가 그 바깥의 미판정 잔여(스윕
 * 재열거가 다시 오지 않는 게시물 포함)를 흡수한다 — caption·content_type·video_url·
 * is_paid_partnership이 이미 {@code brand_post_meta}에 저장돼 있으므로 Hiker 콜 없이
 * {@link #judgeOne(BrandPostMetaRepository.UnjudgedPost)}로 바로 판정한다. Tier0~3 규칙은
 * {@link #judgeCore}로 추출해 PostInfo 경로와 완전히 공유한다 — 입력 소스(Hiker 응답 vs
 * 저장된 메타)만 다를 뿐 판정 결과는 같은 캡션이면 항상 같다.
 */
public class AdDisclosureJudgeService {

	private static final Logger log = LoggerFactory.getLogger(AdDisclosureJudgeService.class);

	private final BrandPostMetaRepository metaRepo;
	private final AdDisclosureExtractor extractor;
	private final Executor worker;

	public AdDisclosureJudgeService(BrandPostMetaRepository metaRepo, AdDisclosureExtractor extractor,
			Executor worker) {
		this.metaRepo = metaRepo;
		this.extractor = extractor;
		this.worker = worker;
	}

	public void judgePosts(List<PostInfo> posts) {
		if (posts.isEmpty()) {
			return;
		}
		List<PostInfo> candidates = selectCandidates(posts);
		if (candidates.isEmpty()) {
			return;
		}
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (PostInfo p : candidates) {
			tasks.add(CompletableFuture.runAsync(() -> judgeSafely(p), worker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
	}

	private List<PostInfo> selectCandidates(List<PostInfo> posts) {
		Set<String> codes = new LinkedHashSet<>();
		for (PostInfo p : posts) {
			codes.add(p.shortCode());
		}
		Map<String, BrandPostMetaRepository.AdJudgmentState> state = metaRepo.findAdJudgmentState(codes);
		return posts.stream().filter(p -> needsJudgment(p, state.get(p.shortCode()))).toList();
	}

	private static boolean needsJudgment(PostInfo p, BrandPostMetaRepository.AdJudgmentState state) {
		if (state == null || state.adVerdict() == null) {
			return true;
		}
		return !md5(caption(p)).equals(state.judgedCaptionHash());
	}

	private void judgeSafely(PostInfo p) {
		try {
			AdVerdictResult result = judgeOne(p);
			if (!result.discardedPhrases().isEmpty()) {
				// 환각 차단·미분류 카테고리로 버려진 phrase — DB에는 안 남으므로 여기서만 로그(코디네이터
				// 리뷰 반영, c40ead8b 후속: 스펙 §5 "폐기·로그" 요구를 만족).
				log.warn("광고 표기 판정 — 문구 {}건 폐기됨 {}: {}", result.discardedPhrases().size(), p.shortCode(),
						result.discardedPhrases());
			}
			metaRepo.updateAdVerdict(p.shortCode(), result, md5(caption(p)), Instant.now());
		} catch (RuntimeException e) {
			// verdict NULL 유지 — 다음 스윕(캡션 해시 재비교)이 자동 재시도한다(스펙 §5). 단, 180일
			// 초과 게시물은 재열거가 없어 이 재시도 자체가 안 걸린다(클래스 javadoc 참조) — 백필
			// 단계(backfillUnjudged)가 대신 재시도를 흡수한다.
			log.warn("광고 표기 판정 실패(격리, 다음 스윕 재시도) — {}: {}", p.shortCode(), e.toString());
		}
	}

	/**
	 * 미판정 잔여 백필(스펙 §7 개정) — {@code ad_verdict IS NULL}인 행을 최대 limit개 조회해
	 * 저장된 메타만으로 판정한다(Hiker 콜 없음). {@code limit <= 0}이면 비활성(호출부 킬 스위치와
	 * 별개로 이 메서드 자체도 no-op) — {@code monitoring.brand.ad-disclosure.backfill-per-night}가
	 * 0이면 이 경로가 아예 돌지 않게 한다. 병렬 실행·격리는 {@link #judgePosts}와 동일하게
	 * {@link #worker} 풀 + 게시물 단위 try/catch를 재사용한다.
	 */
	public BackfillOutcome backfillUnjudged(int limit) {
		if (limit <= 0) {
			return new BackfillOutcome(0, 0);
		}
		int remaining = metaRepo.countUnjudged();
		if (remaining == 0) {
			return new BackfillOutcome(0, 0);
		}
		List<BrandPostMetaRepository.UnjudgedPost> targets = metaRepo.findUnjudged(limit);
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (BrandPostMetaRepository.UnjudgedPost meta : targets) {
			tasks.add(CompletableFuture.runAsync(() -> judgeMetaSafely(meta), worker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
		return new BackfillOutcome(remaining, targets.size());
	}

	/** @param remaining 이번 배치 시작 시점의 전체 미판정 건수(limit과 무관) @param processed 이번 배치에서 판정을 시도한 건수 */
	public record BackfillOutcome(int remaining, int processed) {
	}

	private void judgeMetaSafely(BrandPostMetaRepository.UnjudgedPost meta) {
		try {
			AdVerdictResult result = judgeOne(meta);
			if (!result.discardedPhrases().isEmpty()) {
				log.warn("광고 표기 판정(백필) — 문구 {}건 폐기됨 {}: {}", result.discardedPhrases().size(),
						meta.shortCode(), result.discardedPhrases());
			}
			metaRepo.updateAdVerdict(meta.shortCode(), result, md5(orEmpty(meta.caption())), Instant.now());
		} catch (RuntimeException e) {
			// verdict NULL 유지 — 다음 밤 백필이 같은 short_code를 다시 findUnjudged로 만나 재시도한다.
			log.warn("광고 표기 판정(백필) 실패(격리, 다음 백필 재시도) — {}: {}", meta.shortCode(), e.toString());
		}
	}

	/** Tier0→3 순서 실행 — package-private으로 열어 오케스트레이션만 별도 테스트할 수 있게 한다. */
	AdVerdictResult judgeOne(PostInfo p) {
		return judgeCore(caption(p), p.contentType(), p.videoUrl(), p.isPaidPartnership());
	}

	/**
	 * 저장된 메타 기반 판정(백필 전용 진입점) — {@link #judgeOne(PostInfo)}와 정확히 같은 Tier0~3
	 * 규칙({@link #judgeCore})을 공유한다. 입력이 Hiker 응답이 아니라 이미 저장된 brand_post_meta
	 * 행이라는 점만 다르다.
	 */
	AdVerdictResult judgeOne(BrandPostMetaRepository.UnjudgedPost meta) {
		return judgeCore(orEmpty(meta.caption()), meta.contentType(), meta.videoUrl(), meta.isPaidPartnership());
	}

	/**
	 * Tier0→3 공통 판정 로직 — {@link #judgeOne(PostInfo)}·{@link
	 * #judgeOne(BrandPostMetaRepository.UnjudgedPost)} 양쪽이 공유하는 유일한 판정 본체다(스펙
	 * §5). 두 진입점은 입력 소스만 다를 뿐, 같은 (caption, contentType, videoUrl,
	 * isPaidPartnership) 조합이면 항상 같은 verdict를 낸다.
	 */
	private AdVerdictResult judgeCore(String caption, String contentType, String videoUrl,
			Boolean isPaidPartnership) {
		if (Boolean.TRUE.equals(isPaidPartnership)) {
			return new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		}
		// 릴스뿐 아니라 일반 피드의 단일 동영상(HikerClient는 contentType을 REELS/FEED 2값으로만
		// 매핑하므로 FEED+videoUrl 보유가 그 경우)도 영상 내 표기가 정본 위치라 캡션 부재로 단정할 수
		// 없다 — 스펙 §5 Tier0.
		boolean isVideo = "REELS".equalsIgnoreCase(contentType) || videoUrl != null;
		if (caption.isBlank()) {
			return isVideo
					? new AdVerdictResult("UNCERTAIN", "RULE", List.of(), List.of(), List.of())
					: new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), List.of(), List.of());
		}
		AdDisclosurePatterns.Match tier1 = AdDisclosurePatterns.findFirstMatch(caption);
		if (tier1 != null) {
			AdPositionRule.Band band = AdPositionRule.evaluate(caption, tier1.start(), tier1.end());
			if (band == AdPositionRule.Band.VISIBLE || band == AdPositionRule.Band.GRAY
					|| band == AdPositionRule.Band.FIRST_HASHTAG) {
				int offset = AdPositionRule.graphemeOffset(caption, tier1.start());
				return new AdVerdictResult("DISCLOSED", "RULE", List.of(),
						List.of(new AdVerdictResult.Evidence(tier1.phrase(), "CLEAR", offset)), List.of());
			}
		}
		List<AdDisclosureExtractor.Disclosure> llm = extractor.extract(caption);
		return AdVerdictCombiner.combine(caption, isVideo, tier1, llm);
	}

	private static String caption(PostInfo p) {
		return orEmpty(p.caption());
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s;
	}

	private static String md5(String s) {
		try {
			byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 알고리즘 부재(도달 불가)", e);
		}
	}
}
