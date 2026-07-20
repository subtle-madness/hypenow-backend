package com.celfit.analytics.llm;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * beauty_taxonomy·beauty_distributors(analysis DB, V30 시드)에서 {@link BeautyTaxonomy}
 * 스냅샷을 조립한다. 배치 프로세스 수명 동안 어휘는 불변 — 첫 로드 후 메모이즈
 * (어휘 수정은 다음 실행부터 반영, 프롬프트와 sanitize가 항상 같은 스냅샷을 본다).
 */
public final class BeautyTaxonomyLoader {

	private final JdbcTemplate analysis;
	private volatile BeautyTaxonomy cached;

	public BeautyTaxonomyLoader(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public BeautyTaxonomy get() {
		BeautyTaxonomy t = cached;
		if (t == null) {
			t = load();
			cached = t;
		}
		return t;
	}

	private BeautyTaxonomy load() {
		List<BeautyTaxonomy.Entry> entries = analysis.query("""
				SELECT main_value, main_label, mid_label, sub_label
				FROM beauty_taxonomy ORDER BY main_order, mid_order, sub_order""",
				(rs, i) -> new BeautyTaxonomy.Entry(
						rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
		List<String> distributors = analysis.queryForList(
				"SELECT name FROM beauty_distributors ORDER BY sort", String.class);
		if (entries.isEmpty() || distributors.isEmpty()) {
			throw new IllegalStateException("분류 어휘 테이블이 비어 있음 — V30 시드 확인");
		}
		return new BeautyTaxonomy(entries, distributors);
	}
}
