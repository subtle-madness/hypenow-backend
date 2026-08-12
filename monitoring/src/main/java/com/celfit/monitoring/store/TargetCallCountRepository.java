package com.celfit.monitoring.store;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * target_call_count 접점 — 캠페인·콘텐츠 모니터링 Hiker 콜의 유저별 일별 누적(2026-08-12 어드민
 * 크롤링 비용 범위 확장). {@link BrandCallCountRepository}와 동형: 쓰기는 콜 단위 +1 upsert 하나뿐,
 * 조회는 was가 읽기 전용 표면(was_reader)으로 직접 SELECT한다 — monitoring엔 조회 코드가 없다.
 */
@Repository
public class TargetCallCountRepository {

	private final JdbcTemplate db;

	public TargetCallCountRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** (유저, KST 일)의 콜 수를 delta만큼 증가 — 행이 없으면 생성. */
	public void add(long userId, LocalDate calledOn, long delta) {
		db.update("""
				INSERT INTO target_call_count (user_id, called_on, calls) VALUES (?, ?, ?)
				ON CONFLICT (user_id, called_on) DO UPDATE SET calls = target_call_count.calls + EXCLUDED.calls
				""", userId, calledOn, delta);
	}
}
