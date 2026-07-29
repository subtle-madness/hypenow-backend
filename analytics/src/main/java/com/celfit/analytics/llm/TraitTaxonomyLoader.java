package com.celfit.analytics.llm;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * trait_taxonomy(analysis DB, V41 시드)에서 {@link TraitTaxonomy} 스냅샷을 조립한다.
 * 배치 프로세스 수명 동안 어휘는 불변 — 첫 로드 후 메모이즈 (BeautyTaxonomyLoader와 동일 취지:
 * 프롬프트와 sanitize가 항상 같은 스냅샷을 본다).
 */
public final class TraitTaxonomyLoader {

	private final JdbcTemplate analysis;
	private volatile TraitTaxonomy cached;

	public TraitTaxonomyLoader(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public TraitTaxonomy get() {
		TraitTaxonomy t = cached;
		if (t == null) {
			t = load();
			cached = t;
		}
		return t;
	}

	private TraitTaxonomy load() {
		List<TraitTaxonomy.Entry> entries = analysis.query("""
				SELECT name, facet FROM trait_taxonomy ORDER BY facet_order, sort""",
				(rs, i) -> new TraitTaxonomy.Entry(rs.getString(1), rs.getString(2)));
		if (entries.isEmpty()) {
			throw new IllegalStateException("trait 어휘 테이블이 비어 있음 — V41 시드 확인");
		}
		return new TraitTaxonomy(entries);
	}
}
