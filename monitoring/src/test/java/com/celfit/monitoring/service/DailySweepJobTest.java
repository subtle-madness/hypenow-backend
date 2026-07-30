package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.HikerFetchException;
import com.celfit.monitoring.hiker.HikerHttp;
import com.celfit.monitoring.hiker.RecordingHikerHttp;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 일일 스윕 배치 — 만료 종결 → 계정당 1회 수집 → 캠페인별 키워드 감지 시 즉시 추적 전환 → 추적 게시물 보강 → 실패 격리.
 * @SpringBootTest 없이 리포지토리 + fake 전송으로 조립한다(서비스 레벨이라 웹 컨텍스트가 불필요).
 */
class DailySweepJobTest {

	/** 스윕 픽스처 한 건 — 열거 응답에 실릴 게시물. takenAt이 null이면 응답에서 taken_at 키 자체가 빠진다. */
	private record FakePost(String code, String caption, Long takenAt) {}

	/**
	 * 스윕 전용 Hiker fake — 계정별로 다른 프로필·열거 응답을 주고 콜 수를 센다.
	 * RegistrationApiTest.SwitchableHiker는 픽스처 1종을 전 계정에 돌려줘서
	 * "계정당 1회"·"한 계정 실패가 다른 계정을 막지 않음"을 검증할 수 없다 — 그래서 따로 둔다.
	 */
	static final class FakeHiker implements HikerHttp {

		private final Map<String, String> userIdByUsername = new HashMap<>();
		private final Map<String, List<FakePost>> postsByUserId = new HashMap<>();
		private final Map<String, FakePost> postByCode = new HashMap<>();
		private final Map<String, String> ownerByCode = new HashMap<>();
		private final Set<String> missingUsernames = new HashSet<>();
		private final Set<String> privateUsernames = new HashSet<>();
		private final Set<String> brokenUsernames = new HashSet<>();
		private final Set<String> missingCodes = new HashSet<>();
		/** 라운드 N번째부터 성공하는 계정 — 스윕 말미 재시도 라운드가 실제로 회복시키는지 본다. */
		private final Map<String, Integer> flakyRemaining = new HashMap<>();

		int profileCalls;
		int mediasCalls;
		int clipsCalls;
		int postCalls;

		/** 정상 계정 등록 — userId는 계정마다 달라야 열거 응답이 계정별로 갈린다. */
		FakeHiker account(String username, String userId, FakePost... posts) {
			userIdByUsername.put(username, userId);
			postsByUserId.put(userId, List.of(posts));
			for (FakePost p : posts) {
				ownerByCode.put(p.code(), username);
				postByCode.put(p.code(), p);
			}
			return this;
		}

		/** 열거 범위 밖(단건 조회로만 닿는) 게시물. */
		FakeHiker standalonePost(String code, String owner, String caption) {
			postByCode.put(code, new FakePost(code, caption, 1_700_000_000L));
			ownerByCode.put(code, owner);
			return this;
		}

		/** 계정 삭제·개명 — 프로필 조회가 404. */
		FakeHiker missingAccount(String username) {
			missingUsernames.add(username);
			return this;
		}

		/** 등록 후 비공개 전환 — 프로필은 응답하지만 is_private이라 열거가 막힌다. */
		FakeHiker privateAccount(String username) {
			privateUsernames.add(username);
			return this;
		}

		/** 일반 수집 실패(5xx·타임아웃) — 재시도 여지가 있는 실패. */
		FakeHiker brokenAccount(String username) {
			brokenUsernames.add(username);
			return this;
		}

		/** 라운드 N번째부터 성공하는 계정 — 스윕 말미 재시도 라운드가 실제로 회복시키는지 본다. */
		FakeHiker flakyAccount(String username, int failures, String userId, FakePost... posts) {
			flakyRemaining.put(username, failures);
			return account(username, userId, posts);
		}

