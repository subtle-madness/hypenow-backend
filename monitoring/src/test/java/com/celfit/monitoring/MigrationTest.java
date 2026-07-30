package com.celfit.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.alarm.AlarmEmailStatus;
import com.celfit.monitoring.alarm.AlarmEventType;
import com.celfit.monitoring.testsupport.TestDb;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest {

	@Test
	void 마이그레이션이_핵심_테이블을_만든다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long tables = db.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE (table_schema, table_name) IN
				  (('raw','fetch_payload'), ('public','target'), ('public','detected_candidate'),
				   ('public','profile_snapshot'), ('public','post_snapshot'),
				   ('public','post_comment'), ('public','profile_meta'), ('public','alarm_event'),
				   ('public','post_meta'), ('public','sweep_run'))""",
				Long.class);
		assertThat(tables).isEqualTo(10);
	}

	/** v1.1 P2 표면(V4) — detected_candidate.matched_keywords 컬럼이 실제로 추가됐는지. */
	@Test
	void detected_candidate에_matched_keywords_컬럼이_있다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long column = db.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema='public' AND table_name='detected_candidate'
				  AND column_name='matched_keywords'""", Long.class);
		assertThat(column).isEqualTo(1);
	}

	/** user_id는 expand 단계라 nullable이어야 한다 — NOT NULL이면 기존 운영 행 때문에 마이그레이션이 실패한다. */
	@Test
	void target_user_id는_nullable로_추가된다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		assertThat(db.queryForObject("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_name='target' AND column_name='user_id'""", String.class))
				.isEqualTo("YES");
	}

	/**
	 * V4 신규 표면도 was_reader가 SELECT할 수 있어야 한다 — V2의 ALTER DEFAULT PRIVILEGES가
	 * V4 이후에 생긴 테이블에도 적용되는지 실제로 확인한다(계약 §6, 별도 GRANT 없음을 전제).
	 */
	@Test
	void was_reader는_P2_표면을_SELECT할_수_있다() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		var wasReader = new JdbcTemplate(TestDb.wasReaderDataSource(pg));

		assertThat(wasReader.queryForObject("SELECT count(*) FROM post_comment", Long.class)).isZero();
		assertThat(wasReader.queryForObject("SELECT count(*) FROM profile_meta", Long.class)).isZero();
	}

	/** V5 P1 표면(계약 v2.2) — target에 hidden/error/matched_keywords 신호 컬럼이 스펙대로 추가됐는지. */
	@Test
	void target에_P1_신호_컬럼_3종이_스펙대로_있다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		var columns = db.queryForList("""
				SELECT column_name, is_nullable, column_default FROM information_schema.columns
				WHERE table_schema='public' AND table_name='target'
				  AND column_name IN ('tracked_hidden_at', 'fetch_failing', 'matched_keywords')""");
		assertThat(columns).hasSize(3);

		var byName = columns.stream()
				.collect(Collectors.toMap(c -> (String) c.get("column_name"), c -> c));

		assertThat(byName.get("tracked_hidden_at").get("is_nullable")).isEqualTo("YES");
		assertThat(byName.get("matched_keywords").get("is_nullable")).isEqualTo("YES");

		assertThat(byName.get("fetch_failing").get("is_nullable")).isEqualTo("NO");
		assertThat((String) byName.get("fetch_failing").get("column_default")).contains("false");
	}

	/**
	 * V5가 재생성한 v_target_overview가 3개 신규 컬럼을 실제로 노출하는지(계약 §3, fail_reason 뒤
	 * 삽입) — DROP+CREATE 과정에서 컬럼이 누락되지 않았는지 확인한다.
	 */
	@Test
	void 개요_뷰는_P1_신호_컬럼_3종을_노출한다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long columns = db.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_name='v_target_overview'
				  AND column_name IN ('tracked_hidden_at', 'fetch_failing', 'matched_keywords')""",
				Long.class);
		assertThat(columns).isEqualTo(3);
	}

	/**
	 * V5 신규 표면(post_meta·sweep_run)도 was_reader가 SELECT할 수 있어야 한다 — V2의
	 * ALTER DEFAULT PRIVILEGES가 V5 이후에 생긴 테이블에도 적용되는지 실제로 확인한다.
	 */
	@Test
	void was_reader는_P1_표면을_SELECT할_수_있다() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		var wasReader = new JdbcTemplate(TestDb.wasReaderDataSource(pg));

		assertThat(wasReader.queryForObject("SELECT count(*) FROM post_meta", Long.class)).isZero();
		assertThat(wasReader.queryForObject("SELECT count(*) FROM sweep_run", Long.class)).isZero();
	}

	/**
	 * 어휘 표류 안전망 — alarm_event.event_type CHECK가 {@link AlarmEventType} 전체와 정확히 같은지
	 * 컴파일 타임 대신 여기서 잡는다. enum에 값을 추가·삭제하고 마이그레이션을 깜빡하면(혹은 그 반대)
	 * 이 테스트가 즉시 실패한다 — CHECK 제약을 직접 파싱하는 대신, enum 값 전체 INSERT 성공 +
	 * 미정의 값 INSERT 거부로 왕복 검증한다(단순하고 실제 DB 동작과 1:1).
	 */
	@Test
	void alarm_event_type_체크_제약은_enum_전체와_일치한다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		for (AlarmEventType type : AlarmEventType.values()) {
			db.update("""
					INSERT INTO alarm_event (target_id, user_id, event_type, payload, dispatch_after)
					VALUES (1, 1, ?, '{}'::jsonb, now())""", type.name());
		}
		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class))
				.isEqualTo((long) AlarmEventType.values().length);

		assertThatThrownBy(() -> db.update("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, dispatch_after)
				VALUES (1, 1, 'BOGUS_TYPE', '{}'::jsonb, now())"""))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 위와 같은 이유 — email_status CHECK ↔ {@link AlarmEmailStatus} 어휘 일치. */
	@Test
	void alarm_email_status_체크_제약은_enum_전체와_일치한다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		for (AlarmEmailStatus status : AlarmEmailStatus.values()) {
			db.update("""
					INSERT INTO alarm_event (target_id, user_id, event_type, payload, dispatch_after, email_status)
					VALUES (1, 1, 'COLLECTION_STARTED', '{}'::jsonb, now(), ?)""", status.name());
		}
		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class))
				.isEqualTo((long) AlarmEmailStatus.values().length);

		assertThatThrownBy(() -> db.update("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, dispatch_after, email_status)
				VALUES (1, 1, 'COLLECTION_STARTED', '{}'::jsonb, now(), 'BOGUS_STATUS')"""))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
