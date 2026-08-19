package com.celfit.monitoring.service;

import com.celfit.monitoring.ad.AdDisclosureJudgeService;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandCommentRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 브랜드 태그 수집 본체(2026-08-06 스펙 + 2026-08-09 크롤링 정책 v1) — 태그 열거 단일 경로
 * (/v2/user/tag/medias)로 수집하되, 깊이는 게시물 나이 티어({@link BrandCrawlPolicy})가 정한다:
 * 매일 최소 14일 깊이(신규 감지 겸용) + due 게시물이 있으면 그 taken_at까지 확장, 등록 백필은
 * 브랜드별 수집 창(collection_months) 전체. 단건 게시물 콜은 전면 금지 유지(08-06 실측 — 열거
 * 대비 추가 지표 없음).
 *
 * <p>저장은 전면 브랜드 전용 스키마(08-06 결정) — 캠페인 테이블(post_snapshot 계열)을 한 줄도
 * 건드리지 않는다(볼륨 격리 + 겹침 게시물 덮어쓰기 차단). 트랜잭션은 여기 없다(CollectService와
 * 같은 이유), 쓰기는 {@link BrandSnapshotWriter}가 짧게 묶는다.
 *
 * <p>2026-08-12 스트리밍 개정 + 2026-08-13 완결 배치 서빙 개정: 적재는 페이지 단위로 즉시
 * 일어나고, 콜백도 페이지마다 그 페이지분만 넘긴다 — 수신자(등록 백필)가 페이지 단위로 보강·정산해
 * 완결된 것부터 목록에 올린다. 구 서빙 창(30일) 커버 기준은 폐기됐다.
 *
 * <p>2026-08-19 수집 개수 상한: 열거는 브랜드당 최신 collection-post-limit(기본 2,000)건에서 의도된
 * 자연 종료 — 목표 컷 전체 touch로 컷 밖 지표 동결.
 */
