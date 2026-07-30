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

	public CollectService(HikerClient hiker, SnapshotWriter writer, CommentRepository comments,
			@Value("${monitoring.enumerate-pages:1}") int enumeratePages,
			@Value("${monitoring.comment-pages:1}") int commentPages) {
		this.hiker = hiker;
		this.writer = writer;
		this.comments = comments;
		this.enumeratePages = enumeratePages;
		this.commentPages = commentPages;
	}

	/** 계정 1회 수집 — 프로필·게시물 스냅샷 + profile_meta upsert. */
	public AccountCollectResult collectAccount(String username) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = hiker.fetchProfile(username);
		List<PostInfo> posts = hiker.fetchRecentPosts(username, profile.userId(), enumeratePages);
		writer.saveAccount(username, today, profile, posts);
		return new AccountCollectResult(profile, posts);
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

	/** 게시물 1회 수집 — 스냅샷 upsert. */
	public PostInfo collectPost(String shortCode) {
		PostInfo post = hiker.fetchPost(shortCode);
		writer.savePost(LocalDate.now(KST), post);
		return post;
	}

	/**
	 * 추적 게시물 댓글 수집 — 게시물당 누적 합집합 upsert(계약은 {@link CommentRepository} 참고).
	 * postUsername은 owner_reply_text 판정 기준(게시물 소유 계정)이다.
	 */
	public void collectComments(String shortCode, String postUsername) {
		List<CommentInfo> fetched = hiker.fetchComments(shortCode, postUsername, commentPages);
		comments.upsertForPost(shortCode, fetched);
	}
}
