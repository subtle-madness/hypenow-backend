package com.celfit.monitoring.config;

import com.celfit.monitoring.store.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 브랜드 해시태그 제안 런타임 설정(2026-09-03 자동 시드 재설계 §3-5) — app_setting을 짧은 TTL
 * (기본 5초)로 캐시한다. {@code IgSourceSettings}와 같은 관용구다: 키 부재·이상값은 기본값으로 접고,
 * 조회가 실패하면(DB 장애) 직전 캐시를 유지하며 캐시가 아예 없으면 기본값으로 fail-safe한다 —
 * 설정 조회 예외가 제안 API를 500으로 떨구지 않게 한다.
 *
 * <p>세 키의 기본값은 Flyway 시드({@code V…__brand_hashtag_seed_settings.sql})와 같은 값이다.
 * 여기 상수는 "마이그레이션 이전·행 삭제" 같은 예외 상태의 안전망이지 정본이 아니다 — 기준값
 * 변경은 후속 마이그레이션으로 한다(CLAUDE.md 규칙).
 */
@Service
public class BrandHashtagSeedSettings {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSeedSettings.class);
	private static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

	static final String KEY_MIN_POSTS = "brand.hashtag-seed.min-posts";
	static final String KEY_STOPLIST = "brand.hashtag-seed.stoplist";
	static final String KEY_AI_ENABLED = "brand.hashtag-seed.ai-enabled";

	private static final int DEFAULT_MIN_POSTS = 7;
	static final String DEFAULT_STOPLIST =
			"광고,협찬,이벤트,공구,체험단,유료광고,광고포함,ad,sponsored,pr";

	private final AppSettingRepository settings;
	private final Clock clock;
	private final Duration ttl;

	private volatile Snapshot cache;

	// 테스트 전용 3-arg 생성자가 함께 있어 후보가 2개라 Spring이 암묵 선택을 못 한다 — 명시 지정 필수.
	@Autowired
	public BrandHashtagSeedSettings(AppSettingRepository settings) {
		this(settings, Clock.systemUTC(), DEFAULT_TTL);
	}

	/** 테스트 전용 — clock/ttl을 결정적으로 제어한다(IgSourceSettings와 같은 구조). */
	BrandHashtagSeedSettings(AppSettingRepository settings, Clock clock, Duration ttl) {
		this.settings = settings;
		this.clock = clock;
		this.ttl = ttl;
	}

	/** FREQ 임계(등장 게시물 수, 이 값 이상이면 그 태그를 쓴다). */
	public int minPosts() {
		return snapshot().minPosts();
	}

	/** FREQ 후보·AI 결과에서 제외할 태그(전부 소문자). */
	public Set<String> stoplist() {
		return snapshot().stoplist();
	}

	/** AI 경로 킬 스위치 — false면 FREQ 실패 시 곧장 FALLBACK이다. */
	public boolean aiEnabled() {
		return snapshot().aiEnabled();
	}

	private synchronized Snapshot snapshot() {
		Instant now = clock.instant();
		Snapshot current = cache;
		if (current != null && now.isBefore(current.expiresAt())) {
			return current;
		}
		try {
			Snapshot fresh = load(now);
			cache = fresh;
			return fresh;
		} catch (RuntimeException e) {
			log.warn("해시태그 제안 설정 조회 실패 — 안전측 기본값으로 fail-safe: {}", e.toString());
			Snapshot fallback = current != null ? current.withExpiry(now.plus(ttl))
					: new Snapshot(DEFAULT_MIN_POSTS, parseStoplist(DEFAULT_STOPLIST), true, now.plus(ttl));
			cache = fallback;
			return fallback;
		}
	}

	private Snapshot load(Instant now) {
		int minPosts = settings.find(KEY_MIN_POSTS).map(BrandHashtagSeedSettings::parseMinPosts)
				.orElse(DEFAULT_MIN_POSTS);
		Set<String> stoplist = parseStoplist(settings.find(KEY_STOPLIST).orElse(DEFAULT_STOPLIST));
		boolean aiEnabled = settings.find(KEY_AI_ENABLED)
				.map(v -> "true".equalsIgnoreCase(v.trim())).orElse(true);
		return new Snapshot(minPosts, stoplist, aiEnabled, now.plus(ttl));
	}

	/** 숫자 아님·0 이하는 기본값 — 0 이하를 허용하면 후보 0건에도 FREQ가 나가 규칙이 무너진다. */
	private static int parseMinPosts(String raw) {
		try {
			int parsed = Integer.parseInt(raw.trim());
			if (parsed <= 0) {
				log.warn("{} 값이 0 이하다({}) — 기본값 {}로 접는다", KEY_MIN_POSTS, raw, DEFAULT_MIN_POSTS);
				return DEFAULT_MIN_POSTS;
			}
			return parsed;
		} catch (NumberFormatException e) {
			log.warn("{} 값이 숫자가 아니다({}) — 기본값 {}로 접는다", KEY_MIN_POSTS, raw, DEFAULT_MIN_POSTS);
			return DEFAULT_MIN_POSTS;
		}
	}

	/** 쉼표 구분 → 트림 → 소문자 → 빈 토큰 제거(IgSourceSettings.parseSelfPaths 동형). */
	private static Set<String> parseStoplist(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(raw.split(","))
				.map(token -> token.trim().toLowerCase(Locale.ROOT))
				.filter(token -> !token.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	/** 캐시 스냅샷 — 3개 판정값 + 만료 시각. 성공 조회만 값을 갱신한다. */
	private record Snapshot(int minPosts, Set<String> stoplist, boolean aiEnabled, Instant expiresAt) {

		Snapshot withExpiry(Instant newExpiresAt) {
			return new Snapshot(minPosts, stoplist, aiEnabled, newExpiresAt);
		}
	}
}
