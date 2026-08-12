package com.celfit.monitoring.hiker;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * "지금 이 스레드의 Hiker 콜은 어느 브랜드 몫인가"를 드는 스레드 로컬 컨텍스트(2026-08-12
 * 어드민 크롤링 비용 설계) — {@link CountingHikerHttp}가 콜 성공 시점에 읽는다.
 *
 * <p>전파는 명시적이다: 브랜드 보강(enrich)은 워커 풀로 팬아웃되는데 ThreadLocal은 스레드 경계를
 * 넘지 못하므로, 태스크를 제출하는 쪽이 태스크 본문을 {@link #runScoped}로 다시 감싸야 한다
 * (BrandCollectService의 ensureAuthors·collectCommentsGated 참조). 컨텍스트가 없는 콜(캠페인
 * 모니터링 등 브랜드 밖 파이프라인)은 집계 대상이 아니라서 null이 오답이 아니라 정상값이다.
 */
@Component
public class BrandCallContext {

	private final ThreadLocal<Long> current = new ThreadLocal<>();

	/** 현재 스레드의 브랜드 — 없으면 null(브랜드 밖 콜, 집계 제외). */
	public Long currentBrandId() {
		return current.get();
	}

	public <T> T scoped(long brandId, Supplier<T> body) {
		Long prev = current.get();
		current.set(brandId);
		try {
			return body.get();
		} finally {
			// 중첩 스코프(이론상)에서도 바깥 브랜드로 복원 — 최상위면 remove로 누수 차단.
			if (prev == null) {
				current.remove();
			} else {
				current.set(prev);
			}
		}
	}

	public void runScoped(long brandId, Runnable body) {
		scoped(brandId, () -> {
			body.run();
			return null;
		});
	}
}
