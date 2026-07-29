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
import com.celfit.monitoring.store.CandidateRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * 일일 스윕 배치 — 만료 종결 → 계정당 1회 수집 → 캠페인별 키워드 감지 → 추적 게시물 보강 → 실패 격리.
 * @SpringBootTest 없이 리포지토리 + fake 전송으로 조립한다(서비스 레벨이라 웹 컨텍스트가 불필요).
 */
class DailySweepJobTest {

	/** 스윕 픽스처 한 건 — 열거 응답에 실릴 게시물. */
	private record FakePost(String code, String caption, long takenAt) {}

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
			return """
					{"code":"%s","product_type":"clips","taken_at":%d,"caption":{"text":"%s"},
					"like_count":10,"comment_count":2,"play_count":100,"user":{"username":"%s"}}"""
					.formatted(post.code(), post.takenAt(), post.caption(), owner);
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

	JdbcTemplate db;
	TargetRepository targets;
	CandidateRepository candidates;
	FakeHiker hiker;
	DailySweepJob job;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		candidates = new CandidateRepository(db);
		hiker = new FakeHiker();
		var client = new HikerClient(new RecordingHikerHttp(hiker, new RawPayloadRepository(db)));
		var collect = new CollectService(client, new SnapshotWriter(new SnapshotRepository(db)), 1);
		job = new DailySweepJob(targets, candidates, collect);
	}

	private static KeywordRule any(String keyword) {
		return new KeywordRule(List.of(), List.of(keyword), List.of());
	}

	private long watching(String username, KeywordRule rule, String key, Instant expiresAt) {
		return targets.insert(TargetType.ACCOUNT, username, null, rule,
				TargetStatus.WATCHING, null, key, expiresAt);
	}

	private long tracking(String username, String trackedShortCode, String key) {
		return targets.insert(TargetType.ACCOUNT, username, null, any("무관"),
				TargetStatus.TRACKING, trackedShortCode, key, FUTURE);
	}

	private long candidateCount(long targetId) {
		return db.queryForObject("SELECT count(*) FROM detected_candidate WHERE target_id=?",
				Long.class, targetId);
	}

	private TargetStatus statusOf(long targetId) {
		return targets.findById(targetId).orElseThrow().status();
	}

	// ① 만료
	@Test
	void 만료_지난_활성_캠페인은_EXPIRED로_종결되고_수집하지_않는다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", 1_785_000_000L));
		long expired = watching("someuser", any("Rare Beginnings"), "rk-expired", PAST);

		job.run();

		assertThat(statusOf(expired)).isEqualTo(TargetStatus.EXPIRED);
		// 만기 지난 캠페인까지 수집하면 종료된 캠페인만큼 매일 Hiker 콜이 새어 나간다.
		assertThat(hiker.profileCalls).isZero();
		assertThat(candidateCount(expired)).isZero();
	}

	// ② 계정당 1회 수집 + 캠페인별 규칙
	@Test
	void 같은_계정_두_캠페인은_수집_1회_감지는_각자() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상 런칭", 1_785_000_000L),
				new FakePost("BBB", "오늘의 데일리 메이크업", 1_784_900_000L));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);
		long b = watching("someuser", any("절대없는키워드zz"), "rk-b", FUTURE);

		job.run();

		// 캠페인 수만큼 수집하면 같은 계정을 여러 번 긁어 콜이 배로 든다 — 관측 대상 단위로 1회.
		assertThat(hiker.profileCalls).isEqualTo(1);
		assertThat(hiker.mediasCalls).isEqualTo(1);
		assertThat(candidateCount(a)).isEqualTo(1);
		assertThat(candidateCount(b)).isZero();
		// 스냅샷도 관측 대상 단위 1행(캠페인 2개여도 프로필 1행·게시물 2행).
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot", Long.class)).isEqualTo(2);
		assertThat(db.queryForObject("""
				SELECT last_fetched_at IS NOT NULL FROM target WHERE id=?""", Boolean.class, a)).isTrue();
	}

	// ③ 후보 축적 + 재실행 멱등
	@Test
	void 매칭_게시물은_PENDING_후보로_쌓이고_재실행해도_중복되지_않는다() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상 런칭", 1_785_000_000L),
				new FakePost("BBB", "Rare Beginnings 앵콜", 1_784_900_000L),
				new FakePost("CCC", "무관한 게시물", 1_784_800_000L));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();
		job.run();

		assertThat(candidateCount(a)).isEqualTo(2);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM detected_candidate WHERE status='PENDING'""", Long.class))
				.isEqualTo(2);
		// 캡션 발췌가 비면 was 검토 화면에서 무엇이 걸렸는지 알 수 없다.
		assertThat(db.queryForObject("""
				SELECT caption_excerpt FROM detected_candidate WHERE short_code='AAA'""", String.class))
				.contains("Rare Beginnings");
	}

	// ④ 추적 게시물 보강
	@Test
	void 열거_밖으로_밀려난_추적_게시물만_단건_콜로_보강한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", 1_785_000_000L))
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
		targets.insert(TargetType.POST, "postowner", "P111", null,
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
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", 1_785_000_000L));
		long bad = watching("bad_user", any("Rare Beginnings"), "rk-bad", FUTURE);
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(candidateCount(good)).isEqualTo(1);
		// 일반 실패는 종결하지 않는다 — 다음 스윕에서 재시도할 여지를 남긴다.
		assertThat(statusOf(bad)).isEqualTo(TargetStatus.WATCHING);
		assertThat(candidateCount(bad)).isZero();
	}

	// ⑥ 404 계정
	@Test
	void 계정_404는_그_계정의_활성_캠페인_전부를_FAILED로_종결한다() {
		hiker.missingAccount("gone_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", 1_785_000_000L));
		long gone1 = watching("gone_user", any("Rare Beginnings"), "rk-gone1", FUTURE);
		long gone2 = tracking("gone_user", "ZZZ", "rk-gone2");
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(statusOf(gone1)).isEqualTo(TargetStatus.FAILED);
		assertThat(statusOf(gone2)).isEqualTo(TargetStatus.FAILED);
		assertThat(db.queryForObject("SELECT fail_reason FROM target WHERE id=?", String.class, gone1))
				.isEqualTo("SUBJECT_NOT_FOUND");
		assertThat(statusOf(good)).isEqualTo(TargetStatus.WATCHING);
		assertThat(candidateCount(good)).isEqualTo(1);
	}

	/**
	 * 추적 게시물만 삭제된 경우는 계정 전체 실패와 다르다 — 같은 계정의 다른 캠페인은 멀쩡하다.
	 * 여기서 계정 단위로 묶어 종결하면 게시물 하나 삭제가 남의 캠페인까지 끝낸다.
	 */
	@Test
	void 추적_게시물_404는_해당_캠페인만_FAILED로_종결한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", 1_785_000_000L))
				.missingPost("DEAD1");
		long dead = tracking("someuser", "DEAD1", "rk-dead");
		long alive = watching("someuser", any("Rare Beginnings"), "rk-alive", FUTURE);

		job.run();

		assertThat(statusOf(dead)).isEqualTo(TargetStatus.FAILED);
		assertThat(db.queryForObject("SELECT fail_reason FROM target WHERE id=?", String.class, dead))
				.isEqualTo("SUBJECT_NOT_FOUND");
		assertThat(statusOf(alive)).isEqualTo(TargetStatus.WATCHING);
		assertThat(candidateCount(alive)).isEqualTo(1);
	}

	/**
	 * 등록 후 비공개로 전환된 계정도 결정적 수집 불가다(설계 §5 "계정 소멸·비공개 등 → FAILED").
	 * 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
	 */
	@Test
	void 비공개_전환_계정은_활성_캠페인_전부를_FAILED_PRIVATE_ACCOUNT로_종결한다() {
		hiker.privateAccount("shy_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", 1_785_000_000L));
		long shy1 = watching("shy_user", any("Rare Beginnings"), "rk-shy1", FUTURE);
		long shy2 = tracking("shy_user", "ZZZ", "rk-shy2");
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(statusOf(shy1)).isEqualTo(TargetStatus.FAILED);
		assertThat(statusOf(shy2)).isEqualTo(TargetStatus.FAILED);
		// fail_reason 어휘는 계약 §2와 같은 것을 쓴다 — was가 사유별 안내를 갈라 보여준다.
		assertThat(db.queryForList("SELECT fail_reason FROM target WHERE id IN (?,?)",
				String.class, shy1, shy2)).containsOnly("PRIVATE_ACCOUNT");
		assertThat(statusOf(good)).isEqualTo(TargetStatus.WATCHING);
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
}
