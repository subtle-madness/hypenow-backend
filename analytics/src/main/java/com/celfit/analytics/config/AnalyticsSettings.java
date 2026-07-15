package com.celfit.analytics.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 런타임 설정 리더 — raw DB의 app_setting(key,value)을 읽는 유일한 Java 창구.
 * 키가 없으면 기본값 (뷰의 COALESCE 컨벤션과 동일). 값 갱신은 admin SQL로.
 */
@Component
public class AnalyticsSettings {

	/** 댓글 분류 등 LLM 호출 모델. 스파이크(F-1) 결과로 교체 가능. */
	public static final String KEY_LLM_MODEL = "analytics.llm-model";
	/** 1회 실행당 분석(LLM 호출) 콘텐츠 수 상한 — 비용 가드. */
	public static final String KEY_ANALYZE_BATCH_LIMIT = "analytics.analyze-batch-limit";
	/** 1회 실행당 계정 카피(LLM 호출) 계정 수 상한 — 비용 가드. */
	public static final String KEY_ACCOUNT_ANALYZE_BATCH_LIMIT = "analytics.account-analyze-batch-limit";
	/** stale 계정 재분석 최소 간격(일) — 매일 크롤 구조에서 계정당 매일 호출 방지 (스펙 §2-2). */
	public static final String KEY_ACCOUNT_ANALYZE_COOLDOWN_DAYS = "analytics.account-analyze-cooldown-days";

	static final String DEFAULT_LLM_MODEL = "claude-opus-4-8";
	static final int DEFAULT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_COOLDOWN_DAYS = 7;

	private final JdbcTemplate raw;

	public AnalyticsSettings(JdbcTemplate rawJdbcTemplate) {
		this.raw = rawJdbcTemplate;
	}

	public String llmModel() {
		return read(KEY_LLM_MODEL).orElse(DEFAULT_LLM_MODEL);
	}

	public int analyzeBatchLimit() {
		return read(KEY_ANALYZE_BATCH_LIMIT).map(Integer::parseInt).orElse(DEFAULT_ANALYZE_BATCH_LIMIT);
	}

	public int accountAnalyzeBatchLimit() {
		return read(KEY_ACCOUNT_ANALYZE_BATCH_LIMIT).map(Integer::parseInt)
				.orElse(DEFAULT_ACCOUNT_ANALYZE_BATCH_LIMIT);
	}

	public int accountAnalyzeCooldownDays() {
		return read(KEY_ACCOUNT_ANALYZE_COOLDOWN_DAYS).map(Integer::parseInt)
				.orElse(DEFAULT_ACCOUNT_ANALYZE_COOLDOWN_DAYS);
	}

	private java.util.Optional<String> read(String key) {
		return raw.query("SELECT value FROM app_setting WHERE key = ?",
				rs -> rs.next() ? java.util.Optional.of(rs.getString(1)) : java.util.Optional.empty(), key);
	}
}
