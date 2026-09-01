package com.celfit.monitoring.hiker;

import com.celfit.monitoring.store.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 자체크롤 런타임 토글 — app_setting을 짧은 TTL(기본 5초)로 캐시해 재확인한다(킬스위치는 TTL만큼
 * 지연 반영, 08-31 F4). self-enabled이고 NOT force-hiker이고 프록시(레지덴셜) URL이 설정돼 있을 때만
 * 자체 1순위(08-31 F5 — 프록시 미설정 상태에서 데이터센터 IP 직결 차단). 키 부재/이상값은 안전측
 * (false=Hiker). 프로필 표면은 og/wpi(기본 wpi).
 *
 * <p>app_setting 조회가 DataAccessException 등으로 실패하면(DB 장애) 직전 캐시값을 유지하고, 캐시가
 * 아예 없으면(부팅 직후 DB 다운 등) 안전측 기본값(self off)으로 fail-safe한다 — 이 계층의 예외가
 * FailoverInstagramSource.route()의 hikerCall보다 먼저 터져 Hiker 전용 경로까지 죽이는 것을 막는다.
 *
 * <p>댓글 GraphQL의 doc_id·friendly_name도 같은 캐시로 매 콜 재조회한다 — IG doc_id는 2~4주 주기로
 * 회전해(운영 실측) app_setting 값을 재배포 없이 바꿔 대응하기 위함. app_setting에 값이 없으면
 * env 주입(InstagramProxyProperties, monitoring.proxy.comment-doc-id 등)으로 폴백한다.
 */
@Service
public class IgSourceSettings {

	private static final Logger log = LoggerFactory.getLogger(IgSourceSettings.class);
	private static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

	private final AppSettingRepository settings;
	private final InstagramProxyProperties proxyProps;
	private final Clock clock;
	private final Duration ttl;
	private final AtomicBoolean proxyMissingWarned = new AtomicBoolean(false);

	private volatile Snapshot cache;

	// 테스트 전용 4-arg 생성자가 함께 있어 후보가 2개라 Spring이 암묵 선택을 못 한다 — 명시 지정 필수.
	@Autowired
	public IgSourceSettings(AppSettingRepository settings, InstagramProxyProperties proxyProps) {
		this(settings, proxyProps, Clock.systemUTC(), DEFAULT_TTL);
	}

	/** 테스트 전용 — clock/ttl을 결정적으로 제어한다. */
	IgSourceSettings(AppSettingRepository settings, InstagramProxyProperties proxyProps, Clock clock,
			Duration ttl) {
		this.settings = settings;
		this.proxyProps = proxyProps;
		this.clock = clock;
		this.ttl = ttl;
	}

	public boolean selfEnabled() {
		Snapshot s = snapshot();
		if (s.forceHiker() || !s.selfEnabledRaw()) {
			return false;
		}
		if (!proxyConfigured()) {
			if (proxyMissingWarned.compareAndSet(false, true)) {
				log.warn("자체크롤 self-enabled=true지만 레지덴셜 프록시 URL 미설정 — Hiker로 강제"
						+ "(데이터센터 IP 직결·egress 평판 훼손 방지)");
			}
			return false;
		}
		return true;
	}

	public String profileSurface() {
		return snapshot().profileSurface();
	}

	public String commentDocId() {
		return snapshot().commentDocId();
	}

	public String commentFriendlyName() {
		return snapshot().commentFriendlyName();
	}

	/** 레지덴셜 프록시 최소 요건 — 모바일은 선택, 레지덴셜 부재면 자체크롤 금지(F5). */
	private boolean proxyConfigured() {
		String url = proxyProps.residentialUrl();
		return url != null && !url.isBlank();
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
			log.warn("app_setting 조회 실패 — 안전측 기본값으로 fail-safe: {}", e.toString());
			// 실패해도 TTL만큼은 재시도를 미룬다 — DB 장애 중 매 콜 재조회를 막기 위함.
			Snapshot fallback = current != null ? current.withExpiry(now.plus(ttl))
					: Snapshot.safeDefaults(proxyProps, now.plus(ttl));
			cache = fallback;
			return fallback;
		}
	}

	private Snapshot load(Instant now) {
		boolean forceHiker = bool("ig-source.force-hiker", false);
		boolean selfEnabledRaw = bool("ig-source.self-enabled", false);
		String surface = settings.find("ig-source.profile-surface").filter(v -> !v.isBlank()).orElse("wpi");
		String docId = settings.find("ig-source.comment-doc-id")
				.filter(v -> !v.isBlank())
				.orElse(proxyProps.commentDocId());
		String friendlyName = settings.find("ig-source.comment-friendly-name")
				.filter(v -> !v.isBlank())
				.orElse(proxyProps.commentFriendlyName());
		return new Snapshot(forceHiker, selfEnabledRaw, surface, docId, friendlyName, now.plus(ttl));
	}

	private boolean bool(String key, boolean dflt) {
		return settings.find(key).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(dflt);
	}

	/** 캐시 스냅샷 — 5개 판정값 + 만료 시각. 성공 조회만 값을 갱신(실패는 withExpiry로 만료만 연장). */
	private record Snapshot(boolean forceHiker, boolean selfEnabledRaw, String profileSurface,
			String commentDocId, String commentFriendlyName, Instant expiresAt) {

		Snapshot withExpiry(Instant newExpiresAt) {
			return new Snapshot(forceHiker, selfEnabledRaw, profileSurface, commentDocId, commentFriendlyName,
					newExpiresAt);
		}

		static Snapshot safeDefaults(InstagramProxyProperties proxyProps, Instant expiresAt) {
			return new Snapshot(false, false, "wpi", proxyProps.commentDocId(), proxyProps.commentFriendlyName(),
					expiresAt);
		}
	}
}
