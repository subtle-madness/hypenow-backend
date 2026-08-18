package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.alarm.AlarmEventRepository;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.PostMetaRepository;
import com.celfit.monitoring.store.ProfileMetaRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
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

	/** user.pk가 없는 단건 응답 — ownerUserId를 못 주는 구형 셰이프 재현용. */
	private static String singleNoPk(String code, String extraFields) {
		String extra = extraFields == null ? "" : extraFields + ",";
		return """
				{"media_or_ad":{"code":"%s","product_type":"clips","taken_at":1700000000,%s
				"like_count":10,"comment_count":2,"play_count":100,"ig_play_count":100,
				"caption":{"text":"c"},"user":{"username":"owner1"}},"status":"ok"}"""
				.formatted(code, extra);
	}

	/** 창 밖 단건 재시도 당첨 세션의 추가 필드 — 저장 5·공유 9·리포스트 7. */
	private static final String SINGLE_HIT = "\"save_count\":5,\"reshare_count\":9,\"media_repost_count\":7";

	/** 게시자 프로필 응답(/v2/user/by/username) — follower_count 777 고정. */
	private static final String PROFILE_BODY = """
			{"user":{"pk":424242,"username":"owner1","is_private":false,
			"full_name":"Owner One","profile_pic_url":"https://img/owner1.jpg",
			"follower_count":777,"following_count":10,"media_count":42},"status":"ok"}""";

	JdbcTemplate db;
	List<String> calls;
	ArrayDeque<String> scriptedClips;
	ArrayDeque<String> scriptedSingles;
	String singleBody;
	String profileBody;
	RegistrationService registration;
	/** 콜 집계 스코프(비용 계상, 2026-08-12 범위 확장) — 등록 서비스·데코레이터가 공유한다. */
	final TargetCallContext callContext = new TargetCallContext();

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		calls = new ArrayList<>();
		scriptedClips = new ArrayDeque<>();
		scriptedSingles = new ArrayDeque<>();
		profileBody = PROFILE_BODY;
		// 운영 조립(HikerConfig)과 동형으로 콜 집계 데코레이터를 끼운다 — 등록 콜의 유저 귀속까지 검증.
		var client = new HikerClient(new CountingHikerHttp(path -> {
			calls.add(path);
			if (path.startsWith("/v2/media/comments")) {
				return "{\"response\":{\"comments\":[],\"has_more_comments\":false},\"next_page_id\":null}";
			}
			if (path.startsWith("/v2/user/by/username")) {
				return profileBody;
			}
			if (path.startsWith("/v2/user/clips")) {
				return scriptedClips.isEmpty() ? clipsMiss("none") : scriptedClips.poll();
			}
			// 단건 스크립트(콜 순서대로 소진)가 있으면 우선 — 단건 세션 복권(꽝→당첨) 재현용.
			return scriptedSingles.isEmpty() ? singleBody : scriptedSingles.poll();
		}, new BrandCallContext(), new BrandCallCountRepository(db),
				callContext, new TargetCallCountRepository(db)));
		var targets = new TargetRepository(db);
		var snapshots = new SnapshotRepository(db);
		var alarms = new AlarmRecorder(new AlarmEventRepository(db), targets, snapshots);
		var writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), alarms);
		var collect = new CollectService(client, writer, new CommentRepository(db), snapshots,
				1, 1, 1, 6, Duration.ZERO);
		// 동기 executor — 백필이 register() 리턴 전에 끝나 결과를 바로 단언할 수 있다.
		registration = new RegistrationService(collect, targets, alarms, callContext, Runnable::run);
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
		// 콜 집계(2026-08-12 비용 범위 확장) — 동기 첫 수집·댓글은 물론, executor로 넘어간 백필
		// 재시도 콜(clips 2)까지 등록 유저(7) 몫으로 계상된다(runScoped 재전파 검증).
		assertThat(db.queryForObject(
				"SELECT coalesce(sum(calls),0) FROM target_call_count WHERE user_id=7", Long.class))
				.isEqualTo((long) calls.size());
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

	private long singleCalls() {
		return calls.stream().filter(p -> p.startsWith("/v2/media/info/by/code")).count();
	}

	@Test
	void 등록_직후_창_밖_릴스는_단건_재시도로_당일_채워진다() {
		// 등록 정본 콜은 꽝 세션(fb는 실려 fb 재시도는 안 탄다), clips는 창 밖(다른 릴스만) —
		// 백필이 단건 재시도로 전환해 당첨을 잡아야 등록 당일 3지표가 남는다(08-05 결정).
		scriptedSingles.addAll(List.of(
				single("P903", "\"fb_play_count\":3"),
				single("P903", SINGLE_HIT)));
		scriptedClips.add(clipsMiss("OTHER"));

		var result = registerPost("P903");

		assertThat(result.status()).isEqualTo("TRACKING");
		assertThat(clipsCalls()).isEqualTo(1);          // 창 밖 판정 1콜 — clips 재콜은 없다
		assertThat(singleCalls()).isEqualTo(2);         // 정본 1 + 단건 재시도 당첨 1
		assertThat(snapshotMetric("saves", "P903")).isEqualTo(5L);
		assertThat(snapshotMetric("shares", "P903")).isEqualTo(9L);
		assertThat(snapshotMetric("reposts", "P903")).isEqualTo(7L);
	}

	@Test
	void 등록_단건이_공유만_빠뜨려도_백필이_돈다() {
		// 부분 세션이 저장·리포스트만 주고 공유를 빠뜨린 등록 — 진입 조건이 저장·리포스트만 보면
		// 등록 당일 공유수 단독 누락이 남는다(08-05 옵션 ③).
		singleBody = single("P905", "\"save_count\":4,\"media_repost_count\":7,\"fb_play_count\":3");
		scriptedClips.add(clipsHit("P905"));

		registerPost("P905");

		assertThat(clipsCalls()).isEqualTo(1);          // 당첨 즉시 중단
		assertThat(snapshotMetric("shares", "P905")).isEqualTo(9L);
		assertThat(snapshotMetric("saves", "P905")).isEqualTo(4L);   // 단건 관측 유지(non-null 우선)
	}

	private long profileCalls() {
		return calls.stream().filter(p -> p.startsWith("/v2/user/by/username")).count();
	}

	@Test
	void 등록_직후_게시자_프로필_1콜로_팔로워가_채워진다() {
		singleBody = single("P910", SINGLE_HIT);

		var result = registerPost("P910");

		assertThat(result.status()).isEqualTo("TRACKING");
		assertThat(profileCalls()).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT followers FROM profile_snapshot WHERE username='owner1'", Long.class))
				.isEqualTo(777L);
	}

	@Test
	void 프로필_스냅샷이_이미_있으면_등록_시_프로필_콜이_나가지_않는다() {
		// 스윕의 평생 1회 규칙과 같은 기준 — 어떤 경로로든 이미 채워진 계정에는 콜을 쓰지 않는다.
		db.update("INSERT INTO profile_snapshot (username, captured_on, followers) VALUES ('owner1', CURRENT_DATE, 555)");
		singleBody = single("P911", SINGLE_HIT);

		registerPost("P911");

		assertThat(profileCalls()).isZero();
		assertThat(db.queryForObject(
				"SELECT followers FROM profile_snapshot WHERE username='owner1'", Long.class))
				.isEqualTo(555L);
	}

	@Test
	void 팔로워가_null인_행만_있으면_등록_시_프로필_콜이_다시_나간다() {
		// 행 존재가 아니라 "팔로워를 안다"가 가드 기준이다 — follower_count 없는 응답이 남긴
		// null 행이 계정을 영구 '완료'로 고착시키면 24시간 공백보다 나쁜 영구 null이 된다.
		db.update("INSERT INTO profile_snapshot (username, captured_on, followers) VALUES ('owner1', CURRENT_DATE, NULL)");
		singleBody = single("P913", SINGLE_HIT);

		registerPost("P913");

		assertThat(profileCalls()).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT followers FROM profile_snapshot WHERE username='owner1'", Long.class))
				.isEqualTo(777L);
	}

	@Test
	void 프로필_응답에_full_name이_없어도_단건_응답이_채운_표시_이름을_지우지_않는다() {
		// 등록 순서는 게시물(→ display_name 기록) 뒤 프로필이라, 프로필 응답의 full_name 결손이
		// 무조건 덮어쓰기를 타면 방금 채운 표시 이름이 null로 지워진다(세션 편차는 정상 케이스).
		singleBody = """
				{"media_or_ad":{"code":"P914","product_type":"clips","taken_at":1700000000,
				"save_count":5,"reshare_count":9,"media_repost_count":7,
				"like_count":10,"comment_count":2,"play_count":100,"ig_play_count":100,
				"caption":{"text":"c"},"user":{"username":"owner1","pk":424242,
				"full_name":"포스트 소유자","profile_pic_url":"https://img/o.jpg"}},"status":"ok"}""";
		profileBody = """
				{"user":{"pk":424242,"username":"owner1","is_private":false,
				"follower_count":777,"following_count":10,"media_count":42},"status":"ok"}""";

		registerPost("P914");

		assertThat(profileCalls()).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT display_name FROM profile_meta WHERE username='owner1'", String.class))
				.isEqualTo("포스트 소유자");
	}

	@Test
	void 게시자_username이_빈_문자열이면_프로필_콜을_보내지_않는다() {
		// toPost의 username은 키 부재만 null이고 빈 값은 ""로 온다(shortCode isBlank 방어와 같은 결) —
		// fetchProfile("")은 과금되는 쓰레기 콜이라 아예 보내지 않는다.
		singleBody = """
				{"media_or_ad":{"code":"P915","product_type":"clips","taken_at":1700000000,
				"save_count":5,"reshare_count":9,"media_repost_count":7,
				"like_count":10,"comment_count":2,"play_count":100,"ig_play_count":100,
				"caption":{"text":"c"},"user":{"username":"","pk":424242}},"status":"ok"}""";

		var result = registerPost("P915");

		assertThat(result.status()).isEqualTo("TRACKING");
		assertThat(profileCalls()).isZero();
	}

	@Test
	void 프로필_수집_실패해도_등록은_성공한다() {
		// user 노드 없는 응답 → HikerFetchException — best-effort라 등록은 그대로 201이어야 한다.
		profileBody = "{\"status\":\"fail\"}";
		singleBody = single("P912", SINGLE_HIT);

		var result = registerPost("P912");

		assertThat(result.status()).isEqualTo("TRACKING");
		assertThat(profileCalls()).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM profile_snapshot WHERE username='owner1'", Long.class)).isZero();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM target WHERE short_code='P912'", Long.class)).isEqualTo(1L);
	}

	@Test
	void 등록_직후_ownerUserId가_없어도_단건_재시도로_백필이_돈다() {
		// 구형 셰이프(user.pk 부재)는 clips를 태울 user_id가 없어 예전엔 백필을 통째로 건너뛰었다 —
		// 단건 재시도는 short_code만 있으면 되므로 이제 clips 없이 단건 복권만 돈다.
		scriptedSingles.addAll(List.of(
				singleNoPk("P904", "\"fb_play_count\":3"),
				singleNoPk("P904", SINGLE_HIT)));

		registerPost("P904");

		assertThat(clipsCalls()).isZero();              // user_id가 없으니 clips는 애초에 못 탄다
		assertThat(singleCalls()).isEqualTo(2);         // 정본 1 + 단건 재시도 당첨 1
		assertThat(snapshotMetric("saves", "P904")).isEqualTo(5L);
		assertThat(snapshotMetric("reposts", "P904")).isEqualTo(7L);
	}
}
