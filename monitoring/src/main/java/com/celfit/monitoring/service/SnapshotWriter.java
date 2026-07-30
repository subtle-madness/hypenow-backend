package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.ProfileMetaRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스냅샷 쓰기의 트랜잭션 경계 — 수집 1회분을 원자적으로 커밋한다.
 *
 * <p>{@link CollectService}와 분리된 빈인 이유는 두 가지다.
 * ① Hiker 호출(최대 수 초)을 트랜잭션 밖에 두려면 fetch와 write의 경계가 갈라져야 한다 —
 *   한 클래스 안에서 {@code @Transactional} 메서드를 자기 호출하면 프록시를 타지 않아 경계가 사라진다.
 * ② 트랜잭션이 fetch를 감싸면 Hiker 레이턴시 동안 DB 커넥션을 붙잡고 있고,
 *   수집 실패 시 이미 나간 콜의 원형 적재(RecordingHikerHttp)까지 같이 롤백돼 감사 기록이 사라진다.
 */
@Component
public class SnapshotWriter {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final SnapshotRepository snapshots;
	private final ProfileMetaRepository profileMeta;

	public SnapshotWriter(SnapshotRepository snapshots, ProfileMetaRepository profileMeta) {
		this.snapshots = snapshots;
		this.profileMeta = profileMeta;
	}

	/**
	 * 계정 1회 수집분 — 프로필 1행 + 게시물 N행 + profile_meta 1행을 한 트랜잭션으로(계약 §3 profile_meta).
	 * last_uploaded_at은 열거된 게시물 taken_at 최댓값의 KST 날짜 — 게시물이 없거나 taken_at이
	 * 전부 미상이면 null을 넘겨 ProfileMetaRepository가 기존 값을 보존하게 한다.
	 */
	@Transactional
	public void saveAccount(String username, LocalDate on, ProfileInfo profile, List<PostInfo> posts) {
		snapshots.upsertProfile(username, on, profile);
		posts.forEach(p -> snapshots.upsertPost(on, p));
		profileMeta.upsert(username, profile.fullName(), profile.profilePicUrl(), lastUploadedAt(posts));
	}

	@Transactional
	public void savePost(LocalDate on, PostInfo post) {
		snapshots.upsertPost(on, post);
	}

	private static LocalDate lastUploadedAt(List<PostInfo> posts) {
		return posts.stream()
				.map(PostInfo::takenAt)
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder())
				.map(epoch -> Instant.ofEpochSecond(epoch).atZone(KST).toLocalDate())
				.orElse(null);
	}
}
