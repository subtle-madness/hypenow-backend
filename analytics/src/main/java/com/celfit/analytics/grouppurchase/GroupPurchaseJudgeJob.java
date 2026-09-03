package com.celfit.analytics.grouppurchase;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.LlmQuotaExhaustedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 공동구매(공구) 판정 잡(스펙 2026-09-03-group-purchase-judgment-design.md §5) — 규칙이 확실한
 * 곳은 {@link GroupPurchaseRule}로 즉시 확정 기록하고, 애매분만 {@link GroupPurchaseJudgePort}
 * (LLM)로 보낸다. RULE 확정분도 기록해 was가 {@code group_purchase_judgments} 단일 테이블
 * 조회로 6.21·6.4를 끝낸다(스펙 §4·§6).
 *
 * <p>후보 = analysis DB {@code contents}에서 캡션이 {@code 공구|공동구매}에 걸리거나 이미 판정
 * 행이 있는 것 중, 판정이 없거나(신규) verdict가 NULL이거나(LLM 실패 재시도) 캡션 해시가
 * 달라진(재판정) 것 — 해시는 Java {@link MessageDigest}로 계산해 기록·비교 양쪽에 같은
 * 알고리즘을 쓴다(monitoring {@code AdDisclosureJudgeService}와 동일 원칙, analytics는
 * 코드를 공유하지 않고 이 클래스에서 다시 구현한다).
 *
 * <p>실패 격리는 게시물 단위 — LLM 호출 실패는 verdict NULL로 남겨 다음 실행이 재시도한다
 * (해시가 안 바뀌어도 verdict NULL이면 후보로 재선정된다). {@link #LLM_FAILURE_ABORT_THRESHOLD}
 * 연속 실패에 도달하면 남은 후보는 손대지 않고 실행을 중단한다(AdDisclosureJudgeService
 * 서킷브레이커와 같은 방어선 — 429 폭주 등 런 단위 장애가 후보 전체를 헛되이 두드리지 않게).
 * {@link LlmQuotaExhaustedException}은 일 한도 소진(런 단위 조건)이라 카운터를 보지 않고 즉시
 * 중단한다.
 *
 * <p>킬 스위치 {@code analytics.group-purchase.enabled}(기본 true, crawler 마이그레이션 시드)가
 * 꺼져 있으면 아무 것도 하지 않고 즉시 반환한다.
 */
public class GroupPurchaseJudgeJob {

	private static final Logger log = LoggerFactory.getLogger(GroupPurchaseJudgeJob.class);

	/** LLM 연속 실패 서킷브레이커 임계 — AdDisclosureJudgeService의 llmFailureAbortThreshold와 같은 패턴. */
	static final int LLM_FAILURE_ABORT_THRESHOLD = 5;

	private static final String CANDIDATES_SQL = """
			SELECT c.short_code, c.caption, j.judged_caption_hash, j.verdict
			FROM contents c
			LEFT JOIN group_purchase_judgments j ON j.short_code = c.short_code
			WHERE c.caption ~ '공구|공동구매' OR j.short_code IS NOT NULL
			ORDER BY c.posted_at DESC NULLS LAST, c.short_code""";

	private static final String UPSERT_SQL = """
			INSERT INTO group_purchase_judgments
			  (short_code, verdict, tier, reason, judged_caption_hash, judged_at, model)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (short_code) DO UPDATE SET
			  verdict = EXCLUDED.verdict, tier = EXCLUDED.tier, reason = EXCLUDED.reason,
			  judged_caption_hash = EXCLUDED.judged_caption_hash, judged_at = EXCLUDED.judged_at,
			  model = EXCLUDED.model""";

	private final JdbcTemplate analysis;
	private final GroupPurchaseJudgePort port;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;

	public GroupPurchaseJudgeJob(DataSource analysisDataSource, GroupPurchaseJudgePort port,
			AnalyticsSettings settings, ProgressReporter reporter) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.port = port;
		this.settings = settings;
		this.reporter = reporter;
	}

	public JobResult run() {
		if (!settings.groupPurchaseEnabled()) {
			log.info("공동구매 판정 — 킬 스위치 꺼짐(analytics.group-purchase.enabled=false), 스킵");
			return new JobResult(0, 0, false);
		}
		List<Candidate> candidates = findCandidates();
		if (candidates.isEmpty()) {
			log.info("공동구매 판정 대상 없음");
			return new JobResult(0, 0, false);
		}
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		int consecutiveLlmFailures = 0;
		boolean carriedOver = false;
		reporter.report(0, 0, candidates.size());
		for (Candidate c : candidates) {
			GroupPurchaseRule.Result rule = GroupPurchaseRule.evaluate(c.caption());
			String hash = md5(c.caption());
			if (rule.verdict() != GroupPurchaseRule.Verdict.AMBIGUOUS) {
				boolean verdict = rule.verdict() == GroupPurchaseRule.Verdict.CONFIRMED_TRUE;
				upsert(c.shortCode(), verdict, "RULE", rule.reason(), hash, null);
				processed++;
				reporter.report(processed, failed, candidates.size());
				continue;
			}
			try {
				GroupPurchaseJudgePort.Judgment j = port.judge(c.caption());
				upsert(c.shortCode(), j.groupPurchase(), "LLM", j.reason(), hash, model);
				processed++;
				consecutiveLlmFailures = 0;
			} catch (LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 런 단위 조건이라 연속 실패 카운터와 무관하게 즉시 중단.
				// verdict는 기록하지 않는다(행이 없으면 다음 실행이 그대로 후보로 재선정).
				log.warn("공동구매 판정 — LLM 일 한도 소진, 실행 중단(잔여 {}건 다음 실행 재시도): {}",
						candidates.size() - processed - failed, e.toString());
				carriedOver = true;
				break;
			} catch (RuntimeException e) {
				failed++;
				consecutiveLlmFailures++;
				log.warn("공동구매 판정(LLM) 실패(격리, verdict NULL로 다음 실행 재시도) — {}: {}",
						c.shortCode(), e.toString());
				// verdict NULL로 행을 남겨 다음 실행이 (해시 무관하게) 재시도하게 한다.
				upsert(c.shortCode(), null, "LLM", "판정 실패: " + summarize(e), hash, null);
				if (consecutiveLlmFailures >= LLM_FAILURE_ABORT_THRESHOLD) {
					log.error("공동구매 판정 — LLM 연속 실패 {}건, 실행 중단(잔여 후보는 손대지 않음)",
							consecutiveLlmFailures);
					carriedOver = true;
					break;
				}
			}
			reporter.report(processed, failed, candidates.size());
		}
		log.info("공동구매 판정 완료 ({}건 처리, {}건 실패{})", processed, failed,
				carriedOver ? ", 조기 중단(잔여 다음 실행 재시도)" : "");
		return new JobResult(processed, failed, carriedOver);
	}

	private List<Candidate> findCandidates() {
		List<RawRow> rows = analysis.query(CANDIDATES_SQL, (rs, i) -> {
			String shortCode = rs.getString("short_code");
			String caption = rs.getString("caption");
			String judgedHash = rs.getString("judged_caption_hash");
			boolean verdictVal = rs.getBoolean("verdict");
			Boolean verdict = rs.wasNull() ? null : verdictVal;
			return new RawRow(shortCode, caption, judgedHash, verdict);
		});
		List<Candidate> result = new ArrayList<>();
		for (RawRow r : rows) {
			boolean needsJudgment = r.judgedHash() == null || r.verdict() == null
					|| !r.judgedHash().equals(md5(r.caption()));
			if (needsJudgment) {
				result.add(new Candidate(r.shortCode(), r.caption()));
			}
		}
		return result;
	}

	private void upsert(String shortCode, Boolean verdict, String tier, String reason, String hash, String model) {
		analysis.update(UPSERT_SQL, shortCode, verdict, tier, reason, hash,
				Timestamp.from(Instant.now()), model);
	}

	/** 로그·저장 컬럼에 실리는 실패 사유는 한 줄로 — 스택트레이스 전체는 로그(log.warn)에만. */
	private static String summarize(Exception e) {
		String msg = e.getMessage();
		if (msg == null || msg.isBlank()) {
			return e.getClass().getSimpleName();
		}
		return msg.length() > 200 ? msg.substring(0, 200) : msg;
	}

	private record RawRow(String shortCode, String caption, String judgedHash, Boolean verdict) {}

	private record Candidate(String shortCode, String caption) {}

	/** md5(캡션) — Java {@link MessageDigest}, 기록·비교 양쪽 공용(캡션 null은 md5("")). */
	private static String md5(String caption) {
		String c = caption == null ? "" : caption;
		try {
			byte[] digest = MessageDigest.getInstance("MD5").digest(c.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 알고리즘 부재(도달 불가)", e);
		}
	}
}
