package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.PostShapeUnsupportedException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 브랜드 direct(직접 등록) 게시물 단건 수집(2026-08-18 브랜드 direct 게시물 파이프라인 통합
 * 설계 §2-2·§3-2) — 태그 열거가 절대 도달할 수 없는 게시물(계정에 태그되지 않은 direct 등록분)을
 * 단건 콜로 수집·보강한다.
 *
 * <p><b>"단건 게시물 콜 전면 금지"(08-06·08-09) 결정과 충돌하지 않는다.</b> 그 결정은 <i>태그
 * 열거로 이미 얻은 게시물에 단건 콜을 덧붙이는 것</i>(정책 문서의 "게시물당 단건 상세 콜 1회"
 * 제안)을 기각한 것이고, 근거는 "열거 대비 추가 지표가 없다"였다. direct 게시물은 애초에 열거에
 * 실리지 않으므로(계정에 태그되지 않았다) 그 근거가 성립하지 않는다 — 단건 콜이 유일한 공급원이다.
 * 레거시 url 등록 모드는 지금도 같은 콜({@code CollectService.collectPost}/{@code collectTrackedPost}
 * → {@code /v2/media/info/by/code})을 쓰고 있다 — 통합은 그 콜의 소유자를 브랜드 파이프라인으로
 * 옮기는 것이지 새 콜을 도입하는 것이 아니다.
 */
