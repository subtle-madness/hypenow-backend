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
	/** 제때 크롤 판정 여유(일) — 고정 지표가 업로드 +(pin+여유)일을 넘겨 잡히면 "늦크롤"로 판정한다
	 * (07-20 개정: 백필 MVP 제외(07-19)를 번복 — 늦크롤이어도 최근 N개 윈도우 안이면 대상에 포함해
	 * late_backfill로 분석한다. 이 키는 이제 분석 대상 여부가 아니라 timely/late_backfill 마킹의
	 * 판정 기준점으로 쓰인다). 분석 밀림은 나이 무관 허용. */
	public static final String KEY_ANALYZE_TIMELY_SLACK_DAYS = "analytics.analyze-timely-slack-days";
	/** LLM 프로바이더 선택 — gemini(기본, 07-18 확정) | anthropic(롤백 경로). 전환은 재기동 필요(빈 생성 시 결정). */
	public static final String KEY_LLM_PROVIDER = "analytics.llm-provider";
	/** Gemini 모델 — 07-18 골드셋 확정. 구모델(2.5 등)은 신규 API 키에서 404라 3.1이 유일. */
	public static final String KEY_GEMINI_MODEL = "analytics.gemini-model";
	/** Gemini 분당 호출 상한 — 무료 티어 15 RPM. crawler 판정과 동시 실행 시 합산 초과 주의. */
	public static final String KEY_GEMINI_RPM = "analytics.gemini-rpm";
	/** 1회 실행당 이미지 아카이브(다운로드+업로드) 상한 — 초과분은 이월. */
	public static final String KEY_ARCHIVE_BATCH_LIMIT = "analytics.archive-batch-limit";
	/** Vertex AI GCP 프로젝트 ID — provider=vertex일 때 필수. */
	public static final String KEY_VERTEX_PROJECT = "analytics.vertex-project";
	/** Vertex AI 로케이션 — gemini-3.1-flash-lite는 global/us/eu만 제공(도쿄 없음), 기본 global. */
	public static final String KEY_VERTEX_LOCATION = "analytics.vertex-location";
	/** Vertex 배치 입출력 GCS 버킷 이름(gs:// 없이) — 백필 배치 전용. */
	public static final String KEY_VERTEX_GCS_BUCKET = "analytics.vertex-gcs-bucket";
	/** 최근 N개 윈도우 — 01 뷰(v_recent_content)와 공유하는 키. 분석 자격 OR 분기에서 사용. */
	public static final String KEY_RECENT_WINDOW = "analytics.recent-window";
	/** 동시 처리(병렬) 개수 — LLM 콜 처리량 개선(2026-07-23). Vertex는 RPM 페이싱이 없어(DSQ)
	 * 병렬화 여유가 있고, Gemini로 되돌려도 GeminiHttpApi.pace()가 synchronized라 안전하게
	 * 감속된다. 429 빈도를 보며 재배포 없이 조정할 수 있게 app_setting으로 뺀다. */
	public static final String KEY_ANALYZE_CONCURRENCY = "analytics.analyze-concurrency";
	/** 콘텐츠 분석 전송 방식 — online(기본, 동기 즉시 호출) | batch(Vertex 배치 50% 할인, 익일 수거).
	 * 2026-08-11: 콘텐츠 분석(ANALYZE·LATE_BACKFILL_ANALYZE)에만 적용, 계정 카피는 대상 아님.
	 * 잡 실행 시점마다 매번 읽으므로 재기동 없이 전환된다. 롤백은 값을 online으로 되돌리는 UPDATE 한 줄. */
	public static final String KEY_ANALYZE_TRANSPORT = "analytics.analyze-transport";
	/**
	 * 계정 카피 전송 방식 — online(기본)|batch. 콘텐츠 토글(analytics.analyze-transport)과 독립
	 * (08-11 콘텐츠 전환 때 계정 카피는 의도적으로 제외 — 2026-08-17 후속 전환).
	 */
	public static final String KEY_ACCOUNT_ANALYZE_TRANSPORT = "analytics.account-analyze-transport";

	// app_setting 미설정 시 폴백 — 비용 가드로 최저가 티어(haiku) 고정. Opus 등 상위 모델은 app_setting으로 명시 전환.
	static final String DEFAULT_LLM_MODEL = "claude-haiku-4-5-20251001";
	static final int DEFAULT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_BATCH_LIMIT = 10;
	static final int DEFAULT_ACCOUNT_ANALYZE_COOLDOWN_DAYS = 7;
	static final int DEFAULT_ANALYZE_MATURITY_DAYS = 3;
	static final int DEFAULT_METRIC_PIN_DAYS = 3;
	static final int DEFAULT_ANALYZE_TIMELY_SLACK_DAYS = 2;
	static final String DEFAULT_LLM_PROVIDER = "gemini";
	static final String DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite";
	static final int DEFAULT_GEMINI_RPM = 15;
	static final int DEFAULT_ARCHIVE_BATCH_LIMIT = 1000;
	static final String DEFAULT_VERTEX_LOCATION = "global";
	static final int DEFAULT_RECENT_WINDOW = 12;
	static final int DEFAULT_ANALYZE_CONCURRENCY = 8;
	static final String DEFAULT_ANALYZE_TRANSPORT = "online";
	static final String DEFAULT_ACCOUNT_ANALYZE_TRANSPORT = "online";

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

	/** 07-28 캘린더일 정합 후 잡에서 미사용 — 후보 성숙 가드는 04 뷰 소관. 키 호환을 위해 유지. */
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

	public int archiveBatchLimit() {
		return read(KEY_ARCHIVE_BATCH_LIMIT).map(Integer::parseInt)
				.orElse(DEFAULT_ARCHIVE_BATCH_LIMIT);
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

	public int analyzeConcurrency() {
		return read(KEY_ANALYZE_CONCURRENCY).map(Integer::parseInt).orElse(DEFAULT_ANALYZE_CONCURRENCY);
	}

	/** 잡 실행 시점마다 매번 읽는다(캐시 없음) — 재기동 없이 online↔batch 전환. */
	public String analyzeTransport() {
		return read(KEY_ANALYZE_TRANSPORT).orElse(DEFAULT_ANALYZE_TRANSPORT);
	}

	/** true면 콘텐츠 분석(ANALYZE·LATE_BACKFILL_ANALYZE)이 Vertex 배치 제출 경로로 전환된다.
	 * 계정 카피는 accountBatchTransportEnabled() 별도 토글(2026-08-17). */
	public boolean batchTransportEnabled() {
		return "batch".equals(analyzeTransport());
	}

	/** 잡 실행 시점마다 매번 읽는다(캐시 없음) — 재기동 없이 online↔batch 전환. */
	public String accountAnalyzeTransport() {
		return read(KEY_ACCOUNT_ANALYZE_TRANSPORT).orElse(DEFAULT_ACCOUNT_ANALYZE_TRANSPORT);
	}

	/** true면 계정 카피(ACCOUNT_ANALYZE)가 Vertex 배치 제출 경로로 전환된다. */
	public boolean accountBatchTransportEnabled() {
		return "batch".equals(accountAnalyzeTransport());
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
