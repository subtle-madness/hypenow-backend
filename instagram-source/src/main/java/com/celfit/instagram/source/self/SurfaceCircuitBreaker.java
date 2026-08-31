package com.celfit.instagram.source.self;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 표면별 서킷 — 한 표면(embed/wpi/comment 등)에서 연속 블록이 임계값에 도달하면 트립해, 이후 그
 * 표면 요청은 자체를 스킵하고 곧장 폴백하게 한다(캐스케이드 세금 회피, 스펙 §8-4). 성공하면 리셋.
 * 전역 킬(killAll)은 자체 전체를 즉시 차단(광범위 붕괴 대응). 스레드 안전.
 */
public class SurfaceCircuitBreaker {

	private final int threshold;
	private final ConcurrentHashMap<String, AtomicInteger> streaks = new ConcurrentHashMap<>();
	private volatile boolean killed = false;

	public SurfaceCircuitBreaker(int threshold) {
		this.threshold = threshold;
	}

	public boolean isOpen(String surface) {
		return killed || counter(surface).get() >= threshold;
	}

	public void recordBlock(String surface) {
		counter(surface).incrementAndGet();
	}

	public void recordSuccess(String surface) {
		counter(surface).set(0);
	}

	public void killAll() {
		killed = true;
	}

	public void reset() {
		killed = false;
		streaks.clear();
	}

	private AtomicInteger counter(String surface) {
		return streaks.computeIfAbsent(surface, k -> new AtomicInteger());
	}
}
