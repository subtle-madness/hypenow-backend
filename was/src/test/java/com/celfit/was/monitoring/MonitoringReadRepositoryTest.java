package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MonitoringReadRepositoryTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	JdbcClient jdbc;
	MonitoringReadRepository repository;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();
		repository = new MonitoringReadRepository(jdbc);
	}

	long seedTarget(String username, String trackedShortCode, String status) {
		return jdbc.sql("""
				INSERT INTO target (type, username, keyword_rule, status, tracked_short_code,
				                    registration_key, expires_at)
				VALUES ('ACCOUNT', :username, '{"and":["샤넬"],"any":[],"exclude":[]}'::jsonb,
				        :status, :tracked, gen_random_uuid()::text, now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username).param("status", status).param("tracked", trackedShortCode)
				.query(Long.class).single();
	}

	@Test
	void 타겟_조회는_계약_컬럼을_그대로_돌려준다() {
		long id = seedTarget("some_influencer", null, "WATCHING");

		List<TargetRow> rows = repository.findTargets(List.of(id));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).username()).isEqualTo("some_influencer");
		assertThat(rows.get(0).status()).isEqualTo("WATCHING");
		assertThat(rows.get(0).keywordRule()).contains("샤넬");
		assertThat(rows.get(0).closedAt()).isNull();
	}

	@Test
	void 빈_id_목록은_빈_결과() {
		assertThat(repository.findTargets(List.of())).isEmpty();
	}

	@Test
	void 타겟_다건_조회는_등록_역순() {
		// registered_at을 명시 고정값으로 시드 — now() 기반이면 같은 트랜잭션·짧은 간격에서
		// 동일 timestamp가 나올 수 있어(Postgres now()는 트랜잭션 내 불변) 결정론이 깨진다.
		long first = jdbc.sql("""
				INSERT INTO target (type, username, status, registration_key, expires_at, registered_at)
				VALUES ('ACCOUNT', 'acc_a', 'WATCHING', gen_random_uuid()::text,
				        now() + interval '30 days', '2026-07-27')
				RETURNING id
				""").query(Long.class).single();
		long second = jdbc.sql("""
				INSERT INTO target (type, username, status, registration_key, expires_at, registered_at)
				VALUES ('ACCOUNT', 'acc_b', 'WATCHING', gen_random_uuid()::text,
				        now() + interval '30 days', '2026-07-28')
				RETURNING id
				""").query(Long.class).single();

		List<TargetRow> rows = repository.findTargets(List.of(first, second));

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).id()).isEqualTo(second);   // registered_at DESC
		assertThat(rows.get(1).id()).isEqualTo(first);
	}

	@Test
	void 후보_목록과_워터마크_이후_신규_PENDING() {
		long id = seedTarget("acc1", null, "WATCHING");
		jdbc.sql("""
				INSERT INTO detected_candidate (target_id, short_code, detected_at, caption_excerpt, status)
				VALUES (:t, 'OLD1', now() - interval '2 days', '…샤넬…', 'PENDING'),
				       (:t, 'NEW1', now(), '…샤넬 립스틱…', 'PENDING'),
				       (:t, 'REJ1', now(), '…', 'REJECTED')
				""").param("t", id).update();

		assertThat(repository.findCandidates(id)).hasSize(3);

		List<PendingCandidate> fresh = repository.findPendingCandidatesSince(
				Instant.now().minusSeconds(3600));
		assertThat(fresh).hasSize(1);
		assertThat(fresh.get(0).shortCode()).isEqualTo("NEW1");
		assertThat(fresh.get(0).username()).isEqualTo("acc1");
	}

	@Test
	void 종결_캠페인의_잔여_PENDING은_알람_조회에서_제외된다() {
		// 계약 v1.0: 종결 캠페인 후보는 승인·거절이 모두 409라 알람이 나가면 안 된다
		long active = seedTarget("acc_live", null, "WATCHING");
		long closed = seedTarget("acc_closed", null, "EXPIRED");
		jdbc.sql("""
				INSERT INTO detected_candidate (target_id, short_code, detected_at, caption_excerpt, status)
				VALUES (:a, 'LIVE1', now(), '…', 'PENDING'),
				       (:c, 'DEAD1', now(), '…', 'PENDING')
				""").param("a", active).param("c", closed).update();

		List<PendingCandidate> fresh = repository.findPendingCandidatesSince(
				Instant.now().minusSeconds(3600));

		assertThat(fresh).hasSize(1);
		assertThat(fresh.get(0).shortCode()).isEqualTo("LIVE1");
	}

	@Test
	void 프로필_추이는_날짜순() {
		jdbc.sql("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES ('acc1', '2026-07-27', 100, 10, 5), ('acc1', '2026-07-28', 110, 10, 6)
				""").update();

		List<ProfileSnapshotRow> rows = repository.profileTimeseries("acc1");

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).followers()).isEqualTo(100L);
		assertThat(rows.get(1).followers()).isEqualTo(110L);
	}

	@Test
	void 게시물_추이는_추적_short_code_기준이고_null_지표가_보존된다() {
		long id = seedTarget("acc1", "TRACK1", "TRACKING");
		jdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES ('acc1', 'TRACK1', '2026-07-28', 'FEED', 50, 3, NULL, 7, 1, 0),
				       ('acc1', 'OTHER', '2026-07-28', 'REELS', 999, 9, 1000, 9, 9, 9)
				""").update();

		List<PostSnapshotRow> rows = repository.postTimeseries(id);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).likes()).isEqualTo(50L);
		assertThat(rows.get(0).views()).isNull();   // 피드 조회수 NULL 규칙
	}
}