		/** 게시물 삭제 — 단건 조회가 404. */
		FakeHiker missingPost(String code) {
			missingCodes.add(code);
			return this;
		}

		@Override
		public String get(String path) {
			if (path.startsWith("/v2/user/by/username")) {
				profileCalls++;
				String username = param(path, "username");
				if (missingUsernames.contains(username)) {
					throw new SubjectNotFoundException("404 " + username);
				}
				if (brokenUsernames.contains(username)) {
					throw new HikerFetchException("502 " + username);
				}
				Integer remaining = flakyRemaining.get(username);
				if (remaining != null && remaining > 0) {
					flakyRemaining.put(username, remaining - 1);
					throw new HikerFetchException("502 일시 " + username);
				}
				// 비공개는 200 응답의 is_private 플래그다 — 판정은 HikerClient가 한다(fake가 대신 던지지 않는다).
				if (privateUsernames.contains(username)) {
					return "{\"user\":{\"pk\":9,\"username\":\"" + username + "\",\"is_private\":true},\"status\":\"ok\"}";
				}
				return profileJson(username, userIdByUsername.getOrDefault(username, "0"));
			}
			if (path.startsWith("/v2/user/clips")) {
				clipsCalls++;
				return "{\"response\":{\"items\":[],\"paging_info\":{\"more_available\":false}}}";
			}
			if (path.startsWith("/v2/user/medias")) {
				mediasCalls++;
				return mediasJson(postsByUserId.getOrDefault(param(path, "user_id"), List.of()));
			}
			postCalls++;
			String code = param(path, "code");
			if (missingCodes.contains(code) || !postByCode.containsKey(code)) {
				throw new SubjectNotFoundException("404 " + code);
			}
			return postJson(postByCode.get(code), ownerByCode.get(code));
		}

		private static String profileJson(String username, String userId) {
			return """
					{"user":{"pk":%s,"username":"%s","is_private":false,
					"follower_count":1000,"following_count":10,"media_count":42},"status":"ok"}"""
					.formatted(userId, username);
		}

		private static String mediasJson(List<FakePost> posts) {
			String items = posts.stream().map(FakeHiker::itemJson).collect(Collectors.joining(","));
			return "{\"response\":{\"items\":[" + items + "],\"more_available\":false}}";
		}

		private static String postJson(FakePost post, String owner) {
			return """
					{"num_results":1,"more_available":false,"items":[%s],"status":"ok"}"""
					.formatted(itemJson(post, owner));
		}

		private static String itemJson(FakePost post) {
			return itemJson(post, "unused");
		}

		/** 캡션에 따옴표·역슬래시를 쓰지 않는다는 전제 — 픽스처 문자열이라 이스케이프를 생략한다. */
		private static String itemJson(FakePost post, String owner) {
			// taken_at이 null이면 키를 통째로 뺀다 — 실제 응답의 "게시 시각 미상" 셰이프와 같다.
			String takenAt = post.takenAt() == null ? "" : "\"taken_at\":" + post.takenAt() + ",";
			return """
					{"code":"%s","product_type":"clips",%s"caption":{"text":"%s"},
					"like_count":10,"comment_count":2,"play_count":100,"user":{"username":"%s"}}"""
					.formatted(post.code(), takenAt, post.caption(), owner);
		}

		private static String param(String path, String name) {
			for (String pair : path.substring(path.indexOf('?') + 1).split("&")) {
				int eq = pair.indexOf('=');
				if (eq > 0 && pair.substring(0, eq).equals(name)) {
					return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				}
			}
			return null;
		}
	}

	private static final Instant FUTURE = Instant.now().plusSeconds(86_400);
	private static final Instant PAST = Instant.now().minusSeconds(60);
	/** 감지 하한선(target.registered_at = 등록 시점의 now()) 위쪽 게시 시각. */
	private static final long AFTER = Instant.now().plusSeconds(600).getEpochSecond();
	/** 하한선 아래 — 캠페인 등록 전에 올라온 옛 게시물. */
	private static final long BEFORE = Instant.now().minusSeconds(86_400).getEpochSecond();