@Service
public class BrandCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandCollectService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandSnapshotWriter writer;
	private final BrandSnapshotRepository snapshots;
	private final BrandCommentRepository comments;
	private final TaggedPostRepository taggedPosts;
	private final AuthorProfileRepository authors;
	private final AdDisclosureJudgeService adJudge;
	private final Executor enrichWorker;
	private final int maxPostsPerSweep;
	private final int collectionPostLimit;
	private final int commentPages;
	private final int authorStaleDays;
	private final boolean adDisclosureEnabled;

	public BrandCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			BrandSnapshotRepository snapshots, BrandCommentRepository comments,
			TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
			AdDisclosureJudgeService adJudge,
			@Qualifier("brandEnrichWorkerPool") Executor enrichWorker,
			@Value("${monitoring.brand.max-posts-per-sweep:10000}") int maxPostsPerSweep,
			@Value("${monitoring.brand.collection-post-limit:2000}") int collectionPostLimit,
			@Value("${monitoring.brand.comment-pages:3}") int commentPages,
			@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays,
			@Value("${monitoring.brand.ad-disclosure.enabled:true}") boolean adDisclosureEnabled) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.writer = writer;
		this.snapshots = snapshots;
		this.comments = comments;
		this.taggedPosts = taggedPosts;
		this.authors = authors;
		this.adJudge = adJudge;
		this.enrichWorker = enrichWorker;
		this.maxPostsPerSweep = maxPostsPerSweep;
		this.collectionPostLimit = collectionPostLimit;
		this.commentPages = commentPages;
		this.authorStaleDays = authorStaleDays;
		this.adDisclosureEnabled = adDisclosureEnabled;
	}

	/**
	 * 브랜드 1개분 전량 수집(매일 스윕 경로) — 2026-08-13 개정: 열거 전량 후 일괄 보강이 아니라
	 * <b>페이지마다 보강·정산</b>한다({@link #sweepCore}의 페이지 콜백에 {@link #enrich}를 건다).
	 * 완결 서빙 규칙(정산된 게시물만 노출) 아래서 일괄 보강을 유지하면, 스윕이 도는 동안 새로
	 * 적재된 게시물이 목록에서 사라지고(적재는 끝났는데 정산이 스윕 말미까지 안 온다) 중간 실패
	 * 시 그 스윕에서 만난 게시물이 통째로 미정산 = 다음 스윕까지 미노출로 남는다.
	 *
	 * <p>등록 백필은 이 메서드를 쓰지 않고 두 단계를 각자 executor에서 따로 돈다 — 배치 단위는
	 * 같지만(페이지) ready 판정 시점이 다르다(첫 페이지 배치의 보강 완료).
	 */
	public void sweep(BrandRow brand) {
		sweepCore(brand, page -> enrichSafely(brand, page));
	}

	/**
	 * 페이지 콜백의 보강 실패 격리 — 등록 경로(BrandRegistrationService.runEnrichSafely)와 같은
	 * 규칙이다. {@link #sweepCore}는 콜백 예외를 잡지 않으므로 격리하지 않으면 한 페이지의 보강
	 * 실패가 열거 루프를 통째로 끊어 <b>뒤 페이지가 그날 적재조차 되지 않는다</b>.
	 *
	 * <p>격리하는 이유는 교환비다: 열거분은 페이지당 Hiker 콜을 이미 지불하고 얻은 결과물이라
	 * 보강 쪽 DB 실패로 그날 열거를 버리는 건 손해가 크다. 여기까지 예외가 올라오는 경우는
	 * {@link #enrich}가 정산 마킹을 finally로 보장한 뒤라 <b>markEnriched 자신의 실패</b>로 좁혀지고,
	 * 그때 미정산으로 남은 게시물은 14~180일 구간에 한해 지연이다 — 다음 스윕이 티어 주기에 따라
	 * 같은 게시물을 다시 만나 보강·정산한다(게시자는 stale 판정, 댓글은 워터마크가 재시도 대상으로
	 * 남긴다). 180일 초과 구간은 재열거 자체가 없어 지연이 아니라 영구 미노출이다 — 그래서 정산
	 * 마킹을 이 격리 앞(enrich의 finally)에 두는 것이 본선이고, 여기는 최후 방어선일 뿐이다.
	 * 적재를 건너뛴 페이지는 아예 백스톱이 없다: 한 번도 적재된 적 없는 게시물은 trackedPosts에
	 * 없어 다음 스윕의 깊이 컷(min(14일, 가장 오래된 due))을 끌어내리지 못하므로, 소급 태그된
	 * 14일 이상 게시물이 영구 미수집으로 굳을 수 있다.
	 */
	private void enrichSafely(BrandRow brand, List<PostInfo> page) {
		try {
			enrich(brand, page);
		} catch (RuntimeException e) {
			log.warn("브랜드 스윕 보강 실패(격리, 열거 계속) — {} 다음 스윕이 백스톱: {}",
					brand.username(), e.toString());
		}
	}

	/** 단일 인자 경로 — 페이지 콜백 없이 동작은 동일하다(열거·적재만 하고 누적 결과를 돌려준다). */
	public List<PostInfo> sweepCore(BrandRow brand) {
		return sweepCore(brand, posts -> {});
	}

	/**
	 * core 단계(2026-08-12 스트리밍 개정) — 열거하면서 페이지(~21건)마다 즉시 적재한다. 구 일괄
	 * processCore 대비 의미 불변이고 실행 시점만 당겨진다: 중간 실패 시 앞 페이지 적재분이
	 * 보존되고(다음 스윕이 잔여를 백스톱), 등록 백필은 페이지 단위로 보강·정산을 이어붙일 수 있다.
	 *
	 * <p>onPageCollected는 <b>페이지마다 1회</b> 호출되고(예외로 중단되면 그 페이지부터는 없다),
	 * 인자는 <b>그 페이지의 편입분만</b>이다 — 누적 리스트가 아니다(2026-08-13 완결 배치 서빙
	 * 스펙 §2). 편입 컷에 다 걸려 빈 리스트가 갈 수도 있다. 페이지가 한 장도 처리되지 않았으면
	 * (태그 0건·즉시 커서 소진) 종료 시점에 빈 리스트로 <b>1회</b> 부른다 — 수신자가 ready를 열
	 * 기회를 못 얻으면 그 브랜드가 collecting에 영구히 갇힌다. 구 계약("서빙 창 30일 커버 시
	 * 정확히 1회 + 누적 리스트")은 폐기됐다.
	 *
	 * <p>열거 중단 4종·coveredCutoff·touchCrawledDepth 의미는 기존과 동일하다 — 중단 조건은
	 * ①페이지 전체가 깊이 컷(수집 창/14일) 이전 ②커서 소진(nextPageId null·빈 페이지)
	 * ③커서 미전진(신규 code 0건) ④안전 상한(maxPostsPerSweep) 도달. ①②는 컷까지 다 훑은
	 * 자연 종료라 coveredCutoff=true → touchCrawledDepth로 그 깊이 전체를 touch하고,
	 * ③④는 미커버라 touch하지 않는다(다음 스윕이 같은 깊이를 다시 연다).
	 *
	 * <p>상한은 폭주 방어 밸브다(정상 경로에서 닿으면 안 된다 — 2,000은 tooq급 정상 고물량
	 * 브랜드가 백필·심층 티어 스윕에서 닿아 10,000으로 상향, 08-12 상한 개정 스펙). ④가
	 * 백필에서 나면 커버 깊이 밖 구간은 이후 스윕이 열지 않아 영구 공백이 된다 — 그래서 error
	 * 신호이며, 보정은 운영 절차(상한 상향 + last_swept_on 리셋 재백필)다. touchSwept는 그래도
	 * 유지한다(있는 만큼 즉시 서빙 — 리셋 재열거 루프 방지, 08-12 상한 개정 스펙 §3).
	 */
	public List<PostInfo> sweepCore(BrandRow brand, Consumer<List<PostInfo>> onPageCollected) {
		// 콜 집계 스코프(어드민 크롤링 비용) — 이 안의 Hiker 콜은 전부 이 브랜드 몫으로 계상된다.
		// 등록 백필(두-인자 직접 호출)도 같은 진입점이라 함께 계상된다.
		return callContext.scoped(brand.id(), () -> doSweepCore(brand, onPageCollected));
	}

	private List<PostInfo> doSweepCore(BrandRow brand, Consumer<List<PostInfo>> onPageCollected) {
		refreshBrandProfileSafely(brand);
		Instant now = Instant.now();
		Instant cutoff = enumerationCutoff(brand, now);
		LocalDate today = LocalDate.now(KST);
		Set<String> known = taggedPosts.knownCodes(brand.id());
		Set<String> seen = new LinkedHashSet<>();      // 이번 실행 처리분 — 페이지 간 중복(커서 드리프트) 스킵
		List<PostInfo> collected = new ArrayList<>();  // 편입분 누적 — 콜백·반환(보강 입력)
		int freshTotal = 0;
		boolean anyPageDelivered = false;   // 콜백을 한 번이라도 불렀는지 — 종료 시 폴백 판정용
		String cursor = null;
		boolean coveredCutoff = false;
		while (true) {
			HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), cursor);
			if (page.posts().isEmpty()) {
				// 태그 0건(404 → 빈 페이지)·커서 종료는 자연 종료. 반대로 아직 커서가 살아 있는데
				// 빈 페이지가 오는 건 일시 오류와 구분할 수 없어 커버로 치지 않는다(보수적 판정).
				coveredCutoff = page.nextPageId() == null || seen.isEmpty();
				break;
			}
			List<PostInfo> newItems = page.posts().stream()
					.filter(p -> seen.add(p.shortCode()))   // 첫 관측 유지(구 putIfAbsent 의미)
					.toList();
			int knownBefore = known.size();
			List<PostInfo> pageCollected = processPage(brand, newItems, known, today, now);
			collected.addAll(pageCollected);
			freshTotal += known.size() - knownBefore;
			// 페이지 배치 방출(2026-08-13 완결 배치 서빙 스펙 §2) — 이 페이지분을 즉시 콜백에 넘긴다.
			// 수신자가 보강·정산을 끝내면 그때부터 was 목록에 뜬다. 누적이 아니라 페이지분만 넘기므로
			// 수신자 쪽 중복 필터(구 earlyCodes)가 통째로 불필요해진다. 열거는 여기서 끊기지 않는다.
			onPageCollected.accept(pageCollected);
			anyPageDelivered = true;
			// taken_at 미상 아이템은 "컷 이전" 판정에 넣지 않는다(보수적으로 열거 계속).
			boolean wholePageBeforeCutoff = page.posts().stream()
					.allMatch(p -> p.takenAt() != null
							&& Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff));
			if (wholePageBeforeCutoff || page.nextPageId() == null) {
				coveredCutoff = true;
				break;
			}
			// 수집 개수 상한(2026-08-19 스펙) — 비용 목적의 "의도된 자연 종료"다. 안전 밸브(아래
			// maxPostsPerSweep)와 역할이 다르다: 밸브는 닿으면 안 되는 폭주 방어(ERROR·커버 미처리 —
			// 다음 스윕이 같은 깊이를 다시 연다)이고, 이 컷은 정상 경로(INFO·커버 처리)다.
			// coveredCutoff=true로 목표 컷 "전체"를 touch하는 것이 핵심이다(스펙 §3-2): 컷 밖(더 깊은)
			// due의 last_crawled_at이 실크롤 없이 갱신돼 ①매 스윕이 그 깊이를 다시 여는 낭비 루프가
			// 차단되고 ②그 게시물들은 마지막 수집 시점 지표로 동결된 채 계속 서빙된다(was 목록
			// 상한 2,000과 정합). 판정이 커서 소진 뒤인 이유는 밸브와 동일 — 마지막 페이지에서 정확히
			// 상한에 닿는 건 자연 종료다. 0 이하면 무제한이다(backfill-max-per-run 관용 일치) —
			// 0을 "1페이지 후 목표 컷 전체 동결"로 읽으면 브랜드가 티어 주기 동안 조용히 얼어붙는다.
			if (collectionPostLimit > 0 && seen.size() >= collectionPostLimit) {
				log.info("브랜드 태그 수집 개수 상한({}) 도달 — {} 의도된 자연 종료"
								+ " (열거 {}건, 목표 컷 {}, 실제 커버 깊이 {})",
						collectionPostLimit, brand.username(), seen.size(), cutoff,
						oldestTakenAt(collected));
				coveredCutoff = true;
				break;
			}
			// 상한 판정은 커서 소진 판정 뒤에 둔다 — 마지막 페이지에서 정확히 상한에 닿는 건
			// 자연 종료지 폭주가 아니라, 여기서 경고를 찍으면 오보가 된다.
			if (seen.size() >= maxPostsPerSweep) {
				// 도달 = 폭주 또는 상한 캘리브레이션 오류(08-12 스펙 §3) — 백필 경로면 커버 깊이
				// 밖 구간이 조용히 영구 공백이 되므로, 운영이 보정(상한 상향 + last_swept_on 리셋)
				// 판단을 내릴 수 있게 커버 깊이까지 error로 남긴다.
				log.error("태그 열거 안전 상한({}) 도달 — 브랜드 {} 정상 경로에서 닿으면 안 되는 값, 열거 중단"
								+ " (열거 {}건, 목표 컷 {}, 실제 커버 깊이 {})",
						maxPostsPerSweep, brand.username(), seen.size(), cutoff,
						oldestTakenAt(collected));
				break;
			}
			if (newItems.isEmpty()) {
				log.warn("태그 커서 미전진 의심 — 브랜드 {} 신규 code 0건, 열거 중단", brand.username());
				break;
			}
			cursor = page.nextPageId();
		}
		if (!anyPageDelivered) {
			// 처리할 페이지가 한 장도 없었던 경우(태그 0건·즉시 커서 소진) — 수신자(등록 백필)가
			// ready를 열 수 있게 빈 배치로 1회 부른다. 안 부르면 태그가 없는 브랜드가 collecting에
			// 영구히 갇힌다. 판정은 "콜백을 불렀는가" 하나뿐이다 — 편입 컷에 다 걸려 빈 페이지분을
			// 넘긴 경우도 이미 "부른" 것이라 여기서 또 부르지 않는다.
			onPageCollected.accept(List.of());
		}
		log.info("브랜드 태그 수집 — {} 열거 {}건, 편입 컷 안 {}건, 신규 {}건",
				brand.username(), seen.size(), collected.size(), freshTotal);
		if (coveredCutoff) {
			// 열거에 더 안 실리는 링크(삭제·태그 제거·비공개 전환)까지 포함해 커버한 깊이 전체를
			// touch — 안 하면 그 링크의 due가 영구 true로 굳어 매 스윕이 같은 깊이를 다시 연다.
			taggedPosts.touchCrawledDepth(brand.id(), cutoff, now);
		}
		return List.copyOf(collected);
	}

	/**
	 * 브랜드별 수집 창 컷 — KST 캘린더 개월(요청서 "게시물 taken_at 기준 최근 N개월").
	 * 열거 깊이(백필)와 편입 필터가 같은 컷을 쓴다 — 창 밖 소급 태그가 편입되지 않게.
	 */
	private static Instant collectionCutoff(BrandRow brand, Instant now) {
		return ZonedDateTime.ofInstant(now, KST).minusMonths(brand.collectionMonths()).toInstant();
	}

	/**
	 * 오늘의 열거 깊이 컷(정책 v1 스펙 §4) — 백필(last_swept_on null: 등록 직후·백필 실패
	 * 백스톱·재가입)은 브랜드별 수집 창(collection_months) 전체, 이후엔 min(14일 컷, 가장 오래된
	 * due 게시물의 taken_at). due 판정은 {@link BrandCrawlPolicy} 순수 함수 — 깊은 열거가 얕은
	 * 티어를 자동 포함하므로 정책의 중복 제거 규칙이 구조적으로 성립하고, 스윕이 하루 빠져도 다음
	 * 날 due 계산이 밀린 깊이까지 자동 커버한다(자가 치유).
	 */
	private Instant enumerationCutoff(BrandRow brand, Instant now) {
		if (brand.lastSweptOn() == null) {
			return collectionCutoff(brand, now);
		}
		Instant cutoff = now.minus(BrandCrawlPolicy.DAILY_MAX_AGE);
		for (TaggedPostRepository.TrackedPost t : taggedPosts.trackedPosts(brand.id(),
				now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE))) {
			if (t.takenAt().isBefore(cutoff)
					&& BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now)) {
				cutoff = t.takenAt();
			}
		}
		return cutoff;
	}

	/**
	 * enrichment 단계(2026-08-17 노출 게이트 개정 — 스펙 §8) — 정산 마킹(markEnriched)을 게시자
	 * 프로필 보강 완료 <b>직후</b>로 당긴다. 댓글 수집·광고 표기 판정은 노출 게이트 밖으로 빠져
	 * 각자 격리된 독립 단계가 되고, 프론트 폴링으로 나중에 채워진다(프로그레시브 서빙).
	 *
	 * <p>정산 마킹은 여전히 <b>게시자 보강의 성패와 무관하게 무조건</b> 찍는다(finally — 근거는
	 * 아래 finally 블록 주석, 기존 규칙 그대로). 댓글·광고 판정도 <b>게시자 보강의 성패(배치 DB
	 * 조회가 던지는 하드 실패 포함)와 무관하게</b> 돈다 — 그래서 바깥쪽에 finally를 하나 더 감아
	 * 이중 try/finally로 만든다(2026-08-18 버그 수정: 안쪽 finally만 있으면 ensureAuthors가 던진
	 * 예외가 markEnriched 직후 곧장 메서드 밖으로 전파돼 댓글·판정 호출 자체가 도달 불가였다).
	 * 각 단계의 실패가 서로에게 번지면 안 되므로 collectCommentsGatedSafely·judgeAdDisclosuresSafely
	 * 내부에서 각자 try/catch로 한 번 더 격리한다.
	 *
	 * @see #enrich(BrandRow, List, Runnable) onVisible 훅(계정 게이트 단축) 버전
	 */
	public void enrich(BrandRow brand, List<PostInfo> posts) {
		enrich(brand, posts, null);
	}

	/**
	 * onVisible 훅 버전(2026-08-18 계정 게이트 단축) — 등록 백필의 <b>첫 페이지</b>가 계정 단위
	 * ready(markServing)를 게시물 게이트(markEnriched)와 <b>같은 지점</b>에서 열 수 있게 하는 확장
	 * 진입점이다({@link BrandRegistrationService#runBackfillSafely} 참조). 댓글 수집·광고 표기
	 * 판정은 개정 전과 마찬가지로 이 지점 <b>밖</b>이라, 계정 게이트를 열기 전에 그 두 단계(수 초~
	 * 수십 초, 워커 병렬화에도 브랜드당 순차 join)를 기다리지 않는다 — 이게 이 훅의 존재 이유다.
	 * 종전 배선(등록 백필이 {@link #enrich(BrandRow, List)} 전체 반환을 기다린 뒤 markServing)은
	 * 댓글·판정까지 다 끝나야 계정이 뜨는 회귀였다.
	 *
	 * <p>onVisible은 <b>markEnriched 마킹과 같은 finally 안, 그 직후</b>에 부른다 — ensureAuthors가
	 * 하드 실패해도(배치 DB 조회 예외) markEnriched처럼 무조건 호출된다. null이면 조용히 무시한다
	 * (야간 스윕 {@link #enrichSafely}가 쓰는 2-인자 {@link #enrich(BrandRow, List)}는 이 인자 없이
	 * 위임하므로 스윕 경로는 무변 — 계정 게이트 자체가 스윕에 없다).
	 *
	 * <p>posts가 비어 있으면 ensureAuthors조차 돌지 않지만 onVisible은 그래도 부른다(구 markServing
	 * 무조건 호출과 동치) — 등록 첫 페이지가 편입 컷·태그 0건으로 빈 배치일 수 있고, 여기서 안
	 * 부르면 그 브랜드가 collecting에 영구히 갇힌다({@link #sweepCore} 콜백 계약 참조).
	 */
	public void enrich(BrandRow brand, List<PostInfo> posts, Runnable onVisible) {
		if (posts.isEmpty()) {
			if (onVisible != null) {
				onVisible.run();
			}
			return;
		}
		try {
			try {
				ensureAuthors(brand.id(), posts);
			} finally {
				// 정산 마킹(2026-08-17 노출 게이트 개정 — 스펙 §8) — 게시자 보강 성패와 무관하게 찍는다.
				// was 게이트(enriched_at IS NOT NULL)의 의미가 "게시자 보강 완료 = 노출 가능"으로
				// 좁혀졌다. finally인 이유는 기존과 동일(180일 초과 게시물엔 재열거 백스톱이 없다).
				taggedPosts.markEnriched(brand.id(),
						posts.stream().map(PostInfo::shortCode).toList(), Instant.now());
				// 계정 게이트 훅(2026-08-18) — markEnriched와 같은 보장(finally, 하드 실패 무관)으로
				// 댓글·판정 시작 전에 1회 호출한다.
				if (onVisible != null) {
					onVisible.run();
				}
			}
		} finally {
			// 댓글·광고 판정은 노출 게이트 밖 — ensureAuthors의 하드 실패(위 예외가 여기까지 전파되는
			// 중이어도) 포함해 항상 시도한다(2026-08-18 수정). 각자 실패해도 위 정산에는 영향 없다.
			collectCommentsGatedSafely(brand.id(), posts);
			judgeAdDisclosuresSafely(posts);
		}
		log.info("브랜드 태그 보강 — {} 게시자 수집·정산 완료({}건 대상)", brand.username(), posts.size());
	}

	/**
	 * 댓글 게이트 격리 래퍼 — 노출 게이트 개정(스펙 §8) 전에는 이 실패가 markEnriched를 감싸는
	 * finally 덕에 우연히 격리됐지만, 이제 markEnriched가 먼저 찍히므로 명시적 격리가 필요하다.
	 */
	private void collectCommentsGatedSafely(long brandId, List<PostInfo> posts) {
		try {
			collectCommentsGated(brandId, posts);
		} catch (RuntimeException e) {
			log.warn("댓글 게이트 실패(격리, 다음 스윕이 워터마크로 재시도) — {}: {}", brandId, e.toString());
		}
	}

	/**
	 * 광고 표기 판정 격리 래퍼(스펙 §7) — 판정 실패가 수집·보강에 영향 없어야 한다. 실제 격리는
	 * {@link AdDisclosureJudgeService#judgePosts}가 게시물 단위로 이미 하지만, 후보 선정 배치
	 * 조회(findAdJudgmentState) 실패 같은 상위 레벨 예외까지 방어하려면 이 래퍼가 한 번 더 필요하다
	 * (collectCommentsGatedSafely와 같은 이유). "다음 스윕 재시도"는 180일 이하 게시물에 한한
	 * 이야기다 — 180일 초과 게시물은 재열거 자체가 없어(BrandCrawlPolicy.due 무조건 false) verdict
	 * NULL이 영구 잔존할 수 있다.
	 *
	 * <p>킬 스위치({@code monitoring.brand.ad-disclosure.enabled}, 기본 true, 2026-08-18) — LLM
	 * 쿼터 장애 등으로 판정이 스윕 자체를 지연시킬 때 was 노출 토글(expose)과 독립적으로 판정만
	 * 끄는 롤백 수단. 진입점 맨 앞에서 가드해 adJudge 호출 자체가 나가지 않게 한다(no-op 리턴 —
	 * verdict는 그대로 NULL/기존값 유지, 다음에 켜지면 캡션 해시 비교로 자연 재판정).
	 */
	private void judgeAdDisclosuresSafely(List<PostInfo> posts) {
		if (!adDisclosureEnabled) {
			log.debug("광고 표기 판정 킬 스위치 비활성 — 판정 스킵 ({}건)", posts.size());
			return;
		}
		try {
			adJudge.judgePosts(posts);
		} catch (RuntimeException e) {
			log.warn("광고 표기 판정 배치 실패(격리, 다음 스윕 재시도): {}", e.toString());
		}
	}

	/**
	 * 브랜드 자신의 프로필 매일 갱신(사용자 결정 08-06 — 등록 1회가 아님) + 추이 일 1행.
	 * <b>반드시 best-effort</b>: 프로필 콜 실패(일시 오류·비공개 전환 포함)가 태그 열거 수집을
	 * 막으면 안 된다 — 브랜드는 탈퇴까지 추적이 정본(스펙 §8)이라 상태 전이도 하지 않는다.
	 */
	private void refreshBrandProfileSafely(BrandRow brand) {
		try {
			ProfileInfo profile = hiker.fetchProfile(brand.username());
			writer.saveBrandProfile(brand.id(), brand.username(), LocalDate.now(KST), profile);
		} catch (RuntimeException e) {
			log.warn("브랜드 프로필 갱신 실패(격리, best-effort) — {}: {}", brand.username(), e.toString());
		}
	}

	/** 상한 도달 로그용 커버 깊이 — 적재분 중 최고령 taken_at(전부 미상이면 null). */
	private static Instant oldestTakenAt(Collection<PostInfo> posts) {
		return posts.stream().map(PostInfo::takenAt).filter(Objects::nonNull)
				.map(Instant::ofEpochSecond).min(Instant::compareTo).orElse(null);
	}

	/**
	 * 페이지 1개분 처리(구 processCore의 페이지 단위판) — 편입 컷(브랜드별 수집 창) 필터 → 복권
	 * 지표 보정 → 스냅샷 적재 → 신규 링크(known 갱신) → last_crawled_at 갱신. 전부 upsert/멱등이라
	 * 재실행 안전.
	 */
	private List<PostInfo> processPage(BrandRow brand, List<PostInfo> posts, Set<String> known,
			LocalDate today, Instant now) {
		Instant enrollCutoff = collectionCutoff(brand, now);
		// taken_at 미상은 보수적으로 제외(잘못된 편입 방지) — 다음 열거에서 채워지면 잡힌다.
		List<PostInfo> inWindow = posts.stream()
				.filter(p -> p.takenAt() != null
						&& !Instant.ofEpochSecond(p.takenAt()).isBefore(enrollCutoff))
				.toList();
		if (inWindow.isEmpty()) {
			return List.of();
		}
		List<PostInfo> adjusted = adjustLotteryMetrics(inWindow);
		for (PostInfo p : adjusted) {
			writer.savePost(today, p);
		}
		for (PostInfo p : adjusted) {
			if (known.add(p.shortCode())) {
				taggedPosts.insert(brand.id(), p);
			}
		}
		// 만난 게시물 전부(신규 포함) — 다음 스윕의 티어 판정(due) 입력. 180일 초과분 갱신도
		// 무해하다(판정식이 영구 제외라 이들을 위한 콜은 발생하지 않는다 — 스펙 §4).
		taggedPosts.touchCrawled(brand.id(),
				adjusted.stream().map(PostInfo::shortCode).toList(), now);
		return adjusted;
	}

	// ── 복권 3종(저장·공유·리포스트) 적재 규칙 — 재시도 콜 없음(비용 모델에 예산 없음) ──
	// ① 부재=0(DECISIONS 08-05): saves 관측 ∧ reposts/shares(숨김 제외) 부재면 0 기록.
	//    save·repost 키는 세션에 동반 실리므로(566/596) 당첨 세션 근거가 있을 때만 "부재=생략"
	//    해석이 성립한다. 캠페인 모듈은 재시도 소진 시점에 적용하지만 태그 경로는 재시도가 없어
	//    저장 전에 즉시 적용한다.
	// ② 0 캐리(DECISIONS 08-05): ① 후에도 null인 지표는 이력 판정(양수 관측 전무 ∧ 전일 0 종료)
	//    으로 0을 잇는다 — 구조적 키 부재 게시물이 매일 null 구멍을 내지 않게. 실제 값이 생기면
	//    키가 오기 시작해 자동 해제된다.
	// ③ 전부 꽝 세션(saves도 부재)은 근거가 없으므로 null(미관측) 유지.
	//    fb 캐리포워드·역전파는 BrandSnapshotRepository.upsertPost가 처리한다 — 여기서 안 건드린다.

	/**
	 * package-private로 승격(2026-08-18 direct 통합 §2-2) — {@link BrandDirectCollectService}가
	 * direct 단건 수집에 재사용한다. 복권 3종(저장·공유·리포스트) 부재=0·0 캐리 규칙은 여기 한 벌만
	 * 존재해야 한다 — 두 벌이 되면 반드시 갈라진다(direct 경로가 별도 사본을 두지 않는 이유).
	 */
	List<PostInfo> adjustLotteryMetrics(List<PostInfo> posts) {
		List<PostInfo> step1 = posts.stream().map(p -> {
			if (!"REELS".equals(p.contentType()) || p.saves() == null) {
				return p;
			}
			Long zeroShares = p.shares() == null && !p.sharesHidden() ? 0L : null;
			Long zeroReposts = p.reposts() == null ? 0L : null;
			return zeroShares == null && zeroReposts == null ? p
					: p.mergedMetrics(null, zeroShares, zeroReposts);
		}).toList();
		Set<String> repostsCandidates = new LinkedHashSet<>();
		Set<String> sharesCandidates = new LinkedHashSet<>();
		for (PostInfo p : step1) {
			if (!"REELS".equals(p.contentType())) {
				continue;
			}
			if (p.reposts() == null) {
				repostsCandidates.add(p.shortCode());
			}
			if (p.shares() == null && !p.sharesHidden()) {
				sharesCandidates.add(p.shortCode());
			}
		}
		if (repostsCandidates.isEmpty() && sharesCandidates.isEmpty()) {
			return step1;   // 후보가 없으면 이력 쿼리 자체를 내지 않는다.
		}
		LocalDate today = LocalDate.now(KST);
		Set<String> repostsCarry = snapshots.codesWithRepostsZeroCarry(repostsCandidates, today);
		Set<String> sharesCarry = snapshots.codesWithSharesZeroCarry(sharesCandidates, today);
		return step1.stream().map(p -> {
			Long zeroReposts = p.reposts() == null && repostsCarry.contains(p.shortCode()) ? 0L : null;
			Long zeroShares = p.shares() == null && !p.sharesHidden()
					&& sharesCarry.contains(p.shortCode()) ? 0L : null;
			return zeroShares == null && zeroReposts == null ? p
					: p.mergedMetrics(null, zeroShares, zeroReposts);
		}).toList();
	}

	/**
	 * 게시자 프로필 — 편입 컷 안 게시물 작성자 중 미보유·30일 경과(stale)만 /v2/user/by/id 1콜
	 * (스펙 §2·§8: 신규 감지 시 1회 + 등장 시 stale 갱신, 월 일괄 배치 아님). 브랜드 간 전역
	 * 캐시(author_profile)라 같은 인플루언서를 여러 브랜드가 태그해도 콜은 30일에 1번이다.
	 * 게시자 단위 격리 — 한 명의 실패가 나머지 게시자·게시물 수집에 번지면 안 된다.
	 */
	private void ensureAuthors(long brandId, Collection<PostInfo> posts) {
		Set<String> ids = posts.stream().map(PostInfo::ownerUserId).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (ids.isEmpty()) {
			return;
		}
		Set<String> fresh = authors.freshIgUserIds(ids,
				Instant.now().minus(Duration.ofDays(authorStaleDays)));
		// 게시자별 독립 콜이라 공유 워커 풀(monitoring.brand.enrich-concurrency — 08-13부터 10)로
		// 병렬화한다(2026-08-07 스펙 — 콜당 ~1.5초 순차가 보강 시간의 본체였다).
		// 격리 규칙은 그대로: 한 명의 실패는 로그만, 나머지는 계속.
		// 태스크 본문은 runScoped로 다시 감싼다 — 콜 집계의 브랜드 컨텍스트(ThreadLocal)는 워커
		// 스레드로 넘어가지 않기 때문(BrandCallContext 주석 참조).
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (String id : ids) {
			if (fresh.contains(id)) {
				continue;
			}
			tasks.add(CompletableFuture.runAsync(() -> callContext.runScoped(brandId,
					() -> fetchAuthorWithRetry(id)), enrichWorker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
	}

	/**
	 * 게시자 프로필 1건 — 404는 1회 재시도한다(08-13 실측: 실존 계정에서 2.0% 발생, 재시도
	 * 복구율 2/2). 전송 계층은 404를 "결정적 부재"로 보고 즉시 전파하는데, /v2/user/by/id에
	 * 한해 그 전제가 틀렸다. 다른 엔드포인트(by/username·게시물 단건)의 404는 여전히 결정적이라
	 * 전송 계층을 건드리지 않고 여기서만 되쏜다.
	 *
	 * <p>타임아웃·5xx는 재시도하지 않는다 — 전송 계층이 이미 maxRetries를 태운 뒤이고, 실측상
	 * 느린 콜은 3회 연속 16~21초로 전부 실패해 워커만 45초 묶었다.
	 */
	private void fetchAuthorWithRetry(String igUserId) {
		try {
			authors.upsert(hiker.fetchAuthorProfile(igUserId));
			return;
		} catch (SubjectNotFoundException e) {
			log.info("게시자 404 — user_id {} 1회 재시도", igUserId);
		} catch (RuntimeException e) {
			log.warn("게시자 프로필 수집 실패(격리) — user_id {}: {}", igUserId, e.toString());
			return;
		}
		try {
			authors.upsert(hiker.fetchAuthorProfile(igUserId));
		} catch (RuntimeException e) {
			log.warn("게시자 프로필 재시도 실패(격리) — user_id {}: {}", igUserId, e.toString());
		}
	}

	/**
	 * 댓글 게이트(스펙 §2) — 열거 comment_count가 저장값(마지막 댓글 수집 시점의 값)보다 클 때만
	 * /v2/media/comments 최대 commentPages콜(3콜 45개), 기지 댓글 페이지에서 중단. 신규 게시물은
	 * 저장값 0이라 "댓글 1개 이상만 수집"이 자동 성립한다(댓글 숨김·0건 게시물은 콜 자체 없음).
	 * 게시물 단위 격리.
	 */
	private void collectCommentsGated(long brandId, Collection<PostInfo> posts) {
		List<PostInfo> candidates = posts.stream().filter(p -> p.comments() != null).toList();
		if (candidates.isEmpty()) {
			return;
		}
		Map<String, Long> stored = taggedPosts.commentsCollectedCounts(brandId,
				candidates.stream().map(PostInfo::shortCode).toList());
		// 게시물별 독립 콜이라 같은 공유 워커 풀로 병렬화한다(ensureAuthors와 같은 근거). 게이트
		// 판정은 제출 전에, 워터마크 갱신은 태스크 안에서 — 의미 불변, 실행만 동시.
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (PostInfo p : candidates) {
			if (p.comments() <= stored.getOrDefault(p.shortCode(), 0L)) {
				continue;
			}
			tasks.add(CompletableFuture.runAsync(() -> callContext.runScoped(brandId, () -> {
				try {
					HikerClient.CommentsFetch fetch = hiker.fetchComments(p.shortCode(), p.username(),
							commentPages, comments.findIds(p.shortCode()));
					comments.upsertForPost(p.shortCode(), fetch.comments());
					// 저장값은 열거 관측치로 갱신한다 — 다음 게이트가 "그 사이 증가분"만 보게.
					// 단, 미완주(중간 페이지 실패)면 유지한다 — 워터마크를 올리면 다음 스윕 게이트가
					// 닫혀 못 받은 페이지가 영영 빈다(받은 부분은 위 upsert로 이미 보존됐다).
					if (fetch.complete()) {
						taggedPosts.updateCommentsCollected(brandId, p.shortCode(), p.comments());
					}
				} catch (RuntimeException e) {
					log.warn("태그 댓글 수집 실패(격리) — 게시물 {}: {}", p.shortCode(), e.toString());
				}
			}), enrichWorker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
	}
}