@Service
public class BrandDirectCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandDirectCollectService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 야간 스윕 2단계 보강 배치 크기 — sweep 1단계의 페이지 배치(~21건)와 같은 규모감. */
	private static final int SWEEP_BATCH_SIZE = 20;

	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandSnapshotWriter writer;
	private final TaggedPostRepository taggedPosts;
	private final BrandCollectService collect;
	/** 스윕당 브랜드당 단건 수집 상한(설계 §5) — 0 이하는 무제한. */
	private final int sweepLimit;
	/** 해시태그 감시 세트 크기(2026-09-02 설계 §1) — 편입 쪽(BrandHashtagCollectService)과 같은 키. */
	private final int monitoringSetSize;
	/**
	 * unenumerated 처리 동시 실행 가드(2026-08-28 리뷰 지적) — {@link #sweepUnenumerated}(야간 스윕
	 * 2단계)와 {@link #backfillUnenriched}(기동 즉시 백필)는 같은 모수(direct∪hashtag 미크롤 행)를
	 * 겹쳐 건드릴 수 있다(배포 재기동이 새벽 스윕 시간대 근처에 걸리면). {@link
	 * com.celfit.monitoring.ad.AdDisclosureJudgeService#backfillRunning}과 같은 단일 공유
	 * AtomicBoolean으로 겹침을 막는다 — 두 진입점 다 브랜드 1건 처리 단위로 획득·해제하므로, 겹치면
	 * 그 브랜드(그 호출) 한 건만 스킵되고 데이터가 깨지지는 않는다(같은 게시물을 두 콜이 동시에
	 * Hiker에 이중 과금하는 것만 막는 목적 — upsert·markEnriched 자체는 멱등이라 스킵된 쪽은 다음
	 * 스윕이나 다음 기동이 다시 잡는다).
	 *
	 * <p>package-private으로 열어 테스트가 겹침 상태를 직접 주입할 수 있게 한다(동시 호출 타이밍을
	 * 실제 스레드 경합으로 재현하지 않고 결정적으로 검증하기 위함 — {@code judgeOne}과 같은 이유).
	 */
	final AtomicBoolean unenumeratedBusy = new AtomicBoolean(false);

	public BrandDirectCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			TaggedPostRepository taggedPosts, BrandCollectService collect,
			@Value("${monitoring.brand.unenumerated-sweep-limit:2000}") int sweepLimit,
			@Value("${monitoring.brand.hashtag.post-limit:2000}") int monitoringSetSize) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.writer = writer;
		this.taggedPosts = taggedPosts;
		this.collect = collect;
		this.sweepLimit = sweepLimit;
		this.monitoringSetSize = monitoringSetSize;
	}

	/**
	 * 게시물 1건 등록·이관 경로 — 동기 완결(단건 1콜 + enrich). 예외는 호출자(컨트롤러)가 매핑한다:
	 * {@link SubjectNotFoundException}(게시물 부재·삭제), {@code PrivateAccountException}(비공개
	 * 계정), {@link PostShapeUnsupportedException}(게시일 미상 등 셰이프 이상)은 그대로 전파한다.
	 */
	public PostInfo collectAndEnrich(BrandRow brand, String shortCode, Instant registeredAt) {
		return callContext.scoped(brand.id(), () -> doCollectAndEnrich(brand, shortCode, registeredAt));
	}

	private PostInfo doCollectAndEnrich(BrandRow brand, String shortCode, Instant registeredAt) {
		PostInfo post = hiker.fetchPost(shortCode);   // SubjectNotFound·PrivateAccount는 그대로 전파
		if (post.takenAt() == null) {
			// brand_tagged_post.taken_at은 NOT NULL이라 저장 불가 — 호출자가 422로 정산하게 던진다.
			throw new PostShapeUnsupportedException("게시일 미상: " + shortCode);
		}
		PostInfo adjusted = collect.adjustLotteryMetrics(List.of(post)).get(0);
		writer.savePost(LocalDate.now(KST), adjusted);
		taggedPosts.upsertDirect(brand.id(), adjusted, registeredAt);
		taggedPosts.touchCrawled(brand.id(), List.of(shortCode), Instant.now());
		collect.enrich(brand, List.of(adjusted));   // 게시자 + 댓글 + markEnriched(finally)
		return adjusted;
	}

	/**
	 * 야간 스윕 2단계(설계 §3-2, <b>2026-08-27 hashtag 일반화</b>) — {@code unenumeratedDuePosts}
	 * (tagged 열거가 도달할 수 없는 direct·hashtag 성분 행) 중 {@link BrandCrawlPolicy#due}인 것만
	 * 게시물 단위 격리로 단건 수집한다. N건(20) 배치마다 {@link BrandCollectService#enrich}를 불러
	 * markEnriched가 finally로 보장되게 한다(08-13 완결 배치 서빙 규율 — direct-only 행은 태그 열거
	 * 백스톱 자체가 없어 1단계보다 더 엄격히 지켜야 한다).
	 *
	 * <p>모수는 2026-08-19 수집 상한 v2 §7-3 이후 <b>겹침 행(태그·direct 둘 다)까지</b> 포함한다 —
	 * direct 게시물은 수집 개수 상한 밖이라, 1단계가 상한 컷에 걸려 도달하지 못한 겹침 행을 여기서
	 * 단건 콜로 구제한다. 1단계가 실제로 만난 겹침 행은 touchCrawled로 due가 꺼져 여기서 걸러진다.
	 *
	 * <p>게시자 프로필·댓글 병렬화는 {@code enrich} 안의 공유 워커 풀이 이미 한다 — 여기서 추가
	 * 병렬화하지 않는다(전역 동시 콜 상한 계산이 깨진다).
	 *
	 * <p>{@link #unenumeratedBusy}로 {@link #backfillUnenriched}와의 동시 실행을 막는다(2026-08-28
	 * 리뷰 지적) — 겹치면 이번 브랜드 호출은 즉시 스킵하고 정상 반환한다(다음 스윕이 자연 재시도).
	 */
	public void sweepUnenumerated(BrandRow brand) {
		if (!unenumeratedBusy.compareAndSet(false, true)) {
			log.info("unenumerated 처리 겹침 - 이번 호출 스킵 brand={}", brand.username());
			return;
		}
		try {
			callContext.scoped(brand.id(), () -> {
				doSweepUnenumerated(brand);
				return null;
			});
		} finally {
			unenumeratedBusy.set(false);
		}
	}

	private void doSweepUnenumerated(BrandRow brand) {
		Instant now = Instant.now();
		Instant minTakenAt = now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE);
		// 감시 세트 바닥(2026-09-02 설계 §3) — hashtag 행이 세트 크기 이상이면 바닥이 생기고,
		// 바닥 밖 행은 ①동결 touch(커버 간주 — 대시보드·정렬 정합) ②모수 제외(매일 티어는 touch로
		// 안 꺼진다 — repo 주석 참조) 두 겹으로 처리한다. 이 시점의 바닥은 "어제까지의 편입" 기준이다
		// (스윕 순서가 ①tagged ②여기 ③hashtag 편입이라) — 오늘 편입분이 바닥을 밀어올리는 효과는
		// 다음 날 스윕부터 반영되며, 하루 지연은 수용한다(설계 §3).
		Instant floor = monitoringSetSize <= 0 ? null
				: taggedPosts.nthNewestHashtagTakenAt(brand.id(), monitoringSetSize).orElse(null);
		if (floor != null) {
			// 동결 touch도 브랜드 추적 창(minTakenAt) 하한을 넘지 않는다(F3, 2026-09-02 최종 리뷰 —
			// touchCrawledDepth의 동형 짝과 같은 유계). 하한이 없으면 이미 추적 종료(180일 초과)돼
			// 영구 제외된 행까지 매 스윕마다 갱신 대상으로 훑어 불필요한 UPDATE 범위가 무계로 자란다.
			taggedPosts.touchFrozenHashtag(brand.id(), minTakenAt, floor, now);
		}
		List<TaggedPostRepository.TrackedPost> dueAll = taggedPosts
				.unenumeratedDuePosts(brand.id(), minTakenAt, floor).stream()
				.filter(t -> BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now))
				.toList();
		// 스윕당 상한(2026-08-27 설계 §5, F6 주석 정정 2026-09-02) — 구 감지 데이터 이관분은
		// last_crawled_at이 NULL이라 180일 안이면 전부 즉시 due다. 상한(sweepLimit, 기본 2,000건 —
		// monitoring.brand.unenumerated-sweep-limit)이 없으면 이관 직후 첫 스윕이 브랜드당 due 전량의
		// 단건 콜 + 보강 콜을 한 번에 쏟아내 "전역 동시 콜 14" 예산을 넘긴다. 모수 정렬이 미보강 우선이라
		// (unenumeratedDuePosts) 미보강 잔량이 상한 이하인 평시엔 잘리는 쪽이 이미 보강된 행이지만,
		// 이관 직후처럼 미보강 due가 상한을 넘는 동안은 잘리는 쪽도 미보강 행이다 — 그 경우 백로그가
		// 여러 스윕에 걸쳐 점진적으로 소진된다.
		List<TaggedPostRepository.TrackedPost> due = sweepLimit > 0 && dueAll.size() > sweepLimit
				? dueAll.subList(0, sweepLimit) : dueAll;
		if (due.size() < dueAll.size()) {
			log.info("2단계 단건 수집 상한({}) 컷 — 브랜드 {} due {}건 중 {}건만 수집, 잔여는 다음 스윕",
					sweepLimit, brand.username(), dueAll.size(), due.size());
		}
		List<PostInfo> batch = new ArrayList<>();
		for (TaggedPostRepository.TrackedPost t : due) {
			collectOne(brand, t.shortCode(), now).ifPresent(batch::add);
			if (batch.size() >= SWEEP_BATCH_SIZE) {
				collect.enrich(brand, List.copyOf(batch));
				batch.clear();
			}
		}
		if (!batch.isEmpty()) {
			collect.enrich(brand, batch);
		}
	}

	/**
	 * 기동 즉시 백필(2026-08-28 사용자 지시) — 이관된 미보강 재고를 야간 스윕의 점진 소진에 맡기지
	 * 않고 배포 직후 한 번에 전량 처리한다. {@link #sweepUnenumerated}와 같은 격리 수집·배치 보강
	 * 골격을 재사용하되 두 가지가 다르다: (1) 모수가 {@link TaggedPostRepository#unenrichedUnenumeratedPosts}
	 * (엄격히 enriched_at IS NULL)라 이미 보강된 행은 애초에 안 들어온다, (2) {@link BrandCrawlPolicy#due}
	 * 나이 티어 필터와 {@code sweepLimit} 스윕당 상한을 <b>적용하지 않는다</b> — 이 행들은 이관 직후
	 * 한 번도 크롤된 적 없는 재고라 "천천히 갚아도 되는" 정상 운영 전제(점진 소진)가 성립하지 않는다.
	 *
	 * <p>{@link #unenumeratedBusy}로 {@link #sweepUnenumerated}와의 동시 실행을 막는다(2026-08-28
	 * 리뷰 지적 — 배포 재기동이 새벽 스윕 시간대 근처에 걸리면 같은 게시물을 이중으로 Hiker에
	 * 과금할 수 있다). 겹치면 이번 브랜드 호출은 즉시 0을 반환한다 — 스킵된 행은 멱등이라 데이터
	 * 유실 없이 다음 야간 스윕(또는 다음 재기동)이 그대로 잡는다.
	 *
	 * @return 이번 호출이 시도한 행 수(성공·실패·격리 전부 포함, 겹침 스킵 시 0) — 러너가 브랜드
	 * 합산 로그에 쓴다.
	 */
	public int backfillUnenriched(BrandRow brand) {
		if (!unenumeratedBusy.compareAndSet(false, true)) {
			log.info("unenumerated 처리 겹침 - 이번 호출 스킵 brand={}", brand.username());
			return 0;
		}
		try {
			return callContext.scoped(brand.id(), () -> doBackfillUnenriched(brand));
		} finally {
			unenumeratedBusy.set(false);
		}
	}

	private int doBackfillUnenriched(BrandRow brand) {
		Instant now = Instant.now();
		List<TaggedPostRepository.TrackedPost> due = taggedPosts
				.unenrichedUnenumeratedPosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE));
		List<PostInfo> batch = new ArrayList<>();
		for (TaggedPostRepository.TrackedPost t : due) {
			collectOne(brand, t.shortCode(), now).ifPresent(batch::add);
			if (batch.size() >= SWEEP_BATCH_SIZE) {
				collect.enrich(brand, List.copyOf(batch));
				batch.clear();
			}
		}
		if (!batch.isEmpty()) {
			collect.enrich(brand, batch);
		}
		return due.size();
	}

	/**
	 * 게시물 1건 격리 수집 — 삭제·비공개 전환({@link SubjectNotFoundException})에도 행을 지우지
	 * 않는다. 대신 unavailable_at을 마킹해 was가 hidden으로 노출한다(2026-08-25 설계 — 스펙 §8의
	 * "상태 전이 없음"에 대한 유일한 예외이며, 성공 재관측이 해제하는 가역 마킹이라 CLOSED 같은
	 * 종결 전이가 아니다). 카드는 마지막 스냅샷으로 남는다. 그 외 실패(타임아웃·5xx·셰이프 이상)는
	 * 이 게시물만 건너뛰고 나머지는 계속 — 한 건의 실패가 배치 전체를 죽이면 안 된다.
	 */
	private Optional<PostInfo> collectOne(BrandRow brand, String shortCode, Instant now) {
		try {
			PostInfo post = hiker.fetchPost(shortCode);
			if (post.takenAt() == null) {
				// fetch 자체는 성공했으므로 커버로 기록해 즉시-due 창에서 빼고, 나이 티어 주기로
				// 재시도한다 — 영구 점유 방지(touchCrawled 없이 두면 unenumeratedDuePosts 정렬이
				// 미보강 우선이라 이 행이 계속 상한 창 맨 앞을 차지해 나머지 행이 영구 굶는다).
				log.warn("unenumerated 재수집: taken_at 없는 게시물 셰이프 - 커버 처리 후 건너뜀 brand={} code={}",
						brand.id(), shortCode);
				taggedPosts.touchCrawled(brand.id(), List.of(shortCode), now);
				return Optional.empty();
			}
			PostInfo adjusted = collect.adjustLotteryMetrics(List.of(post)).get(0);
			writer.savePost(LocalDate.now(KST), adjusted);
			taggedPosts.touchCrawled(brand.id(), List.of(shortCode), now);
			return Optional.of(adjusted);
		} catch (SubjectNotFoundException e) {
			log.info("direct 게시물 부재/비공개 — unavailable 마킹: {} ({})", shortCode, e.toString());
			taggedPosts.markUnavailable(brand.id(), shortCode, now);
		} catch (RuntimeException e) {
			log.warn("direct 단건 수집 실패(격리) — {}: {}", shortCode, e.toString());
		}
		return Optional.empty();
	}
}
