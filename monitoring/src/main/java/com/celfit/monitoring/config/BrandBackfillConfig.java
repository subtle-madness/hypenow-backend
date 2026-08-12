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
 *   <li><b>backfill</b>: core(365일 백필 열거 + 페이지 스트리밍 적재 — tooq급 2,000건 브랜드
 *       실측 8분+) — 동시 2스레드(08-12): 연속 등록 시 뒤 브랜드의 조기 서빙(~1분 30초)이 앞
 *       브랜드 완주(8분+)를 통째로 기다리던 줄을 절반으로 줄인다. 같은 브랜드의 백필 2개가
 *       겹치는 건 재가입 연타 정도의 희귀 경로고, 적재가 전부 멱등 upsert라 중복 콜 낭비만
 *       있고 데이터는 안 깨진다(스윕과 백필의 같은 브랜드 겹침이 이미 같은 성질).</li>
 *   <li><b>enrich</b>: 게시자 프로필 ~수십 콜 + 댓글 ~수십 콜(수 분) — 이걸 백필 큐에서 빼내는
 *       것이 분리의 목적이다: 같은 큐면 뒤 계정의 ready가 앞 계정 보강(전체 콜의 ~85%)까지
 *       기다린다(08-07 운영 실측 — cclime_official 등록→ready 8.5분 중 5분이 앞 계정 대기).</li>
 * </ul>
 *
 * <p>캠페인 등록의 metricsBackfillExecutor와 분리하는 이유: 브랜드 백필 1건은 수십 초~분 단위
 * 콜 체인이라 공유하면 캠페인 등록 백필(최대 ~1분)이 그 뒤에 줄을 선다. Hiker 콜 병렬화는
 * enrich 내부의 brandEnrichWorkerPool(동시 6)이 담당한다. 전역 동시 콜은 스윕과 등록 백필이
 * 겹치는 최악의 경우 워커 6 + 스윕 core 1 + 등록 core 2 = <b>최대 9</b>다 — 08-12 운영 서버
 * 동시성 램프 실측(레벨당 30콜): 동시 20까지 429·5xx 0건으로 하드 리밋은 없고, 동시 12부터
 * 15~22초 꼬리 응답이 상시화되는데 운영 request-timeout 15초를 넘으면 재시도로 콜 과금이
 * 2배가 된다 → 안전 운용 구간은 전역 ~10 이하(9는 그 안, p95 5초 안쪽 구간). 힙 예산은 동시
 * in-flight 콜당 ~10MB(TAGGED body 1.7MB + 파싱 트리)로 계산한다 — rawJson 제거(08-12) 전제.
 * 종료로 끊겨도 last_swept_on null(core) 또는 게시자 stale·댓글 워터마크(enrichment)로 다음
 * 스윕이 백스톱한다.
 */
@Configuration
public class BrandBackfillConfig {

	@Bean(name = "brandBackfillExecutor")
	public Executor brandBackfillExecutor(
			@Value("${monitoring.brand.backfill-concurrency:2}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-backfill-" + seq.incrementAndGet());
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
