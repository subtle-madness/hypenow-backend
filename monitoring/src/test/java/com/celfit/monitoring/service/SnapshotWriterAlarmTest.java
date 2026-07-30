package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.alarm.AlarmEventRepository;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.ProfileMetaRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 스냅샷 쓰기 경로의 지표 비공개 적재 — 비교는 upsert **직전**에 일어나야 한다.
 * 순서가 뒤집히면 방금 쓴 값과 자기 자신을 비교해 전이가 영원히 안 잡힌다.
 */
class SnapshotWriterAlarmTest {

	JdbcTemplate db;
	TargetRepository targets;
	SnapshotWriter writer;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		var snapshots = new SnapshotRepository(db);
		var recorder = new AlarmRecorder(new AlarmEventRepository(db), targets, snapshots);
		writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), recorder);
	}

	private PostInfo post(Long views) {
		return new PostInfo("SC1", "acct_a", "REELS", "캡션", 1_785_000_000L,
				100L, 5L, views, 20L, 3L, 1L, "{}", true);
	}

	private long alarmCount() {
		return db.queryForObject("SELECT count(*) FROM alarm_event", Long.class);
	}

	@Test
	void 단건_저장은_직전_스냅샷과_비교해_비공개를_적재한다() {
		targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, "SC1", "rk-1", Instant.now().plusSeconds(86_400));

		writer.savePost(LocalDate.of(2026, 7, 29), post(5000L));
		assertThat(alarmCount()).isZero();          // 첫날은 비교 대상 없음

		writer.savePost(LocalDate.of(2026, 7, 30), post(null));

		assertThat(alarmCount()).isEqualTo(1);
		// 오늘 값도 정상 적재됐다 — 알람이 upsert를 가로채면 안 된다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='SC1'""", Long.class)).isEqualTo(2);
	}

	@Test
	void 계정_저장도_게시물마다_비교한다() {
		targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, "SC1", "rk-1", Instant.now().plusSeconds(86_400));
		var profile = new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "이름", "https://img", "{}");

		writer.saveAccount("acct_a", LocalDate.of(2026, 7, 29), profile, List.of(post(5000L)));
		writer.saveAccount("acct_a", LocalDate.of(2026, 7, 30), profile, List.of(post(null)));

		assertThat(alarmCount()).isEqualTo(1);
	}

	/**
	 * 리뷰 I1 — 같은 날 두 번째 이후 수집은 비교 기준이 **당일 행**(직전 관측)이어야 한다.
	 * `<`로 당일을 건너뛰면 두 번째 수집이 어제 값과 또 비교돼 이미 적재한 이벤트를 중복 적재한다.
	 */
	@Test
	void 같은_날_재수집은_지표_비공개를_중복_적재하지_않는다() {
		targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, "SC1", "rk-1", Instant.now().plusSeconds(86_400));

		writer.savePost(LocalDate.of(2026, 7, 29), post(5000L));      // 어제: views=5000
		writer.savePost(LocalDate.of(2026, 7, 30), post(null));       // 오늘 1차 수집: 비공개 감지(이벤트 1)
		assertThat(alarmCount()).isEqualTo(1);

		writer.savePost(LocalDate.of(2026, 7, 30), post(null));       // 오늘 2차 수집(재스윕 등): null→null

		assertThat(alarmCount()).isEqualTo(1);   // 여전히 1행 — 재적재 없음
	}
}
