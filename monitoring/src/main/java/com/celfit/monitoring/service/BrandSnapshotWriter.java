package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 태그 수집의 쓰기 트랜잭션 경계 — {@link SnapshotWriter}와 같은 이유로 fetch와 분리된
 * 별도 빈(Hiker 콜을 트랜잭션 밖에 두기 위함). 전면 전용 스키마(08-06)라 캠페인 테이블·알람
 * (AlarmRecorder)을 일절 건드리지 않는다 — 브랜드 태그 게시물에는 알람 개념이 없다.
 */
@Component
public class BrandSnapshotWriter {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final BrandSnapshotRepository snapshots;
	private final BrandPostMetaRepository postMeta;
	private final BrandRepository brands;

	public BrandSnapshotWriter(BrandSnapshotRepository snapshots, BrandPostMetaRepository postMeta,
			BrandRepository brands) {
		this.snapshots = snapshots;
		this.postMeta = postMeta;
		this.brands = brands;
	}

	/**
	 * 게시물 1건 — 스냅샷 1행 + 표시 메타 upsert를 한 트랜잭션으로. taken_at을 못 얻은 게시물은
	 * 잘못된 게시일을 만들지 않도록 메타 upsert만 스킵한다(SnapshotWriter.savePostRow 관용구 —
	 * 단, 브랜드 경로는 윈도우 필터가 taken_at 미상을 이미 걸러 실제로는 항상 존재한다).
	 */
	@Transactional
	public void savePost(LocalDate on, PostInfo post) {
		snapshots.upsertPost(on, post);
		if (post.takenAt() != null) {
			LocalDate uploadedAt = Instant.ofEpochSecond(post.takenAt()).atZone(KST).toLocalDate();
			String caption = post.caption() != null ? post.caption() : "";
			postMeta.upsert(post.shortCode(), post.username(), post.contentType(), uploadedAt,
					caption, post.thumbnailUrl(), post.videoUrl(), post.videoDuration(),
					post.isPaidPartnership());
		}
	}

	/** 브랜드 프로필 관측 1건 — 최신값(brand_account)과 추이(brand_profile_snapshot)를 한 트랜잭션으로. */
	@Transactional
	public void saveBrandProfile(long brandId, String username, LocalDate on, ProfileInfo profile) {
		brands.refreshProfile(brandId, profile);
		snapshots.upsertBrandProfile(username, on, profile);
	}
}
