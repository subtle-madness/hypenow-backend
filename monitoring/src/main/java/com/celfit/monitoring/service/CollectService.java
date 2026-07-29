package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 1회의 정본 경로 — 등록(동기 첫 수집)과 02:00 스윕이 같은 코드를 쓴다.
 * 원형 적재는 여기 없다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 */
@Service
public class CollectService {

	public record AccountCollectResult(ProfileInfo profile, List<PostInfo> posts) {}

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final SnapshotRepository snapshots;
	private final int enumeratePages;

	public CollectService(HikerClient hiker, SnapshotRepository snapshots,
			@Value("${monitoring.enumerate-pages:1}") int enumeratePages) {
		this.hiker = hiker;
		this.snapshots = snapshots;
		this.enumeratePages = enumeratePages;
	}

	/** 계정 1회 수집 — 프로필·게시물 스냅샷 upsert. */
	@Transactional
	public AccountCollectResult collectAccount(String username) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = hiker.fetchProfile(username);
		snapshots.upsertProfile(username, today, profile);
		List<PostInfo> posts = hiker.fetchRecentPosts(username, profile.userId(), enumeratePages);
		posts.forEach(p -> snapshots.upsertPost(today, p));
		return new AccountCollectResult(profile, posts);
	}

	/** 게시물 1회 수집 — 스냅샷 upsert. */
	@Transactional
	public PostInfo collectPost(String shortCode) {
		PostInfo post = hiker.fetchPost(shortCode);
		snapshots.upsertPost(LocalDate.now(KST), post);
		return post;
	}
}
