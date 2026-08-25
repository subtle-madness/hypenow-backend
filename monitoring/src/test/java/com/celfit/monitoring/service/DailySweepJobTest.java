package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.alarm.AlarmEventRepository;
import com.celfit.monitoring.alarm.AlarmEventType;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.HikerFetchException;
import com.celfit.monitoring.hiker.HikerHttp;
import com.celfit.monitoring.hiker.RecordingHikerHttp;
import com.celfit.monitoring.hiker.ShortCodes;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.image.ImageDownloader;
import com.celfit.monitoring.image.ParImageStore;
import com.celfit.monitoring.image.PostThumbnailArchiveJob;
import com.celfit.monitoring.image.ProfileImageArchiveJob;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.ExpiredTarget;
import com.celfit.monitoring.store.PostMetaRepository;
import com.celfit.monitoring.store.ProfileMetaRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.SweepRunRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
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
		private final Map<String, String> commentsJsonByMediaId = new HashMap<>();
		private final Set<String> brokenCommentMediaIds = new HashSet<>();
		/** 라운드 N번째부터 성공하는 계정 — 스윕 말미 재시도 라운드가 실제로 회복시키는지 본다. */
		private final Map<String, Integer> flakyRemaining = new HashMap<>();
		/** 단건 수집(추적 게시물 보강) 경로의 일시 실패 — 열거 밖 추적 게시물도 재시도 라운드가 회복시키는지 본다. */
		private final Map<String, Integer> flakyCodesRemaining = new HashMap<>();

		int profileCalls;
		int mediasCalls;
		int clipsCalls;
		int postCalls;
		int commentsCalls;
		String lastClipsUserId;
		private final java.util.ArrayDeque<String> scriptedClips = new java.util.ArrayDeque<>();

		/** clips 응답을 콜 순서대로 스크립트한다 — 저장·리포스트 세션 복권(꽝→당첨) 재현용. */
		FakeHiker clipsResponses(String... bodies) {
			scriptedClips.addAll(List.of(bodies));
			return this;
		}

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

		/**
		 * 열거(medias) 응답에만 싣는 추가 필드 — 세션이 저장·공유·리포스트 키를 실었다 뺐다 하는
		 * 실제 응답 셰이프(findings §2 계열)를 응답 경로별로 다르게 재현한다. singleOnlyFields와 짝.
		 */
		FakeHiker enumOnlyFields(String code, String jsonFields) {
			enumExtraByCode.put(code, jsonFields);
			return this;
		}

		/** 단건(media/by/code) 응답에만 싣는 추가 필드 — enumOnlyFields의 반대쪽. */
		FakeHiker singleOnlyFields(String code, String jsonFields) {
			singleExtraByCode.put(code, jsonFields);
			return this;
		}

		/**
		 * 단건 응답 추가 필드를 콜 순서대로 스크립트한다 — 단건 콜 세션 복권(꽝→당첨) 재현용.
		 * 빈 문자열은 꽝(추가 필드 없음), 소진되면 singleOnlyFields(고정분)로 돌아간다.
		 */
		FakeHiker singleFieldsResponses(String code, String... perCallFields) {
			singleExtraQueueByCode.computeIfAbsent(code, k -> new java.util.ArrayDeque<>())
					.addAll(List.of(perCallFields));
			return this;
		}

		/** 단건 응답의 user.pk를 뺀다 — ownerUserId를 못 주는 구형 셰이프 재현용. */
		FakeHiker withoutOwnerPk(String code) {
			pkLessCodes.add(code);
			return this;
		}

		private final Map<String, String> enumExtraByCode = new HashMap<>();
		private final Map<String, String> singleExtraByCode = new HashMap<>();
		private final Map<String, java.util.ArrayDeque<String>> singleExtraQueueByCode = new HashMap<>();
		private final Set<String> pkLessCodes = new HashSet<>();

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

		/** 단건 조회(추적 게시물 보강)가 라운드 N번째부터 성공하는 게시물 — standalonePost와 함께 쓴다. */
		FakeHiker flakyPost(String code, int failures) {
			flakyCodesRemaining.put(code, failures);
			return this;
		}

		/** 게시물 삭제 — 단건 조회가 404. */
		FakeHiker missingPost(String code) {
			missingCodes.add(code);
			return this;
		}

		/** 특정 게시물의 댓글 응답을 지정 — shortCode에서 산술 유도되는 media pk로 매핑해 둔다. */
		FakeHiker comments(String shortCode, String commentsJson) {
			commentsJsonByMediaId.put(String.valueOf(ShortCodes.toMediaId(shortCode)), commentsJson);
			return this;
		}

		/** 댓글 콜만 실패(5xx) — 계정·스냅샷 수집은 멀쩡한데 댓글만 격리돼야 하는 시나리오용. */
		FakeHiker brokenComments(String shortCode) {
			brokenCommentMediaIds.add(String.valueOf(ShortCodes.toMediaId(shortCode)));
			return this;
		}

		@Override
		public String get(String path) {
			if (path.startsWith("/v2/media/comments")) {
				commentsCalls++;
				String mediaId = param(path, "id");
				if (brokenCommentMediaIds.contains(mediaId)) {
					throw new HikerFetchException("댓글 500");
				}
				String json = commentsJsonByMediaId.get(mediaId);
				return json != null ? json
						: "{\"response\":{\"comments\":[],\"has_more_comments\":false},\"next_page_id\":null}";
			}
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
				lastClipsUserId = param(path, "user_id");
				// 저장·리포스트 재시도 시나리오용 스크립트 — 비어 있으면 기본(빈 목록)으로 돌아간다.
				if (!scriptedClips.isEmpty()) {
					return scriptedClips.poll();
				}
				return "{\"response\":{\"items\":[],\"paging_info\":{\"more_available\":false}}}";
			}
			if (path.startsWith("/v2/user/medias")) {
				mediasCalls++;
				return mediasJson(postsByUserId.getOrDefault(param(path, "user_id"), List.of()));
			}
			postCalls++;
			String code = param(path, "code");
			Integer remaining = flakyCodesRemaining.get(code);
			if (remaining != null && remaining > 0) {
				flakyCodesRemaining.put(code, remaining - 1);
				throw new HikerFetchException("502 일시 " + code);
			}
			if (missingCodes.contains(code) || !postByCode.containsKey(code)) {
				throw new SubjectNotFoundException("404 " + code);
			}
			return postJson(postByCode.get(code), ownerByCode.get(code));
		}

		private static String profileJson(String username, String userId) {
			return """
					{"user":{"pk":%s,"username":"%s","is_private":false,
					"full_name":"%s Full Name","profile_pic_url":"https://img/%s.jpg",
					"follower_count":1000,"following_count":10,"media_count":42},"status":"ok"}"""
					.formatted(userId, username, username, username);
		}

		private String mediasJson(List<FakePost> posts) {
			String items = posts.stream()
					.map(p -> itemJson(p, "unused", enumExtraByCode.get(p.code())))
					.collect(Collectors.joining(","));
			return "{\"response\":{\"items\":[" + items + "],\"more_available\":false}}";
		}

		private String postJson(FakePost post, String owner) {
			// 단건은 /v2/media/info/by/code의 media_or_ad 셰이프(#337) — 단건 전용 추가 필드(#330)는 유지.
			var queue = singleExtraQueueByCode.get(post.code());
			String extra = queue != null && !queue.isEmpty() ? queue.poll() : singleExtraByCode.get(post.code());
			if (extra != null && extra.isBlank()) {
				extra = null;   // 스크립트의 빈 문자열 = 꽝 세션(추가 필드 없음)
			}
			return """
					{"media_or_ad":%s,"status":"ok"}"""
					.formatted(itemJson(post, owner, extra));
		}

		/** 캡션에 따옴표·역슬래시를 쓰지 않는다는 전제 — 픽스처 문자열이라 이스케이프를 생략한다. */
		private String itemJson(FakePost post, String owner, String extraFields) {
			// taken_at이 null이면 키를 통째로 뺀다 — 실제 응답의 "게시 시각 미상" 셰이프와 같다.
			String takenAt = post.takenAt() == null ? "" : "\"taken_at\":" + post.takenAt() + ",";
			String extra = extraFields == null ? "" : extraFields + ",";
			// user.pk는 소유 계정 IG pk(고정 424242) — 단건형 계정 clips 재시도의 user_id 공급원.
			String user = pkLessCodes.contains(post.code())
					? "{\"username\":\"%s\"}".formatted(owner)
					: "{\"username\":\"%s\",\"pk\":424242}".formatted(owner);
			return """
					{"code":"%s","product_type":"clips",%s%s"caption":{"text":"%s"},
					"like_count":10,"comment_count":2,"play_count":100,"ig_play_count":100,
					"fb_play_count":0,"user":%s}"""
					.formatted(post.code(), takenAt, extra, post.caption(), user);
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

	/**
	 * insert()가 항상 던지는 대장 리포지토리 — 알람 적재 실패가 스윕 본 작업(자동 전환)을
	 * 막지 않고, "일시 실패"로 오분류돼 재시도 라운드에 편입되지도 않는지 검증한다.
	 */
	private static final class ThrowingAlarmEventRepository extends AlarmEventRepository {
		ThrowingAlarmEventRepository() {
			super(null);
		}

		@Override
		public long insert(long targetId, long userId, AlarmEventType type, String payloadJson,
				Instant occurredAt, Instant dispatchAfter) {
			throw new RuntimeException("DB 폭발(테스트)");
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
	SweepRunRepository sweepRuns;
	SnapshotRepository snapshots;
	FakeHiker hiker;
	DailySweepJob job;
	AlarmRecorder alarms;
	/** PAR URL 미설정 = no-op(설계 스펙 §3-1) — 스윕 본 시나리오와 무관해 실제 HTTP를 절대 태우지 않는다. */
	ProfileImageArchiveJob imageArchive;
	/** 위 imageArchive와 동형(트랙 KK 확장) — PAR URL 미설정 no-op. */
	PostThumbnailArchiveJob thumbnailArchive;
	/** 콜 집계 스코프(비용 계상, 2026-08-12 범위 확장) — job 조립 전체가 공유한다. */
	final TargetCallContext callContext = new TargetCallContext();

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		sweepRuns = new SweepRunRepository(db);
		hiker = new FakeHiker();
		// 운영 조립(HikerConfig)과 동형으로 콜 집계 데코레이터를 끼운다 — 스윕의 유저 귀속 스코프까지 검증.
		var client = new HikerClient(new CountingHikerHttp(
				new RecordingHikerHttp(hiker, new RawPayloadRepository(db)),
				new BrandCallContext(), new BrandCallCountRepository(db),
				callContext, new TargetCallCountRepository(db)));
		snapshots = new SnapshotRepository(db);
		alarms = new AlarmRecorder(new AlarmEventRepository(db), targets, snapshots);
		var writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), alarms);
		var collect = new CollectService(client, writer, new CommentRepository(db), snapshots, 1, 1, 1, 0, java.time.Duration.ZERO);
		imageArchive = new ProfileImageArchiveJob(db, new ParImageStore(""), ImageDownloader.http(), "");
		thumbnailArchive = new PostThumbnailArchiveJob(db, new ParImageStore(""), ImageDownloader.http(), "");
		job = new DailySweepJob(targets, collect, alarms, sweepRuns, snapshots, callContext, 3, Duration.ZERO,
				imageArchive, thumbnailArchive);
	}

	private List<String> alarmTypes() {
		return db.queryForList("SELECT event_type FROM alarm_event ORDER BY id", String.class);
	}

	private Boolean hiddenOf(long targetId) {
		return db.queryForObject(
				"SELECT tracked_hidden_at IS NOT NULL FROM target WHERE id=?", Boolean.class, targetId);
	}

	private Boolean fetchFailingOf(long targetId) {
		return db.queryForObject("SELECT fetch_failing FROM target WHERE id=?", Boolean.class, targetId);
	}

	private List<Boolean> sweepRunOks() {
		return db.queryForList("SELECT ok FROM sweep_run ORDER BY id", Boolean.class);
	}

	private static KeywordRule any(String keyword) {
		return new KeywordRule(List.of(), List.of(keyword), List.of());
	}

	private long watching(String username, KeywordRule rule, String key, Instant expiresAt) {
		return watching(username, rule, key, expiresAt, 7L);
	}

	/** userId를 명시하는 오버로드 — 이중 추적 배제 테스트는 유저별 시나리오가 필요하다(null 포함). */
	private long watching(String username, KeywordRule rule, String key, Instant expiresAt, Long userId) {
		return targets.insert(TargetType.ACCOUNT, userId, username, null, rule,
				TargetStatus.WATCHING, null, key, expiresAt);
	}

	private long tracking(String username, String trackedShortCode, String key) {
		return tracking(username, trackedShortCode, key, 7L);
	}

	/** userId를 명시하는 오버로드 — "다른 유저가 이미 추적 중"인 시나리오를 만들 때 쓴다. */
	private long tracking(String username, String trackedShortCode, String key, Long userId) {
		return targets.insert(TargetType.ACCOUNT, userId, username, null, any("무관"),
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

	// ②-보강: 콜 집계(2026-08-12 어드민 크롤링 비용 범위 확장) — 스윕 콜의 유저 귀속.
	@Test
	void 스윕_콜은_계정_공유분은_전원에게_단건분은_소유_유저에게_계상된다() {
		hiker.account("shared", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		watching("shared", any("Rare Beginnings"), "rk-a", FUTURE, 7L);
		watching("shared", any("절대없는키워드zz"), "rk-b", FUTURE, 8L);

		job.run();

		// 실제 콜 5: 계정 공유(프로필+medias+clips) 3 + 유저 7 전용(감지 전환 단건 정본 1, 추적 댓글 1).
		assertThat(hiker.profileCalls + hiker.mediasCalls + hiker.clipsCalls
				+ hiker.postCalls + hiker.commentsCalls).isEqualTo(5);
		// 집계는 "콜 × 서빙 유저" — 공유 3콜은 양쪽 모두, 단건 2콜은 유저 7에게만 계상된다.
		assertThat(db.queryForObject(
				"SELECT coalesce(sum(calls),0) FROM target_call_count WHERE user_id=7", Long.class)).isEqualTo(5);
		assertThat(db.queryForObject(
				"SELECT coalesce(sum(calls),0) FROM target_call_count WHERE user_id=8", Long.class)).isEqualTo(3);
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
		// 감지 당일도 추적 게시물 규칙과 동일 — 채택된 NEWER 1건만 단건 정본 수집(1번 결정, 08-04).
		assertThat(hiker.postCalls).isEqualTo(1);
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

	/**
	 * 추적 게시물은 열거 안에 있어도 단건 1콜을 정본으로 다시 수집한다(1번 결정, 08-04) —
	 * 열거 응답은 세션에 따라 공유·저장·리포스트 키를 실었다 뺐다 하지만(운영 채움율 11~58% 요동),
	 * 단건 응답은 좋아요·댓글·조회·공유 4지표를 확정적으로 준다(08-04 실측 25/25).
	 * 비용은 추적 게시물당 +1콜/일(현 운영 49건 기준 월 +$1.47).
	 */
	@Test
	void 열거_안의_추적_게시물도_단건_콜을_정본으로_지표를_보강한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "협찬 게시물", AFTER))
				.singleOnlyFields("AAA", "\"reshare_count\":5");
		long fresh = tracking("someuser", "AAA", "rk-fresh");

		job.run();

		assertThat(hiker.postCalls).isEqualTo(1);
		// 열거 스냅샷에는 없던 공유수가 단건 응답에서 채워져야 한다 — 같은 날 upsert 덮어쓰기.
		assertThat(db.queryForObject(
				"SELECT shares FROM post_snapshot WHERE short_code='AAA'", Long.class)).isEqualTo(5);
		assertThat(statusOf(fresh)).isEqualTo(TargetStatus.TRACKING);
	}

	/**
	 * 머지는 non-null 우선 — 단건 응답에 빠진 지표를 방금 열거 관측으로 보존한다. 세션 복불복은
	 * 같은 시각에도 응답 경로별로 다르게 걸리므로(08-04 실측: 열거에만 저장수, 단건에만 공유수)
	 * 두 응답이 상호보완돼야 하고, 단건이 열거 값을 null로 덮으면 방금 관측한 값을 유실한다.
	 */
	@Test
	void 단건_응답에_빠진_지표는_같은_스윕의_열거_관측을_보존한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "협찬 게시물", AFTER))
				.enumOnlyFields("AAA", "\"save_count\":7,\"media_repost_count\":2")
				.singleOnlyFields("AAA", "\"reshare_count\":5");
		tracking("someuser", "AAA", "rk-fresh");

		job.run();

		assertThat(db.queryForObject(
				"SELECT saves FROM post_snapshot WHERE short_code='AAA'", Long.class)).isEqualTo(7);
		assertThat(db.queryForObject(
				"SELECT reposts FROM post_snapshot WHERE short_code='AAA'", Long.class)).isEqualTo(2);
		assertThat(db.queryForObject(
				"SELECT shares FROM post_snapshot WHERE short_code='AAA'", Long.class)).isEqualTo(5);
	}

	@Test
	void 열거_밖으로_밀려난_추적_게시물도_단건_콜로_수집한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", AFTER))
				.standalonePost("OLD9", "someuser", "예전 협찬 게시물");
		long stale = tracking("someuser", "OLD9", "rk-stale");
		long fresh = tracking("someuser", "AAA", "rk-fresh");

		job.run();

		// 추적 게시물은 열거 포함 여부와 무관하게 각자 단건 1콜(1번 결정, 08-04) — OLD9 + AAA.
		assertThat(hiker.postCalls).isEqualTo(2);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='OLD9'""", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='AAA'""", Long.class)).isEqualTo(1);
		assertThat(statusOf(stale)).isEqualTo(TargetStatus.TRACKING);
		assertThat(statusOf(fresh)).isEqualTo(TargetStatus.TRACKING);
	}

	/**
	 * POST 등록분만 있는 계정은 열거할 이유가 없다 — 열거(medias·clips)는 그대로 낭비라 안 탄다.
	 * 다만 프로필 콜은 예외다: profile_snapshot 행이 아직 없어 팔로워 1회 수집(트랙 II 후속, 사용자
	 * 결정)이 이 스윕에서 1회 나간다 — {@link #profile_snapshot_행이_이미_있으면_프로필_콜이_다시_나가지_않는다}가
	 * "1회만"의 반대쪽 반쪽을 검증한다.
	 */
	@Test
	void 게시물_단독_캠페인은_열거_없이_단건과_팔로워_1회_수집만_한다() {
		hiker.standalonePost("P111", "postowner", "게시물 등록 캡션");
		targets.insert(TargetType.POST, 7L, "postowner", "P111", null,
				TargetStatus.TRACKING, "P111", "rk-post", FUTURE);

		job.run();

		assertThat(hiker.mediasCalls).isZero();
		assertThat(hiker.clipsCalls).isZero();
		assertThat(hiker.postCalls).isEqualTo(1);
		assertThat(hiker.profileCalls).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='P111'""", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT followers FROM profile_snapshot WHERE username='postowner'", Long.class))
				.isEqualTo(1000L);   // FakeHiker.profileJson의 follower_count 고정값
	}

	// ④-1 저장·리포스트 세션 복권 재시도 배선(08-04) — 재시도 자체의 규칙(상한·당첨 중단·피드 제외)은
	// CollectServiceTest가 검증하고, 여기서는 스윕이 어느 경로로 무엇을 넘기는지(배선)만 본다.
	// 기본 job은 재시도 0(비활성)으로 조립되므로 이 시나리오들만 전용 job을 만든다.

	/** 꽝 세션 clips 응답 — 재생수만 있고 저장·공유·리포스트 키가 없다. */
	private static String clipsMiss(String code) {
		return """
				{"response":{"items":[{"media":{"code":"%s","product_type":"clips",
				"ig_play_count":100}}],"paging_info":{"more_available":false}},"next_page_id":null}"""
				.formatted(code);
	}

	/** 당첨 세션 clips 응답 — 저장 5·공유 9·리포스트 7. */
	private static String clipsHit(String code) {
		return """
				{"response":{"items":[{"media":{"code":"%s","product_type":"clips","ig_play_count":100,
				"save_count":5,"reshare_count":9,"media_repost_count":7}}],
				"paging_info":{"more_available":false}},"next_page_id":null}"""
				.formatted(code);
	}

	/** 저장·리포스트 재시도(상한 6회)를 켠 job — 기본 setUp 조립(비활성 0)과 같은 리포지토리를 공유한다. */
	private DailySweepJob retryEnabledJob() {
		var client = new HikerClient(new RecordingHikerHttp(hiker, new RawPayloadRepository(db)));
		var writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), alarms);
		var collect = new CollectService(client, writer, new CommentRepository(db), snapshots,
				1, 1, 1, 6, Duration.ZERO);
		return new DailySweepJob(targets, collect, alarms, sweepRuns, snapshots, callContext, 3, Duration.ZERO,
				imageArchive, thumbnailArchive);
	}

	private Long snapshotMetric(String column, String shortCode) {
		return db.queryForObject(
				"SELECT " + column + " FROM post_snapshot WHERE short_code=? ORDER BY captured_on DESC LIMIT 1",
				Long.class, shortCode);
	}

	@Test
	void 단건형_계정도_저장리포스트_미관측이면_단건_응답의_pk로_clips_열거를_새로_태운다() {
		// POST 등록만 있는 계정은 열거가 없어 저장·리포스트가 단건 복권(15~23%)에만 걸려 있었다 —
		// 단건 응답 user.pk(424242)를 user_id 삼아 clips를 꽝→당첨까지 재콜한다(사용자 결정 08-04).
		hiker.standalonePost("P555", "postonly_user", "캡션")
				.clipsResponses(clipsMiss("P555"), clipsHit("P555"));
		targets.insert(TargetType.POST, 7L, "postonly_user", "P555", null,
				TargetStatus.TRACKING, "P555", "rk-mr1", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isEqualTo(2);                  // 꽝 1 + 당첨 1, 당첨 즉시 중단
		assertThat(hiker.lastClipsUserId).isEqualTo("424242");      // 단건 응답 user.pk에서 유도
		assertThat(snapshotMetric("saves", "P555")).isEqualTo(5L);
		assertThat(snapshotMetric("reposts", "P555")).isEqualTo(7L);
	}

	@Test
	void 계정형_추적_릴스도_미관측이면_프로필_user_id로_clips_재시도한다() {
		// 열거 계정은 기본 clips 보강 콜(1번째 스크립트)이 꽝이어도 재시도(2번째)가 당첨을 잡는다.
		hiker.account("acct_mr", "777", new FakePost("R777", "캡션", AFTER))
				.clipsResponses(clipsMiss("R777"), clipsHit("R777"));
		targets.insert(TargetType.ACCOUNT, 7L, "acct_mr", null, null,
				TargetStatus.TRACKING, "R777", "rk-mr2", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isEqualTo(2);                  // 열거 기본 보강 1 + 재시도 1
		assertThat(hiker.lastClipsUserId).isEqualTo("777");         // 열거 계정은 프로필 user_id를 쓴다
		assertThat(snapshotMetric("saves", "R777")).isEqualTo(5L);
		assertThat(snapshotMetric("reposts", "R777")).isEqualTo(7L);
	}

	@Test
	void 단건형_계정_추적_릴스가_열거_창_밖이면_단건_재시도로_3지표를_채운다() {
		// clips 응답에 추적 게시물이 없다(최근 릴스 창 밖) — 08-04엔 즉시 포기했지만, 단건 콜도
		// 3키 전부 세션 복권임이 확인돼(08-05 실측) 단건 재시도로 전환한다. 정본 콜(꽝) 뒤
		// 재시도 1콜이 당첨을 잡아 저장·공유·리포스트가 그날 스냅샷에 남아야 한다.
		hiker.standalonePost("P557", "outwin_user", "캡션")
				.clipsResponses(clipsMiss("OTHER"))
				.singleFieldsResponses("P557", "",
						"\"save_count\":5,\"reshare_count\":9,\"media_repost_count\":7");
		targets.insert(TargetType.POST, 7L, "outwin_user", "P557", null,
				TargetStatus.TRACKING, "P557", "rk-mr4", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isEqualTo(1);              // 창 밖 판정 1콜 — clips 재콜은 없다
		assertThat(hiker.postCalls).isEqualTo(2);               // 정본 1 + 단건 재시도 당첨 1
		assertThat(snapshotMetric("saves", "P557")).isEqualTo(5L);
		assertThat(snapshotMetric("shares", "P557")).isEqualTo(9L);
		assertThat(snapshotMetric("reposts", "P557")).isEqualTo(7L);
	}

	@Test
	void 단건_응답에_user_pk가_없어도_단건_재시도는_돈다() {
		// 구형 셰이프(ownerUserId 부재)는 clips를 태울 user_id가 없어 예전엔 보강을 통째로 건너뛰었다 —
		// 단건 재시도는 short_code만 있으면 되므로 이제 clips 없이 단건 복권만 돈다.
		hiker.standalonePost("P558", "nopk_user", "캡션")
				.withoutOwnerPk("P558")
				.singleFieldsResponses("P558", "",
						"\"save_count\":5,\"reshare_count\":9,\"media_repost_count\":7");
		targets.insert(TargetType.POST, 7L, "nopk_user", "P558", null,
				TargetStatus.TRACKING, "P558", "rk-mr5", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isZero();                  // user_id가 없으니 clips는 애초에 못 탄다
		assertThat(hiker.postCalls).isEqualTo(2);               // 정본 1 + 단건 재시도 당첨 1
		assertThat(snapshotMetric("saves", "P558")).isEqualTo(5L);
		assertThat(snapshotMetric("reposts", "P558")).isEqualTo(7L);
	}

	@Test
	void 재시도_소진_시_리포스트_키_부재는_0으로_스냅샷에_남는다() {
		// 리포스트가 0인 게시물은 키 자체가 생략된다(08-05 대조 실험) — 저장·공유만 실리는 세션을
		// 상한까지 만난 뒤에도 리포스트 키가 없으면 0으로 기록해야 스냅샷 공백이 남지 않는다.
		String saveOnly = """
				{"response":{"items":[{"media":{"code":"P560","product_type":"clips",
				"ig_play_count":100,"save_count":5,"reshare_count":9}}],
				"paging_info":{"more_available":false}},"next_page_id":null}""";
		hiker.standalonePost("P560", "zerorepost_user", "캡션")
				.clipsResponses(saveOnly, saveOnly, saveOnly, saveOnly, saveOnly, saveOnly);
		targets.insert(TargetType.POST, 7L, "zerorepost_user", "P560", null,
				TargetStatus.TRACKING, "P560", "rk-mr7", FUTURE);

		retryEnabledJob().run();

		assertThat(snapshotMetric("saves", "P560")).isEqualTo(5L);
		assertThat(snapshotMetric("shares", "P560")).isEqualTo(9L);
		assertThat(snapshotMetric("reposts", "P560")).isEqualTo(0L);
	}

	@Test
	void 전일_0_간주_이력_게시물은_재시도_없이_0을_잇는다() {
		// 리포스트 0 게시물은 키가 영영 안 온다 — 어제 0 간주로 끝났고 양수 이력이 없으면
		// 오늘은 상한까지 헛 재시도를 돌지 않고 캐리 판정이 즉시 0을 기록해야 한다(08-05).
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, saves, shares, reposts)
				VALUES ('carry_user', 'P562', CURRENT_DATE - 1, 'REELS', 4, 9, 0)""");
		hiker.standalonePost("P562", "carry_user", "캡션")
				.singleOnlyFields("P562", "\"save_count\":4,\"reshare_count\":9");   // 오늘도 리포스트 키 부재
		targets.insert(TargetType.POST, 7L, "carry_user", "P562", null,
				TargetStatus.TRACKING, "P562", "rk-mr9", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isZero();                  // 재시도 자체가 안 돈다
		assertThat(snapshotMetric("reposts", "P562")).isEqualTo(0L);
		assertThat(snapshotMetric("saves", "P562")).isEqualTo(4L);
	}

	@Test
	void 공유_숨김_게시물은_공유만_미관측이어도_재시도를_돌지_않는다() {
		// 게시자가 공유 횟수를 숨긴 게시물(share_count_disabled)의 공유는 영영 안 온다 —
		// 재시도 대상에 넣으면 매일 상한까지 헛 콜을 태운다(08-05 실측 숨김 11건).
		hiker.standalonePost("P561", "sharehide_user", "캡션")
				.singleOnlyFields("P561",
						"\"save_count\":4,\"media_repost_count\":7,\"share_count_disabled\":true");
		targets.insert(TargetType.POST, 7L, "sharehide_user", "P561", null,
				TargetStatus.TRACKING, "P561", "rk-mr8", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isZero();                  // 저장·리포스트 완비 + 공유는 숨김 → 재시도 없음
		assertThat(snapshotMetric("saves", "P561")).isEqualTo(4L);
		assertThat(snapshotMetric("shares", "P561")).isNull();  // 숨김은 0이 아니라 비공개(null 유지)
	}

	@Test
	void 공유수만_미관측인_추적_릴스도_재시도_대상이다() {
		// 그날 단건 정본이 부분 세션(저장·리포스트만, 공유 키 부재)이어도 재시도가 돌아야 한다 —
		// 진입 조건이 저장·리포스트만 보면 공유수 단독 누락이 그대로 남는다(08-05 옵션 ③).
		hiker.standalonePost("P559", "sharemiss_user", "캡션")
				.singleOnlyFields("P559", "\"save_count\":4,\"media_repost_count\":7")
				.clipsResponses(clipsHit("P559"));
		targets.insert(TargetType.POST, 7L, "sharemiss_user", "P559", null,
				TargetStatus.TRACKING, "P559", "rk-mr6", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isEqualTo(1);              // 당첨 즉시 중단
		assertThat(snapshotMetric("shares", "P559")).isEqualTo(9L);
		assertThat(snapshotMetric("saves", "P559")).isEqualTo(4L);   // 단건 관측 유지(non-null 우선)
	}

	@Test
	void 피드_추적_게시물은_저장리포스트_재시도_대상이_아니다() {
		// 피드는 저장·공유 키가 전 세션 부재(08-04 실측 0/181) — 재시도를 태워도 헛 콜이다(사용자 결정).
		hiker.standalonePost("P666", "feedonly_user", "캡션")
				.singleOnlyFields("P666", "\"product_type\":\"feed\"");
		targets.insert(TargetType.POST, 7L, "feedonly_user", "P666", null,
				TargetStatus.TRACKING, "P666", "rk-mr3", FUTURE);

		retryEnabledJob().run();

		assertThat(hiker.clipsCalls).isZero();
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

	// ⑥ 404 계정 — v2.2: FAILED 종결 대신 hidden 전이(status 유지, 재공개 감지 대상으로 남는다)
	@Test
	void 계정_404는_그_계정의_활성_캠페인_전부를_hidden_전이한다() {
		hiker.missingAccount("gone_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", AFTER));
		long gone1 = watching("gone_user", any("Rare Beginnings"), "rk-gone1", FUTURE);
		long gone2 = tracking("gone_user", "ZZZ", "rk-gone2");
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		// status는 유지한다 — FAILED 종결이 아니라 hidden 신호(재공개 감지를 위해 findActive에 남는다).
		assertThat(statusOf(gone1)).isEqualTo(TargetStatus.WATCHING);
		assertThat(statusOf(gone2)).isEqualTo(TargetStatus.TRACKING);
		assertThat(hiddenOf(gone1)).isTrue();
		assertThat(hiddenOf(gone2)).isTrue();
		// good은 다른 계정 소속이라 계정 실패와 무관하게 정상 감지·자동 전환된다.
		assertThat(statusOf(good)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(good)).isEqualTo("GGG");
	}

	/**
	 * 추적 게시물만 삭제된 경우는 계정 전체 실패와 다르다 — 같은 계정의 다른 캠페인은 멀쩡하다.
	 * 여기서 계정 단위로 묶어 hidden 전이하면 게시물 하나 삭제가 남의 캠페인까지 건드린다.
	 * v2.2: status TRACKING 유지 + tracked_hidden_at 세팅 + CONTENT_UNAVAILABLE 1회(계약 §3).
	 */
	@Test
	void 추적_게시물_404는_해당_캠페인만_hidden_전이한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER))
				.missingPost("DEAD1");
		long dead = tracking("someuser", "DEAD1", "rk-dead");
		long alive = watching("someuser", any("Rare Beginnings"), "rk-alive", FUTURE);

		job.run();

		assertThat(statusOf(dead)).isEqualTo(TargetStatus.TRACKING);   // status는 유지된다
		assertThat(hiddenOf(dead)).isTrue();
		var alarm = db.queryForMap(
				"SELECT event_type, payload FROM alarm_event WHERE target_id=?", dead);
		assertThat(alarm.get("event_type")).isEqualTo("CONTENT_UNAVAILABLE");
		assertThat(alarm.get("payload").toString()).contains("SUBJECT_NOT_FOUND");
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=?", Long.class, dead)).isEqualTo(1L);
		// alive는 같은 계정이지만 다른 캠페인이라 정상 감지·자동 전환된다.
		assertThat(statusOf(alive)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(alive)).isEqualTo("AAA");
	}

	/**
	 * 이미 hidden인 target이 다음 스윕에서도 같은 사유로 실패하면 markHidden이 전이(false)를
	 * 못 만들어 알람이 재발화하지 않는다(계약 §3 alarm_event "전이 시점에만 1회").
	 */
	@Test
	void 이미_hidden인_target의_반복_실패는_알람을_재발화하지_않는다() {
		hiker.missingAccount("gone_user");
		long gone = watching("gone_user", any("Rare Beginnings"), "rk-gone", FUTURE);

		job.run();
		job.run();

		assertThat(hiddenOf(gone)).isTrue();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=? AND event_type='CONTENT_UNAVAILABLE'",
				Long.class, gone)).isEqualTo(1L);
	}

	/** hidden target이 다음 스윕에서 다시 수집에 성공하면(재공개) tracked_hidden_at이 복귀한다. */
	@Test
	void hidden_target의_수집_성공은_tracked_hidden_at을_복귀시킨다() {
		hiker.missingAccount("flip_user");
		long a = watching("flip_user", any("Rare Beginnings"), "rk-flip", FUTURE);
		job.run();
		assertThat(hiddenOf(a)).isTrue();

		// 재공개 — 다음 스윕부터는 정상 응답한다.
		hiker = new FakeHiker();
		hiker.account("flip_user", "555", new FakePost("REV1", "Rare Beginnings 복귀", AFTER));
		var client = new HikerClient(new RecordingHikerHttp(hiker, new RawPayloadRepository(db)));
		var writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), alarms);
		var collect = new CollectService(client, writer, new CommentRepository(db), snapshots, 1, 1, 1, 0, java.time.Duration.ZERO);
		var revivedJob = new DailySweepJob(targets, collect, alarms, sweepRuns, snapshots, callContext, 3,
				Duration.ZERO, imageArchive, thumbnailArchive);

		revivedJob.run();

		assertThat(hiddenOf(a)).isFalse();
	}

	/**
	 * 등록 후 비공개로 전환된 계정도 결정적 수집 불가다 — v2.2부터는 FAILED 종결이 아니라
	 * 각 캠페인이 개별적으로 hidden 전이한다(status 유지, 캠페인마다 알람 1건).
	 * 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
	 */
	@Test
	void 비공개_전환_계정은_활성_캠페인_전부를_hidden_전이한다() {
		hiker.privateAccount("shy_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", AFTER));
		long shy1 = watching("shy_user", any("Rare Beginnings"), "rk-shy1", FUTURE);
		long shy2 = tracking("shy_user", "ZZZ", "rk-shy2");
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(statusOf(shy1)).isEqualTo(TargetStatus.WATCHING);
		assertThat(statusOf(shy2)).isEqualTo(TargetStatus.TRACKING);
		assertThat(hiddenOf(shy1)).isTrue();
		assertThat(hiddenOf(shy2)).isTrue();
		// 캠페인마다 개별 알람 — payload의 failReason은 계약 §2와 같은 어휘(PRIVATE_ACCOUNT)를 쓴다.
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id IN (?,?) AND event_type='CONTENT_UNAVAILABLE'",
				Long.class, shy1, shy2)).isEqualTo(2L);
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

	// ⑦ 댓글 수집(v1.1)

	private static final String ONE_COMMENT = """
			{"response":{"comments":[{"pk":"999","text":"좋아요","comment_like_count":3,
			"created_at_utc":1700000000,"user":{"username":"fan1"},"preview_child_comments":[]}]},
			"has_more_comments":false,"next_page_id":null}""";

	/** 같은 게시물을 두 캠페인이 추적해도 댓글 콜은 1회다 — 계약 §3 "게시물 단위로 캠페인 간 공유". */
	@Test
	void 같은_게시물을_추적하는_캠페인이_여러_개여도_댓글은_한_번만_수집한다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", AFTER))
				.comments("AAA", ONE_COMMENT);
		tracking("someuser", "AAA", "rk-t1");
		tracking("someuser", "AAA", "rk-t2");

		job.run();

		assertThat(hiker.commentsCalls).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='AAA'", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT author FROM post_comment WHERE short_code='AAA'", String.class)).isEqualTo("fan1");
	}

	private static final String TWO_COMMENTS_UPDATED = """
			{"response":{"comments":[
				{"pk":"999","text":"좋아요","comment_like_count":10,
				 "created_at_utc":1700000000,"user":{"username":"fan1"},"preview_child_comments":[]},
				{"pk":"1000","text":"저도요","comment_like_count":1,
				 "created_at_utc":1700000100,"user":{"username":"fan2"},"preview_child_comments":[]}]},
			"has_more_comments":false,"next_page_id":null}""";

	/**
	 * 게시물당 누적 합집합 upsert — 스윕을 두 번 돌리면 이전 수집분(id)이 보존되고 신규 id가
	 * 추가되며, 같은 id를 재관측하면 like_count 같은 갱신 대상 컬럼만 새 값으로 바뀐다(계약 §3 post_comment).
	 */
	@Test
	void 댓글은_스윕마다_누적_합집합으로_upsert된다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", AFTER))
				.comments("AAA", ONE_COMMENT);
		tracking("someuser", "AAA", "rk-t1");

		job.run();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='AAA'", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT like_count FROM post_comment WHERE short_code='AAA' AND id='999'", Long.class))
				.isEqualTo(3L);

		hiker.comments("AAA", TWO_COMMENTS_UPDATED);
		job.run();

		var ids = db.queryForList(
				"SELECT id FROM post_comment WHERE short_code='AAA' ORDER BY id", String.class);
		assertThat(ids).containsExactly("1000", "999");   // 이전 수집분(999)이 보존되고 신규(1000)가 추가된다
		assertThat(db.queryForObject(
				"SELECT like_count FROM post_comment WHERE short_code='AAA' AND id='999'", Long.class))
				.isEqualTo(10L);   // 재관측 시 like_count가 갱신된다
	}

	/** 댓글 콜 실패는 그 게시물만 격리한다 — 계정 스냅샷·다른 게시물 댓글 수집은 계속된다. */
	@Test
	void 댓글_수집_실패는_해당_게시물만_격리한다() {
		hiker.account("someuser", "111",
						new FakePost("AAA", "최근 게시물", AFTER), new FakePost("BBB", "다른 게시물", AFTER - 10))
				.brokenComments("AAA")
				.comments("BBB", ONE_COMMENT);
		long trackedBad = tracking("someuser", "AAA", "rk-bad");
		tracking("someuser", "BBB", "rk-good");

		job.run();

		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='AAA'", Long.class)).isZero();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='BBB'", Long.class)).isEqualTo(1);
		// 댓글 실패가 계정 스냅샷·캠페인 상태에는 번지지 않는다.
		assertThat(statusOf(trackedBad)).isEqualTo(TargetStatus.TRACKING);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_snapshot WHERE short_code='AAA'", Long.class)).isEqualTo(1);
	}

	/**
	 * v2.2: hidden 전이는 status를 건드리지 않는다 — 이 target은 findActive에 그대로 남아
	 * (재공개 감지를 위해) 댓글 수집 대상에서도 빠지지 않는다. 옛 FAILED 종결 시절엔 상태가
	 * TRACKING에서 빠져 자동으로 제외됐지만, 그 근거 자체가 v2.2에서 사라졌다.
	 */
	@Test
	void 계정_수집_실패로_hidden_전이된_캠페인도_댓글_수집_대상에는_남는다() {
		hiker.missingAccount("gone_user");
		tracking("gone_user", "ZZZ", "rk-gone");

		job.run();

		assertThat(hiker.commentsCalls).isEqualTo(1);
	}

	// ⑧ profile_meta upsert(v1.1)

	@Test
	void 계정_수집마다_profile_meta가_upsert된다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", AFTER));
		watching("someuser", any("무관"), "rk-pm", FUTURE);

		job.run();

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='someuser'");
		assertThat(row.get("display_name")).isEqualTo("someuser Full Name");
		assertThat(row.get("profile_image_url")).isEqualTo("https://img/someuser.jpg");
		assertThat(row.get("last_uploaded_at")).isNotNull();   // AAA의 taken_at(AFTER)에서 유도된 KST 날짜
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

	/**
	 * 열거 밖 추적 게시물 단건 콜의 일시 실패도 재시도 대상이다 — "일시 오류는 당일 안에 무조건
	 * 수집"이라는 요구는 계정 열거뿐 아니라 캠페인별 단건 보강 콜에도 예외 없이 적용돼야 한다.
	 * 계정 갈래(sweepAccount)는 예외 없이 반환하지만 그 안 캠페인 하나가 일시 실패했으므로,
	 * 그 계정 전체가 재시도 라운드에 편입돼야 한다.
	 */
	@Test
	void 추적_게시물_단건_수집의_일시_실패도_재시도_라운드에서_회복된다() {
		hiker.account("someuser", "111", new FakePost("AAA", "최근 게시물", AFTER))
				.standalonePost("OLD9", "someuser", "예전 협찬 게시물")
				.flakyPost("OLD9", 1);
		long stale = tracking("someuser", "OLD9", "rk-stale");

		job.run();

		assertThat(hiker.postCalls).isEqualTo(2);   // 최초 실패 1 + 라운드 성공 1
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='OLD9'""", Long.class)).isEqualTo(1);
		assertThat(statusOf(stale)).isEqualTo(TargetStatus.TRACKING);
	}

	// ⑨ fetch_failing(v2.2) — 재시도 라운드를 다 써도 해소 안 된 일시 오류는 target 단위로 신호한다.

	/**
	 * 핵심 회귀 케이스: 같은 계정의 두 캠페인 중 하나만 라운드 소진까지 실패하면, fetch_failing은
	 * 그 target에만 세팅되고 같은 계정의 성공한 target은 오염되지 않는다(설계 §B-2).
	 */
	@Test
	void 일시_실패_라운드_소진은_해당_target만_fetch_failing으로_전이하고_같은_계정_성공_target은_오염되지_않는다() {
		hiker.account("someuser", "111")   // ACCOUNT 열거 자체는 성공 — 열거 밖 단건 추적 게시물만 문제
				.standalonePost("GOOD1", "someuser", "정상 게시물")
				.standalonePost("BAD1", "someuser", "일시 오류 게시물")
				.flakyPost("BAD1", 999);   // 라운드(3회)를 다 써도 회복 안 함
		long good = tracking("someuser", "GOOD1", "rk-good");
		long bad = tracking("someuser", "BAD1", "rk-bad");

		job.run();

		assertThat(fetchFailingOf(bad)).isTrue();
		assertThat(fetchFailingOf(good)).isFalse();
		assertThat(statusOf(bad)).isEqualTo(TargetStatus.TRACKING);   // status는 유지된다
		var alarm = db.queryForMap("SELECT event_type, payload FROM alarm_event WHERE target_id=?", bad);
		assertThat(alarm.get("event_type")).isEqualTo("CONTENT_UNAVAILABLE");
		assertThat(alarm.get("payload").toString()).contains("FETCH_FAILED");
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=?", Long.class, good)).isEqualTo(0L);
	}

	/** failing target이 다음 스윕에서 성공하면 복귀한다 — 복귀 지점은 touchFetched 하나뿐이다. */
	@Test
	void failing_target의_다음_성공_수집은_fetch_failing을_복귀시킨다() {
		hiker.account("someuser", "111")
				.standalonePost("BAD1", "someuser", "일시 오류 게시물")
				.flakyPost("BAD1", 999);
		long target = tracking("someuser", "BAD1", "rk-bad");

		job.run();
		assertThat(fetchFailingOf(target)).isTrue();

		hiker.flakyPost("BAD1", 0);   // 다음 스윕부터는 정상 응답
		job.run();

		assertThat(fetchFailingOf(target)).isFalse();
	}

	/** 이미 failing인 target이 다음 날도 소진되면 markFetchFailing이 갱신하지 않아 알람이 재발화하지 않는다. */
	@Test
	void 이미_failing인_target의_반복_소진은_알람을_재발화하지_않는다() {
		hiker.account("someuser", "111")
				.standalonePost("BAD1", "someuser", "일시 오류 게시물")
				.flakyPost("BAD1", 999);
		long target = tracking("someuser", "BAD1", "rk-bad");

		job.run();
		job.run();

		assertThat(fetchFailingOf(target)).isTrue();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=? AND event_type='CONTENT_UNAVAILABLE'",
				Long.class, target)).isEqualTo(1L);
	}

	// ⑩ matched_keywords(v2.2) — 감지 자동 전환 시점에 실제 매칭된 키워드를 target에 기록한다.

	@Test
	void 감지_자동_전환_시_matched_keywords가_기록된다() {
		hiker.account("someuser", "111", new FakePost("AAA", "샤넬 립스틱 신상 협찬", AFTER));
		long a = watching("someuser",
				new KeywordRule(List.of("샤넬"), List.of("립스틱", "틴트"), List.of()), "rk-mk", FUTURE);

		job.run();

		String json = db.queryForObject("SELECT matched_keywords::text FROM target WHERE id=?", String.class, a);
		// and 전부(샤넬) + any 중 캡션에 실제 존재한 것(립스틱) — "틴트"는 캡션에 없어 빠진다.
		assertThat(json).contains("샤넬", "립스틱").doesNotContain("틴트");
	}

	// ⑪ sweep_run(v2.2) — was가 max(completed_at) WHERE ok로 마지막 성공 배치 완료 시각을 읽는다.

	@Test
	void sweep_run은_정상_완주시_ok_true_1행을_남긴다() {
		hiker.account("someuser", "111", new FakePost("AAA", "무관 게시물", AFTER));
		watching("someuser", any("무관"), "rk-a", FUTURE);

		job.run();

		var rows = db.queryForList("SELECT started_at, completed_at, ok FROM sweep_run");
		assertThat(rows).hasSize(1);
		var row = rows.getFirst();
		assertThat(row.get("started_at")).isNotNull();
		assertThat(row.get("completed_at")).isNotNull();
		assertThat(row.get("ok")).isEqualTo(true);
	}

	/** expireOverdue가 던지도록 만든 대장 리포지토리 — 스윕 도중 크래시를 흉내낸다. */
	private static final class CrashingExpireTargetRepository extends TargetRepository {
		CrashingExpireTargetRepository(JdbcTemplate db) {
			super(db);
		}

		@Override
		public List<ExpiredTarget> expireOverdue() {
			throw new RuntimeException("DB 폭발(테스트)");
		}
	}

	@Test
	void sweep_run은_도중_크래시시_ok가_true로_남지_않는다() {
		var crashingTargets = new CrashingExpireTargetRepository(db);
		var client = new HikerClient(new RecordingHikerHttp(hiker, new RawPayloadRepository(db)));
		var crashAlarms = new AlarmRecorder(new AlarmEventRepository(db), crashingTargets, snapshots);
		var writer = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), crashAlarms);
		var crashCollect = new CollectService(client, writer, new CommentRepository(db), snapshots, 1, 1, 1, 0, java.time.Duration.ZERO);
		var crashingJob = new DailySweepJob(crashingTargets, crashCollect, crashAlarms, sweepRuns, snapshots,
				callContext, 3, Duration.ZERO, imageArchive, thumbnailArchive);

		assertThatThrownBy(crashingJob::run).isInstanceOf(RuntimeException.class);

		assertThat(db.queryForObject("SELECT ok FROM sweep_run", Boolean.class)).isFalse();
	}

	// ⑫ 만료 vs hidden(v2.2) — 만료는 hidden 신호를 지우지 않는다("만료 후 hidden 유지").

	@Test
	void 만료돼도_tracked_hidden_at은_유지된다() {
		hiker.missingAccount("gone_user");
		long id = watching("gone_user", any("Rare Beginnings"), "rk-gone", FUTURE);
		job.run();
		assertThat(hiddenOf(id)).isTrue();

		targets.updateExpiresAt(id, PAST);
		job.run();

		assertThat(statusOf(id)).isEqualTo(TargetStatus.EXPIRED);
		assertThat(hiddenOf(id)).isTrue();
	}

	@Test
	void 자동_전환은_수집_시작_알람을_아침_레인으로_남긴다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(alarmTypes()).containsExactly("COLLECTION_STARTED");
		assertThat(db.queryForObject("""
				SELECT dispatch_after <> occurred_at FROM alarm_event""", Boolean.class)).isTrue();
	}

	@Test
	void 만료_종결은_수집_종료_알람을_남긴다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		watching("someuser", any("Rare Beginnings"), "rk-expired", PAST);

		job.run();

		assertThat(alarmTypes()).containsExactly("COLLECTION_ENDED");
	}

	/** 계정 소멸·비공개는 재시도로 해소되지 않는다 — 사용자에게 알려야 다른 게시물로 재등록한다. */
	@Test
	void 결정적_실패_종결은_콘텐츠_이용불가_알람을_남긴다() {
		hiker.missingAccount("gone_user").privateAccount("shy_user");
		watching("gone_user", any("Rare Beginnings"), "rk-gone", FUTURE);
		watching("shy_user", any("Rare Beginnings"), "rk-shy", FUTURE);

		job.run();

		assertThat(alarmTypes()).containsExactly("CONTENT_UNAVAILABLE", "CONTENT_UNAVAILABLE");
	}

	/**
	 * 알람 적재 실패가 RuntimeException으로 새면 sweepAccount의 catch(RuntimeException)에 걸려
	 * "일시 실패"로 오분류되고, 그 계정 전체가 재시도 라운드에 편입된다 — 자동 전환은 이미 끝났는데
	 * 계정을 다시 훑어 콜만 새는 것이다. AlarmRecorder가 스스로 삼키므로 이 오분류가 생기지 않는다.
	 */
	@Test
	void 알람_적재가_실패해도_자동_전환은_완료되고_재시도_라운드에_들어가지_않는다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		var throwingAlarms = new AlarmRecorder(new ThrowingAlarmEventRepository(), targets, snapshots);
		var throwingClient = new HikerClient(new RecordingHikerHttp(hiker, new RawPayloadRepository(db)));
		var throwingWriter = new SnapshotWriter(snapshots, new ProfileMetaRepository(db), new PostMetaRepository(db), throwingAlarms);
		var throwingCollect = new CollectService(throwingClient, throwingWriter, new CommentRepository(db), snapshots, 1, 1, 1, 0, java.time.Duration.ZERO);
		var throwingJob = new DailySweepJob(targets, throwingCollect, throwingAlarms, sweepRuns, snapshots,
				callContext, 3, Duration.ZERO, imageArchive, thumbnailArchive);
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		throwingJob.run();

		// 전환(본 작업)은 알람 적재 실패와 무관하게 정상 완료된다.
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(a)).isEqualTo("AAA");
		// 재시도 라운드가 돌았다면 profileCalls가 최초 1콜을 넘어선다 — 여기선 1이어야 오분류가 없다는 뜻.
		assertThat(hiker.profileCalls).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class)).isZero();
	}

	// ⑬ 같은 유저 이중 추적 배제(계약 §6.25) — 감지 후보 중 같은 user_id가 이미 추적 중인 shortcode는 뺀다.

	/** 배제 후 다음 후보가 잡혀야 한다 — 배제가 "감지 자체를 막는" 게 아니라 "그 후보만 빼는" 것임을 검증. */
	@Test
	void 같은_유저가_이미_추적중인_게시물은_감지에서_제외되고_다음_게시물이_잡힌다() {
		hiker.account("someuser", "111",
				new FakePost("A", "Rare Beginnings 신상 런칭", AFTER + 100),   // taken_at 최신 — 배제 없으면 이게 뽑힌다
				new FakePost("B", "Rare Beginnings 앵콜", AFTER));
		tracking("otherusername", "A", "rk-existing", 7L);   // 유저 7이 이미 A를 추적 중
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE, 7L);

		job.run();

		// A는 배제되고 taken_at 다음 순위인 B가 채택된다.
		assertThat(trackedOf(a)).isEqualTo("B");
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
	}

	/**
	 * 가장 중요한 회귀 가드 — 배제를 유저 스코프 없이 전역으로 구현하면 이 테스트가 깨진다.
	 * 브랜드와 대행사가 같은 인플루언서를 각자 시딩하는 건 정상 시나리오다(계약 §6.25).
	 */
	@Test
	void 다른_유저가_추적중인_게시물은_감지에서_제외되지_않는다() {
		hiker.account("someuser", "111", new FakePost("A", "Rare Beginnings 신상 런칭", AFTER));
		tracking("otherusername", "A", "rk-existing", 8L);   // 유저 8이 A를 추적 중
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE, 7L);   // 감지는 유저 7

		job.run();

		// 다른 유저(8)의 추적은 유저 7의 감지에 영향을 주지 않는다.
		assertThat(trackedOf(a)).isEqualTo("A");
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
	}

	/**
	 * hidden도 배제 대상이다(사용자 결정) — 비공개 전환으로 hidden된 행이 재공개 시 tracking으로
	 * 복귀하는데, 그 사이 감지가 새 행을 만들면 이중 추적이 되기 때문이다.
	 */
	@Test
	void 숨김_상태로_추적중인_게시물도_감지에서_제외된다() {
		hiker.account("someuser", "111",
				new FakePost("A", "Rare Beginnings 신상 런칭", AFTER + 100),
				new FakePost("B", "Rare Beginnings 앵콜", AFTER));
		long existing = tracking("otherusername", "A", "rk-existing", 7L);
		targets.markHidden(existing);   // A는 hidden 상태로 추적 중
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE, 7L);

		job.run();

		assertThat(trackedOf(a)).isEqualTo("B");
	}

	/** 후보가 전부 배제되면 이번 스윕은 전환하지 않는다 — WATCHING 유지, 알람도 없다(다음 스윕이 자연 재시도). */
	@Test
	void 후보가_전부_배제되면_WATCHING을_유지하고_알람도_남기지_않는다() {
		hiker.account("someuser", "111", new FakePost("A", "Rare Beginnings 신상 런칭", AFTER));
		tracking("otherusername", "A", "rk-existing", 7L);   // 유일한 후보 A가 이미 유저 7의 추적 대상
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE, 7L);

		job.run();

		assertThat(statusOf(a)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(a)).isNull();
		assertThat(alarmTypes()).doesNotContain("COLLECTION_STARTED");
	}

	/** V3 이전 등록분(user_id null)은 유저 스코프 배제 자체가 불가하다 — 건너뛰고 기존 동작 그대로 감지한다. */
	@Test
	void user_id가_없는_행은_배제_없이_기존대로_감지된다() {
		hiker.account("someuser", "111", new FakePost("A", "Rare Beginnings 신상 런칭", AFTER));
		tracking("otherusername", "A", "rk-existing", 7L);   // A는 유저 7이 이미 추적 중이지만
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE, null);   // 이 행은 userId null

		job.run();

		// 유저 스코프가 없어 배제 판단 자체가 불가 — 기존 동작대로 A가 채택된다.
		assertThat(trackedOf(a)).isEqualTo("A");
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
	}

	// ⑭ 팔로워 1회 수집(트랙 II 후속, 사용자 결정) — POST 등록분만 있는 계정에 profile_snapshot 행이
	// 아직 없을 때만 프로필을 1콜 조회한다. 매일 갱신하지 않고, 실패해도 스윕·캠페인 생존에 영향을 주지 않는다.

	/** "1회만"의 반대쪽 반쪽 — 이미 채워진 계정은 다시 프로필 콜을 태우지 않는다. */
	@Test
	void profile_snapshot_행이_이미_있으면_프로필_콜이_다시_나가지_않는다() {
		hiker.standalonePost("P222", "postowner2", "게시물 등록 캡션");
		targets.insert(TargetType.POST, 7L, "postowner2", "P222", null,
				TargetStatus.TRACKING, "P222", "rk-post2", FUTURE);

		job.run();
		assertThat(hiker.profileCalls).isEqualTo(1);   // 첫 스윕 — 아직 행이 없어 1회 채운다

		job.run();

		// 둘째 스윕에서도 postCalls(단건 게시물 보강)는 매일 그대로 나가지만, 프로필 콜은 늘지 않는다.
		assertThat(hiker.profileCalls).isEqualTo(1);
		assertThat(hiker.postCalls).isEqualTo(2);
	}

	/**
	 * best-effort 검증(가장 중요한 회귀 방어) — 프로필 조회가 어떤 예외로 실패해도 스윕은 계속되고,
	 * 그 계정의 캠페인은 hidden 전이되지 않는다. POST 등록분의 생존 판정은 지금까지처럼 단건 게시물
	 * 수집 성공 여부 하나로만 유지된다 — 프로필 콜 실패가 새 고장 경로가 되면 안 된다.
	 */
	@Test
	void 프로필_조회가_SUBJECT_NOT_FOUND로_실패해도_스윕은_계속되고_캠페인은_hidden_전이되지_않는다() {
		hiker.standalonePost("P333", "profile404_user", "게시물 등록 캡션")
				.missingAccount("profile404_user");
		long post = targets.insert(TargetType.POST, 7L, "profile404_user", "P333", null,
				TargetStatus.TRACKING, "P333", "rk-post3", FUTURE);

		job.run();

		assertThat(hiker.postCalls).isEqualTo(1);   // 게시물 단건 수집은 프로필 실패와 무관하게 성공한다
		assertThat(statusOf(post)).isEqualTo(TargetStatus.TRACKING);
		assertThat(hiddenOf(post)).isFalse();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=?", Long.class, post)).isEqualTo(0L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM profile_snapshot WHERE username='profile404_user'", Long.class)).isZero();
	}

	@Test
	void 프로필_조회가_PRIVATE_ACCOUNT로_실패해도_스윕은_계속되고_캠페인은_hidden_전이되지_않는다() {
		hiker.standalonePost("P444", "profileprivate_user", "게시물 등록 캡션")
				.privateAccount("profileprivate_user");
		long post = targets.insert(TargetType.POST, 7L, "profileprivate_user", "P444", null,
				TargetStatus.TRACKING, "P444", "rk-post4", FUTURE);

		job.run();

		assertThat(hiker.postCalls).isEqualTo(1);
		assertThat(statusOf(post)).isEqualTo(TargetStatus.TRACKING);
		assertThat(hiddenOf(post)).isFalse();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=?", Long.class, post)).isEqualTo(0L);
	}

	@Test
	void 프로필_조회가_일반_오류로_실패해도_스윕은_계속되고_캠페인은_hidden_전이되지_않는다() {
		hiker.standalonePost("P555", "profilebroken_user", "게시물 등록 캡션")
				.brokenAccount("profilebroken_user");
		long post = targets.insert(TargetType.POST, 7L, "profilebroken_user", "P555", null,
				TargetStatus.TRACKING, "P555", "rk-post5", FUTURE);

		job.run();

		assertThat(hiker.postCalls).isEqualTo(1);
		assertThat(statusOf(post)).isEqualTo(TargetStatus.TRACKING);
		assertThat(hiddenOf(post)).isFalse();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM alarm_event WHERE target_id=?", Long.class, post)).isEqualTo(0L);
	}

	/**
	 * ACCOUNT 타입이 섞인 계정은 기존대로 collectAccount(열거 포함)만 타고, 프로필 전용 경로가
	 * 중복 호출되지 않는다 — profileCalls가 1을 넘으면 이 계정에 콜이 두 배로 나간다는 뜻이다.
	 */
	@Test
	void ACCOUNT_타입이_섞인_계정은_열거_경로만_타고_프로필_전용_경로가_중복_호출되지_않는다() {
		hiker.account("mixeduser", "999", new FakePost("MX1", "무관 게시물", AFTER));
		watching("mixeduser", any("절대없는키워드zz"), "rk-mixed-a", FUTURE);
		targets.insert(TargetType.POST, 7L, "mixeduser", "MX1", null,
				TargetStatus.TRACKING, "MX1", "rk-mixed-p", FUTURE);

		job.run();

		assertThat(hiker.profileCalls).isEqualTo(1);
		assertThat(hiker.mediasCalls).isEqualTo(1);
		assertThat(hiker.postCalls).isEqualTo(1);   // MX1은 열거에 포함돼도 단건 정본 수집(1번 결정, 08-04)
		assertThat(db.queryForObject(
				"SELECT count(*) FROM profile_snapshot WHERE username='mixeduser'", Long.class)).isEqualTo(1);
	}
}
