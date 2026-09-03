package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.instagram.source.HikerFetchException;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.instagram.source.SubjectNotFoundException;
import com.celfit.monitoring.service.CollectService;
import com.celfit.monitoring.testsupport.TestDb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// 백필 재시도 스케줄러 틱 방지(2026-09, 결함 3) — @SpringBootTest 컨텍스트에서 5분 주기가 돌지 않게.
@SpringBootTest(properties = "monitoring.brand.backfill-retry.enabled=false")
class RegistrationApiTest {

	/** 경로별 픽스처 fake — 열거는 clips(조회수 머지) → medias 두 콜을 쏜다. notFound=true면 전 경로 404. */
	static class SwitchableHiker implements HikerHttp {
		volatile boolean notFound = false;
		volatile boolean privateAccount = false;
		volatile boolean postWithoutOwner = false;
		/** 프로필·clips는 성공하고 열거(medias)만 터지는 부분 실패 — 이미 나간 콜의 원형이 살아남는지 본다. */
		volatile boolean mediasFail = false;
		/** 게시물 0건 계정 — 프로필은 200이지만 medias 열거가 404 "Entries not found"를 준다. */
		volatile boolean mediasNotFound = false;
		/** POST 등록 직후 즉시 댓글 수집(best-effort)이 실패해도 등록이 성공하는지 보는 스위치. */
		volatile boolean commentsFail = false;
		/**
		 * 응답이 계속 더 있다고(has_more_comments=true) 말하는 댓글 픽스처로 전환 — 등록이
		 * registration-comment-pages(1)만 쓰는지, comment-pages(스윕용, 3)로 새지 않는지를
		 * 콜 횟수로 검증하기 위한 스위치. comments.json은 has_more_comments=false라 페이지
		 * 설정값과 무관하게 항상 1콜로 끝나 이 분리를 검증할 수 없다.
		 */
		volatile boolean commentsHasMorePages = false;
		/** /v2/media/comments 콜 횟수 — 등록/스윕 페이지 수 분리 검증용. */
		volatile int commentsCalls = 0;

		/** 단건 응답에서 user만 빠진 변형 — 소유 계정을 알 수 없어 등록도 스냅샷 적재도 불가한 셰이프. */
		private static final String POST_WITHOUT_OWNER = """
				{"media_or_ad":{"code":"DbV7LgZsKG8","product_type":"clips",
				"taken_at":1785254651,"like_count":1,"comment_count":1,"media_repost_count":1},"status":"ok"}""";

