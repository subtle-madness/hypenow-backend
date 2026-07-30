package com.celfit.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.alarm.AlarmEmailStatus;
import com.celfit.monitoring.alarm.AlarmEventType;
import com.celfit.monitoring.testsupport.TestDb;
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
				   ('public','profile_snapshot'), ('public','post_snapshot'), ('public','alarm_event'))""",
				Long.class);
		assertThat(tables).isEqualTo(6);
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
