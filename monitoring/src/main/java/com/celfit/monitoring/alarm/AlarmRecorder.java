package com.celfit.monitoring.alarm;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.PostMetrics;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetOwner;
import com.celfit.monitoring.store.TargetRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알람 이벤트 적재의 유일한 입구 — 발송 레인 선택과 수신자 가드가 여기 한 곳에 모인다.
 *
 * <p>예외를 삼키지 않는다: 적재 실패를 조용히 넘기면 알람이 통째로 사라진 걸 아무도 모른다.
 * 호출자 쪽에서 격리한다({@code DailySweepJob}은 이미 계정·캠페인 단위 try/catch를 가진다).
 * 스냅샷 쓰기 경로는 트랜잭션 안이라 더더욱 삼키면 안 된다 — PostgreSQL은 문장 하나가 실패하면
 * 그 트랜잭션 전체를 abort 상태로 만들어, 삼킨 뒤의 후속 SQL이 전부 깨진다.
 */
@Component
public class AlarmRecorder {

	private static final Logger log = LoggerFactory.getLogger(AlarmRecorder.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final AlarmEventRepository events;
	private final TargetRepository targets;
	private final SnapshotRepository snapshots;

	public AlarmRecorder(AlarmEventRepository events, TargetRepository targets,
			SnapshotRepository snapshots) {
		this.events = events;
		this.targets = targets;
		this.snapshots = snapshots;
	}

	/** 게시물 직접 등록 — 사용자가 방금 누른 행동이라 즉시 레인(스펙 §1-5). */
	public void collectionStartedImmediate(long targetId, Long userId, String username, String shortCode) {
		Instant now = Instant.now();
		record(targetId, userId, AlarmEventType.COLLECTION_STARTED,
				basePayload(username, shortCode), now, DispatchLane.immediate(now));
	}

	/** 스윕 첫 감지 자동 전환 — 새벽에 일어난 일이라 아침 레인. */
	public void collectionStartedScheduled(long targetId, Long userId, String username, String shortCode) {
		Instant now = Instant.now();
		record(targetId, userId, AlarmEventType.COLLECTION_STARTED,
				basePayload(username, shortCode), now, DispatchLane.morning(now));
	}

	public void collectionEnded(long targetId, Long userId, String username, String shortCode) {
		Instant now = Instant.now();
		record(targetId, userId, AlarmEventType.COLLECTION_ENDED,
				basePayload(username, shortCode), now, DispatchLane.morning(now));
	}

	public void contentUnavailable(long targetId, Long userId, String username, String shortCode,
			String failReason) {
		Instant now = Instant.now();
		Map<String, Object> payload = basePayload(username, shortCode);
		payload.put("failReason", failReason);
		record(targetId, userId, AlarmEventType.CONTENT_UNAVAILABLE, payload, now, DispatchLane.morning(now));
	}

	/**
	 * 지표 비공개 감지 — **upsert 직전**에 불러야 한다(직전 스냅샷이 아직 남아 있어야 비교가 성립).
	 * 추적 캠페인 조회를 먼저 하는 이유: 계정 열거는 남의 게시물까지 12건씩 들고 오는데,
	 * 그 전부에 대해 직전 스냅샷을 읽으면 스윕 한 번에 불필요한 쿼리가 계정당 열두 번씩 늘어난다.
	 */
	public void recordMetricsHidden(LocalDate capturedOn, PostInfo post) {
		List<TargetOwner> owners = targets.findTrackingOwners(post.shortCode());
		if (owners.isEmpty()) {
			return;
		}
		PostMetrics previous = snapshots.findLatestPostBefore(post.shortCode(), capturedOn).orElse(null);
		if (previous == null) {
			return;   // 첫 수집 — 비교 대상 없음
		}
		List<String> hidden = hiddenMetrics(post, previous);
		if (hidden.isEmpty()) {
			return;
		}
		for (TargetOwner owner : owners) {
			Instant now = Instant.now();
			Map<String, Object> payload = basePayload(owner.username(), post.shortCode());
			payload.put("metrics", hidden);
			record(owner.targetId(), owner.userId(), AlarmEventType.METRICS_HIDDEN, payload,
					now, DispatchLane.morning(now));
		}
	}

	/**
	 * 값 → null로 바뀐 지표 목록. 오탐 규칙 두 가지가 여기 있다(스펙 §3-2):
	 * ① 조회·저장·공유는 릴스 전용이라 피드에서는 상시 null — 비교 대상에서 뺀다.
	 * ② 릴스 조회수는 clips 보강이 성공했을 때만 신뢰한다 — 보강 실패도 views를 null로 만든다.
	 * null→null·null→값(복귀)은 이벤트가 아니다. 같은 지표의 반복 알람은 "전이"만 잡으므로 자연히 1회다.
	 */
	static List<String> hiddenMetrics(PostInfo now, PostMetrics before) {
		boolean reels = "REELS".equals(now.contentType());
		List<String> hidden = new ArrayList<>();
		addIfHidden(hidden, "likes", before.likes(), now.likes(), true);
		addIfHidden(hidden, "comments", before.comments(), now.comments(), true);
		addIfHidden(hidden, "views", before.views(), now.views(), reels && now.viewsTrusted());
		addIfHidden(hidden, "saves", before.saves(), now.saves(), reels);
		addIfHidden(hidden, "shares", before.shares(), now.shares(), reels);
		addIfHidden(hidden, "reposts", before.reposts(), now.reposts(), true);   // 전 타입 제공(findings §2)
		return hidden;
	}

	private static void addIfHidden(List<String> out, String metric, Long before, Long after,
			boolean comparable) {
		if (comparable && before != null && after == null) {
			out.add(metric);
		}
	}

	/** payload는 null 값을 담을 수 있어야 한다(shortCode 미정 캠페인) — Map.of는 null을 거부한다. */
	private static Map<String, Object> basePayload(String username, String shortCode) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", username);
		payload.put("shortCode", shortCode);
		return payload;
	}

	private void record(long targetId, Long userId, AlarmEventType type, Map<String, Object> payload,
			Instant occurredAt, Instant dispatchAfter) {
		if (userId == null) {
			// V3 이전 등록분 — 백필 런북 전까지는 수신자를 알 방법이 없다(스펙 §2-1·§7).
			log.warn("수신자 미상 — 알람 적재 스킵: target {} {}", targetId, type);
			return;
		}
		events.insert(targetId, userId, type, JSON.writeValueAsString(payload), occurredAt, dispatchAfter);
	}
}
