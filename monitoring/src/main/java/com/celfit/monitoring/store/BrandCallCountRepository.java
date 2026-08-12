package com.celfit.monitoring.store;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * brand_call_count 접점 — 브랜드별 Hiker 콜 일별 누적(2026-08-12 어드민 크롤링 비용 설계).
 * 쓰기는 콜 단위 +1 upsert 하나뿐이다(콜 간격이 초 단위라 행 잠금 경합 무시 가능).
 * 조회는 was가 읽기 전용 표면(was_reader)으로 직접 SELECT한다 — monitoring엔 조회 코드가 없다.
 */
@Repository
public class BrandCallCountRepository {

	private final JdbcTemplate db;

	public BrandCallCountRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** (브랜드, KST 일)의 콜 수를 delta만큼 증가 — 행이 없으면 생성. */
	public void add(long brandId, LocalDate calledOn, long delta) {
		db.update("""
				INSERT INTO brand_call_count (brand_id, called_on, calls) VALUES (?, ?, ?)
				ON CONFLICT (brand_id, called_on) DO UPDATE SET calls = brand_call_count.calls + EXCLUDED.calls
				""", brandId, calledOn, delta);
	}
}
