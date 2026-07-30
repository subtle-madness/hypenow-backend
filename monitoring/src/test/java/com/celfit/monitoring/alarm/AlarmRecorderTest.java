package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 알람 적재 규칙 — 수신자 스킵과 METRICS_HIDDEN 오탐 방지가 핵심이다. */
class AlarmRecorderTest {

	private static final Instant FUTURE = Instant.now().plusSeconds(86_400);

	JdbcTemplate db;
	TargetRepository targets;
	SnapshotRepository snapshots;
	AlarmEventRepository events;
	AlarmRecorder recorder;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		snapshots = new SnapshotRepository(db);
		events = new AlarmEventRepository(db);
		recorder = new AlarmRecorder(events, targets, snapshots);
	}

	private long tracking(Long userId, String shortCode, String key) {
		return targets.insert(TargetType.ACCOUNT, userId, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, shortCode, key, FUTURE);
	}

	private List<Map<String, Object>> allEvents() {
		return db.queryForList("SELECT * FROM alarm_event ORDER BY id");
	}

	private PostInfo post(String contentType, Long likes, Long views, Long saves, boolean viewsTrusted) {
		return new PostInfo("SC1", "acct_a", contentType, "캡션", 1_785_000_000L,
				likes, 5L, views, saves, null, 1L, "{}", viewsTrusted);
	}

	private void seedYesterday(String contentType, Long likes, Long views, Long saves) {
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES ('acct_a', 'SC1', DATE '2026-07-29', ?, ?, 5, ?, ?, NULL, 1)""",
				contentType, likes, views, saves);
	}

	@Test
	void 수집_시작_즉시_레인은_발송_시각이_발생_시각과_같다() {
		long id = tracking(7L, "SC1", "rk-1");

		recorder.collectionStartedImmediate(id, 7L, "acct_a", "SC1");

		var row = allEvents().getFirst();
		assertThat(row.get("event_type")).isEqualTo("COLLECTION_STARTED");
		assertThat(row.get("user_id")).isEqualTo(7L);
		assertThat(row.get("email_status")).isEqualTo("PENDING");
		assertThat(row.get("dispatch_after")).isEqualTo(row.get("occurred_at"));
	}

	@Test
	void 자동_전환은_아침_레인이라_발송_시각이_발생_시각과_다르다() {
		long id = tracking(7L, "SC1", "rk-1");

		recorder.collectionStartedScheduled(id, 7L, "acct_a", "SC1");

		var row = allEvents().getFirst();
		assertThat(row.get("dispatch_after")).isNotEqualTo(row.get("occurred_at"));
	}

	/** user_id가 없는 기존 캠페인은 수신자를 알 수 없다 — 적재하면 영원히 못 보내는 행이 쌓인다. */
	@Test
	void 수신자_없는_캠페인은_적재하지_않는다() {
		long id = tracking(null, "SC1", "rk-1");

		recorder.collectionStartedImmediate(id, null, "acct_a", "SC1");
		recorder.collectionEnded(id, null, "acct_a", "SC1");
		recorder.contentUnavailable(id, null, "acct_a", "SC1", "SUBJECT_NOT_FOUND");

		assertThat(allEvents()).isEmpty();
	}

	@Test
	void 종료_실패_이벤트는_사유를_payload에_싣는다() {
		long id = tracking(7L, "SC1", "rk-1");

		recorder.contentUnavailable(id, 7L, "acct_a", "SC1", "PRIVATE_ACCOUNT");

		var row = allEvents().getFirst();
		assertThat(row.get("event_type")).isEqualTo("CONTENT_UNAVAILABLE");
		assertThat(row.get("payload").toString()).contains("PRIVATE_ACCOUNT").contains("SC1");
	}

	@Test
	void 지표가_값에서_null로_바뀌면_비공개_이벤트를_남긴다() {
		tracking(7L, "SC1", "rk-1");
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, null, true));

		var row = allEvents().getFirst();
		assertThat(row.get("event_type")).isEqualTo("METRICS_HIDDEN");
		assertThat(row.get("payload").toString()).contains("views").contains("saves");
	}

	/** 피드는 조회·저장·공유 키가 응답에 아예 없다 — 상시 null을 비교하면 매일 오탐이 나간다. */
	@Test
	void 피드의_상시_null_지표는_비교하지_않는다() {
		tracking(7L, "SC1", "rk-1");
		// 어제 릴스로 잡혔다가 오늘 피드로 판정이 갈린 극단 케이스 — 그래도 오탐이면 안 된다.
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("FEED", 100L, null, null, true));

		assertThat(allEvents()).isEmpty();
	}

	/** clips 보강 실패는 조회수를 조용히 null로 만든다 — 그걸 비공개로 읽으면 장애 때마다 알람이 터진다. */
	@Test
	void clips_보강_실패면_조회수_비교를_건너뛴다() {
		tracking(7L, "SC1", "rk-1");
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, 20L, false));

		assertThat(allEvents()).isEmpty();
	}

	@Test
	void null에서_값_복귀와_null_유지는_이벤트가_아니다() {
		tracking(7L, "SC1", "rk-1");
		seedYesterday("REELS", 100L, null, null);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, 5000L, null, true));

		assertThat(allEvents()).isEmpty();
	}

	/** 스냅샷은 게시물 단위라 여러 캠페인이 공유한다 — 각 수신자가 자기 알람을 받아야 한다. */
	@Test
	void 같은_게시물을_추적하는_캠페인마다_이벤트가_생긴다() {
		tracking(7L, "SC1", "rk-1");
		tracking(9L, "SC1", "rk-2");
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, 20L, true));

		assertThat(allEvents()).hasSize(2);
		assertThat(allEvents().stream().map(r -> r.get("user_id"))).containsExactlyInAnyOrder(7L, 9L);
	}

	/** 추적 캠페인이 없는 게시물(열거로 딸려 온 남의 게시물)은 비교 자체를 하지 않는다. */
	@Test
	void 추적_캠페인이_없으면_이벤트가_없다() {
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, null, true));

		assertThat(allEvents()).isEmpty();
	}

	/** 첫 수집은 비교 대상이 없다 — 직전 스냅샷 부재를 "전부 비공개"로 읽으면 등록 다음날 알람이 쏟아진다. */
	@Test
	void 직전_스냅샷이_없으면_이벤트가_없다() {
		tracking(7L, "SC1", "rk-1");

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", null, null, null, true));

		assertThat(allEvents()).isEmpty();
	}
}
