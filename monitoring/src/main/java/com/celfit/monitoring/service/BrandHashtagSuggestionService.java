package com.celfit.monitoring.service;

import com.celfit.monitoring.config.BrandHashtagSeedSettings;
import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 해시태그 제안 계산(2026-09-03 자동 시드 재설계 §3) — 계정명 문자열 절삭(2026-08-17~)을
 * 대체한다. <b>DB에 쓰지 않는다</b>: 저장은 was가 전담한다(08-28 "태그 생성 권한 was 일원화" 유지).
 * {@code brand_hashtag}를 읽지도 않는다 — "이미 태그가 있는 브랜드인가"는 was가 태그 GET으로
 * 판정한다(§4-2).
 *
 * <p>3단으로 내려간다:
 * <ol>
 *   <li><b>FREQ</b> — 그 브랜드에 태그된 게시물 캡션의 해시태그 빈도. 최다 태그의 등장 게시물 수가
 *       {@code min-posts}(기본 7) 이상일 때.</li>
 *   <li><b>AI</b> — IG 표시명 + 계정명으로 상호 해시태그 1개({@link BrandHashtagSuggester}).</li>
 *   <li><b>FALLBACK</b> — 계정명에서 점·언더스코어를 뺀 소문자({@link #fallbackTag}).</li>
 * </ol>
 *
 * <p><b>{@code tag}는 절대 비지 않는다</b>(§3-1 계약). 각 단계의 예외는 격리하고 아래 단계로
 * 내려가며, 마지막 FALLBACK은 계정명만 있으면 항상 값을 만든다. 상태를 저장하지 않고 AI가
 * temperature 0이라 같은 입력엔 같은 답을 낸다.
 */
public class BrandHashtagSuggestionService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSuggestionService.class);

	/** 태그: path(freq|ai|fallback) · result(ok|error). */
	static final String METRIC = "brand.hashtag.suggest";

	/** 글자·숫자만 남긴다 — 점과 언더스코어가 함께 사라진다(§3-4의 "점·언더스코어 제거"). */
	private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");
	/** 위 결과가 비었을 때의 2차 정리 — 언더스코어는 유효 태그 문자라 살린다. */
	private static final Pattern NOT_TAG_CHAR = Pattern.compile("[^\\p{L}\\p{N}_]");

	/**
	 * 최종 방어값 — 계정명이 아예 없거나(널·공백, 호출부 결함 방어) {@link #fallbackTag}가 문자를
	 * 하나도 못 건지는 극단(계정명이 점·특수문자뿐)에서만 쓴다. 실제 IG 계정명은 가입 규칙상 항상
	 * 영숫자·언더스코어를 최소 1자 포함하므로 정상 운영에서는 이 상수까지 내려올 일이 없다 —
	 * "tag는 절대 비지 않는다"(§3-1)는 계약을 코드에서 완전히 닫아 두기 위한 방어값이다.
	 */
	static final String UNREACHABLE_FALLBACK_TAG = "brand";

	/**
	 * 제안 1건(§3-1 응답 본문). {@code tag}는 항상 비어 있지 않다 — compact 생성자가 타입 경계에서
	 * 그 계약을 강제한다(공백·빈 문자열은 즉시 {@link IllegalArgumentException}).
	 */
	public record Suggestion(String path, String tag, int topCount, int candidatePosts) {

		public Suggestion {
			if (tag == null || tag.isBlank()) {
				throw new IllegalArgumentException(
						"해시태그 제안 tag는 비어 있을 수 없다(§3-1 계약) — path=" + path);
			}
		}
	}

	private final TaggedPostRepository taggedPosts;
	private final BrandRepository brands;
	private final BrandHashtagSuggester suggester;
	private final BrandHashtagSeedSettings settings;
	private final MeterRegistry registry;

	public BrandHashtagSuggestionService(TaggedPostRepository taggedPosts, BrandRepository brands,
			BrandHashtagSuggester suggester, BrandHashtagSeedSettings settings, MeterRegistry registry) {
		this.taggedPosts = taggedPosts;
		this.brands = brands;
		this.suggester = suggester;
		this.settings = settings;
		this.registry = registry;
	}

	/**
	 * 이 브랜드에 심을 해시태그 1개를 계산한다. 예외를 던지지 않는다.
	 *
	 * @param brandId  monitoring {@code brand_account.id}
	 * @param username IG 계정명 — FALLBACK의 재료라 반드시 있어야 한다. 널·공백이면(호출부 결함
	 *                 방어) 계산을 시도하지 않고 곧장 {@link #UNREACHABLE_FALLBACK_TAG}로 응답한다.
	 */
	public Suggestion suggest(long brandId, String username) {
		if (username == null || username.isBlank()) {
			log.warn("해시태그 제안 계정명이 비어 있음(도달 불가 방어, FALLBACK 처리) — brandId={}", brandId);
			return respond("FALLBACK", UNREACHABLE_FALLBACK_TAG, 0, 0, username, true);
		}
		Set<String> stoplist = settings.stoplist();
		int topCount = 0;
		int candidatePosts = 0;
		String freqTag = null;
		boolean degraded = false;
		try {
			List<TaggedCaption> captions = taggedPosts.findCaptionsForSeed(brandId);
			candidatePosts = captions.size();
			List<HashtagCandidateExtractor.Candidate> candidates =
					HashtagCandidateExtractor.extract(captions, stoplist);
			if (!candidates.isEmpty()) {
				topCount = candidates.getFirst().postCount();
				if (topCount >= settings.minPosts()) {
					freqTag = candidates.getFirst().tag();
				}
			}
		} catch (RuntimeException e) {
			log.warn("해시태그 제안 빈도 집계 실패(격리, AI로 내려간다) — username={}: {}", username, e.toString());
			degraded = true;
		}
		if (freqTag != null) {
			return respond("FREQ", freqTag, topCount, candidatePosts, username, degraded);
		}
		if (settings.aiEnabled()) {
			try {
				String fullName = brands.findFullName(brandId).orElse(null);
				Optional<String> aiTag = suggester.suggest(fullName, username, stoplist);
				if (aiTag.isPresent()) {
					return respond("AI", aiTag.get(), topCount, candidatePosts, username, degraded);
				}
			} catch (RuntimeException e) {
				log.warn("해시태그 제안 AI 실패(격리, FALLBACK으로 내려간다) — username={}: {}",
						username, e.toString());
				degraded = true;
			}
		}
		return respond("FALLBACK", fallbackTag(username), topCount, candidatePosts, username, degraded);
	}

	/**
	 * 최종 안전장치(§3-4) — 계정명에서 점·언더스코어를 빼고 소문자화한다
	 * ({@code dr.piel_official} → {@code drpielofficial}).
	 *
	 * <p>계정명이 점·언더스코어뿐인 극단 케이스에서는 언더스코어를 살려 값을 만들고(언더스코어는
	 * 유효 태그 문자다), 그래도 비면 {@link #UNREACHABLE_FALLBACK_TAG}로 만든다 — IG 계정명 규칙상
	 * 도달 불가지만 "tag는 절대 비지 않는다"는 계약을 코드에서 닫아 둔다.
	 */
	static String fallbackTag(String username) {
		String lower = username.toLowerCase(Locale.ROOT);
		String stripped = NOT_LETTER_OR_DIGIT.matcher(lower).replaceAll("");
		if (!stripped.isEmpty()) {
			return stripped;
		}
		String withUnderscore = NOT_TAG_CHAR.matcher(lower).replaceAll("");
		return withUnderscore.isEmpty() ? UNREACHABLE_FALLBACK_TAG : withUnderscore;
	}

	/** 응답 1건 = 로그 1줄 + 카운터 1증가(§3-6). degraded는 "일부 계산이 예외로 실패했다"는 표식이다. */
	private Suggestion respond(String path, String tag, int topCount, int candidatePosts,
			String username, boolean degraded) {
		log.info("브랜드 해시태그 제안 — username={}, path={}, tag={}, topCount={}, candidatePosts={}",
				username, path, tag, topCount, candidatePosts);
		count(path.toLowerCase(Locale.ROOT), degraded ? "error" : "ok");
		return new Suggestion(path, tag, topCount, candidatePosts);
	}

	/** 지표 기록 실패는 삼킨다(MicrometerInstagramSourceMetrics 관용구) — 관측이 본류를 깨지 않는다. */
	private void count(String path, String result) {
		try {
			Counter.builder(METRIC).tag("path", path).tag("result", result).register(registry).increment();
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 제안 지표 기록 실패(무시) — {} {}: {}", path, result, e.toString());
		}
	}
}
