package com.celfit.monitoring.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 등록 백필(태그 스펙 §5) 전용 executor 2개 + ready 타이머 — 등록 동기 응답(was 10초
 * read timeout 예산) 밖에서 백필을 core/enrichment 2단계로 돌린다. 두 단계는 <b>페이지 배치</b>로
 * 맞물린다(2026-08-13 완결 배치 서빙 스펙): core가 페이지를 적재할 때마다 그 페이지분을 enrich
 * 큐로 넘긴다. ready는 <b>첫 배치 완결 또는 (serving-open-timeout 경과 후 정산 1건 이상) 중 빠른
 * 쪽</b>에 열린다(2026-08-14 게시물 단위 정산 개정) — 정산(enriched_at)된 게시물만 목록에 뜨므로
 * "열거만 끝나면 ready"였던 구 단계식 계약은 폐기됐다.
 *
 * <ul>
 *   <li><b>backfill</b>: core(365일 백필 열거 + 페이지 스트리밍 적재 — tooq급 2,000건 브랜드
 *       실측 8분+) — 동시 2스레드(08-12): 연속 등록 시 뒤 브랜드가 앞 브랜드 완주(8분+)를
 *       통째로 기다리던 줄을 절반으로 줄인다. 같은 브랜드의 백필 2개가 겹치는 건 재가입 연타
 *       정도의 희귀 경로고, 적재가 전부 멱등 upsert라 중복 콜 낭비만 있고 데이터는 안 깨진다
 *       (스윕과 백필의 같은 브랜드 겹침이 이미 같은 성질).</li>
 *   <li><b>enrich</b>: 게시자 프로필 ~수십 콜 + 댓글 ~수십 콜(수 분) — 이걸 백필 큐에서 빼내는
 *       것이 분리의 목적이다: 같은 큐면 뒤 계정의 첫 배치 정산이 앞 계정 보강(전체 콜의 ~85%)
 *       까지 기다린다(08-07 운영 실측 — cclime_official 등록→ready 8.5분 중 5분이 앞 계정 대기).</li>
 * </ul>
 *
 * <p>캠페인 등록의 metricsBackfillExecutor와 분리하는 이유: 브랜드 백필 1건은 수십 초~분 단위
 * 콜 체인이라 공유하면 캠페인 등록 백필(최대 ~1분)이 그 뒤에 줄을 선다. Hiker 콜 병렬화는
 * enrich 내부의 brandEnrichWorkerPool(동시 10)이 담당한다. 전역 동시 콜은 스윕과 등록 백필이
 * 겹치는 최악의 경우 워커 10 + 스윕 core 1 + 등록 core 2 = <b>최대 13</b>이다(08-13 워커 상향
 * 전에는 9). 08-12 운영 서버 동시성 램프 실측(레벨당 30콜)에서는 동시 20까지 429·5xx 0건으로
 * 하드 리밋이 없는 대신 동시 12부터 15~22초 꼬리가 상시화돼 안전 구간을 ~10으로 잡았지만,
 * <b>08-13 재실측에서 그 상한 근거가 서지 않았다</b>: 정상 브랜드로 워커 6/8/10 세 레벨을 돌려
 * 5초 초과 콜이 세 레벨 모두 0건이었다(레벨 간 증가 없음). 관측되는 꼬리는 동시성에 비례하지
 * 않고 특정 콜에 산발적으로 붙는다 → 13은 실측이 지지하는 구간이다. 힙 예산은 동시 in-flight
 * 콜당 ~10MB(TAGGED body 1.7MB + 파싱 트리)로 계산한다 — rawJson 제거(08-12) 전제. 종료로
 * 끊겨도 last_swept_on null(core) 또는 게시자 stale·댓글 워터마크(enrichment)로 다음 스윕이
 * 백스톱한다(미정산분은 다음 스윕의 페이지 배치가 정산까지 다시 태운다).
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

	/**
	 * 보강 큐 — 2026-08-13부터 동시 2스레드(설정 {@code monitoring.brand.enrich-executor-concurrency}).
	 * 단일 스레드였을 때는 core가 2병렬인데 보강이 1이라, 연속 등록 시 둘째 브랜드의 보강이 첫
	 * 브랜드 완주를 통째로 기다렸다. 완결 배치 서빙 이전에는 열거만으로 ready가 열려 이 줄이 안
	 * 보였지만, 이제는 정산이 곧 노출이라 둘째 브랜드의 화면이 그대로 빈다.
	 *
	 * <p>같은 개정으로 제출 단위가 "브랜드 1건 = 큰 태스크 1개"에서 "페이지 1건 = 작은 태스크
	 * 다수"로 바뀌었다. FIFO 큐에서 잔 태스크가 섞이므로 뒤 브랜드가 앞 브랜드 완주 전체를
	 * 기다리지 않고 사이사이 진행된다.
	 *
	 * <p><b>Hiker 동시 콜 예산은 늘지 않는다</b> — 보강 콜은 전부 공유 워커 풀
	 * (brandEnrichWorkerPool)을 통해 나가고, 이 executor는 그 풀을 여러 브랜드가 나눠 쓰게 할
	 * 뿐이다(브랜드당 실효 워커가 반감하는 대신 전역 상한은 워커 풀 크기 그대로다).
	 */
	@Bean(name = "brandEnrichExecutor")
	public Executor brandEnrichExecutor(
			@Value("${monitoring.brand.enrich-executor-concurrency:2}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-enrich-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * enrich 내부 Hiker 콜(게시자 프로필·댓글) 병렬 실행용 워커 풀 — 전역 Hiker 동시 콜의 실질
	 * 상한이다(brandEnrichExecutor를 늘려도 여기가 안 늘면 콜은 안 늘어난다). 반드시 brandEnrichExecutor와
	 * <b>별도 풀</b>이어야 한다: enrich는 brandEnrichExecutor 스레드에서 join()으로 워커 완료를
	 * 기다리므로 같은 executor를 쓰면 영구 자기 교착이다. 큐는 무제한(newFixedThreadPool 기본) —
	 * 유계 큐 + AbortPolicy면 제출 루프에서 RejectedExecutionException이 동기로 터져 "한 건 실패는
	 * 로그만" 격리 규칙이 깨진다.
	 */
	/**
	 * ready 개방 타이머(2026-08-14 게시물 단위 정산 스펙 §2) — 등록 백필이 첫 배치 완결을 최대
	 * serving-open-timeout(기본 10초)까지만 기다리게 하는 단발 타이머 전용. enrich executor에
	 * 태우지 않는 이유: 포화 시 큐 대기가 10초 약속을 깨뜨린다. 태스크는 가드된 no-op 체크
	 * 수준(마킹 UPDATE 1건 최대)이라 단일 스레드로 충분하다.
	 */
	@Bean(name = "brandServingTimer")
	public ScheduledExecutorService brandServingTimer() {
		return Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "brand-serving-timer");
			t.setDaemon(true);
			return t;
		});
	}

	@Bean(name = "brandEnrichWorkerPool")
	public Executor brandEnrichWorkerPool(
			@Value("${monitoring.brand.enrich-concurrency:10}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-enrich-worker-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}
}
