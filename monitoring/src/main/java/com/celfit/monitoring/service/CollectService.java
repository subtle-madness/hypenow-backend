package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수집 1회의 정본 경로 — 등록(동기 첫 수집)과 02:00 스윕이 같은 코드를 쓴다. */
@Service
public class CollectService {

	public record AccountCollectResult(ProfileInfo profile, List<PostInfo> posts) {}

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final RawPayloadRepository rawPayloads;
	private final SnapshotRepository snapshots;
	private final int enumeratePages;

	public CollectService(HikerClient hiker, RawPayloadRepository rawPayloads,
			SnapshotRepository snapshots, @Value("${monitoring.enumerate-pages:1}") int enumeratePages) {
		this.hiker = hiker;
		this.rawPayloads = rawPayloads;
		this.snapshots = snapshots;
		this.enumeratePages = enumeratePages;
	}

	/** 계정 1회 수집 — 원형 적재 + 프로필·게시물 스냅샷 upsert. */
	@Transactional
	public AccountCollectResult collectAccount(String username) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = hiker.fetchProfile(username);
		rawPayloads.save("PROFILE", username, 200, profile.rawJson());
		snapshots.upsertProfile(username, today, profile);
		List<PostInfo> posts = hiker.fetchRecentPosts(username, profile.userId(), enumeratePages);
		if (!posts.isEmpty()) {
			rawPayloads.save("POSTS", username, 200, posts.getFirst().rawJson());
			posts.forEach(p -> snapshots.upsertPost(today, p));
		}
		return new AccountCollectResult(profile, posts);
	}

	/** 게시물 1회 수집 — 원형 적재 + 스냅샷 upsert. */
	@Transactional
	public PostInfo collectPost(String shortCode) {
		PostInfo post = hiker.fetchPost(shortCode);
		rawPayloads.save("POST", shortCode, 200, post.rawJson());
		snapshots.upsertPost(LocalDate.now(KST), post);
		return post;
	}
}
