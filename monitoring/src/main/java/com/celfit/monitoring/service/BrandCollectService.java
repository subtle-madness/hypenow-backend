package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.SnapshotRepository;
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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 브랜드 태그 수집 본체(2026-08-06 스펙) — 태그 열거 단일 경로(/v2/user/tag/medias)로
 * 감지(매일)·트래킹(3일 1회, 등록 백필 겸용)을 돈다. 단건 게시물 콜은 전면 금지다(스펙 §1 —
 * 태그 열거는 릴스 조회수가 인라인이고, 윈도우=열거 깊이 정합이라 창 밖 추적 자체가 없다).
 *
 * <p><b>감지는 1페이지 1콜 고정</b>(스펙 §3 — 비용 모델 감지 월 30콜과 정합). 스펙 §6의
 * "페이지 전체가 기지일 때 중단"은 페이지 <b>안</b>에서 기지 code를 만나도 조기 중단하지 말라는
 * 뜻으로 새긴다(정렬이 태그된 시점 순이라 소급 태그가 기지 뒤에 섞인다) — 1페이지 너머의 심층
 * 소급 태그는 3일 트래킹(105개 깊이)이 잡는다.
 *
 * <p>스냅샷·게시물 메타·게시자 표시 메타는 기존 {@link SnapshotWriter#savePost} 깔때기를
 * 재사용한다 — fb 캐리포워드·역전파(08-03)는 SnapshotRepository.upsertPost가 그대로 적용되고,
 * METRICS_HIDDEN 알람은 추적 캠페인이 없는 code에서 자연 no-op이다. 트랜잭션은 여기 없다
 * (CollectService와 같은 이유 — Hiker 콜이 트랜잭션에 들어가면 커넥션을 계정 수만큼 점유한다).
 */
@Service
public class BrandCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandCollectService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final SnapshotWriter writer;
	private final SnapshotRepository snapshots;
	private final CommentRepository comments;
	private final TaggedPostRepository taggedPosts;
	private final AuthorProfileRepository authors;
	private final int windowDays;
	private final int windowPosts;
	private final int commentPages;
	private final int authorStaleDays;

	public BrandCollectService(HikerClient hiker, SnapshotWriter writer, SnapshotRepository snapshots,
			CommentRepository comments, TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
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
		this.windowDays = windowDays;
		this.windowPosts = windowPosts;
		this.commentPages = commentPages;
		this.authorStaleDays = authorStaleDays;
	}

	/**
	 * 감지(매일) — 1페이지 1콜 고정. 신규 in-window 게시물을 링크·적재하고, 기지 게시물도
	 * 페이지가 공짜로 실어 온 지표는 스냅샷에 upsert한다(추가 콜 0 — 관측을 버리지 않는다).
	 * 콜이 드는 작업(게시자 프로필·댓글)은 신규 감지분에만 낸다(주기 규칙은 스펙 §3 그대로).
	 */
	public void detect(BrandRow brand) {
		HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), null);
		process(brand, dedupeByCode(page.posts()), false);
	}

	/**
	 * 트래킹(3일 1회)·등록 백필 공용 — 목표 개수(windowPosts=105)까지 next_page_id 추종.
	 * 페이지당 건수(실측 21)는 IG 소관 값이라 가정하지 않는다(스펙 §6 하드코딩 금지). 이날 감지는
	 * 이 열거가 겸한다(추가 콜 없음). 중단: ①누적 unique code ≥ windowPosts ②페이지 전체가
	 * 90일 컷 이전(소급 태그 혼입 때문에 "오래된 글 1건 발견 즉시 중단" 금지 — 스펙 §5)
	 * ③커서 소진 ④커서 미전진(신규 code 0건 — fetchRecentPosts 가드 관용구).
	 */
	public void track(BrandRow brand) {
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
		process(brand, List.copyOf(byCode.values()), true);
	}

	/**
	 * 페이지 처리 공통 — 윈도우 필터 → 신규/기지 분리 → 복권 지표 보정 → 스냅샷 적재 →
	 * 링크 insert → 게시자 프로필 → 댓글 게이트. tracking=false(감지)면 콜이 드는 게시자·댓글은
	 * 신규 감지분에만 낸다.
	 */
	private void process(BrandRow brand, List<PostInfo> posts, boolean tracking) {
		Instant cutoff = windowCutoff();
		// taken_at 미상은 보수적으로 제외(잘못된 윈도우 편입 방지) — 다음 열거에서 채워지면 잡힌다.
		List<PostInfo> inWindow = posts.stream()
				.filter(p -> p.takenAt() != null
						&& !Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff))
				.toList();
		if (inWindow.isEmpty()) {
			return;
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
		List<PostInfo> freshPosts = adjusted.stream()
				.filter(p -> freshCodes.contains(p.shortCode())).toList();
		for (PostInfo p : freshPosts) {
			taggedPosts.insert(brand.id(), p);
		}
		List<PostInfo> callScope = tracking ? adjusted : freshPosts;
		ensureAuthors(callScope);
		collectCommentsGated(brand.id(), callScope);
		log.info("브랜드 태그 수집 — {} {} 열거 {}건, 윈도우 내 {}건, 신규 {}건",
				brand.username(), tracking ? "트래킹" : "감지", posts.size(), inWindow.size(), freshPosts.size());
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
	//    fb 캐리포워드·역전파는 SnapshotRepository.upsertPost가 이미 처리한다 — 여기서 안 건드린다.

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
	 * 게시자 프로필 — 처리 대상 게시물의 작성자 중 미보유·30일 경과(stale)만 /v2/user/by/id 1콜
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
		for (String id : ids) {
			if (fresh.contains(id)) {
				continue;
			}
			try {
				authors.upsert(hiker.fetchAuthorProfile(id));
			} catch (RuntimeException e) {
				log.warn("게시자 프로필 수집 실패(격리) — user_id {}: {}", id, e.toString());
			}
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
		for (PostInfo p : candidates) {
			if (p.comments() <= stored.getOrDefault(p.shortCode(), 0L)) {
				continue;
			}
			try {
				List<CommentInfo> fetched = hiker.fetchComments(p.shortCode(), p.username(),
						commentPages, comments.findIds(p.shortCode()));
				comments.upsertForPost(p.shortCode(), fetched);
				// 저장값은 열거 관측치로 갱신한다 — 다음 게이트가 "그 사이 증가분"만 보게.
				taggedPosts.updateCommentsCollected(brandId, p.shortCode(), p.comments());
			} catch (RuntimeException e) {
				log.warn("태그 댓글 수집 실패(격리) — 게시물 {}: {}", p.shortCode(), e.toString());
			}
		}
	}

	private Instant windowCutoff() {
		return Instant.now().minus(Duration.ofDays(windowDays));
	}

	/** 페이지 경계 중복 방지 — 첫 관측 유지(fetchRecentPosts의 putIfAbsent 관용구). */
	private static List<PostInfo> dedupeByCode(List<PostInfo> posts) {
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		posts.forEach(p -> byCode.putIfAbsent(p.shortCode(), p));
		return byCode.size() == posts.size() ? posts : new ArrayList<>(byCode.values());
	}
}