		private static String fixture(String name) {
			try (var in = RegistrationApiTest.class.getResourceAsStream("/hiker/" + name)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public String get(String path) {
			if (notFound) {
				throw new SubjectNotFoundException("404");
			}
			if (path.startsWith("/v2/user/by/username")) {
				return privateAccount ? "{\"user\":{\"pk\":1,\"is_private\":true},\"status\":\"ok\"}"
						: fixture("profile.json");
			}
			if (path.startsWith("/v2/user/medias")) {
				if (mediasFail) {
					throw new HikerFetchException("열거 실패");
				}
				if (mediasNotFound) {
					throw new SubjectNotFoundException("404 Entries not found");
				}
				return fixture("medias.json");
			}
			if (path.startsWith("/v2/user/clips")) {
				if (mediasNotFound) {
					// 게시물 0건 계정은 릴스도 0건이라 clips도 같은 404를 준다 — clips가 살아 있으면
					// 그 릴스들이 그리드 숨김 릴스로 열거에 합류해 "0건"이라는 전제가 깨진다(08-07).
					throw new SubjectNotFoundException("404 Entries not found");
				}
				return fixture("clips.json");
			}
			if (path.startsWith("/v2/media/comments")) {
				commentsCalls++;
				if (commentsFail) {
					throw new HikerFetchException("댓글 수집 실패");
				}
				if (commentsHasMorePages) {
					// pk를 콜 순번으로 바꿔 무진전 가드에 걸리지 않게 한다 — 페이지 수 설정이 실제로
					// 콜 횟수를 좌우하는지만 보면 되므로 다른 필드는 최소로 채운다.
					return """
							{"response":{"comments":[
							{"pk":"multi-%d","text":"댓글","comment_like_count":1,
							 "created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}
							],"has_more_comments":true},"next_page_id":"cursor-%d"}"""
							.formatted(commentsCalls, commentsCalls);
				}
				return fixture("comments.json");
			}
			return postWithoutOwner ? POST_WITHOUT_OWNER : fixture("media-info-by-code.json");
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		SwitchableHiker fakeHiker() {
			return new SwitchableHiker();
		}
	}

	@DynamicPropertySource
	static void dbProps(DynamicPropertyRegistry r) {
		var pg = TestDb.container();
		r.add("spring.datasource.url", pg::getJdbcUrl);
		r.add("spring.datasource.username", pg::getUsername);
		r.add("spring.datasource.password", pg::getPassword);
	}

	@Autowired WebApplicationContext ctx;
	@Autowired JdbcTemplate db;
	@Autowired SwitchableHiker hiker;
	@Autowired CollectService collectService;
	MockMvc mvc;

	private static final String ACCOUNT_BODY = """
			{"registrationKey":"rk-1","type":"ACCOUNT","userId":7,"username":"someuser",
			 "keywordRule":{"and":[],"any":["샤넬"],"exclude":[]},
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";

	private static final String POST_BODY = """
			{"registrationKey":"rk-post","type":"POST","userId":7,"shortCode":"DbV7LgZsKG8",
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		db.update("DELETE FROM detected_candidate");
		db.update("DELETE FROM alarm_event");
		db.update("DELETE FROM target");
		// 스냅샷·원형도 비운다 — 다른 테스트 클래스(StoreTest)가 남긴 행이 섞이면
		// 아래 절대값 단언(1행·2행)이 실행 순서에 따라 흔들린다.
		db.update("DELETE FROM profile_snapshot");
		db.update("DELETE FROM post_snapshot");
		db.update("DELETE FROM post_comment");
		db.update("DELETE FROM raw.fetch_payload");
		hiker.notFound = false;
		hiker.privateAccount = false;
		hiker.postWithoutOwner = false;
		hiker.mediasFail = false;
		hiker.mediasNotFound = false;
		hiker.commentsFail = false;
		hiker.commentsHasMorePages = false;
		hiker.commentsCalls = 0;
	}

	/**
	 * external.call 히스토그램 버킷 노출 회귀 고정(2026-08-23) — yml의 percentiles-histogram
	 * 바인딩과 프로메테우스 지표명(external_call_seconds_bucket)이 대시보드 PromQL의 전제다.
	 * 어느 쪽이 깨져도 앱은 멀쩡히 뜨고 패널만 영구 공백이 되므로 테스트로만 잡을 수 있다
	 * (was ActuatorPrometheusTest의 http_server_requests 버킷 단언과 같은 취지).
	 */
	@Test
	void 프로메테우스_엔드포인트가_외부_콜_히스토그램_버킷을_노출한다() throws Exception {
		// 등록 시도로 외부(fake) 콜을 최소 1건 유발 — 버킷 라인은 첫 관측 후에만 나타난다.
		// 등록 자체의 성패는 이 테스트의 관심사가 아니다(프로필 콜 1건이면 충분).
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY));

		mvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("external_call_seconds_bucket")));
	}

	/**
	 * 수집이 도중에 터져도 이미 나간 콜의 원형은 남아야 한다 — 감사·재파싱의 근거이자
	 * 장애 때 "어디까지 받았나"를 되짚는 유일한 기록이다.
	 *
	 * <p>이 단언은 트랜잭션 구조를 판별한다: CollectService가 {@code @Transactional}로 fetch까지
	 * 감싸던 시절에는 열거 실패가 앞서 성공한 프로필·clips의 원형 적재까지 롤백해 0행이 됐다.
	 * 지금은 fetch가 트랜잭션 밖이라(쓰기만 SnapshotWriter가 묶는다) 원형이 살아남는다.
	 * 프록시가 실제로 붙는 스프링 컨텍스트에서 확인해야 의미가 있어 여기에 둔다.
	 */
	@Test
	void 열거_실패로_등록이_502여도_이미_받은_원형은_남는다() throws Exception {
		hiker.mediasFail = true;

		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("FETCH_FAILED"));

		assertThat(db.queryForObject("""
				SELECT count(*) FROM raw.fetch_payload WHERE kind='PROFILE' AND subject='someuser'""",
				Long.class)).isEqualTo(1);
		// 열거는 clips(조회수 보강) → medias 순서라 clips 1콜은 성공해 있다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM raw.fetch_payload WHERE kind='POSTS'""", Long.class)).isEqualTo(1);
		// 원형이 남는 것과 별개로 캠페인은 만들어지지 않는다 — 첫 수집이 끝나야 등록이다.
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
		// 프로필 스냅샷도 없다 — 수집 1회분은 SnapshotWriter가 한 트랜잭션으로 묶어 통째로 커밋한다.
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isZero();
	}

	/**
	 * 게시물 0건 계정 — Hiker는 프로필(200)은 정상 응답하지만 열거(medias)에 404
	 * {"detail":"Entries not found"}를 준다(릴스 0건 계정이 /v2/user/clips에서 겪는 것과 동일 규칙).
	 * HikerBackend.fetchRecentPosts가 이 404를 빈 리스트로 강등하지 않으면 SubjectNotFoundException이
	 * 전파돼 등록이 404 SUBJECT_NOT_FOUND로 죽고 target 행이 생기지 않는다(실제 계정 wo_om3 재현 사례).
	 */
	@Test
	void 게시물_0건_계정도_WATCHING으로_등록되고_target_행이_생긴다() throws Exception {
		hiker.mediasNotFound = true;

		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WATCHING"))
				.andExpect(jsonPath("$.firstSnapshot.recentPostCount").value(0));
		assertThat(db.queryForObject("SELECT count(*) FROM target WHERE registration_key='rk-1'", Long.class))
				.isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot", Long.class)).isZero();
	}

	@Test
	void 계정_등록은_동기_첫_수집까지_하고_201() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WATCHING"))
				.andExpect(jsonPath("$.firstSnapshot.profile.followers").isNumber());
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		// 원형은 콜 단위로 남는다 — 프로필 1콜 + 열거 2콜(clips 조회수 보강 + medias)
		// + FB 몫 재시도 1콜(등록 = 최초 수집인데 clips 픽스처가 fb 키 없는 IG 전용 세션 셰이프라
		// 신규 릴스의 fb 미관측 → clips 1회 재조회, findings §2 결론 4).
		// 파싱 결과에 실린 body(구 PostInfo.rawJson — 08-12 제거)를 저장하던 시절엔 clips 응답이 통째로 감사에서 빠졌다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM raw.fetch_payload WHERE kind='PROFILE' AND subject='someuser'""",
				Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM raw.fetch_payload WHERE kind='POSTS'""", Long.class)).isEqualTo(3);
		// 키워드 규칙은 was가 조회 표면에서 그대로 읽는 컬럼이다 — 요청 그대로 jsonb에 실려야 한다
		assertThat(db.queryForObject("""
				SELECT keyword_rule ->> 'any' FROM target WHERE registration_key='rk-1'""", String.class))
				.contains("샤넬");
		// +09:00 오프셋이 UTC로 옮겨져 저장되는지 — 여기가 틀리면 만료 스윕이 9시간 어긋난다
		assertThat(db.queryForObject("""
				SELECT expires_at = timestamptz '2026-12-31T15:00:00Z' FROM target
				WHERE registration_key='rk-1'""", Boolean.class)).isTrue();
		assertThat(db.queryForObject("""
				SELECT last_fetched_at IS NOT NULL FROM target WHERE registration_key='rk-1'""",
				Boolean.class)).isTrue();
	}

	@Test
	void 같은_registrationKey는_replay_200_행_1개_재수집_없음() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());
		Long rawAfterFirst = db.queryForObject("SELECT count(*) FROM raw.fetch_payload", Long.class);

		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isOk())
				// replay는 Hiker를 다시 부르지 않으므로 돌려줄 첫 스냅샷이 없다(계약 §2-1).
				// jsonPath().doesNotExist()는 `"firstSnapshot":null`도 통과시키므로(JsonPath가 null을 반환)
				// 키가 정말 빠졌는지는 본문 문자열로 못박는다.
				.andExpect(jsonPath("$.firstSnapshot").doesNotExist())
				.andExpect(content().string(not(containsString("firstSnapshot"))));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isEqualTo(1);
		// 원형 적재 행이 안 늘었다 = 콜이 안 나갔다. replay가 조용히 재수집하면 재시도마다 과금된다.
		assertThat(db.queryForObject("SELECT count(*) FROM raw.fetch_payload", Long.class))
				.isEqualTo(rawAfterFirst);
	}

	@Test
	void 키워드_and_any_모두_비면_400_VALIDATION() throws Exception {
		String bad = ACCOUNT_BODY.replace("\"any\":[\"샤넬\"]", "\"any\":[]");
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	/** userId가 없으면 알람 수신자를 영원히 알 수 없다 — 등록 자체를 막는다(계약 v2 §2-1 필수 필드). */
	@Test
	void userId_누락은_400_VALIDATION_target_미생성() throws Exception {
		String bad = ACCOUNT_BODY.replace("\"userId\":7,", "");
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	@Test
	void 등록된_캠페인에_user_id가_실린다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());
		assertThat(db.queryForObject("""
				SELECT user_id FROM target WHERE registration_key='rk-1'""", Long.class)).isEqualTo(7L);
	}

	@Test
	void 계정_없음은_404_SUBJECT_NOT_FOUND_target_미생성() throws Exception {
		hiker.notFound = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	/** 비공개는 "없음"과 다르다 — 계정은 실재하지만 열거가 막힌 것이라 422로 구분해 내려야 한다. */
	@Test
	void 비공개_계정은_422_PRIVATE_ACCOUNT_target_미생성() throws Exception {
		hiker.privateAccount = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("PRIVATE_ACCOUNT"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	/** 소유 계정 없는 단건 응답은 셰이프 이상이다 — DB NOT NULL 위반(500)이 아니라 502로 나가야 한다. */
	@Test
	void 소유_계정_없는_게시물_응답은_502_FETCH_FAILED() throws Exception {
		hiker.postWithoutOwner = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("FETCH_FAILED"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	/**
	 * 스윕 스케줄러가 실제 컨텍스트에 뜨는지 — 별도 @SpringBootTest 클래스를 새로 세우면
	 * 컨텍스트를 하나 더 띄우게 되므로 이미 뜬 여기에 얹는다. 잡히는 회귀는 둘이다:
	 * @Component가 빠져 크론이 조용히 안 도는 것, 그리고 application.yml의 sweep-cron 값이
	 * 크론으로 파싱 불가라 컨텍스트가 뜨다 죽는 것(그 경우 이 클래스 전체가 실패한다).
	 */
	@Test
	void 스윕_스케줄러는_기본_설정에서도_빈으로_뜬다() {
		assertThat(ctx.getBean(com.celfit.monitoring.service.SweepScheduler.class)).isNotNull();
	}

	/** 캐치올 advice가 프레임워크 4xx를 500으로 강등하면 배선 오류를 장애로 오인하게 된다. */
	@Test
	void 없는_경로는_500이_아니라_404로_남는다() throws Exception {
		mvc.perform(post("/api/none")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}

	/** POST 등록은 승인 절차가 없다 — 등록 즉시 TRACKING이고 추적 게시물이 곧 등록한 숏코드다. */
	@Test
	void 게시물_등록은_바로_TRACKING이고_post_snapshot을_남긴다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("TRACKING"))
				.andExpect(jsonPath("$.firstSnapshot.post.likes").isNumber());
		assertThat(db.queryForObject("""
				SELECT tracked_short_code FROM target WHERE registration_key='rk-post'""", String.class))
				.isEqualTo("DbV7LgZsKG8");
		// POST 등록도 소유 계정을 기록한다(계약 §3 target.username — 사용자가 준 건 숏코드뿐이라 응답에서 얻는다)
		assertThat(db.queryForObject("""
				SELECT username FROM target WHERE registration_key='rk-post'""", String.class))
				.isEqualTo("sephora");
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='DbV7LgZsKG8'""", Long.class))
				.isEqualTo(1);
	}

	/**
	 * 게시물 직접 등록은 그 자리에서 수집이 시작된다 — alarm_event 원장에는 아침 레인으로 적재된다
	 * (즉시 레인은 2026-08-27 주간 개편에서 폐지됐다, 설계 §2 — 소비가 주간 다이제스트 하나뿐).
	 */
	@Test
	void 게시물_등록은_수집_시작_알람을_아침_레인으로_남긴다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated());

		assertThat(db.queryForObject("""
				SELECT event_type FROM alarm_event""", String.class)).isEqualTo("COLLECTION_STARTED");
		assertThat(db.queryForObject("""
				SELECT dispatch_after <> occurred_at FROM alarm_event""", Boolean.class)).isTrue();
		assertThat(db.queryForObject("SELECT user_id FROM alarm_event", Long.class)).isEqualTo(7L);
	}

	/**
	 * 등록 직후 최대 24시간 댓글 공백을 없애는 핵심 동작 — POST 등록은 collectPost 직후
	 * collectComments까지 동기로 호출해 그 자리에서 post_comment를 채운다.
	 */
	@Test
	void 게시물_등록은_댓글도_즉시_수집한다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated());

		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_comment WHERE short_code='DbV7LgZsKG8'""", Long.class))
				.isEqualTo(6);   // comments.json 픽스처의 유효 댓글(top-level) 개수
	}

	/**
	 * 등록/스윕 페이지 수 분리(07-31) — application.yml의 comment-pages(스윕용)는 3이지만
	 * 등록은 registration-comment-pages(1)만 써야 한다. has_more_comments=true로 계속 더
	 * 있다고 응답해도 등록 경로에서 콜이 1회로 끝나는지로 이 분리가 실제 배선까지 지켜지는지 본다.
	 */
	@Test
	void 게시물_등록의_댓글_수집은_스윕_페이지_수와_무관하게_1페이지만_부른다() throws Exception {
		hiker.commentsHasMorePages = true;

		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated());

		assertThat(hiker.commentsCalls).isEqualTo(1);
	}

	/**
	 * 위 테스트의 반대쪽 — application.yml의 comment-pages(스윕용)=3이 실 배선된 CollectService
	 * 빈에 실제로 들어갔는지 확인한다. 등록 경로가 1로 멈추는 것만 봐서는 "설정을 안 읽어서 우연히
	 * 1"인지 "3인데 등록만 1로 분리된 것"인지 구별이 안 된다 — 스윕용 메서드를 직접 호출해 3을 본다.
	 */
	@Test
	void 스윕용_댓글_수집은_설정된_comment_pages_3을_쓴다() {
		hiker.commentsHasMorePages = true;

		collectService.collectComments("DbV7LgZsKG8", "sephora");

		assertThat(hiker.commentsCalls).isEqualTo(3);
	}

	/** 댓글 수집은 best-effort다 — 실패해도 등록 자체는 201로 성공해야 한다(당일 스윕이 백스톱). */
	@Test
	void 댓글_수집_실패는_게시물_등록을_막지_않는다() throws Exception {
		hiker.commentsFail = true;

		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("TRACKING"));

		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_comment WHERE short_code='DbV7LgZsKG8'""", Long.class))
				.isZero();
	}

	/** 계정 등록은 아직 수집 시작이 아니다(WATCHING) — 여기서 알람이 나가면 "시작"이 두 번 온다. */
	@Test
	void 계정_등록은_알람을_남기지_않는다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());

		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class)).isZero();
	}

	/** replay(200)는 새 캠페인이 아니다 — 재시도마다 알람이 쌓이면 사용자가 같은 메일을 반복해 받는다. */
	@Test
	void 같은_키_replay는_알람을_추가로_남기지_않는다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isOk());

		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class)).isEqualTo(1);
	}
}
