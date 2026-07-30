package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.TraitMappingPort;
import com.celfit.analytics.llm.TraitTaxonomy;
import com.celfit.analytics.llm.TraitTaxonomyLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * trait 배치 매핑 원샷 잡 (2026-07-29 어휘 통제 스펙 §3-3).
 * DRY: 어휘 밖 고유 raw를 LLM으로 매핑해 trait_canon_log에 기록·통계만 남긴다.
 * APPLY: canon_log(+어휘 항등) 기반으로 account_analyses.traits 전 이력 행을 in-place UPDATE.
 * canon_log가 이미 있는 raw는 LLM 재호출 없이 재사용(재실행 안전·저렴) — 매핑 불가는
 * canon_value='' 센티널 1행이라 재대상에서 빠지고, 응답 누락 raw만 다음 실행에서 재시도된다.
 */
public class TraitCanonJob {

	private static final Logger log = LoggerFactory.getLogger(TraitCanonJob.class);

	static final int BATCH = 100;
	/** 1:N 분해 상한 — 스펙 결정 ②. */
	static final int MAX_CANON_PER_RAW = 2;

	private final JdbcTemplate analysis;
	private final TraitTaxonomyLoader loader;
	private final TraitMappingPort port;
	private final ProgressReporter dryReporter;
	private final ProgressReporter applyReporter;

	public TraitCanonJob(DataSource analysisDataSource, TraitTaxonomyLoader loader,
			TraitMappingPort port, ProgressReporter dryReporter, ProgressReporter applyReporter) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.loader = loader;
		this.port = port;
		this.dryReporter = dryReporter;
		this.applyReporter = applyReporter;
	}

	public JobResult run(boolean dryRun) {
		TraitTaxonomy vocab = loader.get();
		int mapped = mapUnmapped(vocab, dryRun ? dryReporter : applyReporter);
		if (dryRun) {
			logStats();
			return new JobResult(mapped, 0, false);
		}
		int updated = applyToRows(vocab);
		logStats();
		return new JobResult(updated, 0, false);
	}

	/** 1단계(두 모드 공통): 어휘 밖·미기록 고유 raw 수집 → LLM 매핑 → canon_log INSERT. */
	private int mapUnmapped(TraitTaxonomy vocab, ProgressReporter reporter) {
		// 캐노니컬 대조는 공백 무시 — 모델이 "가을웜톤"을 "가을 웜톤"처럼 띄어 반환하면 정확일치
		// 필터에서 탈락해 매핑 가능한 raw가 '' 센티널로 영구 드롭되는 실측 결함(07-29 dev DRY).
		// 정규화 키로 찾되 기록은 항상 어휘 정본 표기로 남긴다.
		Map<String, String> canonByNorm = new HashMap<>();
		vocab.names().forEach(n -> canonByNorm.put(squashSpaces(n), n));
		List<String> raws = analysis.queryForList("""
				SELECT DISTINCT t FROM account_analyses, jsonb_array_elements_text(traits) AS t
				WHERE t NOT IN (SELECT name FROM trait_taxonomy)
				  AND t NOT IN (SELECT raw_value FROM trait_canon_log)
				ORDER BY t""", String.class);
		int recorded = 0;
		reporter.report(0, 0, raws.size());
		for (int from = 0; from < raws.size(); from += BATCH) {
			List<String> batch = raws.subList(from, Math.min(from + BATCH, raws.size()));
			Map<String, List<String>> result = port.map(batch);
			for (String raw : batch) {
				List<String> canon = result.get(raw);
				if (canon == null) {
					continue; // 응답 누락 — 다음 실행에서 재시도
				}
				List<String> valid = canon.stream()
						.map(c -> canonByNorm.get(squashSpaces(c)))
						.filter(java.util.Objects::nonNull)
						.distinct().limit(MAX_CANON_PER_RAW).toList();
				if (valid.isEmpty()) {
					insertLog(raw, ""); // 매핑 불가(어휘 밖 캐노니컬만 온 배신 응답 포함) — 드롭 확정
				} else {
					valid.forEach(c -> insertLog(raw, c));
				}
				recorded++;
			}
			reporter.report(Math.min(from + BATCH, raws.size()), 0, raws.size());
		}
		return recorded;
	}

	/** 어휘 대조용 정규화 — 모든 공백류 제거. */
	private static String squashSpaces(String s) {
		return s.replaceAll("\\s+", "");
	}

	private void insertLog(String raw, String canon) {
		analysis.update("""
				INSERT INTO trait_canon_log (raw_value, canon_value, mapped_at)
				VALUES (?, ?, now()) ON CONFLICT DO NOTHING""", raw, canon);
	}

	/** 2단계(APPLY만): canon_log + 어휘 항등으로 전 이력 행 치환. 변경 없는 행은 스킵. */
	private int applyToRows(TraitTaxonomy vocab) {
		Set<String> names = vocab.names();
		Map<String, List<String>> canonByRaw = new HashMap<>();
		analysis.query("SELECT raw_value, canon_value FROM trait_canon_log WHERE canon_value <> ''",
				rs -> {
					canonByRaw.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
							.add(rs.getString(2));
				});
		List<Map<String, Object>> rows = analysis.queryForList("""
				SELECT handle, analyzed_at, traits::text AS traits
				FROM account_analyses WHERE traits IS NOT NULL""");
		var om = new tools.jackson.databind.ObjectMapper();
		int updated = 0;
		applyReporter.report(0, 0, rows.size());
		int seen = 0;
		for (Map<String, Object> row : rows) {
			List<String> raw = om.readValue((String) row.get("traits"),
					om.getTypeFactory().constructCollectionType(List.class, String.class));
			List<String> next = raw.stream()
					.flatMap(t -> names.contains(t)
							? java.util.stream.Stream.of(t)
							: canonByRaw.getOrDefault(t, List.of()).stream())
					.distinct()
					.limit(AccountAnalysisWriter.MAX_TRAITS)
					.toList();
			seen++;
			if (!next.equals(raw)) {
				analysis.update("UPDATE account_analyses SET traits = ?::jsonb WHERE handle = ? AND analyzed_at = ?",
						om.writeValueAsString(next), row.get("handle"), row.get("analyzed_at"));
				updated++;
			}
			applyReporter.report(seen, 0, rows.size());
		}
		return updated;
	}

	private void logStats() {
		Long mappedRaws = analysis.queryForObject(
				"SELECT count(DISTINCT raw_value) FROM trait_canon_log WHERE canon_value <> ''", Long.class);
		Long droppedRaws = analysis.queryForObject(
				"SELECT count(*) FROM trait_canon_log WHERE canon_value = ''", Long.class);
		Long distinctNow = analysis.queryForObject(
				"SELECT count(DISTINCT t) FROM account_analyses, jsonb_array_elements_text(traits) AS t",
				Long.class);
		log.info("trait 매핑 통계 — 매핑 raw {}건, 드롭 raw {}건, 현재 고유 trait {}종",
				mappedRaws, droppedRaws, distinctNow);
	}
}