	JdbcTemplate db;
	TargetRepository targets;
	FakeHiker hiker;
	DailySweepJob job;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		hiker = new FakeHiker();
		var client = new HikerClient(new RecordingHikerHttp(hiker, new RawPayloadRepository(db)));
		var collect = new CollectService(client, new SnapshotWriter(new SnapshotRepository(db)), 1);
		job = new DailySweepJob(targets, collect, 3, Duration.ZERO);
	}

	private static KeywordRule any(String keyword) {
		return new KeywordRule(List.of(), List.of(keyword), List.of());
	}

	private long watching(String username, KeywordRule rule, String key, Instant expiresAt) {
		return targets.insert(TargetType.ACCOUNT, 7L, username, null, rule,
				TargetStatus.WATCHING, null, key, expiresAt);
	}

	private long tracking(String username, String trackedShortCode, String key) {
		return targets.insert(TargetType.ACCOUNT, 7L, username, null, any("무관"),
				TargetStatus.TRACKING, trackedShortCode, key, FUTURE);
	}

	private String trackedOf(long targetId) {
		return db.queryForObject("SELECT tracked_short_code FROM target WHERE id=?", String.class, targetId);
	}

	private long candidateCount() {
		return db.queryForObject("SELECT count(*) FROM detected_candidate", Long.class);
	}

	private TargetStatus statusOf(long targetId) {
		return targets.findById(targetId).orElseThrow().status();
	}

	// ① 만료
	@Test
	void 만료_지난_활성_캠페인은_EXPIRED로_종결되고_수집하지_않는다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		long expired = watching("someuser", any("Rare Beginnings"), "rk-expired", PAST);

		job.run();

		assertThat(statusOf(expired)).isEqualTo(TargetStatus.EXPIRED);
		// 만기 지난 캠페인까지 수집하면 종료된 캠페인만큼 매일 Hiker 콜이 새어 나간다.
		assertThat(hiker.profileCalls).isZero();
		assertThat(trackedOf(expired)).isNull();
	}

	// ② 계정당 1회 수집 + 캠페인별 규칙
	@Test
	void 같은_계정_두_캠페인은_수집_1회_감지는_각자() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상 런칭", AFTER),
				new FakePost("BBB", "오늘의 데일리 메이크업", AFTER - 100));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);
		long b = watching("someuser", any("절대없는키워드zz"), "rk-b", FUTURE);

		job.run();

		// 캠페인 수만큼 수집하면 같은 계정을 여러 번 긁어 콜이 배로 든다 — 관측 대상 단위로 1회.
		assertThat(hiker.profileCalls).isEqualTo(1);
		// 열거 1회는 clips(조회수 보강) + medias 2콜이다 — 과금 단위가 콜이라 이 구성을 못박는다.
		assertThat(hiker.clipsCalls).isEqualTo(1);
		assertThat(hiker.mediasCalls).isEqualTo(1);
		// 감지 즉시 추적 전환 — 승인 대기 단계가 없다(스펙 §2-2).
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(a)).isEqualTo("AAA");
		assertThat(statusOf(b)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(b)).isNull();
		// 스냅샷도 관측 대상 단위 1행(캠페인 2개여도 프로필 1행·게시물 2행).
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot", Long.class)).isEqualTo(2);
		assertThat(db.queryForObject("""
				SELECT last_fetched_at IS NOT NULL FROM target WHERE id=?""", Boolean.class, a)).isTrue();
	}

	/**
	 * 첫 감지 1건 규칙 — 같은 스윕에서 여러 게시물이 걸려도 캠페인:추적 게시물은 1:1이다.
	 * 채택 기준은 taken_at 최신(가장 최근 협찬 게시물이 캠페인의 그것일 확률이 높다).
	 * 열거 순서에 기대면 핀 고정 게시물이 앞에 오는 응답에서 옛 게시물이 뽑힌다.
	 */
	@Test
	void 같은_스윕_다중_매칭은_게시_시각_최신_1건만_추적한다() {
		hiker.account("someuser", "111",
				new FakePost("OLDER", "Rare Beginnings 앵콜", AFTER),
				new FakePost("NEWER", "Rare Beginnings 신상 런칭", AFTER + 100),
				new FakePost("CCC", "무관한 게시물", AFTER + 200));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(trackedOf(a)).isEqualTo("NEWER");
		// 후보 적재는 중단됐다 — detected_candidate에 새 행이 생기면 승인 화면이 되살아난다.
		assertThat(candidateCount()).isZero();
		// 매칭 게시물은 방금 열거에서 스냅샷이 남았다 — 단건 보강 콜이 나가면 콜이 두 배가 된다.
		assertThat(hiker.postCalls).isZero();
	}

	/**
	 * taken_at이 완전히 같으면 열거 순서 말고는 결정 근거가 없다 — API 응답 순서에 기대면
	 * 스윕마다(또는 재정렬 방식이 바뀔 때마다) 채택 게시물이 흔들릴 수 있다.
	 * short_code 사전순(뒤쪽=최대)으로 고정해 결정론을 확보한다.
	 */
	@Test
	void 게시_시각_동률이면_short_code_사전순으로_결정된다() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상", AFTER),
				new FakePost("ZZZ", "Rare Beginnings 앵콜", AFTER));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		// 사전순 뒤쪽(최대)이 승자 — "AAA"가 열거에서 먼저 나와도 결과가 흔들리지 않는다.
		assertThat(trackedOf(a)).isEqualTo("ZZZ");
	}

	/** 전환은 한 번뿐 — 이미 TRACKING인 캠페인은 새 매칭이 떠도 추적 대상이 바뀌지 않는다. */
	@Test
	void 이미_추적_중인_캠페인은_새_매칭으로_갈아치우지_않는다() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상", AFTER),
				new FakePost("BBB", "Rare Beginnings 앵콜", AFTER + 100));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();
		job.run();

		assertThat(trackedOf(a)).isEqualTo("BBB");   // 첫 스윕에서 최신 1건 채택
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
	}

	/**
	 * 감지 하한선 — 등록 시각 이후 게시물만 잡는다(설계 §5, 07-29 확정).
	 * 없으면 첫 스윕에서 등록 전 옛 키워드 게시물을 추적 대상으로 잡아 캠페인이 통째로 헛돈다.
	 */
	@Test
	void 등록_시각_이전_게시물은_키워드가_맞아도_추적되지_않는다() {
		hiker.account("someuser", "111",
				new FakePost("OLDPOST", "Rare Beginnings 신상 런칭", BEFORE),
				new FakePost("NEWPOST", "Rare Beginnings 앵콜", AFTER));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(trackedOf(a)).isEqualTo("NEWPOST");
		// 감지에서 빠졌을 뿐 관측은 한다 — 지표 스냅샷은 등록 전 게시물도 남는다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='OLDPOST'""", Long.class))
				.isEqualTo(1);
	}

	/** 게시 시각을 모르면 하한선 판정 자체가 불가능하다 — 잘못 잡은 추적은 되돌릴 수 없으므로 보수적으로 뺀다. */
	@Test
	void 게시_시각을_모르는_게시물은_보수적으로_추적하지_않는다() {
		hiker.account("someuser", "111", new FakePost("NOTIME", "Rare Beginnings 신상 런칭", null));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(statusOf(a)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(a)).isNull();
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='NOTIME'""", Long.class))
				.isEqualTo(1);
	}

	// ④ 추적 게시물 보강
	@Test
	void 열거_밖으로_밀려난_추적_게시물만_단건_콜로_보강한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", AFTER))
				.standalonePost("OLD9", "someuser", "예전 협찬 게시물");
		long stale = tracking("someuser", "OLD9", "rk-stale");
		long fresh = tracking("someuser", "AAA", "rk-fresh");

		job.run();

		// 열거에 이미 들어온 추적 게시물까지 단건으로 또 부르면 콜이 두 배가 된다.
		assertThat(hiker.postCalls).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='OLD9'""", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='AAA'""", Long.class)).isEqualTo(1);
		assertThat(statusOf(stale)).isEqualTo(TargetStatus.TRACKING);
		assertThat(statusOf(fresh)).isEqualTo(TargetStatus.TRACKING);
	}

	/** POST 등록분만 있는 계정은 열거할 이유가 없다 — 프로필·열거 콜이 나가면 그대로 낭비다. */
	@Test
	void 게시물_단독_캠페인은_열거_없이_단건만_수집한다() {
		hiker.standalonePost("P111", "postowner", "게시물 등록 캡션");
		targets.insert(TargetType.POST, 7L, "postowner", "P111", null,
				TargetStatus.TRACKING, "P111", "rk-post", FUTURE);

		job.run();

		assertThat(hiker.profileCalls).isZero();
		assertThat(hiker.mediasCalls).isZero();
		assertThat(hiker.postCalls).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='P111'""", Long.class)).isEqualTo(1);
	}

	// ⑤ 실패 격리
	@Test
	void 한_계정_수집_오류가_다른_계정_처리를_막지_않는다() {
		hiker.brokenAccount("bad_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", AFTER));
		long bad = watching("bad_user", any("Rare Beginnings"), "rk-bad", FUTURE);
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(trackedOf(good)).isEqualTo("GGG");
		// 일반 실패는 종결하지 않는다 — 다음 스윕에서 재시도할 여지를 남긴다.
		assertThat(statusOf(bad)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(bad)).isNull();
	}

	// ⑥ 404 계정
	@Test
	void 계정_404는_그_계정의_활성_캠페인_전부를_FAILED로_종결한다() {
		hiker.missingAccount("gone_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", AFTER));
		long gone1 = watching("gone_user", any("Rare Beginnings"), "rk-gone1", FUTURE);
		long gone2 = tracking("gone_user", "ZZZ", "rk-gone2");
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(statusOf(gone1)).isEqualTo(TargetStatus.FAILED);
		assertThat(statusOf(gone2)).isEqualTo(TargetStatus.FAILED);
		assertThat(db.queryForObject("SELECT fail_reason FROM target WHERE id=?", String.class, gone1))
				.isEqualTo("SUBJECT_NOT_FOUND");
		// good은 다른 계정 소속이라 계정 실패와 무관하게 정상 감지·자동 전환된다.
		assertThat(statusOf(good)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(good)).isEqualTo("GGG");
	}

	/**
	 * 추적 게시물만 삭제된 경우는 계정 전체 실패와 다르다 — 같은 계정의 다른 캠페인은 멀쩡하다.
	 * 여기서 계정 단위로 묶어 종결하면 게시물 하나 삭제가 남의 캠페인까지 끝낸다.
	 */
	@Test
	void 추적_게시물_404는_해당_캠페인만_FAILED로_종결한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER))
				.missingPost("DEAD1");
		long dead = tracking("someuser", "DEAD1", "rk-dead");
		long alive = watching("someuser", any("Rare Beginnings"), "rk-alive", FUTURE);

		job.run();

		assertThat(statusOf(dead)).isEqualTo(TargetStatus.FAILED);
		assertThat(db.queryForObject("SELECT fail_reason FROM target WHERE id=?", String.class, dead))
				.isEqualTo("SUBJECT_NOT_FOUND");
		// alive는 같은 계정이지만 다른 캠페인이라 정상 감지·자동 전환된다.
		assertThat(statusOf(alive)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(alive)).isEqualTo("AAA");
	}

	/**
	 * 등록 후 비공개로 전환된 계정도 결정적 수집 불가다(설계 §5 "계정 소멸·비공개 등 → FAILED").
	 * 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
	 */
	@Test
	void 비공개_전환_계정은_활성_캠페인_전부를_FAILED_PRIVATE_ACCOUNT로_종결한다() {
		hiker.privateAccount("shy_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", AFTER));
		long shy1 = watching("shy_user", any("Rare Beginnings"), "rk-shy1", FUTURE);
		long shy2 = tracking("shy_user", "ZZZ", "rk-shy2");
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(statusOf(shy1)).isEqualTo(TargetStatus.FAILED);
		assertThat(statusOf(shy2)).isEqualTo(TargetStatus.FAILED);
		// fail_reason 어휘는 계약 §2와 같은 것을 쓴다 — was가 사유별 안내를 갈라 보여준다.
		assertThat(db.queryForList("SELECT fail_reason FROM target WHERE id IN (?,?)",
				String.class, shy1, shy2)).containsOnly("PRIVATE_ACCOUNT");
		assertThat(statusOf(good)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(good)).isEqualTo("GGG");
	}

	/** 콜 카운트 단언이 fake 배선 실수로 0이 되는 걸 막는 최소 가드. */
	@Test
	void fake는_계정별로_다른_열거_응답을_준다() {
		hiker.account("u1", "1", new FakePost("A1", "하나", 1L))
				.account("u2", "2", new FakePost("B1", "둘", 2L), new FakePost("B2", "셋", 3L));
		watching("u1", any("하나"), "rk-1", FUTURE);
		watching("u2", any("둘"), "rk-2", FUTURE);

		job.run();

		assertThat(hiker.profileCalls).isEqualTo(2);
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot WHERE username='u1'",
				Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot WHERE username='u2'",
				Long.class)).isEqualTo(2);
	}

	/**
	 * 일시 오류는 알람이 아니라 재시도 대상이다(스펙 §2-3) — 시스템이 당일 안에 수집을 완수해야 한다.
	 * 라운드가 없으면 5xx 한 번에 그 계정의 하루가 통째로 비고, 캠페인은 상태도 안 바뀌어 아무도 모른다.
	 */
	@Test
	void 일시_실패_계정은_스윕_말미_재시도_라운드에서_회복된다() {
		hiker.flakyAccount("flaky_user", 2, "333",
				new FakePost("FFF", "Rare Beginnings 신상", AFTER));
		long flaky = watching("flaky_user", any("Rare Beginnings"), "rk-flaky", FUTURE);

		job.run();

		assertThat(trackedOf(flaky)).isEqualTo("FFF");
		assertThat(hiker.profileCalls).isEqualTo(3);   // 최초 + 라운드 2
	}

	/** 라운드를 다 써도 안 되면 상태는 그대로 둔다 — 다음날 스윕이 자연 회복시킨다(알람 없음). */
	@Test
	void 라운드를_다_써도_실패하면_상태를_건드리지_않는다() {
		hiker.brokenAccount("bad_user");
		long bad = watching("bad_user", any("Rare Beginnings"), "rk-bad", FUTURE);

		job.run();

		assertThat(statusOf(bad)).isEqualTo(TargetStatus.WATCHING);
		assertThat(hiker.profileCalls).isEqualTo(4);   // 최초 + 라운드 3
	}

	/** 결정적 실패(404·비공개)는 재시도 대상이 아니다 — 라운드에 넣으면 종결이 라운드 수만큼 늦어진다. */
	@Test
	void 계정_404는_재시도_라운드에_들어가지_않는다() {
		hiker.missingAccount("gone_user");
		watching("gone_user", any("Rare Beginnings"), "rk-gone", FUTURE);

		job.run();

		assertThat(hiker.profileCalls).isEqualTo(1);
	}
}
