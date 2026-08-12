package com.celfit.monitoring.hiker;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * "지금 이 스레드의 Hiker 콜은 어느 유저(들)의 캠페인·콘텐츠 모니터링 몫인가"를 드는 스레드 로컬
 * 컨텍스트(2026-08-12 어드민 크롤링 비용 범위 확장) — {@link CountingHikerHttp}가 콜 성공 시점에
 * 읽어 target_call_count에 유저마다 +1한다.
 *
 * <p>{@link BrandCallContext}와 대칭이되 값이 <b>유저 집합</b>이다: 스윕의 계정 열거 1콜은 그 계정에
 * 캠페인을 건 유저 전원을 서빙하므로(DailySweepJob.sweepAccount — 열거는 캠페인 수와 무관하게 1회),
 * 콜 시점에 서빙 유저 전원에게 계상해야 was가 기간 계산 없이 유저 행만 읽을 수 있다. 같은 유저가
 * 그 계정에 캠페인을 여러 개 걸어도 집합이라 1콜 1계상이다.
 *
 * <p>전파는 명시적이다(ThreadLocal은 스레드 경계를 넘지 못한다) — 등록 직후 지표 백필처럼 태스크를
 * executor에 제출하는 쪽이 본문을 {@link #runScoped}로 다시 감싼다(RegistrationService 참조).
 * 컨텍스트가 없는 콜(브랜드 파이프라인 등)은 집계 대상이 아니라 null이 정상값이다.
 */
@Component
public class TargetCallContext {

	private final ThreadLocal<Set<Long>> current = new ThreadLocal<>();

	/** 현재 스레드의 서빙 유저 집합 — 없으면 null(캠페인 밖 콜, 집계 제외). */
	public Set<Long> currentUserIds() {
		return current.get();
	}

	public <T> T scoped(Set<Long> userIds, Supplier<T> body) {
		Set<Long> prev = current.get();
		current.set(userIds);
		try {
			return body.get();
		} finally {
			// 중첩 스코프(계정 스코프 안의 타깃 단건 스코프)에서 바깥 집합으로 복원 — 최상위면 remove로 누수 차단.
			if (prev == null) {
				current.remove();
			} else {
				current.set(prev);
			}
		}
	}

	public void runScoped(Set<Long> userIds, Runnable body) {
		scoped(userIds, () -> {
			body.run();
			return null;
		});
	}
}
