package com.celfit.monitoring.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * 유계 병렬 실행 도우미(2026-09-03 야간 스윕 단축) — 브랜드 루프(N)와 2단계 단건 콜(K)이 같은
 * 골격을 쓴다. 항목을 병렬로 처리하되 결과 리스트는 <b>입력 순서</b>로 돌려준다(완주 순서가
 * 아니다 — 배치 구성이 실행 타이밍에 흔들리면 회귀 검증이 불가능해진다).
 *
 * <p><b>concurrency ≤ 1이면 executor를 아예 쓰지 않고 호출 스레드에서 직렬로 돈다</b> — 병렬화
 * 이전과 정확히 같은 코드 경로다. 운영 킬스위치({@link BrandSweepSettings})가 이 분기를 노린다:
 * "1로 내리면 현행 복원"이 스레드 스케줄링에 의존하지 않는 구조적 사실이 된다.
 *
 * <p>병렬 경로는 <b>전용 풀 + 세마포어</b> 두 겹으로 상한을 건다. 풀 크기가 하드 상한(재배포로만
 * 변경)이고 세마포어가 런타임 하향분이다. 기본 설정에서는 둘이 같아 세마포어가 한 번도 블록하지
 * 않는다 — 세마포어가 실제로 무는 것은 운영자가 의도적으로 병렬도를 낮췄을 때뿐이고, 그때 다른
 * 브랜드 태스크가 잠깐 풀 스레드를 점유한 채 대기할 수 있으나 퍼밋 보유자는 항상 진행하므로
 * 교착은 없다(진행 보장).
 *
 * <p>예외는 삼키지 않는다 — 모든 태스크가 <i>완료된 뒤</i> 첫 예외를 그대로 올린다. 호출부는
 * 이미 항목 단위 try/catch로 격리하고 있어(한 건 실패는 로그만) 실제로는 여기까지 오지 않는 것이
 * 정상이며, 그래도 새는 예외는 삼키지 말고 상위 격리에 맡긴다.
 */
final class ParallelRunner {

	private ParallelRunner() {
	}

	static <T, R> List<R> map(List<T> items, int concurrency, Executor pool, Function<T, R> fn) {
		if (concurrency <= 1 || items.size() <= 1) {
			List<R> serial = new ArrayList<>(items.size());
			for (T item : items) {
				serial.add(fn.apply(item));
			}
			return serial;
		}
		Semaphore permits = new Semaphore(concurrency);
		List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
		for (T item : items) {
			futures.add(CompletableFuture.supplyAsync(() -> {
				// 인터럽트를 삼키는 대신 무시한다 — 이 풀은 데몬 고정 풀이고, 중간에 퍼밋 획득이
				// 취소되면 반환 리스트가 구멍 난 채로 배치가 구성된다.
				permits.acquireUninterruptibly();
				try {
					return fn.apply(item);
				} finally {
					permits.release();
				}
			}, pool));
		}
		// allOf는 전부(예외 완료 포함) 끝난 뒤에 완료된다 — 첫 예외가 올라가도 남은 태스크가
		// 유령으로 돌지 않는다.
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
		return futures.stream().map(CompletableFuture::join).toList();
	}
}
