package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
		jdbc.sql("""
				TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot,
				         post_meta, post_comment, profile_meta, sweep_run RESTART IDENTITY
				""")
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

	@Test
	void 타겟_조회는_P1_확장_컬럼도_그대로_돌려준다() {
		long id = jdbc.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, registration_key, expires_at,
				                    user_id, tracked_hidden_at, fetch_failing, matched_keywords)
				VALUES ('ACCOUNT', 'acc1', 'TRACKING', 'SHORT1', gen_random_uuid()::text,
				        now() + interval '30 days', 7, now(), true, '["샤넬","립스틱"]'::jsonb)
				RETURNING id
				""").query(Long.class).single();

		TargetRow row = repository.findTargets(List.of(id)).get(0);

		assertThat(row.userId()).isEqualTo(7L);
		assertThat(row.trackedHiddenAt()).isNotNull();
		assertThat(row.fetchFailing()).isTrue();
		assertThat(row.matchedKeywords()).contains("샤넬");
	}

	@Test
	void 빈_shortCode_목록은_post_meta_snapshots_comments_모두_빈_결과() {
		assertThat(repository.findPostMeta(List.of())).isEmpty();
		assertThat(repository.findSnapshots(List.of(), LocalDate.now())).isEmpty();
		assertThat(repository.findComments(List.of(), 8)).isEmpty();
	}

	@Test
	void post_meta_배치_조회() {
		jdbc.sql("""
				INSERT INTO post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url)
				VALUES ('SHORT1', 'acc1', 'REELS', '2026-07-27', '캡션1', 'http://cdn/1.jpg'),
				       ('SHORT2', 'acc2', 'FEED', '2026-07-28', '', NULL)
				""").update();

		List<PostMetaRow> rows = repository.findPostMeta(List.of("SHORT1", "SHORT2", "MISSING"));

		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(PostMetaRow::shortCode).containsExactlyInAnyOrder("SHORT1", "SHORT2");
	}

	@Test
	void snapshots_배치_조회는_shortCode로_그룹핑_가능하고_상한_이후는_제외() {
		jdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, likes, comments,
				                           views, saves, shares, reposts)
				VALUES ('acc1', 'SHORT1', '2026-07-27', 'REELS', 10, 1, 100, 2, 3, 0),
				       ('acc1', 'SHORT1', '2026-07-29', 'REELS', 20, 2, 200, 3, 4, 1),
				       ('acc2', 'SHORT2', '2026-07-27', 'FEED', 5, 1, NULL, 1, NULL, NULL)
				""").update();

		List<TrackedSnapshotRow> rows = repository.findSnapshots(List.of("SHORT1", "SHORT2"), LocalDate.of(2026, 7, 27));

		assertThat(rows).hasSize(2);   // 2026-07-29 행은 상한(07-27) 이후라 제외
		assertThat(rows).extracting(TrackedSnapshotRow::shortCode).containsExactlyInAnyOrder("SHORT1", "SHORT2");
		assertThat(rows).allSatisfy(r -> assertThat(r.likesHidden()).isFalse());   // 미지정 시 기본 false
	}

	/** 좋아요 숨김 관측 행 — likes null과 함께 likes_hidden이 읽혀야 FE 구분 표시가 성립한다. */
	@Test
	void 좋아요_숨김_행은_likes_hidden이_같이_읽힌다() {
		jdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, likes_hidden, comments, views, saves, shares, reposts)
				VALUES ('acc1', 'SHORT1', '2026-07-27', 'REELS', NULL, true, 13, 100, 2, 3, 0)
				""").update();

		List<TrackedSnapshotRow> rows = repository.findSnapshots(List.of("SHORT1"), LocalDate.of(2026, 7, 27));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).likes()).isNull();
		assertThat(rows.get(0).likesHidden()).isTrue();
		assertThat(rows.get(0).sharesHidden()).isFalse();   // 미지정 시 기본 false
	}

	/** 공유 숨김 관측 행(v2.7) — shares null과 함께 shares_hidden이 읽혀야 FE 구분 표시가 성립한다. */
	@Test
	void 공유_숨김_행은_shares_hidden이_같이_읽힌다() {
		jdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, shares_hidden, reposts)
				VALUES ('acc1', 'SHORT1', '2026-07-27', 'REELS', 10, 13, 100, 2, NULL, true, 0)
				""").update();

		List<TrackedSnapshotRow> rows = repository.findSnapshots(List.of("SHORT1"), LocalDate.of(2026, 7, 27));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).shares()).isNull();
		assertThat(rows.get(0).sharesHidden()).isTrue();
	}

	@Test
	void comments_배치_조회는_shortCode당_상한_건수만_최신순() {
		jdbc.sql("""
				INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at)
				VALUES ('SHORT1', 'c1', 'author_a', '본문1', 1, '2026-07-27T00:00:00+09:00'),
				       ('SHORT1', 'c2', 'author_b', '본문2', 2, '2026-07-28T00:00:00+09:00'),
				       ('SHORT1', 'c3', 'author_c', '본문3', 3, '2026-07-29T00:00:00+09:00')
				""").update();

		List<PostCommentRow> rows = repository.findComments(List.of("SHORT1"), 2);

		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(PostCommentRow::id).containsExactly("c3", "c2");   // 최신순 상한 2
	}

	@Test
	void profile_meta_배치_조회() {
		jdbc.sql("""
				INSERT INTO profile_meta (username, display_name, profile_image_url, last_uploaded_at, updated_at)
				VALUES ('acc1', '표시이름', 'http://cdn/p.jpg', '2026-07-29', now())
				""").update();

		List<ProfileMetaRow> rows = repository.findProfileMeta(List.of("acc1", "missing"));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).displayName()).isEqualTo("표시이름");
	}

	@Test
	void 최신_프로필_스냅샷_배치_조회는_계정당_최신_1행() {
		jdbc.sql("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES ('acc1', '2026-07-27', 100, 10, 5), ('acc1', '2026-07-28', 110, 10, 6)
				""").update();

		List<ProfileSnapshotBatchRow> rows = repository.findLatestProfileSnapshots(List.of("acc1"));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).followers()).isEqualTo(110L);
	}

	@Test
	void 성공한_스윕이_없으면_lastSuccessfulSweepAt은_null() {
		assertThat(repository.lastSuccessfulSweepAt()).isNull();
	}

	@Test
	void lastSuccessfulSweepAt은_성공한_스윕_중_가장_최근_완료시각() {
		jdbc.sql("""
				INSERT INTO sweep_run (started_at, completed_at, ok)
				VALUES (now() - interval '2 day', now() - interval '2 day' + interval '1 hour', true),
				       (now() - interval '1 day', now() - interval '1 day' + interval '1 hour', true),
				       (now(), NULL, NULL)
				""").update();

		OffsetDateTime last = repository.lastSuccessfulSweepAt();

		assertThat(last).isNotNull();
		assertThat(last).isAfter(OffsetDateTime.now().minusDays(2));
	}
}
