package com.celfit.monitoring.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 등록 백필(태그 스펙 §5) 전용 executor 2개 — 등록 동기 응답(was 10초 read timeout 예산)
 * 밖에서 백필을 core/enrichment 2단계로 돌린다. 두 단계는 <b>페이지 배치</b>로 맞물린다
 * (2026-08-13 완결 배치 서빙 스펙): core가 페이지를 적재할 때마다 그 페이지분을 enrich 큐로
 * 넘기고, ready는 <b>첫 페이지 배치의 보강 완료</b> 시점에 열린다 — 정산(enriched_at)된 게시물만
 * 목록에 뜨므로 "열거만 끝나면 ready"였던 구 단계식 계약은 폐기됐다.
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
 * 겹치는 최악의 경우 워커 10 + 스윕 core 1 + 등록 core 2 + 해시태그 스윕 1 = <b>최대 14</b>이다
 * (08-18 해시태그 스윕 전용 executor 분리 반영 — 태그당 최대 4페이지 열거뿐이라 +1로 최소화했다.
 * 08-13 워커 상향 전에는 9였다). 08-12 운영 서버 동시성 램프 실측(레벨당 30콜)에서는 동시 20까지
 * 429·5xx 0건으로 하드 리밋이 없는 대신 동시 12부터 15~22초 꼬리가 상시화돼 안전 구간을 ~10으로
 * 잡았지만, <b>08-13 재실측에서 그 상한 근거가 서지 않았다</b>: 정상 브랜드로 워커 6/8/10 세
 * 레벨을 돌려 5초 초과 콜이 세 레벨 모두 0건이었다(레벨 간 증가 없음). 관측되는 꼬리는 동시성에
 * 비례하지 않고 특정 콜에 산발적으로 붙는다 → 14는 실측이 지지하는 구간이다. 힙 예산은 동시
 * in-flight 콜당 ~10MB(TAGGED body 1.7MB + 파싱 트리)로 계산한다 — rawJson 제거(08-12) 전제.
 * 종료로 끊겨도 last_swept_on null(core) 또는 게시자 stale·댓글 워터마크(enrichment)로 다음
 * 스윕이 백스톱한다(미정산분은 다음 스윕의 페이지 배치가 정산까지 다시 태운다).
 *
 * <p><b>brandFollowupExecutor(2026-09 완주 스탬프 축소 개정)는 위 "전역 동시 콜 최대 14" 계산에
 * 들어가지 않는다</b> — 등록 백필 전용 후행 단계(댓글 수집·광고 표기 판정)의 <b>제출 주체</b>일
 * 뿐, 콜은 여전히 전부 brandEnrichWorkerPool(Hiker)·ad-disclosure 전용 풀(LLM)로 나간다. 이
 * executor를 늘려도 Hiker·LLM 동시 콜 예산은 늘지 않는다.</p>
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
	 * 해시태그 스윕 전용 큐 — 2026-08-18 신설(운영 사고 후속: 신규 계정 8개 연속 등록 → 스윕의 LLM
	 * 브랜드 관련성 판정(BrandMentionJudge, Gemini)이 쿼터 429로 콜당 ~6초 재시도 백오프를 태웠는데, 그때까지 스윕이
	 * brandEnrichExecutor(2스레드)를 등록 백필의 보강과 같이 썼다 — 429 warn 788줄이 전부
	 * brand-enrich-1/2 스레드였다. 429가 아니어도 LLM 판정은 콜당 수 초짜리 느린 외부 호출이라,
	 * 빠른 Hiker 콜 다수인 보강과 섞이면 스윕 1건이 보강 워커를 분 단위로 점유하는 구조적 결함이라
	 * 큐 자체를 분리했다.
	 *
	 * <p>동시 1스레드(기본, {@code monitoring.brand.hashtag-sweep-concurrency}) — 스윕은 ready
	 * 폴링과 무관한 꼬리 작업이고 야간 크론이 백스톱하므로 직렬로도 충분하다. 전역 Hiker 동시 콜
	 * 예산 증가도 최소화하려는 의도(+1, 클래스 javadoc 참조).
	 */
	@Bean(name = "brandHashtagSweepExecutor")
	public Executor brandHashtagSweepExecutor(
			@Value("${monitoring.brand.hashtag-sweep-concurrency:1}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-hashtag-sweep-" + seq.incrementAndGet());
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

	/**
	 * 후행(댓글 수집·광고 표기 판정) 전용 큐 — 2026-09 완주 스탬프 축소 개정 신설. 등록 백필
	 * ({@link com.celfit.monitoring.service.BrandCollectService#enrichUserTriggeredDeferred})만
	 * 여기 제출한다. 야간 스윕·해시태그 스윕·direct 동기 등록은 그대로 direct 실행(제자리)이라 이
	 * executor를 참조하지 않는다(무변 보장의 배선 근거).
	 *
	 * <p>기존 brandEnrichExecutor 재사용을 기각한 이유(설계 §2-3) — 08-18 운영 사고(느린 LLM 판정이
	 * 빠른 Hiker 보강 큐를 분 단위로 점유해 뒤 계정 백필을 지연시킨 구조)의 재현이다. 정산·ready
	 * 임계 경로(게시자 보강)와 후행 꼬리(댓글·판정)가 같은 스레드 풀을 나눠 갖는 건 그 사고와 같은
	 * 실수라 전용 풀로 분리했다.
	 *
	 * <p>동시성 기본값 1(설정 {@code monitoring.brand.followup-concurrency}) — 후행은 꼬리 작업이고
	 * 큐가 길어지는 건 의도한 동작이다. 올리면 같은 워커 풀(brandEnrichWorkerPool, 10)의 슬롯을
	 * 정산·ready 임계 경로와 더 나눠 갖게 돼 스탬프가 다시 느려진다. <b>Hiker·LLM 동시 콜 예산에는
	 * 영향 없다</b>(클래스 javadoc 참조) — 여기서 늘리는 건 제출 주체 수뿐이다.
	 */
	@Bean(name = "brandFollowupExecutor")
	public Executor brandFollowupExecutor(
			@Value("${monitoring.brand.followup-concurrency:1}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-followup-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}
}
