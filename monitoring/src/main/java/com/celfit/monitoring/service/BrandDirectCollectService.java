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

	public BrandDirectCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			TaggedPostRepository taggedPosts, BrandCollectService collect,
			@Value("${monitoring.brand.unenumerated-sweep-limit:300}") int sweepLimit) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.writer = writer;
		this.taggedPosts = taggedPosts;
		this.collect = collect;
		this.sweepLimit = sweepLimit;
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
	 */
	public void sweepUnenumerated(BrandRow brand) {
		callContext.scoped(brand.id(), () -> {
			doSweepUnenumerated(brand);
			return null;
		});
	}

	private void doSweepUnenumerated(BrandRow brand) {
		Instant now = Instant.now();
		List<TaggedPostRepository.TrackedPost> dueAll = taggedPosts
				.unenumeratedDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE)).stream()
				.filter(t -> BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now))
				.toList();
		// 스윕당 상한(2026-08-27 설계 §5) — 구 감지 데이터 이관분은 last_crawled_at이 NULL이라 180일
		// 안이면 전부 즉시 due다. 상한이 없으면 이관 직후 첫 스윕이 브랜드당 최대 1,000건의 단건 콜 +
		// 보강 콜을 한 번에 쏟아내 "전역 동시 콜 14" 예산을 넘긴다. 모수 정렬이 미보강 우선이라
		// (unenumeratedDuePosts) 잘리는 쪽은 항상 이미 보강된 행이고, 잔여는 다음 스윕이 이어받는다.
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
				log.warn("direct 단건 수집 — 게시일 미상, 건너뜀: {}", shortCode);
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
