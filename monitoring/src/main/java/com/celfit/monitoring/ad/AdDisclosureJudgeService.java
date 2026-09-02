package com.celfit.monitoring.ad;

import com.celfit.instagram.source.PostInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
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
 * <p><b>백필 단계</b>(2026-08-18 스펙 §7 개정, 08-18 상한 제거 재개정) — 사용자 확정 원칙은
 * "광고 판정은 처음에 전량 돌고, 이후에는 캡션 변경분만 돈다"다. {@link #judgePosts}(스윕
 * 경유)만으로는 이 원칙이 180일 추적 창 안에서만 성립한다. {@link #backfillUnjudged}가 그
 * 바깥의 미판정 잔여(스윕 재열거가 다시 오지 않는 게시물 포함)를 흡수한다 — caption·
 * content_type·video_url·is_paid_partnership이 이미 {@code brand_post_meta}에 저장돼
 * 있으므로 Hiker 콜 없이 {@link #judgeOne(BrandPostMetaRepository.UnjudgedPost)}로 바로
 * 판정한다. Tier0~3 규칙은 {@link #judgeCore}로 추출해 PostInfo 경로와 완전히 공유한다 —
 * 입력 소스(Hiker 응답 vs 저장된 메타)만 다를 뿐 판정 결과는 같은 캡션이면 항상 같다.
 *
 * <p>{@link #backfillUnjudged}는 배치({@link #batchSize}건씩 {@code findUnjudged} 반복 조회)
 * 단위로 돈다. 배치 하나 안에서 재시도해도 갱신되지 않는 항목(영구 실패)을 만나면 그 항목은
 * 이번 호출에서 더는 재시도하지 않고 종료한다 — 재시도는 다음 호출(기동 러너 또는 다음 스윕)의
 * 몫이라, 전량 실패 배치가 같은 호출 안에서 무한히 재조회되는 것을 막는다. {@link
 * #backfillRunning}은 기동 백필과 스윕 말미 백필이 겹칠 때 동시 실행을 막는 가드다.
 *
 * <p><b>백필 방어선</b>(2026-08-18 429 폭주 실측 계기 — #490 백필 기동 즉시·상한 제거 후 스테이징
 * 무료 키 쿼터 공유로 15분간 분당 83~146건 429). {@link #llmFailureAbortThreshold} — LLM 호출
 * 연속 실패가 이 값에 도달하면 진행 중 배치 완료 후 런 자체를 중단한다({@link #circuitOpen}).
 * verdict는 NULL로 남아 다음 주기가 재시도한다. 임계 도달 이후 서킷이 열린 동안 제출되는 항목은
 * LLM을 아예 호출하지 않고 스킵한다(추가 소모 방지).
 *
 * <p><b>1회 실행 처리량 상한은 두지 않는다</b>(2026-08-27 결정, DECISIONS.md 참조 — 08-18에
 * 재도입했던 프로퍼티 기반 상한을 같은 결정으로 폐기). 상한(총량 분산)은 verdict NULL 잔량을
 * 오래 남기는데, expose 개통(08-19) 이후에는 그 잔량이 곧 FE의 부분 판정·과소 위험 카운트
 * 서빙으로 이어져 상한이 있는 동안 사용자가 틀린 데이터를 본다 — 분산은 총 LLM 비용을 줄이지도
 * 않고 미룰 뿐이다. 버스트 속도 방어는 워커 풀 동시성({@code ad-disclosure.concurrency})이,
 * 장애 폭주 방어는 위 서킷브레이커가 이미 담당한다. 잘못된 판정이 배포됐을 때는 {@code enabled}
 * 킬 스위치·{@code AD_DISCLOSURE_EXPOSE}·리셋 후 재배포로 대응하는 것이 정본이다.
 */
public class AdDisclosureJudgeService {

	private static final Logger log = LoggerFactory.getLogger(AdDisclosureJudgeService.class);

	/** 백필 1회 조회 배치 크기 — 상한이 아니라 구현 디테일(진행 로그 단위)이다. */
	private static final int DEFAULT_BACKFILL_BATCH_SIZE = 500;
	/** LLM 연속 실패 서킷브레이커 임계 — 08-18 429 폭주 방어선. */
	private static final int DEFAULT_LLM_FAILURE_ABORT_THRESHOLD = 10;
	/**
	 * 한글 판별 — 음절(U+AC00–U+D7A3)·자모(U+1100–U+11FF 조합형, U+3130–U+318F 호환형) 어디에도
	 * 없으면 캡션이 완전 비한국어라고 본다(2026-08-27 FOREIGN_POST 분리 결정, DECISIONS.md 참조).
	 */
	private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]");

	private final BrandPostMetaRepository metaRepo;
	private final AdDisclosureExtractor extractor;
	private final Executor worker;
	private final int batchSize;
	private final int llmFailureAbortThreshold;
	private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
	private final AtomicInteger consecutiveLlmFailures = new AtomicInteger(0);
	private final AtomicBoolean circuitOpen = new AtomicBoolean(false);

	public AdDisclosureJudgeService(BrandPostMetaRepository metaRepo, AdDisclosureExtractor extractor,
			Executor worker) {
		this(metaRepo, extractor, worker, DEFAULT_BACKFILL_BATCH_SIZE, DEFAULT_LLM_FAILURE_ABORT_THRESHOLD);
	}

	/** Spring 배선용 — 서킷브레이커 임계를 app 설정에서 주입(08-18 429 실측 방어선). */
	public AdDisclosureJudgeService(BrandPostMetaRepository metaRepo, AdDisclosureExtractor extractor,
			Executor worker, int llmFailureAbortThreshold) {
		this(metaRepo, extractor, worker, DEFAULT_BACKFILL_BATCH_SIZE, llmFailureAbortThreshold);
	}

	/** 배치 크기·서킷브레이커 임계를 함께 주입하는 테스트 전용 생성자 — 다중 배치 루프·방어선을 큰
	 * 픽스처 없이 검증하기 위함. */
	AdDisclosureJudgeService(BrandPostMetaRepository metaRepo, AdDisclosureExtractor extractor, Executor worker,
			int batchSize, int llmFailureAbortThreshold) {
		this.metaRepo = metaRepo;
		this.extractor = extractor;
		this.worker = worker;
		this.batchSize = batchSize;
		this.llmFailureAbortThreshold = llmFailureAbortThreshold;
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
	 * 미판정 잔여 백필(2026-08-18 상한 제거 개정) — {@code ad_verdict IS NULL}인 행을 잔량이
	 * 0이 될 때까지 {@link #batchSize}건씩 반복 조회해 저장된 메타만으로 판정한다(Hiker 콜
	 * 없음). LLM 전용 풀(동시 4)이 자연 속도 제한이라 별도 상한을 두지 않는다. 배치마다
	 * 진행 상황을 info 로그로 남긴다.
	 *
	 * <p>{@link #backfillRunning}으로 동시 실행을 막는다 — 이미 실행 중이면 즉시
	 * {@code (0, 0)}을 반환하고 스킵한다(기동 러너와 야간 스윕 훅이 겹칠 수 있어서). 병렬
	 * 실행·격리는 {@link #judgePosts}와 동일하게 {@link #worker} 풀 + 게시물 단위 try/catch를
	 * 재사용한다.
	 */
	public BackfillOutcome backfillUnjudged() {
		if (!backfillRunning.compareAndSet(false, true)) {
			log.info("광고 판정 백필 — 이미 실행 중이라 이번 호출은 스킵");
			return new BackfillOutcome(0, 0);
		}
		try {
			int initialRemaining = metaRepo.countUnjudged();
			if (initialRemaining == 0) {
				return new BackfillOutcome(0, 0);
			}
			consecutiveLlmFailures.set(0);
			circuitOpen.set(false);
			Set<String> attempted = new HashSet<>();
			int processed = 0;
			while (true) {
				List<BrandPostMetaRepository.UnjudgedPost> batch = metaRepo.findUnjudged(batchSize);
				List<BrandPostMetaRepository.UnjudgedPost> fresh =
						batch.stream().filter(m -> attempted.add(m.shortCode())).toList();
				if (fresh.isEmpty()) {
					// 배치 전부가 이번 호출에서 이미 한 번 시도했는데도 여전히 NULL(영구 실패) —
					// 같은 배치가 무한히 재조회되는 것을 막고, 재시도는 다음 호출로 넘긴다.
					break;
				}
				List<CompletableFuture<Void>> tasks = new ArrayList<>();
				for (BrandPostMetaRepository.UnjudgedPost meta : fresh) {
					tasks.add(CompletableFuture.runAsync(() -> judgeMetaSafely(meta), worker));
				}
				CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
				processed += fresh.size();
				int remaining = metaRepo.countUnjudged();
				log.info("광고 판정 백필 진행 — 잔여 {}건", remaining);
				if (remaining == 0) {
					break;
				}
				if (circuitOpen.get()) {
					log.warn("쿼터/전송 연속 실패 — 백필 중단, 잔여 {}건 다음 주기 재시도", remaining);
					break;
				}
			}
			return new BackfillOutcome(initialRemaining, processed);
		} finally {
			backfillRunning.set(false);
		}
	}

	/** @param remaining 이번 호출 시작 시점의 전체 미판정 건수 @param processed 이번 호출에서 판정을 시도한 총 건수(여러 배치 합산) */
	public record BackfillOutcome(int remaining, int processed) {
	}

	private void judgeMetaSafely(BrandPostMetaRepository.UnjudgedPost meta) {
		if (circuitOpen.get()) {
			// 서킷 열림 — LLM(포함 규칙 판정 전체)을 더 호출하지 않는다. verdict NULL 유지, 다음
			// 백필 호출이 attempted 집합과 무관하게(다음 호출은 별개 attempted) 다시 시도한다.
			return;
		}
		try {
			AdVerdictResult result = judgeOne(meta);
			consecutiveLlmFailures.set(0);
			if (!result.discardedPhrases().isEmpty()) {
				log.warn("광고 표기 판정(백필) — 문구 {}건 폐기됨 {}: {}", result.discardedPhrases().size(),
						meta.shortCode(), result.discardedPhrases());
			}
			metaRepo.updateAdVerdict(meta.shortCode(), result, md5(orEmpty(meta.caption())), Instant.now());
		} catch (RuntimeException e) {
			// verdict NULL 유지 — 이번 호출에서는 attempted 집합에 걸려 재시도하지 않고, 다음 백필
			// 호출(기동 러너 또는 스윕 말미 안전망)이 같은 short_code를 다시 findUnjudged로 만나 재시도한다.
			log.warn("광고 표기 판정(백필) 실패(격리, 다음 백필 재시도) — {}: {}", meta.shortCode(), e.toString());
			int failures = consecutiveLlmFailures.incrementAndGet();
			if (failures >= llmFailureAbortThreshold) {
				circuitOpen.set(true);
			}
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
		// 릴스뿐 아니라 일반 피드의 단일 동영상(HikerBackend는 contentType을 REELS/FEED 2값으로만
		// 매핑하므로 FEED+videoUrl 보유가 그 경우)도 영상 내 표기가 정본 위치라 캡션 부재로 단정할 수
		// 없다 — 스펙 §5 Tier0.
		boolean isVideo = "REELS".equalsIgnoreCase(contentType) || videoUrl != null;
		if (caption.isBlank()) {
			return isVideo
					? new AdVerdictResult("UNCERTAIN", "RULE", List.of(), List.of(), List.of())
					: new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), List.of(), List.of());
		}
		// 완전 외국어(비한국어) 게시물 — 한국 공정위 지침 적용 대상 자체가 아니라 판정을 제외한다
		// (2026-08-27 결정, DECISIONS.md 참조). 캡션에 한글이 한 글자라도 있으면(예: 한국어 문장 +
		// 외국어 단독 표기) 이 규칙을 건너뛰고 기존 Tier1~3 경로(LLM FOREIGN 카테고리 →
		// INSUFFICIENT+FOREIGN_LANGUAGE)로 그대로 흐른다 — is_paid_partnership·공백 캡션 규칙보다
		// 뒤에 둬 그 두 규칙의 언어 무관 우선순위를 건드리지 않는다.
		if (!HANGUL.matcher(caption).find()) {
			return new AdVerdictResult("FOREIGN_POST", "RULE", List.of(), List.of(), List.of());
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
