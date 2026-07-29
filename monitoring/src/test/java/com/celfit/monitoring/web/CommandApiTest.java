package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.store.CandidateRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** 명령 API 4종(승인·거절·연장·해지) — 계약 §2-2~2-5의 상태 전이 규칙. */
@SpringBootTest
class CommandApiTest {

	/** Hiker 픽스처 fake는 등록 테스트와 같은 것을 쓴다 — 승인 시 1회 수집이 media-by-code 경로를 탄다. */
	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		RegistrationApiTest.SwitchableHiker fakeHiker() {
			return new RegistrationApiTest.SwitchableHiker();
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
	@Autowired TargetRepository targets;
	@Autowired CandidateRepository candidates;
	@Autowired RegistrationApiTest.SwitchableHiker hiker;
	MockMvc mvc;

	/** 픽스처(media-by-code.json)의 숏코드 — 승인 후 적재되는 post_snapshot 행과 맞물린다. */
	private static final String SHORT_CODE = "DbV7LgZsKG8";
	private static final KeywordRule RULE = new KeywordRule(List.of(), List.of("샤넬"), List.of());
	private static final Instant EXPIRES = Instant.now().plus(30, ChronoUnit.DAYS);

	private final AtomicInteger keySeq = new AtomicInteger();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		db.update("DELETE FROM detected_candidate");
		db.update("DELETE FROM target");
		// 다른 테스트 클래스가 남긴 스냅샷·원형이 섞이면 아래 절대값 단언이 실행 순서에 따라 흔들린다.
		db.update("DELETE FROM profile_snapshot");
		db.update("DELETE FROM post_snapshot");
		db.update("DELETE FROM raw.fetch_payload");
		hiker.notFound = false;
	}

	private long seedTarget(TargetStatus status, String trackedShortCode) {
		return targets.insert(TargetType.ACCOUNT, "someuser", null, RULE, status, trackedShortCode,
				"rk-" + keySeq.incrementAndGet(), EXPIRES);
	}

	/** 후보는 감지 배치를 거치지 않고 직접 심는다 — 이 테스트가 검증하는 건 승인/거절 전이뿐이다. */
	private long seedCandidate(long targetId, String shortCode) {
		candidates.insertPending(targetId, shortCode, "샤넬 립스틱 발색샷");
		return db.queryForObject("""
				SELECT id FROM detected_candidate WHERE target_id=? AND short_code=?""",
				Long.class, targetId, shortCode);
	}

	private String targetStatus(long targetId) {
		return db.queryForObject("SELECT status FROM target WHERE id=?", String.class, targetId);
	}

	private String candidateStatus(long candidateId) {
		return db.queryForObject("SELECT status FROM detected_candidate WHERE id=?", String.class,
				candidateId);
	}

