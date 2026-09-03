package com.celfit.monitoring.service;

import com.celfit.monitoring.store.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 야간 브랜드 스윕 병렬도 런타임 토글(2026-09-03 스윕 단축) — app_setting을 짧은 TTL(기본 5초)로
 * 캐시해 재확인한다({@link com.celfit.monitoring.hiker.IgSourceSettings}와 같은 관용구).
 *
 * <ul>
 *   <li>{@code brand-sweep.brand-concurrency}(N) — {@link BrandSweepJob}의 브랜드 루프 병렬도.</li>
 *   <li>{@code brand-sweep.unenumerated-concurrency}(K) — {@link BrandDirectCollectService}
 *       2단계(unenumerated 단건 재수집)의 게시물 콜 병렬도.</li>
 * </ul>
 *
 * <p><b>1은 킬스위치다</b> — 호출부가 1을 받으면 executor를 아예 쓰지 않고 호출 스레드에서 도는
 * 직렬 경로(개정 전과 같은 코드 경로)를 탄다. 운영 이상 시 이 두 키를 1로 UPDATE하면 재배포 없이
 * TTL(5초) 안에 현행 직렬 동작으로 복원된다.
 *
 * <p><b>상한은 전용 executor 풀 크기</b>(생성자 인자 = 프로퍼티 기본값 = 시드 기본값)로 클램프한다.
 * 풀보다 큰 병렬도는 어차피 실현되지 않으므로 값만 커지고 관측이 어긋나는 것을 막는다 —
 * <b>상향은 재배포(프로퍼티), 하향은 런타임</b>이 이 토글의 계약이다(킬스위치 방향으로만 즉시
 * 움직인다). 키 부재·비수치·파싱 실패는 코드 기본값, 1 미만은 1로 클램프한다.
 *
 * <p>app_setting 조회가 실패하면(DB 장애) 직전 캐시값을 유지하고, 캐시가 아예 없으면 코드
 * 기본값으로 fail-safe한다 — 토글 조회 실패가 스윕 자체를 죽이면 안 된다.
 */
@Service
public class BrandSweepSettings {

	private static final Logger log = LoggerFactory.getLogger(BrandSweepSettings.class);
	private static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

	static final String KEY_BRAND_CONCURRENCY = "brand-sweep.brand-concurrency";
	static final String KEY_UNENUMERATED_CONCURRENCY = "brand-sweep.unenumerated-concurrency";

	private final AppSettingRepository settings;
	/** 브랜드 루프 병렬도 기본값 겸 하드 상한 — {@code brandSweepExecutor} 풀 크기와 같은 프로퍼티. */
	private final int brandDefault;
	/** 2단계 단건 콜 병렬도 기본값 겸 하드 상한 — {@code brandUnenumeratedWorkerPool} 풀 크기와 같은 프로퍼티. */
	private final int unenumeratedDefault;
	private final Clock clock;
	private final Duration ttl;

	private volatile Snapshot cache;

	@Autowired
	public BrandSweepSettings(AppSettingRepository settings,
			@Value("${monitoring.brand.sweep-concurrency:3}") int brandDefault,
			@Value("${monitoring.brand.unenumerated-concurrency:8}") int unenumeratedDefault) {
		this(settings, brandDefault, unenumeratedDefault, Clock.systemUTC(), DEFAULT_TTL);
	}

	/** 테스트 전용 — clock/ttl을 결정적으로 제어한다. */
	BrandSweepSettings(AppSettingRepository settings, int brandDefault, int unenumeratedDefault, Clock clock,
			Duration ttl) {
		this.settings = settings;
		this.brandDefault = Math.max(1, brandDefault);
		this.unenumeratedDefault = Math.max(1, unenumeratedDefault);
		this.clock = clock;
		this.ttl = ttl;
	}

	/** 브랜드 루프 병렬도 N(1 = 직렬 복원). */
	public int brandConcurrency() {
		return snapshot().brandConcurrency();
	}

	/** 2단계 단건 콜 병렬도 K(1 = 직렬 복원). */
	public int unenumeratedConcurrency() {
		return snapshot().unenumeratedConcurrency();
	}

	private synchronized Snapshot snapshot() {
		Instant now = clock.instant();
		Snapshot current = cache;
		if (current != null && now.isBefore(current.expiresAt())) {
			return current;
		}
		try {
			Snapshot fresh = new Snapshot(read(KEY_BRAND_CONCURRENCY, brandDefault),
					read(KEY_UNENUMERATED_CONCURRENCY, unenumeratedDefault), now.plus(ttl));
			cache = fresh;
			return fresh;
		} catch (RuntimeException e) {
			log.warn("스윕 병렬도 설정 조회 실패 — 직전 값 유지(없으면 코드 기본값): {}", e.toString());
			// 실패해도 TTL만큼은 재시도를 미룬다 — DB 장애 중 매 브랜드 재조회를 막기 위함.
			Snapshot fallback = current != null ? current.withExpiry(now.plus(ttl))
					: new Snapshot(brandDefault, unenumeratedDefault, now.plus(ttl));
			cache = fallback;
			return fallback;
		}
	}

	/** 값 파싱 + [1, 기본값(=풀 크기)] 클램프. 부재·비수치는 기본값. */
	private int read(String key, int dflt) {
		String raw = settings.find(key).orElse(null);
		if (raw == null || raw.isBlank()) {
			return dflt;
		}
		int parsed;
		try {
			parsed = Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			log.warn("스윕 병렬도 설정 값이 수치가 아님 — 기본값 {} 사용: {}={}", dflt, key, raw);
			return dflt;
		}
		return Math.clamp(parsed, 1, dflt);
	}

	/** 캐시 스냅샷 — 두 병렬도 + 만료 시각. 성공 조회만 값을 갱신(실패는 withExpiry로 만료만 연장). */
	private record Snapshot(int brandConcurrency, int unenumeratedConcurrency, Instant expiresAt) {

		Snapshot withExpiry(Instant newExpiresAt) {
			return new Snapshot(brandConcurrency, unenumeratedConcurrency, newExpiresAt);
		}
	}
}
