package com.celfit.was.v1.common;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 목록 다음 페이지 선계산 실행기(스펙 §5) — 응답 반환 후 N+1 페이지를 캐시에 미리 적재한다.
 * 실패·포화는 조용히 버린다(fail-open과 동일): 프리페치는 최적화지 기능이 아니다.
 * 중복 계산 방지는 @Cacheable(sync=true)가 담당 — 여기서는 스킵 판단을 하지 않는다.
 */
@Component
public class PagePrefetcher {

	private static final Logger log = LoggerFactory.getLogger(PagePrefetcher.class);

	private final ExecutorService pool = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(64), runnable -> {
				Thread t = new Thread(runnable, "page-prefetch");
				t.setDaemon(true);
				return t;
			}, new ThreadPoolExecutor.DiscardPolicy());

	/** 마지막 페이지·부분 페이지면 프리페치하지 않는다. */
	public static boolean hasNextPage(int returned, int limit, int offset, long total) {
		return returned == limit && offset + limit < total;
	}

	public void prefetch(Runnable task) {
		pool.execute(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				log.debug("프리페치 실패(무시)", e);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		pool.shutdownNow();
	}
}
