package com.celfit.instagram.source.self;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 회복가능 자체 실패를 재시도한다 — K=1(요청당 새 exit IP)이라 재시도가 곧 IP 교체다(crawler
 * BLOCK_MAX_ATTEMPTS=3 계승). 401(익명 한도)·전송 실패·429는 재시도로 회복(다음 IP는 예산이
 * 남았을 확률), NOT_FOUND·구조적 400·로그인 벽은 재시도 무의미라 즉시 전파. 재시도를 소진하면
 * 마지막 예외를 던져 FailoverInstagramSource가 Hiker로 폴백하게 한다.
 */
public final class SelfRetry {

	private static final Logger log = LoggerFactory.getLogger(SelfRetry.class);
	private final int maxAttempts;

	public SelfRetry(int maxAttempts) {
		this.maxAttempts = Math.max(1, maxAttempts);
	}

	public <T> T call(String surface, Supplier<T> op) {
		SelfCrawlException last = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return op.get();
			} catch (SelfCrawlException e) {
				last = e;
				if (!recoverable(e.errorClass()) || attempt == maxAttempts) {
					throw e;
				}
				log.info("자체 {} 재시도 {}/{} — {} (다음 시도=새 IP)",
						surface, attempt + 1, maxAttempts, e.errorClass());
			}
		}
		throw last;
	}

	private static boolean recoverable(SelfErrorClass ec) {
		return ec == SelfErrorClass.RECOVERABLE_401
				|| ec == SelfErrorClass.TRANSPORT
				|| ec == SelfErrorClass.RATE_LIMIT_429;
	}
}
