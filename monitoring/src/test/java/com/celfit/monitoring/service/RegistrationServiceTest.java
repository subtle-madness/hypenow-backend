package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.alarm.AlarmEventRepository;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.PostMetaRepository;
import com.celfit.monitoring.store.ProfileMetaRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 등록 직후 저장·리포스트 백필(08-04) — POST 등록의 동기 응답은 was 10초 read timeout 예산이라
 * 재시도(6회×10s)를 품을 수 없다. 대신 등록 커밋 뒤 백필 executor로 같은 재시도 루프를 돌려
 * "등록 당일 스냅샷이 다음날 새벽 스윕까지 비는" 공백을 없앤다. 테스트는 executor를 동기
 * (Runnable::run)로 갈아 끼워 백필 결과를 register() 반환 직후 바로 검증한다.
 */
class RegistrationServiceTest {

	private static final Instant FUTURE = Instant.now().plusSeconds(86_400);

	/** 꽝 세션 clips — 재생수만 있고 저장·공유·리포스트 키가 없다. */
	private static String clipsMiss(String code) {
		return """
				{"response":{"items":[{"media":{"code":"%s","product_type":"clips",
				"ig_play_count":100}}],"paging_info":{"more_available":false}},"next_page_id":null}"""
				.formatted(code);
	}

	/** 당첨 세션 clips — 저장 5·공유 9·리포스트 7. */
	private static String clipsHit(String code) {
		return """
				{"response":{"items":[{"media":{"code":"%s","product_type":"clips","ig_play_count":100,
				"save_count":5,"reshare_count":9,"media_repost_count":7}}],
				"paging_info":{"more_available":false}},"next_page_id":null}"""
				.formatted(code);
	}

	/** 단건 응답(media_or_ad) — extraFields로 지표·타입을 시나리오별로 얹는다. */
	private static String single(String code, String extraFields) {
		String extra = extraFields == null ? "" : extraFields + ",";
		return """
				{"media_or_ad":{"code":"%s","product_type":"clips","taken_at":1700000000,%s
				"like_count":10,"comment_count":2,"play_count":100,"ig_play_count":100,
				"caption":{"text":"c"},"user":{"username":"owner1","pk":424242}},"status":"ok"}"""
				.formatted(code, extra);
	}

	JdbcTemplate db;
	List<String> calls;
	ArrayDeque<String> scriptedClips;
	String singleBody;
	RegistrationService registration;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		calls = new ArrayList<>();
		scriptedClips = new ArrayDeque<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/media/comments")) {
				return "{\"response\":{\"comments\":[],\"has_more_comments\":false},\"next_page_id\":null}";
			}
			if (path.startsWith("/v2/user/clips")) {
				return scriptedClips.isEmpty() ? clipsMiss("none") : scriptedClips.poll();
			}
			return singleBody;
		});
		var targets = new TargetRepository(db);
		var snapshots = new SnapshotRepository(db);
		var alarms = new AlarmRecorder(new AlarmEventRepository(db), targets, snapshots);
		var writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), alarms);
		var collect = new CollectService(client, writer, new CommentRepository(db), snapshots,
				1, 1, 1, 6, Duration.ZERO);
		// 동기 executor — 백필이 register() 리턴 전에 끝나 결과를 바로 단언할 수 있다.
		registration = new RegistrationService(collect, targets, alarms, Runnable::run);
	}

	private RegistrationService.Result registerPost(String shortCode) {
		return registration.register(new RegisterCommand(
				"rk-" + shortCode, TargetType.POST, 7L, null, shortCode, null, FUTURE));
	}

	private long clipsCalls() {
		return calls.stream().filter(p -> p.startsWith("/v2/user/clips")).count();
	}

	private Long snapshotMetric(String column, String shortCode) {
		return db.queryForObject(
				"SELECT " + column + " FROM post_snapshot WHERE short_code=? ORDER BY captured_on DESC LIMIT 1",
				Long.class, shortCode);
	}

	@Test
	void 등록_직후_저장리포스트_미관측_릴스는_백필_재시도로_당일_채워진다() {
		singleBody = single("P900", null);   // 저장·리포스트 키 없는 꽝 세션
		scriptedClips.addAll(List.of(clipsMiss("P900"), clipsHit("P900")));

		var result = registerPost("P900");

		assertThat(result.status()).isEqualTo("TRACKING");
		assertThat(clipsCalls()).isEqualTo(2);   // 꽝 1 + 당첨 1, 당첨 즉시 중단
		assertThat(snapshotMetric("saves", "P900")).isEqualTo(5L);
		assertThat(snapshotMetric("reposts", "P900")).isEqualTo(7L);
	}

	@Test
	void 등록_단건_응답에_저장리포스트가_이미_있으면_백필을_돌리지_않는다() {
		singleBody = single("P901", "\"save_count\":11,\"reshare_count\":22,\"media_repost_count\":33");

		registerPost("P901");

		assertThat(clipsCalls()).isZero();
		assertThat(snapshotMetric("saves", "P901")).isEqualTo(11L);
	}

	@Test
	void 등록_피드는_백필_대상이_아니다() {
		singleBody = single("P902", "\"product_type\":\"feed\"");

		registerPost("P902");

		assertThat(clipsCalls()).isZero();
	}
}
