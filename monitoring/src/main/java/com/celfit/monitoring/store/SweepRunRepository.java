package com.celfit.monitoring.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 일일 스윕 실행 대장 접점(계약 §3 sweep_run, v2.2) — 1실행 1행.
 * was는 {@code max(completed_at) WHERE ok}로 6.26 {@code meta.lastCollectedAt}(마지막 성공 배치
 * 완료 시각)을 읽는다: {@code ok=true}는 스윕 루프 정상 완주(계정 단위 격리 실패 포함 — 부분 실패는
 * {@code target.fetch_failing}으로 표현), 크래시·중단된 실행은 ok가 true가 되지 않아 워터마크에서
 * 자연 제외된다.
 *
 * <p>이 기록 자체의 실패가 스윕을 막으면 안 된다 — 호출부({@link com.celfit.monitoring.service.DailySweepJob})가
 * start/complete 각각을 격리한다(이 클래스는 격리를 갖지 않는다).
 */
@Repository
public class SweepRunRepository {

	private final JdbcTemplate db;

	public SweepRunRepository(JdbcTemplate db) {
		this.db = db;
	}

	public long start() {
		return db.queryForObject("INSERT INTO sweep_run (started_at) VALUES (now()) RETURNING id", Long.class);
	}

	public void complete(long id, boolean ok) {
		db.update("UPDATE sweep_run SET completed_at = now(), ok = ? WHERE id = ?", ok, id);
	}
}
