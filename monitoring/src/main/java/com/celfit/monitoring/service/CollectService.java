package com.celfit.monitoring.service;

import com.celfit.instagram.source.ClipCounts;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 수집 1회의 정본 경로 — 등록(동기 첫 수집)과 02:00 스윕이 같은 코드를 쓴다.
 * 원형 적재는 여기 없다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 *
 * <p>여기에는 트랜잭션이 없다. Hiker 호출(계정 1회당 최대 3콜)이 트랜잭션 안에 들어가면
 * 그 레이턴시 내내 DB 커넥션을 점유하고, 스윕이 계정 수만큼 이 경로를 도는 동안 풀이 마른다.
 * 쓰기만 {@link SnapshotWriter}(계정·게시물)와 {@link CommentRepository}(댓글)가 짧은 트랜잭션으로 묶는다.
 *
 * <p><b>동기·비동기 소스 분리</b> — {@code *ForRegistration} 3종({@link #collectAccountForRegistration}·
 * {@link #collectPostForRegistration}·{@link #collectCommentsForRegistration})은 was→monitoring
 * 동기 등록 요청 스레드에서 돈다({@link RegistrationService} 참조) — {@link #syncHiker}(Hiker 1순위 +
 * 장애 시에만 self 구조)를 쓴다. 스윕용({@link #collectAccount}·{@link #collectPost}·
 * {@link #collectComments}·{@link #collectTrackedPost})은 {@link DailySweepJob}(02:00 크론) 전용이라
 * {@link #hiker}(자체 1순위 + Hiker 폴백, 배치용 절감 경로)를 그대로 쓴다.
 *
 * <p><b>{@link #retryReelsMetrics}만 예외</b> — DailySweepJob(스케줄)과 등록 직후 백그라운드 백필
 * (metricsBackfillExecutor, {@link RegistrationService#scheduleMetricsBackfill}, 사용자 트리거)이
 * 같은 메서드를 공유한다. 그래서 2026-09 사용자 트리거 도입 시점 토글을 위해 이 메서드만
 * {@link #userTriggeredHiker}로 라우팅하는 {@link #retryReelsMetricsUserTriggered} 대응판을 따로
 * 둔다(필드+진입점 분리 패턴, BrandCollectService.enrichSync와 동형) — DailySweepJob은 그대로
 * {@link #retryReelsMetrics}(hiker, 자체 1순위)를 쓴다.
 *
 * <p><b>{@link #metricsRetryHiker}는 위 소스 분리와 다른 축</b>(2026-09-03 self 셰이프 회귀 수정) —
 * 저장·공유·리포스트 단건 복권 재시도({@code retrySinglesOnce})의 fetchPost 콜만 이 Hiker 직결
 * 소스로 고정한다. self(EmbedPostFetcher)는 3지표를 구조적으로 항상 null 반환하는데, 그 응답도
 * 예외 없는 "성공"이라 FailoverInstagramSource가 폴백하지 않는다(route()는 무예외 반환을 전부
 * 성공 처리) — self가 섞인 소스(hiker·userTriggeredHiker, self-paths 토글로 self가 켜지면)로
 * 재시도하면 매 시도가 결정론적으로 꽝이라 최대 6회 재시도가 전부 무력화된다(09-03 운영 로그
 * 15전 0승 실측, 소실률 3.0%→4.5% 회귀 원인). clips 경로({@code retryClipsOnce})는 self 절감
 * 목적을 보존해 그대로 호출부 소스(hiker/userTriggeredHiker)를 쓴다 — self 미지원 표면이라
 * UnsupportedOperationException 하드게이트로 이미 Hiker에 떨어진다(SelfCrawlBackend 참조).
 */
@Service
public class CollectService {

	public record AccountCollectResult(ProfileInfo profile, List<PostInfo> posts) {}

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Logger log = LoggerFactory.getLogger(CollectService.class);

	/** 배치(스윕) 전용 — 자체 1순위 + Hiker 폴백. */
	private final InstagramSource hiker;
	/** 등록(동기) 전용 — Hiker 1순위 + 장애 시에만 self 구조. 클래스 javadoc 참조. */
	private final InstagramSource syncHiker;
	/** 등록 직후 메트릭 백필(사용자 트리거 비동기, {@link #retryReelsMetricsUserTriggered}) 전용 — 2026-09
	 * 도입 시점 토글(userTriggeredInstagramSource, HikerConfig 참조). 클래스 javadoc 참조. */
	private final InstagramSource userTriggeredHiker;
	/** 저장·공유·리포스트 단건 복권 재시도({@code retrySinglesOnce}) 전용 — Hiker 직결(self 미경유).
	 * 클래스 javadoc 참조(2026-09-03). */
	private final InstagramSource metricsRetryHiker;
	private final SnapshotWriter writer;
	private final CommentRepository comments;
	private final SnapshotRepository snapshots;
	private final int enumeratePages;
	private final int commentPages;
	private final int registrationCommentPages;
	private final int metricsRetryMax;
	private final Duration metricsRetryDelay;

	/**
	 * 스윕용 commentPages(운영 3페이지)와 등록용 registrationCommentPages(항상 1페이지)를 분리해서
	 * 받는다. 등록은 동기 경로라 was 10초 read timeout 예산을 쓰므로 페이지 수를 스윕과 다르게
	 * 묶어야 한다(설계 §배경). 둘을 같은 값으로 채우는 편의 생성자는 두지 않는다 — 그 경로로
	 * 배선하면 등록이 조용히 3페이지를 부르게 된다.
	 *
	 * <p>metricsRetryMax·metricsRetryDelay는 저장·리포스트 세션 복권 재시도({@link #retryReelsMetrics})의
	 * 상한(운영 6회 — 당첨률 ~45%로 6회면 당일 확보율 ~98.5%)과 재콜 간격이다. 간격이 필요한 이유:
	 * Hiker가 수 초 단위로 같은 응답을 돌려주는 캐시가 관측돼(08-04, 연속 3콜 동일) 즉시 재콜하면
	 * 같은 꽝 세션을 되받아 재시도가 헛돈다.
	 */
	public CollectService(InstagramSource hiker, @Qualifier("syncInstagramSource") InstagramSource syncHiker,
			@Qualifier("userTriggeredInstagramSource") InstagramSource userTriggeredHiker,
			@Qualifier("metricsRetryInstagramSource") InstagramSource metricsRetryHiker,
			SnapshotWriter writer, CommentRepository comments,
			SnapshotRepository snapshots,
			@Value("${monitoring.enumerate-pages:1}") int enumeratePages,
			@Value("${monitoring.comment-pages:1}") int commentPages,
			@Value("${monitoring.registration-comment-pages:1}") int registrationCommentPages,
			@Value("${monitoring.metrics-retry-max:6}") int metricsRetryMax,
			@Value("${monitoring.metrics-retry-delay:10s}") Duration metricsRetryDelay) {
		this.hiker = hiker;
		this.syncHiker = syncHiker;
		this.userTriggeredHiker = userTriggeredHiker;
		this.metricsRetryHiker = metricsRetryHiker;
		this.writer = writer;
		this.comments = comments;
		this.snapshots = snapshots;
		this.enumeratePages = enumeratePages;
		this.metricsRetryMax = metricsRetryMax;
		this.metricsRetryDelay = metricsRetryDelay;
		this.commentPages = commentPages;
		this.registrationCommentPages = registrationCommentPages;
	}

	/** 계정 1회 수집(스윕용) — 프로필·게시물 스냅샷 + profile_meta upsert. FB 몫 재시도 없음. */
	public AccountCollectResult collectAccount(String username) {
		return collectAccount(username, false, hiker);
	}

	/** 계정 1회 수집(등록 전용) — 스윕용과 달리 fb 미관측 신규 릴스에 한해 clips 1회 재조회. */
	public AccountCollectResult collectAccountForRegistration(String username) {
		return collectAccount(username, true, syncHiker);
	}

	private AccountCollectResult collectAccount(String username, boolean retryFb, InstagramSource source) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = source.fetchProfile(username);
		List<PostInfo> posts = source.fetchRecentPosts(username, profile.userId(), enumeratePages);
		if (retryFb) {
			posts = retryFbForNewReels(source, profile.userId(), posts);
		}
		writer.saveAccount(username, today, profile, posts);
		return new AccountCollectResult(profile, posts);
	}

	// ── FB 몫 재시도는 등록(진짜 최초 1회)에만(findings §2 결론 4, 08-03 축소) ──
	// Hiker 세션의 20~30%만 fb_play_count(FB 교차게시 몫)를 실어 준다. 처음엔 "관측 이력이 생길
	// 때까지 스윕마다 재시도"였지만, 교차게시 안 한 릴스는 fb 키가 영영 안 잡힐 수 있어(실측:
	// _milking 8콜 연속 미관측) 헛 재시도가 매일 반복됐다(test 스윕 실측 단건 +65%). 역전파가
	// 있어 관측이 늦어도 과거가 소급 정정되므로, 재시도는 등록 시 1회로만 남긴다 — 게시물당
	// 평생 최대 1콜. 재시도는 최선 노력이다: 실패·재차 미실림이어도 수집은 그대로 간다.

	/** 열거 경로 — fb 미관측 신규 릴스가 남아 있으면 clips 콜만 1회 다시 태워 FB 몫을 머지한다. */
	private List<PostInfo> retryFbForNewReels(InstagramSource source, String userId, List<PostInfo> posts) {
		List<String> candidates = posts.stream()
				.filter(p -> "REELS".equals(p.contentType()) && p.views() != null && p.fbPlays() == null)
				.map(PostInfo::shortCode)
				.toList();
		if (candidates.isEmpty() || snapshots.codesWithFbObserved(candidates).containsAll(candidates)) {
			return posts;
		}
		log.info("FB 몫 미관측 신규 릴스 감지 — clips 1회 재조회: user_id {}", userId);
		Map<String, ClipCounts> retried = source.fetchClipCounts(userId, enumeratePages);
		return posts.stream()
				.map(p -> {
					ClipCounts c = retried.get(p.shortCode());
					return p.fbPlays() == null && c != null && c.fbPlays() != null
							? p.withFbPlays(c.fbPlays()) : p;
				})
				.toList();
	}

	/** 단건 경로(등록 전용, syncHiker 고정) — 같은 규칙. 재시도 응답에 fb가 실렸으면 그 응답 전체
	 * (더 신선)를 쓴다. */
	private PostInfo retryFbForNewReel(PostInfo post) {
		boolean needsFb = "REELS".equals(post.contentType()) && post.views() != null && post.fbPlays() == null;
		if (!needsFb || !snapshots.codesWithFbObserved(Set.of(post.shortCode())).isEmpty()) {
			return post;
		}
		try {
			PostInfo retried = syncHiker.fetchPost(post.shortCode());
			return retried.fbPlays() != null ? retried : post;
		} catch (RuntimeException e) {
			log.warn("FB 몫 재조회 실패 — 원 결과로 진행: {} {}", post.shortCode(), e.getMessage());
			return post;
		}
	}

	/**
	 * 프로필 전용 1콜 수집(팔로워 1회 수집, 트랙 II 후속) — {@link #collectAccount}(프로필+열거)와
	 * 달리 열거(+클립 보강)를 하지 않는다. POST 등록분만 있는 계정에 profile_snapshot 행이 아직
	 * 없을 때 DailySweepJob이 계정당 평생 1회만 호출한다.
	 */
	public ProfileInfo collectProfileOnly(String username) {
		ProfileInfo profile = hiker.fetchProfile(username);
		writer.saveProfileOnly(username, LocalDate.now(KST), profile);
		return profile;
	}

	/** 게시물 1회 수집(스윕용) — 스냅샷 upsert. FB 몫 재시도 없음. */
	public PostInfo collectPost(String shortCode) {
		PostInfo post = hiker.fetchPost(shortCode);
		writer.savePost(LocalDate.now(KST), post);
		return post;
	}

	/**
	 * 추적 게시물 수집(열거 포함분, 1번 결정 08-04) — 방금 열거로 스냅샷이 남았어도 단건 1콜을
	 * 정본으로 다시 수집한다. 열거 응답은 세션에 따라 공유·저장·리포스트 키를 실었다 뺐다 하지만
	 * (운영 채움율 11~58% 요동) 단건 응답은 좋아요·댓글·조회·공유 4지표를 확정적으로 준다(08-04
	 * 실측 25/25). 단건에 빠진 지표는 열거 관측을 폴백으로 머지해 두 응답이 상호보완된다 —
	 * 같은 날 upsert 덮어쓰기라 머지 없이는 방금 열거가 관측한 값을 null로 유실한다.
	 * 비용은 추적 게시물당 +1콜/일. FB 몫 재시도 없음(스윕 경로 규칙 그대로).
	 */
	public PostInfo collectTrackedPost(String shortCode, PostInfo enumerated) {
		PostInfo single = hiker.fetchPost(shortCode);
		PostInfo merged = enumerated == null ? single : single.mergedWith(enumerated);
		writer.savePost(LocalDate.now(KST), merged);
		return merged;
	}

	/** 게시물 1회 수집(등록 전용) — fb 미관측이면 단건 1회 재조회 후 저장. */
	public PostInfo collectPostForRegistration(String shortCode) {
		PostInfo post = retryFbForNewReel(syncHiker.fetchPost(shortCode));
		writer.savePost(LocalDate.now(KST), post);
		return post;
	}

	// ── 저장·리포스트 세션 복권 재시도(08-04 결정, 상한 metricsRetryMax) ──────────
	// 저장·리포스트 키는 Hiker 세션의 ~45%(clips 콜 기준)에만 실리고, 한 콜 안에서는 전부
	// 실리거나 전부 빠진다(운영 원형 151콜 전수: 혼재 0건). fb 몫과 달리 "영영 안 오는" 게시물이
	// 없어(당첨 세션은 전 릴스에 싣는다) 스윕 재시도가 헛돌지 않고, 당첨 1회로 계정의 릴스 전체가
	// 채워진다. 피드는 저장·공유 키가 전 세션 부재(0/181)라 대상에서 제외한다(사용자 결정 08-04).
	//
	// 열거 창(최근 12건×페이지) 밖 게시물은 clips 재콜이 영영 못 잡는다 — 단건 콜로 전환해
	// 같은 복권을 돌린다(08-05 결정). 08-04엔 "단건은 공유수 확정 제공(25/25)"이라 보고 즉시
	// 포기했지만, 운영 원형 재실측(49콜)에서 단건도 3키 전부 세션 복권임이 확인됐다(reshare 59%
	// ·save 47%·repost 29%) — 창 밖 게시물엔 단건 콜이 유일 공급원이라 재시도 없이는 3종이 빈다.

	/**
	 * 저장·공유·리포스트 재시도 필요 판정(스윕·등록·재시도 진입이 공유하는 단일 기준) — 릴스이면서
	 * 3지표 중 미관측이 남아 있으면 참. 단, 공유는 게시자 숨김({@link PostInfo#sharesHidden}이면
	 * 영영 안 오므로 판정에서 제외한다 — 넣으면 숨김 게시물이 매일 상한까지 헛 재시도를 돈다.
	 * 진입·종료 조건은 3지표 공통이다(08-05 옵션 ③): 08-04엔 "공유는 단건 정본이 확정 제공"
	 * 전제로 저장·리포스트만 봤지만, 전제가 반증돼 부분 세션이 공유만 빠뜨린 날의 단독 누락도
	 * 재시도가 메꿔야 한다(당첨 clips 열거는 공유를 사실상 상시 실어 추가 비용이 거의 없다).
	 */
	public static boolean needsMetricsRetry(PostInfo p) {
		return "REELS".equals(p.contentType())
				&& (p.saves() == null || p.reposts() == null
						|| (p.shares() == null && !p.sharesHidden()));
	}

	/**
	 * 미관측 추적 릴스의 저장·리포스트 보강 — clips 열거를 당첨(키 실림)까지 최대 metricsRetryMax회
	 * 재콜하고, 관측을 non-null 머지해 재저장한다. 열거 창 밖으로 판정된 게시물(과 clips를 태울
	 * userId가 아예 없는 계정 — null 허용)은 단건 콜 재시도로 전환한다. 실패·미당첨은 그대로
	 * 종료(best-effort) — 스냅샷은 이미 단건 정본으로 저장돼 있고, 여기서 예외가 새면 스윕 재시도
	 * 라운드가 이 계정을 통째로 다시 돌게 된다.
	 *
	 * <p>시도 상한은 두 경로 합산이다(계정당 콜 예산을 하나로 묶는다). 단건 전환분의 첫 재콜은
	 * 다음 시도(=간격 경과 후)부터 돈다 — 방금 스윕이 같은 게시물 단건 정본 콜을 마친 직후라,
	 * 즉시 재콜하면 Hiker 응답 캐시로 같은 꽝 세션을 되받는다.
	 */
	public void retryReelsMetrics(String userId, List<PostInfo> trackedPosts) {
		retryReelsMetrics(hiker, userId, trackedPosts);
	}

	/**
	 * 등록 직후 백필({@link RegistrationService#scheduleMetricsBackfill}) 전용 진입점 —
	 * {@link #userTriggeredHiker}로 라우팅한다(필드+진입점 분리 패턴). 규칙·상한은
	 * {@link #retryReelsMetrics(String, List)}와 완전히 동일 — 소스만 갈린다.
	 */
	public void retryReelsMetricsUserTriggered(String userId, List<PostInfo> trackedPosts) {
		retryReelsMetrics(userTriggeredHiker, userId, trackedPosts);
	}

	private void retryReelsMetrics(InstagramSource source, String userId, List<PostInfo> trackedPosts) {
		List<PostInfo> clipsPending = new ArrayList<>();
		List<PostInfo> singlePending = new ArrayList<>();
		for (PostInfo p : applyZeroCarry(trackedPosts)) {
			if (!needsMetricsRetry(p)) {
				continue;
			}
			// userId가 없으면(구형 셰이프 단건 등록분) clips를 태울 수 없다 — 전원 단건 재시도.
			if (userId == null) {
				singlePending.add(p);
			} else {
				clipsPending.add(p);
			}
		}
		for (int attempt = 1;
				attempt <= metricsRetryMax && !(clipsPending.isEmpty() && singlePending.isEmpty());
				attempt++) {
			if (!sleepQuietly(metricsRetryDelay)) {
				return;   // 인터럽트는 종료 신호 — 보강만 포기한다(내일 스윕이 다시 시도).
			}
			// 단건을 먼저 돈다 — clips 처리에서 방금 전환된 게시물이 같은 시도에 즉시 재콜되지 않게.
			// metricsRetryHiker 고정(source가 아님) — 클래스 javadoc 참조(2026-09-03 self 셰이프 회귀).
			List<PostInfo> singleNext = retrySinglesOnce(metricsRetryHiker, singlePending, attempt);
			if (!clipsPending.isEmpty()) {
				clipsPending = retryClipsOnce(source, userId, clipsPending, singleNext, attempt);
			}
			singlePending = singleNext;
		}
		int leftover = 0;
		for (List<PostInfo> remaining : List.of(clipsPending, singlePending)) {
			for (PostInfo p : remaining) {
				if (!assumeZeroForOmittedKeys(p)) {
					leftover++;
				}
			}
		}
		if (leftover > 0) {
			log.info("저장·리포스트 재시도 소진 — user_id {} 미충족 {}건(내일 스윕이 재시도)", userId, leftover);
		}
	}

	// ── 0 캐리(08-05) — 구조적 키 부재 게시물의 매일 헛 재시도 차단 ──────────────
	// 리포스트 0·공유 미상 게시물은 키가 영영 안 와 매일 소진 후 0 간주로 끝났다 — 스냅샷이 일
	// 단위라 다음날이면 또 null에서 시작해 상한까지 헛 재시도를 반복한다(하루 ~100콜 낭비 실측).
	// 양수 관측 이력이 전무하고 전일 행이 0으로 끝났으면(판정은 SnapshotRepository, 이중 근거)
	// 해당 지표를 재시도 전에 즉시 0으로 잇는다. 실제 값이 생기면 키가 오기 시작하고 양수 관측이
	// non-null 머지·이력 판정 양쪽에서 이기므로 자동 해제된다.

	/** 재시도 진입 전 0 캐리 적용 — 캐리로 지표가 채워진 게시물은 재저장하고 갱신본을 돌려준다. */
	private List<PostInfo> applyZeroCarry(List<PostInfo> trackedPosts) {
		Set<String> repostsCandidates = new java.util.HashSet<>();
		Set<String> sharesCandidates = new java.util.HashSet<>();
		for (PostInfo p : trackedPosts) {
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
			return trackedPosts;
		}
		LocalDate today = LocalDate.now(KST);
		Set<String> repostsCarry = snapshots.codesWithRepostsZeroCarry(repostsCandidates, today);
		Set<String> sharesCarry = snapshots.codesWithSharesZeroCarry(sharesCandidates, today);
		List<PostInfo> result = new ArrayList<>(trackedPosts.size());
		for (PostInfo p : trackedPosts) {
			Long zeroReposts = p.reposts() == null && repostsCarry.contains(p.shortCode()) ? 0L : null;
			Long zeroShares = p.shares() == null && !p.sharesHidden()
					&& sharesCarry.contains(p.shortCode()) ? 0L : null;
			if (zeroReposts == null && zeroShares == null) {
				result.add(p);
				continue;
			}
			PostInfo merged = p.mergedMetrics(null, zeroShares, zeroReposts);
			writer.savePost(today, merged);
			log.info("0 캐리 — {} shares={} reposts={} (양수 이력 없음·전일 0 종료, 재시도 제외)",
					p.shortCode(), merged.shares(), merged.reposts());
			result.add(merged);
		}
		return result;
	}

	// ── 키 부재 = 0 간주(08-05 사용자 결정) ──────────────────────────────────
	// 리포스트: media_repost_count는 값이 0이면 키 자체가 생략된다 — 운영 전 스냅샷에서 reposts=0
	// 관측이 0건(shares=0 82건·saves=0 61건과 대조)이고, 대조 실험(08-05)에서 인접 호출로 리포스트
	// 111 게시물엔 키가 오고 0 추정 게시물엔 6일 추적 + 추가 재콜 내내 절대 안 왔다.
	// 공유: 게시자 숨김(sharesHidden — share_count_disabled 또는 좋아요 숨김 커플링)이 아닌데도
	// 영구 부재인 게시물이 있다(원인 미상 — 전부 초소형·노출 정지 릴스). 숨김이 아니면 소진 시
	// 0으로 표기한다(사용자 결정 2차). 숨김은 0이 아니라 비공개이므로 null 유지.
	// 판정 조건에 saves 관측을 요구하는 이유: 키 실은 세션을 만났다는 근거가 있어야 "부재=생략"
	// 해석이 성립한다(save·repost 키는 같이 실리는 짝 — 08-04 실측 566/596). 전부 꽝인 날은
	// 근거가 없으므로 0을 쓰지 않는다(내일 스윕 이월) — null(미관측)/0(관측 해석) 구분 유지.

	/** 소진된 게시물의 리포스트·공유(숨김 제외) 0 간주 — 판정 근거(저장 관측)가 있으면 저장하고 true. */
	private boolean assumeZeroForOmittedKeys(PostInfo p) {
		Long zeroReposts = p.reposts() == null ? 0L : null;
		Long zeroShares = p.shares() == null && !p.sharesHidden() ? 0L : null;
		if (p.saves() == null || (zeroReposts == null && zeroShares == null)) {
			return false;
		}
		PostInfo merged = p.mergedMetrics(null, zeroShares, zeroReposts);
		writer.savePost(LocalDate.now(KST), merged);
		log.info("지표 키 부재 0 간주 — {} shares={} reposts={} (재시도 소진, saves={} 관측)",
				p.shortCode(), merged.shares(), merged.reposts(), p.saves());
		return true;
	}

	/** clips 복권 1회 — 창 밖 판정 게시물은 {@code singleNext}로 넘긴다. @return 다음 시도의 clips 대기분. */
	private List<PostInfo> retryClipsOnce(InstagramSource source, String userId, List<PostInfo> pending,
			List<PostInfo> singleNext, int attempt) {
		Map<String, ClipCounts> observed = source.fetchClipCounts(userId, enumeratePages);
		List<PostInfo> next = new ArrayList<>();
		for (PostInfo p : pending) {
			ClipCounts c = observed.get(p.shortCode());
			if (c == null) {
				if (!observed.isEmpty()) {
					// 응답은 정상인데 이 게시물이 없다 — 최근 릴스 창 밖. clips 재콜 대신 단건 복권으로.
					log.info("저장·리포스트 열거 창 밖 — {} 단건 재시도로 전환(user_id {})", p.shortCode(), userId);
					singleNext.add(p);
				} else {
					next.add(p);   // 빈 맵은 콜 실패(fetchClipCounts가 삼킴) — 다음 시도에 맡긴다.
				}
				continue;
			}
			if (!c.hasMetricKeys()) {
				next.add(p);   // 꽝 세션 — 재콜로 다른 세션을 뽑는다.
				continue;
			}
			PostInfo merged = p.mergedMetrics(c.saves(), c.shares(), c.reposts());
			writer.savePost(LocalDate.now(KST), merged);
			log.info("저장·리포스트 당첨 머지 — {} saves={} shares={} reposts={} ({}번째 시도)",
					p.shortCode(), merged.saves(), merged.shares(), merged.reposts(), attempt);
			if (needsMetricsRetry(merged)) {
				next.add(merged);   // 부분 세션 — 남은 지표(숨김 아닌 공유 포함, 옵션 ③)는 계속 시도.
			}
		}
		return next;
	}

	/**
	 * 단건 복권 1회(게시물당 1콜) — 관측된 저장·공유·리포스트만 non-null 머지해 재저장한다.
	 * 종료 조건은 clips 경로와 같은 3지표 완비(옵션 ③) — 특히 창 밖 게시물엔 이 재시도가
	 * 공유수의 사실상 마지막 기회다. 콜 실패는 삼키고 다음 시도에 맡긴다(best-effort —
	 * 상한이 이미 폭주를 막는다).
	 */
	private List<PostInfo> retrySinglesOnce(InstagramSource source, List<PostInfo> pending, int attempt) {
		List<PostInfo> next = new ArrayList<>();
		for (PostInfo p : pending) {
			PostInfo observed;
			try {
				observed = source.fetchPost(p.shortCode());
			} catch (RuntimeException e) {
				log.warn("저장·리포스트 단건 재시도 실패 — {} 다음 시도에 재콜: {}", p.shortCode(), e.toString());
				next.add(p);
				continue;
			}
			if (observed.saves() == null && observed.shares() == null && observed.reposts() == null) {
				next.add(p);   // 꽝 세션 — 재콜로 다른 세션을 뽑는다.
				continue;
			}
			PostInfo merged = p.mergedMetrics(observed.saves(), observed.shares(), observed.reposts());
			writer.savePost(LocalDate.now(KST), merged);
			log.info("저장·리포스트 단건 당첨 머지 — {} saves={} shares={} reposts={} ({}번째 시도)",
					p.shortCode(), merged.saves(), merged.shares(), merged.reposts(), attempt);
			if (needsMetricsRetry(merged)) {
				next.add(merged);   // 부분 세션 — 남은 지표(숨김 아닌 공유 포함)는 계속 시도.
			}
		}
		return next;
	}

	/** 재콜 간격 대기 — 인터럽트면 false(플래그 복원). 응답 캐시 회피용이라 실패해도 치명적이지 않다. */
	private static boolean sleepQuietly(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return true;
		}
		try {
			Thread.sleep(duration.toMillis());
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * 추적 게시물 댓글 수집(스윕용) — commentPages(운영 3페이지, 45건 상한)로 부른다.
	 * 게시물당 누적 합집합 upsert(계약은 {@link CommentRepository} 참고).
	 * postUsername은 owner_reply_text 판정 기준(게시물 소유 계정)이다.
	 */
	public void collectComments(String shortCode, String postUsername) {
		collectComments(hiker, shortCode, postUsername, commentPages);
	}

	/**
	 * 추적 게시물 댓글 수집(등록 전용) — registrationCommentPages(항상 1페이지, 15건)로 부른다.
	 * POST 등록은 was→monitoring 동기 호출 안에서 도는 경로라 commentPages(3)를 그대로 쓰면
	 * 게시물 1콜 + 댓글 3콜로 늘어 10초 read timeout 예산을 넘길 위험이 커진다. 등록의 목적은
	 * "24시간 공백 해소"뿐이라 1페이지면 충분하고, upsert가 누적이라 등록분은 그날 스윕이 3페이지를
	 * 더 훑어도 사라지지 않는다(설계 §배경).
	 */
	public void collectCommentsForRegistration(String shortCode, String postUsername) {
		collectComments(syncHiker, shortCode, postUsername, registrationCommentPages);
	}

	private void collectComments(InstagramSource source, String shortCode, String postUsername, int pages) {
		// 중간 페이지 실패는 부분 결과로 돌아온다(CommentsFetch) — 캠페인 경로는
		// 워터마크가 없어 받은 만큼 upsert하면 끝(누적 합집합이라 다음 스윕이 이어 붙인다).
		comments.upsertForPost(shortCode, source.fetchComments(shortCode, postUsername, pages).comments());
	}
}
