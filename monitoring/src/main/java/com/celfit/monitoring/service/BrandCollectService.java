package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.CommentInfo;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 브랜드 태그 수집 본체(2026-08-06 스펙 + 같은 날 개정) — 태그 열거 단일 경로
 * (/v2/user/tag/medias)로 <b>매일 전량 수집</b>한다: 브랜드마다 105개 깊이 열거(~5콜) 안에서
 * 신규 감지와 전 게시물 지표 갱신을 한 번에(감지/트래킹 구분 폐지 — 사용자 결정 08-06).
 * 등록 백필도 같은 코드다. 단건 게시물 콜은 전면 금지(스펙 §1 — 태그 열거는 릴스 조회수가
 * 인라인이고, 윈도우=열거 깊이 정합이라 창 밖 추적 자체가 없다).
 *
 * <p>저장은 전면 브랜드 전용 스키마(08-06 결정) — 캠페인 테이블(post_snapshot 계열)을 한 줄도
 * 건드리지 않는다(볼륨 격리 + 겹침 게시물 덮어쓰기 차단). 트랜잭션은 여기 없다(CollectService와
 * 같은 이유), 쓰기는 {@link BrandSnapshotWriter}가 짧게 묶는다.
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
	private final int windowDays;
	private final int windowPosts;
	private final int commentPages;
	private final int authorStaleDays;

	public BrandCollectService(HikerClient hiker, BrandSnapshotWriter writer,
			BrandSnapshotRepository snapshots, BrandCommentRepository comments,
			TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
			@Qualifier("brandEnrichWorkerPool") Executor enrichWorker,
			@Value("${monitoring.brand.window-days:90}") int windowDays,
			@Value("${monitoring.brand.window-posts:105}") int windowPosts,
			@Value("${monitoring.brand.comment-pages:3}") int commentPages,
			@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays) {
		this.hiker = hiker;
		this.writer = writer;
		this.snapshots = snapshots;
		this.comments = comments;
		this.taggedPosts = taggedPosts;
		this.authors = authors;
		this.enrichWorker = enrichWorker;
		this.windowDays = windowDays;
		this.windowPosts = windowPosts;
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

	/**
	 * core 단계 — ①브랜드 프로필 1콜(매일 갱신 + 추이 적재, best-effort) ②태그 열거를 목표
	 * 개수(windowPosts=105)까지 next_page_id 추종 ③윈도우 안 전 게시물 스냅샷·메타 적재 + 신규
	 * 링크. 여기까지가 브랜드 화면 목록 렌더에 필요한 전부다(게시자·댓글은 폴백·스냅샷 값으로
	 * 대체 가능 — was BrandPostAssembler) → ready(touchSwept)는 이 반환 직후 찍어도 된다.
	 *
	 * <p>열거 중단: ①누적 unique code ≥ windowPosts ②페이지 전체가 90일 컷 이전(소급 태그
	 * 혼입 때문에 "오래된 글 1건 발견 즉시 중단" 금지 — 스펙 §5) ③커서 소진 ④커서 미전진.
	 * 페이지당 건수(실측 21)는 IG 소관 값이라 가정하지 않는다(스펙 §6 하드코딩 금지).
	 *
	 * @return 윈도우 안 게시물(복권 지표 보정 후) — {@link #enrich}가 재열거 없이 그대로 소비한다.
	 */
	public List<PostInfo> sweepCore(BrandRow brand) {
		refreshBrandProfileSafely(brand);
		Instant cutoff = windowCutoff();
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		String cursor = null;
		while (true) {
			HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), cursor);
			if (page.posts().isEmpty()) {
				break;
			}
			int before = byCode.size();
			page.posts().forEach(p -> byCode.putIfAbsent(p.shortCode(), p));
			// taken_at 미상 아이템은 "컷 이전" 판정에 넣지 않는다(보수적으로 열거 계속).
			boolean wholePageBeforeCutoff = page.posts().stream()
					.allMatch(p -> p.takenAt() != null
							&& Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff));
			if (byCode.size() >= windowPosts || wholePageBeforeCutoff
					|| page.nextPageId() == null) {
				break;
			}
			if (byCode.size() == before) {
				log.warn("태그 커서 미전진 의심 — 브랜드 {} 신규 code 0건, 열거 중단", brand.username());
				break;
			}
			cursor = page.nextPageId();
		}
		return processCore(brand, List.copyOf(byCode.values()));
	}

	/**
	 * enrichment 단계 — core가 넘긴 윈도우 게시물의 게시자 프로필(미보유·30일 stale만) + 댓글
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

	/** core 열거 결과 처리 — 윈도우 필터 → 복권 지표 보정 → 스냅샷·메타 적재 → 신규 링크. */
	private List<PostInfo> processCore(BrandRow brand, List<PostInfo> posts) {
		Instant cutoff = windowCutoff();
		// taken_at 미상은 보수적으로 제외(잘못된 윈도우 편입 방지) — 다음 열거에서 채워지면 잡힌다.
		List<PostInfo> inWindow = posts.stream()
				.filter(p -> p.takenAt() != null
						&& !Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff))
				.toList();
		if (inWindow.isEmpty()) {
			return List.of();
		}
		Set<String> known = taggedPosts.knownCodes(brand.id());
		Set<String> freshCodes = inWindow.stream().map(PostInfo::shortCode)
				.filter(c -> !known.contains(c))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		List<PostInfo> adjusted = adjustLotteryMetrics(inWindow);
		LocalDate today = LocalDate.now(KST);
		for (PostInfo p : adjusted) {
			writer.savePost(today, p);
		}
		for (PostInfo p : adjusted) {
			if (freshCodes.contains(p.shortCode())) {
				taggedPosts.insert(brand.id(), p);
			}
		}
		log.info("브랜드 태그 수집 — {} 열거 {}건, 윈도우 내 {}건, 신규 {}건",
				brand.username(), posts.size(), inWindow.size(), freshCodes.size());
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
	 * 게시자 프로필 — 윈도우 게시물 작성자 중 미보유·30일 경과(stale)만 /v2/user/by/id 1콜
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
					List<CommentInfo> fetched = hiker.fetchComments(p.shortCode(), p.username(),
							commentPages, comments.findIds(p.shortCode()));
					comments.upsertForPost(p.shortCode(), fetched);
					// 저장값은 열거 관측치로 갱신한다 — 다음 게이트가 "그 사이 증가분"만 보게.
					taggedPosts.updateCommentsCollected(brandId, p.shortCode(), p.comments());
				} catch (RuntimeException e) {
					log.warn("태그 댓글 수집 실패(격리) — 게시물 {}: {}", p.shortCode(), e.toString());
				}
			}, enrichWorker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
	}

	private Instant windowCutoff() {
		return Instant.now().minus(Duration.ofDays(windowDays));
	}
}
