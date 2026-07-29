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
 * 실패·포화는 로그만 남기고 버린다(fail-open과 동일): 프리페치는 최적화지 기능이 아니다.
 * 프리페치와 실요청이 겹치면 중복 계산될 수 있으나(non-locking writer라 sync=true는 dedup 아님) 무해 —
 * 같은 값을 같은 키에 쓸 뿐이다. 여기서는 스킵 판단을 하지 않는다.
 * 프리페치 스레드에는 요청 컨텍스트(SecurityContext 등)가 없다 — 비개인화 공통 페이지 계산만 태울 것.
 */
@Component
public class PagePrefetcher {

	private static final Logger log = LoggerFactory.getLogger(PagePrefetcher.class);

	// 큐를 짧게(16) 유지 — TPE는 큐 포화 후에야 max(2)로 늘고, 포화 시 포화 폐기 핸들러가
	// 낡은 프리페치를 빨리 버리는 게 목적(프리페치는 시효성 작업).
	private final ExecutorService pool = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(16), runnable -> {
				Thread t = new Thread(runnable, "page-prefetch");
				t.setDaemon(true);
				t.setUncaughtExceptionHandler((th, ex) -> log.warn("프리페치 스레드 비정상 종료", ex));
				return t;
			}, (r, executor) -> log.debug("프리페치 폐기(포화)"));

	/** 마지막 페이지·부분 페이지면 프리페치하지 않는다. */
	public static boolean hasNextPage(int returned, int limit, int offset, long total) {
		return returned == limit && offset + limit < total;
	}

	public void prefetch(Runnable task) {
		pool.execute(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				log.warn("프리페치 실패(무시)", e);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		pool.shutdownNow();
	}
}
