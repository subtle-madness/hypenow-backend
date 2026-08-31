package com.celfit.instagram.source.self;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 표면별 서킷 — 한 표면(embed/wpi/comment 등)에서 연속 블록이 임계값에 도달하면 트립해, 이후 그
 * 표면 요청은 자체를 스킵하고 곧장 폴백하게 한다(캐스케이드 세금 회피, 스펙 §8-4). 성공하면 리셋.
 * 트립 후 쿨다운이 경과하면 half-open으로 프로브 1회를 허용한다 — 성공하면 완전 리셋, 다시
 * 블록되면 트립 시각을 갱신해 새 쿨다운을 시작한다(프로세스 재시작 없이 시간 기반 복구).
 * 전역 킬(killAll)은 자체 전체를 즉시 차단(광범위 붕괴 대응). 스레드 안전.
 */
public class SurfaceCircuitBreaker {

	private static final long DEFAULT_COOLDOWN_MILLIS = 60_000L;

	private final int threshold;
	private final long cooldownMillis;
	private final LongSupplier clock;
	private final ConcurrentHashMap<String, AtomicInteger> streaks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AtomicLong> trippedAt = new ConcurrentHashMap<>();
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
		return since < cooldownMillis; // 쿨다운 경과면 false(half-open 프로브 1회 허용)
	}

	public void recordBlock(String surface) {
		if (counter(surface).incrementAndGet() >= threshold) {
			// 트립 순간(및 half-open 프로브 실패)마다 트립 시각을 갱신해 쿨다운을 새로 시작
			tripTime(surface).set(clock.getAsLong());
		}
	}

	public void recordSuccess(String surface) {
		counter(surface).set(0);
		tripTime(surface).set(0L);
	}

	public void killAll() {
		killed = true;
	}

	public void reset() {
		killed = false;
		streaks.clear();
		trippedAt.clear();
	}

	private AtomicInteger counter(String surface) {
		return streaks.computeIfAbsent(surface, k -> new AtomicInteger());
	}

	private AtomicLong tripTime(String surface) {
		return trippedAt.computeIfAbsent(surface, k -> new AtomicLong());
	}
}
