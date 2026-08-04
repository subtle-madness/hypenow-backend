package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 수집 1회의 정본 경로 — 등록(동기 첫 수집)과 02:00 스윕이 같은 코드를 쓴다.
 * 원형 적재는 여기 없다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 *
 * <p>여기에는 트랜잭션이 없다. Hiker 호출(계정 1회당 최대 3콜)이 트랜잭션 안에 들어가면
 * 그 레이턴시 내내 DB 커넥션을 점유하고, 스윕이 계정 수만큼 이 경로를 도는 동안 풀이 마른다.
 * 쓰기만 {@link SnapshotWriter}(계정·게시물)와 {@link CommentRepository}(댓글)가 짧은 트랜잭션으로 묶는다.
 */
@Service
public class CollectService {

	public record AccountCollectResult(ProfileInfo profile, List<PostInfo> posts) {}

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Logger log = LoggerFactory.getLogger(CollectService.class);

	private final HikerClient hiker;
	private final SnapshotWriter writer;
	private final CommentRepository comments;
	private final SnapshotRepository snapshots;
	private final int enumeratePages;
	private final int commentPages;
	private final int registrationCommentPages;

	/**
	 * 스윕용 commentPages(운영 3페이지)와 등록용 registrationCommentPages(항상 1페이지)를 분리해서
	 * 받는다. 등록은 동기 경로라 was 10초 read timeout 예산을 쓰므로 페이지 수를 스윕과 다르게
	 * 묶어야 한다(설계 §배경). 둘을 같은 값으로 채우는 편의 생성자는 두지 않는다 — 그 경로로
	 * 배선하면 등록이 조용히 3페이지를 부르게 된다.
	 */
	public CollectService(HikerClient hiker, SnapshotWriter writer, CommentRepository comments,
			SnapshotRepository snapshots,
			@Value("${monitoring.enumerate-pages:1}") int enumeratePages,
			@Value("${monitoring.comment-pages:1}") int commentPages,
			@Value("${monitoring.registration-comment-pages:1}") int registrationCommentPages) {
		this.hiker = hiker;
		this.writer = writer;
		this.comments = comments;
		this.snapshots = snapshots;
		this.enumeratePages = enumeratePages;
		this.commentPages = commentPages;
		this.registrationCommentPages = registrationCommentPages;
	}

	/** 계정 1회 수집(스윕용) — 프로필·게시물 스냅샷 + profile_meta upsert. FB 몫 재시도 없음. */
	public AccountCollectResult collectAccount(String username) {
		return collectAccount(username, false);
	}

	/** 계정 1회 수집(등록 전용) — 스윕용과 달리 fb 미관측 신규 릴스에 한해 clips 1회 재조회. */
	public AccountCollectResult collectAccountForRegistration(String username) {
		return collectAccount(username, true);
	}

	private AccountCollectResult collectAccount(String username, boolean retryFb) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = hiker.fetchProfile(username);
		List<PostInfo> posts = hiker.fetchRecentPosts(username, profile.userId(), enumeratePages);
		if (retryFb) {
			posts = retryFbForNewReels(profile.userId(), posts);
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
	private List<PostInfo> retryFbForNewReels(String userId, List<PostInfo> posts) {
		List<String> candidates = posts.stream()
				.filter(p -> "REELS".equals(p.contentType()) && p.views() != null && p.fbPlays() == null)
				.map(PostInfo::shortCode)
				.toList();
		if (candidates.isEmpty() || snapshots.codesWithFbObserved(candidates).containsAll(candidates)) {
			return posts;
		}
		log.info("FB 몫 미관측 신규 릴스 감지 — clips 1회 재조회: user_id {}", userId);
		Map<String, HikerClient.ClipCounts> retried = hiker.fetchClipCounts(userId, enumeratePages);
		return posts.stream()
				.map(p -> {
					HikerClient.ClipCounts c = retried.get(p.shortCode());
					return p.fbPlays() == null && c != null && c.fbPlays() != null
							? p.withFbPlays(c.fbPlays()) : p;
				})
				.toList();
	}

	/** 단건 경로 — 같은 규칙. 재시도 응답에 fb가 실렸으면 그 응답 전체(더 신선)를 쓴다. */
	private PostInfo retryFbForNewReel(PostInfo post) {
		boolean needsFb = "REELS".equals(post.contentType()) && post.views() != null && post.fbPlays() == null;
		if (!needsFb || !snapshots.codesWithFbObserved(Set.of(post.shortCode())).isEmpty()) {
			return post;
		}
		try {
			PostInfo retried = hiker.fetchPost(post.shortCode());
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
		PostInfo post = retryFbForNewReel(hiker.fetchPost(shortCode));
		writer.savePost(LocalDate.now(KST), post);
		return post;
	}

	/**
	 * 추적 게시물 댓글 수집(스윕용) — commentPages(운영 3페이지, 45건 상한)로 부른다.
	 * 게시물당 누적 합집합 upsert(계약은 {@link CommentRepository} 참고).
	 * postUsername은 owner_reply_text 판정 기준(게시물 소유 계정)이다.
	 */
	public void collectComments(String shortCode, String postUsername) {
		collectComments(shortCode, postUsername, commentPages);
	}

	/**
	 * 추적 게시물 댓글 수집(등록 전용) — registrationCommentPages(항상 1페이지, 15건)로 부른다.
	 * POST 등록은 was→monitoring 동기 호출 안에서 도는 경로라 commentPages(3)를 그대로 쓰면
	 * 게시물 1콜 + 댓글 3콜로 늘어 10초 read timeout 예산을 넘길 위험이 커진다. 등록의 목적은
	 * "24시간 공백 해소"뿐이라 1페이지면 충분하고, upsert가 누적이라 등록분은 그날 스윕이 3페이지를
	 * 더 훑어도 사라지지 않는다(설계 §배경).
	 */
	public void collectCommentsForRegistration(String shortCode, String postUsername) {
		collectComments(shortCode, postUsername, registrationCommentPages);
	}

	private void collectComments(String shortCode, String postUsername, int pages) {
		List<CommentInfo> fetched = hiker.fetchComments(shortCode, postUsername, pages);
		comments.upsertForPost(shortCode, fetched);
	}
}
