package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandCommentRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 365일 전체. 단건 게시물 콜은 전면 금지 유지(08-06 실측 — 열거 대비 추가 지표 없음).
 *
 * <p>저장은 전면 브랜드 전용 스키마(08-06 결정) — 캠페인 테이블(post_snapshot 계열)을 한 줄도
 * 건드리지 않는다(볼륨 격리 + 겹침 게시물 덮어쓰기 차단). 트랜잭션은 여기 없다(CollectService와
 * 같은 이유), 쓰기는 {@link BrandSnapshotWriter}가 짧게 묶는다.
 *
 * <p>2026-08-12 스트리밍 개정: 적재는 페이지 단위로 즉시 일어나고, 등록 백필은 서빙 창(기본
 * 30일) 커버 시점에 콜백으로 FE ready를 당긴다(스펙 docs/superpowers/specs/2026-08-12-…-design.md).
 */
@Service
public class BrandCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandCollectService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final BrandSnapshotWriter writer;
	private final BrandSnapshotRepository snapshots;
	private final BrandCommentRepository comments;
	private final TaggedPostRepository taggedPosts;
	private final AuthorProfileRepository authors;
	private final Executor enrichWorker;
	private final int registrationWindowDays;
	private final int servingWindowDays;
	private final int maxPostsPerSweep;
	private final int commentPages;
	private final int authorStaleDays;

	public BrandCollectService(HikerClient hiker, BrandSnapshotWriter writer,
			BrandSnapshotRepository snapshots, BrandCommentRepository comments,
			TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
			@Qualifier("brandEnrichWorkerPool") Executor enrichWorker,
			@Value("${monitoring.brand.registration-window-days:365}") int registrationWindowDays,
			@Value("${monitoring.brand.serving-window-days:30}") int servingWindowDays,
			@Value("${monitoring.brand.max-posts-per-sweep:10000}") int maxPostsPerSweep,
			@Value("${monitoring.brand.comment-pages:3}") int commentPages,
			@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays) {
		this.hiker = hiker;
		this.writer = writer;
		this.snapshots = snapshots;
		this.comments = comments;
		this.taggedPosts = taggedPosts;
		this.authors = authors;
		this.enrichWorker = enrichWorker;
		this.registrationWindowDays = registrationWindowDays;
		this.servingWindowDays = servingWindowDays;
		this.maxPostsPerSweep = maxPostsPerSweep;
		this.commentPages = commentPages;
		this.authorStaleDays = authorStaleDays;
	}

	/**
	 * 브랜드 1개분 전량 수집(매일 스윕 경로) — {@link #sweepCore}(열거+적재) 후
	 * {@link #enrich}(게시자·댓글)까지 한 호출로 잇는다. 등록 백필은 이 메서드를 쓰지 않고
	 * 두 단계를 각자 executor에서 따로 돈다(단계식 ready — 2026-08-07 결정): ready 판정
	 * (last_swept_on)은 core만 요구하고, 보강 콜 수십 개(전체 콜의 ~85%)를 기다리지 않는다.
	 */
	public void sweep(BrandRow brand) {
		enrich(brand, sweepCore(brand));
	}

	/** 단일 인자 경로(일일 스윕·기존 호출부) — 서빙 콜백 없이 동작은 동일하다. */
	public List<PostInfo> sweepCore(BrandRow brand) {
		return sweepCore(brand, posts -> {});
	}

	/**
	 * core 단계(2026-08-12 스트리밍 개정) — 열거하면서 페이지(~21건)마다 즉시 적재한다. 구 일괄
	 * processCore 대비 의미 불변이고 실행 시점만 당겨진다: 중간 실패 시 앞 페이지 적재분이
	 * 보존되고(다음 스윕이 잔여를 백스톱), 등록 백필은 서빙 창 커버 시점에 FE ready를 당길 수 있다.
	 *
	 * <p>onServingCovered는 <b>정확히 1회</b> 호출된다(예외로 중단되는 경우 제외) — 페이지 전체가
	 * 서빙 창(servingWindowDays)보다 오래된 순간(소급 태그 혼입 대비, 컷 판정과 같은 보수 규칙),
	 * 그전에 열거가 끝나면(자연 종료·상한·미전진 포함) 종료 시점. 인자는 그때까지 적재된 편입분
	 * 누적 리스트다.
	 *
	 * <p>열거 중단 4종·coveredCutoff·touchCrawledDepth 의미는 기존과 동일하다 — 중단 조건은
	 * ①페이지 전체가 깊이 컷(365일/14일) 이전 ②커서 소진(nextPageId null·빈 페이지)
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
	public List<PostInfo> sweepCore(BrandRow brand, Consumer<List<PostInfo>> onServingCovered) {
		refreshBrandProfileSafely(brand);
		Instant now = Instant.now();
		Instant cutoff = enumerationCutoff(brand, now);
		Instant servingCutoff = now.minus(Duration.ofDays(servingWindowDays));
		LocalDate today = LocalDate.now(KST);
		Set<String> known = taggedPosts.knownCodes(brand.id());
		Set<String> seen = new LinkedHashSet<>();      // 이번 실행 처리분 — 페이지 간 중복(커서 드리프트) 스킵
		List<PostInfo> collected = new ArrayList<>();  // 편입분 누적 — 콜백·반환(보강 입력)
		int freshTotal = 0;
		boolean servingMarked = false;
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
			collected.addAll(processPage(brand, newItems, known, today, now));
			freshTotal += known.size() - knownBefore;
			// 서빙 경계 — 페이지 전체가 서빙 창 이전이면 최근 30일은 다 훑었다(taken_at 미상은
			// 컷 판정과 같은 이유로 "이전" 판정에 넣지 않는다). 열거는 계속된다.
			if (!servingMarked && page.posts().stream().allMatch(p -> p.takenAt() != null
					&& Instant.ofEpochSecond(p.takenAt()).isBefore(servingCutoff))) {
				servingMarked = true;
				onServingCovered.accept(List.copyOf(collected));
			}
			// taken_at 미상 아이템은 "컷 이전" 판정에 넣지 않는다(보수적으로 열거 계속).
			boolean wholePageBeforeCutoff = page.posts().stream()
					.allMatch(p -> p.takenAt() != null
							&& Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff));
			if (wholePageBeforeCutoff || page.nextPageId() == null) {
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
		if (!servingMarked) {
			// 서빙 창까지 못 갔거나(게시물이 얕음) 상한·미전진 중단 — 있는 만큼이라도 서빙을 연다.
			onServingCovered.accept(List.copyOf(collected));
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
	 * 오늘의 열거 깊이 컷(정책 v1 스펙 §4) — 백필(last_swept_on null: 등록 직후·백필 실패
	 * 백스톱·재가입)은 등록 윈도우(365일) 전체, 이후엔 min(14일 컷, 가장 오래된 due 게시물의
	 * taken_at). due 판정은 {@link BrandCrawlPolicy} 순수 함수 — 깊은 열거가 얕은 티어를 자동
	 * 포함하므로 정책의 중복 제거 규칙이 구조적으로 성립하고, 스윕이 하루 빠져도 다음 날 due
	 * 계산이 밀린 깊이까지 자동 커버한다(자가 치유).
	 */
	private Instant enumerationCutoff(BrandRow brand, Instant now) {
		if (brand.lastSweptOn() == null) {
			return now.minus(Duration.ofDays(registrationWindowDays));
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
	 * enrichment 단계 — core가 넘긴 편입 컷 안 게시물의 게시자 프로필(미보유·30일 stale만) + 댓글
	 * 게이트. 실패해도 core가 적재한 목록·지표는 이미 서빙 가능하고, 미수집분은 다음 스윕이
	 * 백스톱한다(게시자는 stale 판정, 댓글은 comments_collected_count 워터마크가 남아 있어
	 * 자동 재시도된다).
	 */
	public void enrich(BrandRow brand, List<PostInfo> posts) {
		if (posts.isEmpty()) {
			return;
		}
		ensureAuthors(posts);
		collectCommentsGated(brand.id(), posts);
		log.info("브랜드 태그 보강 — {} 게시자·댓글 수집 완료({}건 대상)", brand.username(), posts.size());
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
	 * 페이지 1개분 처리(구 processCore의 페이지 단위판) — 편입 컷(365일) 필터 → 복권 지표 보정 →
	 * 스냅샷 적재 → 신규 링크(known 갱신) → last_crawled_at 갱신. 전부 upsert/멱등이라 재실행 안전.
	 */
	private List<PostInfo> processPage(BrandRow brand, List<PostInfo> posts, Set<String> known,
			LocalDate today, Instant now) {
		Instant enrollCutoff = now.minus(Duration.ofDays(registrationWindowDays));
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

	private List<PostInfo> adjustLotteryMetrics(List<PostInfo> posts) {
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
	private void ensureAuthors(Collection<PostInfo> posts) {
		Set<String> ids = posts.stream().map(PostInfo::ownerUserId).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (ids.isEmpty()) {
			return;
		}
		Set<String> fresh = authors.freshIgUserIds(ids,
				Instant.now().minus(Duration.ofDays(authorStaleDays)));
		// 게시자별 독립 콜이라 워커 풀(동시 6)로 병렬화한다(2026-08-07 스펙 — 콜당 ~1.5초 순차가
		// 보강 시간의 본체였다). 격리 규칙은 그대로: 한 명의 실패는 로그만, 나머지는 계속.
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (String id : ids) {
			if (fresh.contains(id)) {
				continue;
			}
			tasks.add(CompletableFuture.runAsync(() -> {
				try {
					authors.upsert(hiker.fetchAuthorProfile(id));
				} catch (RuntimeException e) {
					log.warn("게시자 프로필 수집 실패(격리) — user_id {}: {}", id, e.toString());
				}
			}, enrichWorker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
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
		// 게시물별 독립 콜이라 워커 풀(동시 6)로 병렬화한다(ensureAuthors와 같은 근거). 게이트
		// 판정은 제출 전에, 워터마크 갱신은 태스크 안에서 — 의미 불변, 실행만 동시.
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (PostInfo p : candidates) {
			if (p.comments() <= stored.getOrDefault(p.shortCode(), 0L)) {
				continue;
			}
			tasks.add(CompletableFuture.runAsync(() -> {
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
			}, enrichWorker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
	}
}
