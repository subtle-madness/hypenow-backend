package com.celfit.monitoring.service;

import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.util.List;
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

	private final SnapshotRepository snapshots;
	private final AlarmRecorder alarms;

	public SnapshotWriter(SnapshotRepository snapshots, AlarmRecorder alarms) {
		this.snapshots = snapshots;
		this.alarms = alarms;
	}

	/** 계정 1회 수집분 — 프로필 1행 + 게시물 N행을 한 트랜잭션으로. */
	@Transactional
	public void saveAccount(String username, LocalDate on, ProfileInfo profile, List<PostInfo> posts) {
		snapshots.upsertProfile(username, on, profile);
		posts.forEach(p -> savePostRow(on, p));
	}

	@Transactional
	public void savePost(LocalDate on, PostInfo post) {
		savePostRow(on, post);
	}

	/** 순서 고정: 비교 → upsert. 뒤집으면 방금 쓴 값과 자기 자신을 비교해 전이가 영원히 안 잡힌다. */
	private void savePostRow(LocalDate on, PostInfo post) {
		alarms.recordMetricsHidden(on, post);
		snapshots.upsertPost(on, post);
	}
}
