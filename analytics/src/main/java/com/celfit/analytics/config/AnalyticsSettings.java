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
	/** 분석 대상 최소 숙성 일수 — 게시 직후 분석·영구 고정 방지 (B3 숙성 가드, 07-14 확정). */
	public static final String KEY_ANALYZE_MATURITY_DAYS = "analytics.analyze-maturity-days";
	/** 서빙 지표 고정 시점(일) — 02 뷰(v_contents 핀)와 공유하는 키. 제때 크롤 판정의 기준점. */
	public static final String KEY_METRIC_PIN_DAYS = "analytics.metric-pin-days";
	/** 제때 크롤 판정 여유(일) — 백필 MVP 제외(07-19 재정정): 고정 지표가 업로드 +(pin+여유)일을
	 * 넘겨 잡힌 늦크롤분은 +3일 지표가 없어 분석하지 않는다. 분석 밀림은 나이 무관 허용. */
	public static final String KEY_ANALYZE_TIMELY_SLACK_DAYS = "analytics.analyze-timely-slack-days";
	/** LLM 프로바이더 선택 — gemini(기본, 07-18 확정) | anthropic(롤백 경로). 전환은 재기동 필요(빈 생성 시 결정). */
	public static final String KEY_LLM_PROVIDER = "analytics.llm-provider";
	/** Gemini 모델 — 07-18 골드셋 확정. 구모델(2.5 등)은 신규 API 키에서 404라 3.1이 유일. */
	public static final String KEY_GEMINI_MODEL = "analytics.gemini-model";
	/** Gemini 분당 호출 상한 — 무료 티어 15 RPM. crawler 판정과 동시 실행 시 합산 초과 주의. */
	public static final String KEY_GEMINI_RPM = "analytics.gemini-rpm";
	/** Vertex AI GCP 프로젝트 ID — provider=vertex일 때 필수. */
	public static final String KEY_VERTEX_PROJECT = "analytics.vertex-project";
	/** Vertex AI 로케이션 — gemini-3.1-flash-lite는 global/us/eu만 제공(도쿄 없음), 기본 global. */
	public static final String KEY_VERTEX_LOCATION = "analytics.vertex-location";
	/** Vertex 배치 입출력 GCS 버킷 이름(gs:// 없이) — 백필 배치 전용. */
	public static final String KEY_VERTEX_GCS_BUCKET = "analytics.vertex-gcs-bucket";
	/** 최근 N개 윈도우 — 01 뷰(v_recent_content)와 공유하는 키. 분석 자격 OR 분기에서 사용. */
	public static final String KEY_RECENT_WINDOW = "analytics.recent-window";

	static final String DEFAULT_LLM_MODEL = "claude-opus-4-8";
	static final int DEFAULT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_COOLDOWN_DAYS = 7;
	static final int DEFAULT_ANALYZE_MATURITY_DAYS = 3;
	static final int DEFAULT_METRIC_PIN_DAYS = 3;
	static final int DEFAULT_ANALYZE_TIMELY_SLACK_DAYS = 2;
	static final String DEFAULT_LLM_PROVIDER = "gemini";
	static final String DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite";
	static final int DEFAULT_GEMINI_RPM = 15;
	static final String DEFAULT_VERTEX_LOCATION = "global";
	static final int DEFAULT_RECENT_WINDOW = 12;

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

	public int analyzeMaturityDays() {
		return read(KEY_ANALYZE_MATURITY_DAYS).map(Integer::parseInt)
				.orElse(DEFAULT_ANALYZE_MATURITY_DAYS);
	}

	public int metricPinDays() {
		return read(KEY_METRIC_PIN_DAYS).map(Integer::parseInt)
				.orElse(DEFAULT_METRIC_PIN_DAYS);
	}

	public int analyzeTimelySlackDays() {
		return read(KEY_ANALYZE_TIMELY_SLACK_DAYS).map(Integer::parseInt)
				.orElse(DEFAULT_ANALYZE_TIMELY_SLACK_DAYS);
	}

	public String llmProvider() {
		return read(KEY_LLM_PROVIDER).orElse(DEFAULT_LLM_PROVIDER);
	}

	public String geminiModel() {
		return read(KEY_GEMINI_MODEL).orElse(DEFAULT_GEMINI_MODEL);
	}

	public int geminiRpm() {
		return read(KEY_GEMINI_RPM).map(Integer::parseInt).orElse(DEFAULT_GEMINI_RPM);
	}

	/** provider=vertex일 때만 호출됨 — 미설정이면 배선 시점에 fail-fast. */
	public String vertexProject() {
		return read(KEY_VERTEX_PROJECT).orElseThrow(() -> new IllegalStateException(
				KEY_VERTEX_PROJECT + " 미설정 — app_setting에 GCP 프로젝트 ID 등록 필요"));
	}

	public String vertexLocation() {
		return read(KEY_VERTEX_LOCATION).orElse(DEFAULT_VERTEX_LOCATION);
	}

	public String vertexGcsBucket() {
		return read(KEY_VERTEX_GCS_BUCKET).orElseThrow(() -> new IllegalStateException(
				KEY_VERTEX_GCS_BUCKET + " 미설정 — app_setting에 배치용 GCS 버킷 등록 필요"));
	}

	public int recentWindow() {
		return read(KEY_RECENT_WINDOW).map(Integer::parseInt).orElse(DEFAULT_RECENT_WINDOW);
	}

	/** content_analyses.model 등 기록에 쓰는 활성 모델명 — 프로바이더 따라 결정. */
	public String activeLlmModel() {
		return "anthropic".equals(llmProvider()) ? llmModel() : geminiModel();
	}

	private java.util.Optional<String> read(String key) {
		return raw.query("SELECT value FROM app_setting WHERE key = ?",
				rs -> rs.next() ? java.util.Optional.of(rs.getString(1)) : java.util.Optional.empty(), key);
	}
}
