package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.CommentRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

	private final HikerClient hiker;
	private final SnapshotWriter writer;
	private final CommentRepository comments;
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
			@Value("${monitoring.enumerate-pages:1}") int enumeratePages,
			@Value("${monitoring.comment-pages:1}") int commentPages,
			@Value("${monitoring.registration-comment-pages:1}") int registrationCommentPages) {
		this.hiker = hiker;
		this.writer = writer;
		this.comments = comments;
		this.enumeratePages = enumeratePages;
		this.commentPages = commentPages;
		this.registrationCommentPages = registrationCommentPages;
	}

	/** 계정 1회 수집 — 프로필·게시물 스냅샷 + profile_meta upsert. */
	public AccountCollectResult collectAccount(String username) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = hiker.fetchProfile(username);
		List<PostInfo> posts = hiker.fetchRecentPosts(username, profile.userId(), enumeratePages);
		writer.saveAccount(username, today, profile, posts);
		return new AccountCollectResult(profile, posts);
	}

	/** 게시물 1회 수집 — 스냅샷 upsert. */
	public PostInfo collectPost(String shortCode) {
		PostInfo post = hiker.fetchPost(shortCode);
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
