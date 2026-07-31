package com.celfit.was.v1.common;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 무거운 공개 엔드포인트가 HikariCP 커넥션 풀 전체를 독점해 무관한 엔드포인트까지 굶기는 것을
 * 구조적으로 막는 벌크헤드(bulkhead). 07-30 장애: 비로그인 공개 엔드포인트(발굴 리포트 v2 similar 등)에
 * 동시성 40 버스트가 몰려 풀(기본 10) 고갈 → 66초간 무관 요청까지 500. 근본 원인이
 * CPU 바운드 쿼리(2코어 호스트)라 풀 크기 상향은 해법이 아니고, 진입 자체를 제한해야 한다.
 *
 * Semaphore 기반. permit 획득 대기는 짧게(기본 2초, tryAcquire(timeout)) — 멀티탭·새로고침
 * 연타 같은 짧은 버스트는 흡수하되, 그 이상은 즉시 거절한다(30초씩 매달리게 두면 장애가 더
 * 길어진다 — 07-30 사건이 66초까지 늘어진 요인).
 *
 * 호출자는 반드시 tryAcquire()가 true를 반환한 경우에만 finally에서 release()해야 한다
 * (false 반환 시 permit을 잡지 않았으므로 release 불필요·release하면 permit 수가 초과된다).
 */
@Component
public class ConcurrencyLimiter {

	private final Semaphore semaphore;
	private final long acquireTimeoutMillis;

	public ConcurrencyLimiter(
			@Value("${was.concurrency-limit.permits:4}") int permits,
			@Value("${was.concurrency-limit.acquire-timeout-ms:2000}") long acquireTimeoutMillis) {
		this.semaphore = new Semaphore(permits);
		this.acquireTimeoutMillis = acquireTimeoutMillis;
	}

	/**
	 * permit 획득 시도 — 성공하면 true(호출자가 반드시 try/finally로 release() 해야 함),
	 * 대기 시간(기본 2초) 안에 못 얻으면 false(release 불필요).
	 */
	public boolean tryAcquire() {
		try {
			return semaphore.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public void release() {
		semaphore.release();
	}

	/** 테스트 전용 — 현재 가용 permit 수. */
	int availablePermits() {
		return semaphore.availablePermits();
	}
}
