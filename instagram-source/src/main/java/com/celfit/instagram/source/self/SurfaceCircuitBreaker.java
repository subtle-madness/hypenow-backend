package com.celfit.instagram.source.self;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 표면별 서킷 — 한 표면(embed/wpi/comment 등)에서 연속 블록이 임계값에 도달하면 트립해, 이후 그
 * 표면 요청은 자체를 스킵하고 곧장 폴백하게 한다(캐스케이드 세금 회피, 스펙 §8-4). 성공하면 리셋.
 * 트립 후 쿨다운이 경과하면 half-open으로 프로브 1회를 허용한다 — 성공하면 완전 리셋, 다시
 * 블록되면 트립 시각을 갱신해 새 쿨다운을 시작한다(프로세스 재시작 없이 시간 기반 복구).
 * 전역 킬(killAll)은 자체 전체를 즉시 차단(광범위 붕괴 대응). 스레드 안전.
 *
 * <p>트립·half-open 프로브·복구를 로그로 남긴다(운영에서 09-04 self 폴백이 로그 없이 묻혀 "self
 * 사망"으로 오진됐던 관측 공백 중 하나). {@link #isTripped}·{@link #knownSurfaces}는 monitoring이
 * 게이지로 상태를 노출하기 위한 읽기 전용 접근자다.
 */
public class SurfaceCircuitBreaker {

	private static final Logger log = LoggerFactory.getLogger(SurfaceCircuitBreaker.class);
	private static final long DEFAULT_COOLDOWN_MILLIS = 60_000L;

	private final int threshold;
	private final long cooldownMillis;
	private final LongSupplier clock;
	private final ConcurrentHashMap<String, AtomicInteger> streaks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AtomicLong> trippedAt = new ConcurrentHashMap<>();
	// half-open INFO 로그를 트립 사이클당 1회로 제한 — isOpen()은 폴링성으로 자주 불려 매번 찍으면 도배된다.
	private final ConcurrentHashMap<String, AtomicBoolean> halfOpenLogged = new ConcurrentHashMap<>();
	private volatile boolean killed = false;

	public SurfaceCircuitBreaker(int threshold) {
		this(threshold, DEFAULT_COOLDOWN_MILLIS, System::currentTimeMillis);
	}

	public SurfaceCircuitBreaker(int threshold, long cooldownMillis, LongSupplier clock) {
		this.threshold = threshold;
		this.cooldownMillis = cooldownMillis;
		this.clock = clock;
	}

	public boolean isOpen(String surface) {
		if (killed) {
			return true;
		}
		if (counter(surface).get() < threshold) {
			return false;
		}
		long since = clock.getAsLong() - tripTime(surface).get();
		if (since < cooldownMillis) {
			return true;
		}
		// 쿨다운 경과 — half-open 프로브 1회 허용. 같은 트립 사이클에서 재진입해도 로그는 1회만.
		if (halfOpenLogged.computeIfAbsent(surface, k -> new AtomicBoolean()).compareAndSet(false, true)) {
			log.info("half-open 프로브 허용 surface={}", surface);
		}
		return false;
	}

	public void recordBlock(String surface) {
		int count = counter(surface).incrementAndGet();
		if (count == threshold) {
			// 트립 순간 — 쿨다운을 새로 시작.
			tripTime(surface).set(clock.getAsLong());
			log.warn("자체크롤 서킷 트립 surface={} 연속블록={} 쿨다운={}ms", surface, count, cooldownMillis);
		} else if (count > threshold) {
			// half-open 프로브가 다시 블록됨 — 쿨다운 재시작.
			tripTime(surface).set(clock.getAsLong());
			resetHalfOpenLog(surface);
			log.warn("half-open 프로브 실패, 쿨다운 재시작 surface={}", surface);
		}
	}

	public void recordSuccess(String surface) {
		if (counter(surface).get() >= threshold) {
			log.info("자체크롤 서킷 복구 surface={}", surface);
		}
		counter(surface).set(0);
		tripTime(surface).set(0L);
		resetHalfOpenLog(surface);
	}

	public void killAll() {
		killed = true;
	}

	public void reset() {
		killed = false;
		streaks.clear();
		trippedAt.clear();
		halfOpenLogged.clear();
	}

	/** surface가 현재 트립 상태(카운터가 임계값 이상)인지 — 쿨다운 경과 여부(half-open)와 무관하게,
	 * 전역 킬 상태면 알려진 모든 표면이 트립으로 취급된다. monitoring 게이지 노출용. */
	public boolean isTripped(String surface) {
		if (killed) {
			return true;
		}
		// streaks.get(읽기 전용) — counter()의 computeIfAbsent를 쓰면 조회만으로 knownSurfaces()에
		// 없는 표면 이름이 생겨버린다(게이지 등록 같은 읽기 전용 호출의 부수효과 금지).
		AtomicInteger counter = streaks.get(surface);
		return counter != null && counter.get() >= threshold;
	}

	/** 지금까지 관측된(블록·성공이 한 번이라도 기록된) 표면 이름 집합 — 게이지 등록용. */
	public Set<String> knownSurfaces() {
		return streaks.keySet();
	}

	private void resetHalfOpenLog(String surface) {
		AtomicBoolean logged = halfOpenLogged.get(surface);
		if (logged != null) {
			logged.set(false);
		}
	}

	private AtomicInteger counter(String surface) {
		return streaks.computeIfAbsent(surface, k -> new AtomicInteger());
	}

	private AtomicLong tripTime(String surface) {
		return trippedAt.computeIfAbsent(surface, k -> new AtomicLong());
	}
}