	@Test
	void 승인은_TRACKING_전환과_즉시_1회_수집까지_한다() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);
		long candidateId = seedCandidate(targetId, SHORT_CODE);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, candidateId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetId").value(targetId))
				.andExpect(jsonPath("$.status").value("TRACKING"))
				.andExpect(jsonPath("$.trackedShortCode").value(SHORT_CODE));

		assertThat(targetStatus(targetId)).isEqualTo("TRACKING");
		assertThat(db.queryForObject("SELECT tracked_short_code FROM target WHERE id=?", String.class,
				targetId)).isEqualTo(SHORT_CODE);
		assertThat(candidateStatus(candidateId)).isEqualTo("APPROVED");
		// 승인 즉시 수집이 실제로 돌았다는 증거 — 스냅샷 행과 마지막 수집 시각.
		// 여기가 비면 승인 직후 화면에 지표가 하나도 없고 다음 02:00 스윕까지 빈 카드가 남는다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code=?""", Long.class, SHORT_CODE))
				.isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT last_fetched_at IS NOT NULL FROM target WHERE id=?""", Boolean.class, targetId))
				.isTrue();
	}

	@Test
	void 거절은_후보만_닫고_캠페인은_WATCHING을_지속한다() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);
		long candidateId = seedCandidate(targetId, SHORT_CODE);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/reject", targetId, candidateId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.candidateId").value(candidateId))
				.andExpect(jsonPath("$.status").value("REJECTED"));

		assertThat(candidateStatus(candidateId)).isEqualTo("REJECTED");
		assertThat(targetStatus(targetId)).isEqualTo("WATCHING");
		// 거절은 수집을 부르지 않는다 — 콜 과금과 직결된다.
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot", Long.class)).isZero();
	}

	/** 이미 추적 중인 캠페인에 또 승인하면 추적 대상이 조용히 바뀐다 — 409로 막는다. */
	@Test
	void 이미_TRACKING인_캠페인_승인은_409_INVALID_STATE() throws Exception {
		long targetId = seedTarget(TargetStatus.TRACKING, "DoldTracked");
		long candidateId = seedCandidate(targetId, SHORT_CODE);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, candidateId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE"));

		assertThat(db.queryForObject("SELECT tracked_short_code FROM target WHERE id=?", String.class,
				targetId)).isEqualTo("DoldTracked");
		assertThat(candidateStatus(candidateId)).isEqualTo("PENDING");
	}

	/** 거절은 되돌릴 수 없다 — 같은 후보를 다시 승인하면 사용자가 닫은 판단이 조용히 뒤집힌다. */
	@Test
	void 이미_거절한_후보_재승인은_409_INVALID_STATE() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);
		long candidateId = seedCandidate(targetId, SHORT_CODE);
		mvc.perform(post("/api/targets/{id}/candidates/{cid}/reject", targetId, candidateId))
				.andExpect(status().isOk());

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, candidateId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE"));

		assertThat(candidateStatus(candidateId)).isEqualTo("REJECTED");
		assertThat(targetStatus(targetId)).isEqualTo("WATCHING");
	}

	/**
	 * 승인 중 수집이 실패하면 전량 롤백 — 후보는 PENDING, 캠페인은 WATCHING으로 남아야
	 * was가 같은 명령을 그대로 재시도할 수 있다. 부분 커밋되면 "TRACKING인데 지표가 없는" 캠페인이 굳는다.
	 */
	@Test
	void 승인_중_수집_실패는_전량_롤백된다() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);
		long candidateId = seedCandidate(targetId, SHORT_CODE);
		hiker.notFound = true;

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, candidateId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));

		assertThat(candidateStatus(candidateId)).isEqualTo("PENDING");
		assertThat(targetStatus(targetId)).isEqualTo("WATCHING");
		assertThat(db.queryForObject("SELECT tracked_short_code FROM target WHERE id=?", String.class,
				targetId)).isNull();
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot", Long.class)).isZero();
	}

	@Test
	void 없는_후보_승인은_404_CANDIDATE_NOT_FOUND() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, 999_999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CANDIDATE_NOT_FOUND"));

		assertThat(targetStatus(targetId)).isEqualTo("WATCHING");
	}

	/** 다른 캠페인 소속 후보의 id를 넘기면 남의 감지분으로 내 캠페인이 TRACKING이 된다 — 소속까지 봐야 한다. */
	@Test
	void 다른_캠페인_소속_후보_승인은_404_CANDIDATE_NOT_FOUND() throws Exception {
		long mine = seedTarget(TargetStatus.WATCHING, null);
		long other = seedTarget(TargetStatus.WATCHING, null);
		long othersCandidate = seedCandidate(other, SHORT_CODE);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", mine, othersCandidate))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CANDIDATE_NOT_FOUND"));

		assertThat(targetStatus(mine)).isEqualTo("WATCHING");
		assertThat(candidateStatus(othersCandidate)).isEqualTo("PENDING");
	}

	@Test
	void 없는_캠페인_해지는_404_TARGET_NOT_FOUND() throws Exception {
		mvc.perform(delete("/api/targets/{id}", 999_999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TARGET_NOT_FOUND"));
	}

	@Test
	void 연장은_expires_at을_갱신한다() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);

		mvc.perform(patch("/api/targets/{id}", targetId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expiresAt\":\"2027-03-01T00:00:00+09:00\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetId").value(targetId))
				// 에폭 초 숫자로 나가면 was가 파싱에 실패한다 — ISO-8601 문자열로 못박는다
				.andExpect(jsonPath("$.expiresAt").value("2027-02-28T15:00:00Z"));

		// +09:00이 UTC로 옮겨져 저장되는지 — 여기가 틀리면 만료 스윕이 9시간 어긋난다
		assertThat(db.queryForObject("""
				SELECT expires_at = timestamptz '2027-02-28T15:00:00Z' FROM target WHERE id=?""",
				Boolean.class, targetId)).isTrue();
	}

	@Test
	void 해지는_CANCELED로_전이하고_재해지도_200_멱등() throws Exception {
		long targetId = seedTarget(TargetStatus.TRACKING, SHORT_CODE);

		mvc.perform(delete("/api/targets/{id}", targetId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetId").value(targetId))
				.andExpect(jsonPath("$.status").value("CANCELED"));
		assertThat(targetStatus(targetId)).isEqualTo("CANCELED");
		String closedAt = db.queryForObject("SELECT closed_at::text FROM target WHERE id=?",
				String.class, targetId);

		// was의 재시도(타임아웃 복구)가 409로 튕기면 안 된다 — 이미 해지된 것은 성공으로 본다.
		mvc.perform(delete("/api/targets/{id}", targetId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"));
		// 종결 시각도 덮어쓰지 않는다 — 재시도가 캠페인 종료 시점을 뒤로 밀면 정산 근거가 흔들린다.
		assertThat(db.queryForObject("SELECT closed_at::text FROM target WHERE id=?", String.class,
				targetId)).isEqualTo(closedAt);
		// 행·스냅샷은 보존(물리 삭제 아님 — 계약 §2-5)
		assertThat(db.queryForObject("SELECT count(*) FROM target WHERE id=?", Long.class, targetId))
				.isEqualTo(1);
	}

	@Test
	void 종결된_캠페인_연장은_409_INVALID_STATE() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);
		mvc.perform(delete("/api/targets/{id}", targetId)).andExpect(status().isOk());

		mvc.perform(patch("/api/targets/{id}", targetId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expiresAt\":\"2027-03-01T00:00:00+09:00\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE"));
	}
}
