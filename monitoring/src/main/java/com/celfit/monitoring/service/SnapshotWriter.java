package com.celfit.monitoring.service;

import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.PostMetaRepository;
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
 *
 * <p>지표 비공개 알람이 여기 붙는 이유: 직전 스냅샷과의 비교는 **덮어쓰기 전에만** 가능하다.
 * 알람 적재를 같은 트랜잭션에 두는 것도 의도다 — 스냅샷이 롤백되면 그 비교로 만든 알람도 함께 사라져야 한다.
 */
@Component
public class SnapshotWriter {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final SnapshotRepository snapshots;
	private final ProfileMetaRepository profileMeta;
	private final PostMetaRepository postMeta;
	private final AlarmRecorder alarms;

	public SnapshotWriter(SnapshotRepository snapshots, ProfileMetaRepository profileMeta,
			PostMetaRepository postMeta, AlarmRecorder alarms) {
		this.snapshots = snapshots;
		this.profileMeta = profileMeta;
		this.postMeta = postMeta;
		this.alarms = alarms;
	}

	/**
	 * 계정 1회 수집분 — 프로필 1행 + 게시물 N행 + profile_meta 1행을 한 트랜잭션으로(계약 §3 profile_meta).
	 * last_uploaded_at은 열거된 게시물 taken_at 최댓값의 KST 날짜 — 게시물이 없거나 taken_at이
	 * 전부 미상이면 null을 넘겨 ProfileMetaRepository가 기존 값을 보존하게 한다.
	 */
	@Transactional
	public void saveAccount(String username, LocalDate on, ProfileInfo profile, List<PostInfo> posts) {
		snapshots.upsertProfile(username, on, profile);
		posts.forEach(p -> savePostRow(on, p));
		profileMeta.upsert(username, profile.fullName(), profile.profilePicUrl(), lastUploadedAt(posts));
	}

	/**
	 * POST 등록분은 계정 갈래({@link #saveAccount}의 {@code profileMeta.upsert})를 영구히 타지 않는다
	 * ({@code needsEnumeration}이 ACCOUNT 타입 target 존재를 전제로 하기 때문, 트랙 II) — 여기서 안
	 * 채우면 profile_meta 행이 아예 안 생긴다. 단건 응답(/v2/media/by/code)에 owner 필드가 이미
	 * 실려 오므로 Hiker 콜은 늘지 않는다(제로 콜 원칙). 스윕이 매일 이 경로를 타므로 인스타 CDN
	 * 서명 만료(~4일, docs/superpowers/specs/2026-07-21-image-archive-design.md:7)도 자동
	 * 갱신된다 — monitoring의 profile_meta는 analytics 이미지 아카이브(트랙 J) 대상이 아니라서
	 * 이 갱신이 유일한 만료 방어다.
	 *
	 * <p>{@code savePostRow}(공용 private, saveAccount의 게시물 순회에서도 호출됨)가 아니라 여기서
	 * upsert하는 이유: savePostRow에 넣으면 saveAccount 한 번에 게시물 수만큼 중복 upsert가 돈다.
	 */
	@Transactional
	public void savePost(LocalDate on, PostInfo post) {
		savePostRow(on, post);
		profileMeta.upsertOwnerFromPost(post.username(), post.ownerFullName(), post.ownerProfilePicUrl());
	}

	/**
	 * 순서 고정: 비교 → upsert. 뒤집으면 방금 쓴 값과 자기 자신을 비교해 전이가 영원히 안 잡힌다.
	 *
	 * <p>post_meta도 여기서 함께 upsert한다(계약 §3 post_meta) — 이 메서드가 등록 동기 수집·스윕 열거·
	 * 단건 보강 전 경로의 단일 깔때기라, "게시물을 처음 만나는 모든 곳에서 적재" 요구가 자동 충족된다.
	 * taken_at을 못 얻은 게시물은 잘못된 게시일을 만들지 않도록 post_meta upsert 자체를 스킵한다
	 * (스냅샷 적재는 그대로 진행 — post_snapshot은 taken_at에 의존하지 않는다).
	 */
	private void savePostRow(LocalDate on, PostInfo post) {
		alarms.recordMetricsHidden(on, post);
		snapshots.upsertPost(on, post);
		if (post.takenAt() != null) {
			LocalDate uploadedAt = Instant.ofEpochSecond(post.takenAt()).atZone(KST).toLocalDate();
			String caption = post.caption() != null ? post.caption() : "";   // 캡션 없는 게시물은 빈 문자열(계약 §3)
			postMeta.upsert(post.shortCode(), post.username(), post.contentType(), uploadedAt, caption, post.thumbnailUrl());
		}
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
