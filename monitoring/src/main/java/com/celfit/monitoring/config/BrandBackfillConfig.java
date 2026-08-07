package com.celfit.monitoring.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 등록 백필(태그 스펙 §5) 전용 executor 2개 — 등록 동기 응답(was 10초 read timeout 예산)
 * 밖에서 백필을 core/enrichment 2단계로 돌린다(단계식 ready — 2026-08-07 결정).
 *
 * <ul>
 *   <li><b>backfill</b>: core(열거 최대 5페이지 + 적재 + touchSwept, ~30초) — 짧아서 연속 등록도
 *       계정당 ~30초씩만 줄을 선다.</li>
 *   <li><b>enrich</b>: 게시자 프로필 ~수십 콜 + 댓글 ~수십 콜(수 분) — 이걸 백필 큐에서 빼내는
 *       것이 분리의 목적이다: 같은 큐면 뒤 계정의 ready가 앞 계정 보강(전체 콜의 ~85%)까지
 *       기다린다(08-07 운영 실측 — cclime_official 등록→ready 8.5분 중 5분이 앞 계정 대기).</li>
 * </ul>
 *
 * <p>캠페인 등록의 metricsBackfillExecutor와 분리하는 이유: 브랜드 백필 1건은 수십 초~분 단위
 * 콜 체인이라 공유하면 캠페인 등록 백필(최대 ~1분)이 그 뒤에 줄을 선다. 각 executor 단일
 * 스레드·데몬은 같은 관용구다 — 직렬화는 브랜드 단위 큐잉·순서 보장용이고, Hiker 콜 병렬화는
 * enrich 내부의 brandEnrichWorkerPool(동시 6 — 08-07 운영 실측: 동시 8까지 레이턴시 열화·429
 * 전무)이 담당한다. 전역 동시 콜은 워커 6 + core 1 = 최대 7로 실측 한계(8) 안이다. 종료로
 * 끊겨도 last_swept_on null(core) 또는 게시자 stale·댓글 워터마크(enrichment)로 다음 스윕이
 * 백스톱한다.
 */
@Configuration
public class BrandBackfillConfig {

	@Bean(name = "brandBackfillExecutor")
	public Executor brandBackfillExecutor() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "brand-backfill");
			t.setDaemon(true);
			return t;
		});
	}

	@Bean(name = "brandEnrichExecutor")
	public Executor brandEnrichExecutor() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "brand-enrich");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * enrich 내부 Hiker 콜(게시자 프로필·댓글) 병렬 실행용 워커 풀 — 반드시 brandEnrichExecutor와
	 * <b>별도 풀</b>이어야 한다: enrich는 brandEnrichExecutor 스레드에서 join()으로 워커 완료를
	 * 기다리므로 같은 executor를 쓰면 영구 자기 교착이다. 큐는 무제한(newFixedThreadPool 기본) —
	 * 유계 큐 + AbortPolicy면 제출 루프에서 RejectedExecutionException이 동기로 터져 "한 건 실패는
	 * 로그만" 격리 규칙이 깨진다.
	 */
	@Bean(name = "brandEnrichWorkerPool")
	public Executor brandEnrichWorkerPool(
			@Value("${monitoring.brand.enrich-concurrency:6}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-enrich-worker-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}
}
