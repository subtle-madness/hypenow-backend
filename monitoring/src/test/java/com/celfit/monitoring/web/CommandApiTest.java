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
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.service.SnapshotWriter;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** 명령 API 2종(연장·해지) — 계약 v2 §2-2~2-3의 상태 전이 규칙. 승인·기각은 v2에서 폐지됐다. */
@SpringBootTest
class CommandApiTest {

	/** Hiker 픽스처 fake는 등록 테스트와 같은 것을 쓴다 — 컨텍스트 기동 시 실제 API를 타지 않게 막는다. */
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
	@Autowired RegistrationApiTest.SwitchableHiker hiker;
	@Autowired SnapshotWriter snapshotWriter;
	@Autowired TransactionTemplate tx;
	MockMvc mvc;

	/** 픽스처(media-by-code.json)의 숏코드 — 해지 테스트의 추적 게시물 값으로 재사용한다. */
	private static final String SHORT_CODE = "DbV7LgZsKG8";
	private static final KeywordRule RULE = new KeywordRule(List.of(), List.of("샤넬"), List.of());
	private static final Instant EXPIRES = Instant.now().plus(30, ChronoUnit.DAYS);

	private final AtomicInteger keySeq = new AtomicInteger();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		db.update("DELETE FROM detected_candidate");
		db.update("DELETE FROM alarm_event");
		db.update("DELETE FROM target");
		// 다른 테스트 클래스가 남긴 스냅샷·원형이 섞이면 아래 절대값 단언이 실행 순서에 따라 흔들린다.
		db.update("DELETE FROM profile_snapshot");
		db.update("DELETE FROM post_snapshot");
		db.update("DELETE FROM raw.fetch_payload");
		hiker.notFound = false;
	}

	private long seedTarget(TargetStatus status, String trackedShortCode) {
		return targets.insert(TargetType.ACCOUNT, 7L, "someuser", null, RULE, status, trackedShortCode,
				"rk-" + keySeq.incrementAndGet(), EXPIRES);
	}

	private String targetStatus(long targetId) {
		return db.queryForObject("SELECT status FROM target WHERE id=?", String.class, targetId);
	}

	/**
	 * 승인·기각 경로는 계약 v2에서 사라졌다 — 남아 있으면 구 was가 조용히 성공해
	 * "후보 승인"이라는 없어진 개념이 상태 기계에 되돌아온다. 404가 곧 폐지의 증거다.
	 */
	@Test
	void 승인_기각_경로는_더_이상_존재하지_않는다() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, 1))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/targets/{id}/candidates/{cid}/reject", targetId, 1))
				.andExpect(status().isNotFound());

		assertThat(targetStatus(targetId)).isEqualTo("WATCHING");
	}

	/**
	 * 위 롤백 테스트가 성립하는 근거 — SnapshotWriter는 REQUIRED라 바깥 트랜잭션에 **참여**한다.
	 * 전파를 REQUIRES_NEW로 바꾸면 스냅샷만 별도 커밋돼, 승인이 실패해도 지표 행이 남는다
	 * (= "WATCHING인데 추적 지표가 있는" 유령 상태). 그 회귀를 여기서 잡는다.
	 */
	@Test
	void 스냅샷_쓰기는_바깥_트랜잭션에_참여한다() {
		var post = new PostInfo("TXPROBE1", "someuser", null, null, "REELS", "캡션", null, 1_785_000_000L,
				1L, 1L, 1L, null, null, null, "{}", true);

		tx.executeWithoutResult(status -> {
			snapshotWriter.savePost(LocalDate.of(2026, 7, 29), post);
			status.setRollbackOnly();
		});

		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='TXPROBE1'""", Long.class)).isZero();
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
		// 행·스냅샷은 보존(물리 삭제 아님 — 계약 v2 §2-3)
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
