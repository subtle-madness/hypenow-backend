package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.alarm.AlarmEventRepository;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.PostMetaRepository;
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
		writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), recorder);
	}

	private PostInfo post(Long views) {
		return new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션",
				"https://cdn/thumb.jpg", 1_785_000_000L,
				100L, 5L, views, null, 20L, 3L, 1L, "{}", true, false, false);
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
		var profile = new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "이름", "https://img", null, "{}");

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

	// ── post_meta 단일 깔때기(계약 v2.2 §3) ─────────────────────────────────

	@Test
	void savePost는_post_meta도_함께_적재한다() {
		writer.savePost(LocalDate.of(2026, 7, 30), post(5000L));

		var row = db.queryForMap("SELECT * FROM post_meta WHERE short_code='SC1'");
		assertThat(row.get("username")).isEqualTo("acct_a");
		assertThat(row.get("content_type")).isEqualTo("REELS");
		assertThat(row.get("caption")).isEqualTo("캡션");
		assertThat(row.get("thumbnail_url")).isEqualTo("https://cdn/thumb.jpg");
		// taken_at=1_785_000_000(epoch) → KST 날짜로 변환돼 저장된다.
		assertThat(row.get("uploaded_at")).isEqualTo(java.sql.Date.valueOf(
				java.time.Instant.ofEpochSecond(1_785_000_000L)
						.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()));
	}

	/** takenAt을 못 얻은 게시물은 잘못된 게시일을 만들지 않도록 post_meta upsert 자체를 스킵한다(계약 §3). */
	@Test
	void takenAt이_null이면_post_meta_upsert를_스킵한다() {
		var post = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션",
				"https://cdn/thumb.jpg", null,
				100L, 5L, 1000L, null, 20L, 3L, 1L, "{}", true, false, false);

		writer.savePost(LocalDate.of(2026, 7, 30), post);

		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_meta WHERE short_code='SC1'", Long.class)).isZero();
		// 스냅샷 적재는 taken_at과 무관하게 그대로 진행된다.
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_snapshot WHERE short_code='SC1'", Long.class)).isEqualTo(1);
	}

	/** 캡션 없는 게시물은 post_meta.caption NOT NULL 제약을 지키기 위해 빈 문자열로 폴백한다(계약 §3). */
	@Test
	void 캡션이_null이면_빈_문자열로_폴백한다() {
		var post = new PostInfo("SC1", "acct_a", null, null, null, "REELS", null,
				"https://cdn/thumb.jpg", 1_785_000_000L,
				100L, 5L, 1000L, null, 20L, 3L, 1L, "{}", true, false, false);

		writer.savePost(LocalDate.of(2026, 7, 30), post);

		assertThat(db.queryForObject(
				"SELECT caption FROM post_meta WHERE short_code='SC1'", String.class)).isEqualTo("");
	}

	// ── profile_meta POST 등록분(트랙 II) ─────────────────────────────────────

	/** POST 등록분은 계정 갈래(saveAccount)를 영구히 안 타므로 savePost가 유일한 profile_meta 적재 경로다. */
	@Test
	void savePost는_단건_응답_owner_필드로_profile_meta_행을_만든다() {
		var post = new PostInfo("SC1", "acct_a", "표시이름", "https://cdn/owner.jpg", null, "REELS", "캡션",
				"https://cdn/thumb.jpg", 1_785_000_000L,
				100L, 5L, 1000L, null, 20L, 3L, 1L, "{}", true, false, false);

		writer.savePost(LocalDate.of(2026, 7, 30), post);

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("표시이름");
		assertThat(row.get("profile_image_url")).isEqualTo("https://cdn/owner.jpg");
	}

	/**
	 * 공존 계정 회귀 방어 — 같은 계정에 ACCOUNT·POST 캠페인이 공존하면 같은 스윕에서
	 * saveAccount(정본, last_uploaded_at 채움) 뒤에 savePost가 돌 수 있다. 이때 savePost의
	 * upsertOwnerFromPost가 last_uploaded_at을 건드리면 안 된다(COALESCE 방어의 실사용처).
	 */
	@Test
	void saveAccount로_채운_last_uploaded_at은_savePost_이후에도_보존된다() {
		var profile = new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "이름", "https://img", null, "{}");
		var enumerated = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null,
				1_785_000_000L, 100L, 5L, 1000L, null, 20L, 3L, 1L, "{}", true, false, false);
		writer.saveAccount("acct_a", LocalDate.of(2026, 7, 29), profile, List.of(enumerated));
		var lastUploadedAtAfterAccount = db.queryForObject(
				"SELECT last_uploaded_at FROM profile_meta WHERE username='acct_a'", LocalDate.class);

		var postOnly = new PostInfo("SC1", "acct_a", "표시이름", "https://cdn/owner.jpg", null, "REELS", "캡션",
				"https://cdn/thumb.jpg", 1_785_000_000L,
				100L, 5L, 1000L, null, 20L, 3L, 1L, "{}", true, false, false);
		writer.savePost(LocalDate.of(2026, 7, 30), postOnly);

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("last_uploaded_at")).isEqualTo(java.sql.Date.valueOf(lastUploadedAtAfterAccount));
		// owner 필드는 savePost 값으로 갱신된다(COALESCE는 null만 방어, 실값은 갱신 대상)
		assertThat(row.get("display_name")).isEqualTo("표시이름");
	}
}
