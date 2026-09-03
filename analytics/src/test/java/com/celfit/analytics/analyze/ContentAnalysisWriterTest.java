package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.testsupport.TestDb;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * content_analyses 행의 2단계 상태 전이 계약(2026-09-03 설계 §3):
 * insertFacts로 "A만"(pending·해석 NULL·기준선 NULL) → updateSynthesis로 "A+B"(timely 확정·version).
 * 재생성 잡이 같은 updateSynthesis를 쓰므로 기존 마킹 보존도 여기서 고정한다.
 */
class ContentAnalysisWriterTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	JdbcTemplate db;
	DataSource ds;
	ObjectMapper json = new ObjectMapper();

	static final Baseline BASELINE = new Baseline(9000L, 1, 2, 3, new BigDecimal("0.0496"),
			940L, 61L, 67, 19333L, 3L);

	static ContentAttributes facts() {
		return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "캡션 언급")), "high",
				List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징폼"),
				List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
				List.of(new ContentAttributes.Attribute("무드", "화사함")), "cleansing",
				List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), "sponsored", true, true);
	}

	static Synthesis synthesis() {
		return new Synthesis("요약", "패턴", "댓글 인사이트", "high", "판정 근거");
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
	}

	@Test
	void insertFacts는_사실만_채우고_pending으로_남긴다() {
		int inserted = ContentAnalysisWriter.insertFacts(db, json, "sc_a", "gemini-test", facts());

		assertEquals(1, inserted); // 신규 INSERT - 1행(M8)
		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("sponsored", db.queryForObject(
				"SELECT ad_type FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals(Boolean.TRUE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'sc_a'", Boolean.class));
		// 해석 5필드·기준선 10컬럼은 비어 있다 - D+1 기준선은 미성숙 지표를 물어 드로어를 하향 편향시킨다
		assertNull(db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT comment_authenticity_grade FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'sc_a'", Long.class));
		assertNull(db.queryForObject(
				"SELECT synthesis_version FROM content_analyses WHERE short_code = 'sc_a'", Integer.class));
		assertNull(db.queryForObject(
				"SELECT synthesized_at FROM content_analyses WHERE short_code = 'sc_a'",
				java.time.OffsetDateTime.class));
		// analyzed_at은 파트 A INSERT 시각(DEFAULT now()) - 파트 B가 갱신하지 않는다
		assertNotNull(db.queryForObject(
				"SELECT analyzed_at FROM content_analyses WHERE short_code = 'sc_a'",
				java.time.OffsetDateTime.class));
	}

	@Test
	void insertFacts는_중복_제출에도_행을_덮지_않는다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_a", "gemini-test", facts());
		ContentAnalysisWriter.updateSynthesis(db, "sc_a", "gemini-test", BASELINE, synthesis(), "timely");

		// 같은 배치가 두 번 수거돼도 파트 B 결과를 지우면 안 된다(ON CONFLICT DO NOTHING)
		int inserted = ContentAnalysisWriter.insertFacts(db, json, "sc_a", "gemini-test", facts());

		assertEquals(0, inserted); // 이미 존재하는 행 - ON CONFLICT DO NOTHING이 삼킨다(M8)
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void updateSynthesis가_A만_행을_A더하기B로_전이시킨다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_a", "facts-model", facts());

		int updated = ContentAnalysisWriter.updateSynthesis(
				db, "sc_a", "synth-model", BASELINE, synthesis(), "timely");

		assertEquals(1, updated);
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals("요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'sc_a'", Long.class));
		assertEquals(Synthesis.VERSION, db.queryForObject(
				"SELECT synthesis_version FROM content_analyses WHERE short_code = 'sc_a'", Integer.class));
		assertNotNull(db.queryForObject(
				"SELECT synthesized_at FROM content_analyses WHERE short_code = 'sc_a'",
				java.time.OffsetDateTime.class));
		// 파트 A 컬럼은 건드리지 않는다
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'sc_a'", String.class));
		// model은 파트 B가 덮는다(기록 규칙 명시 - 실제로는 둘 다 같은 모델)
		assertEquals("synth-model", db.queryForObject(
				"SELECT model FROM content_analyses WHERE short_code = 'sc_a'", String.class));
	}

	@Test
	void updateSynthesis는_늦크롤이면_late_backfill로_마킹한다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_b", "facts-model", facts());

		ContentAnalysisWriter.updateSynthesis(db, "sc_b", "m", BASELINE, synthesis(), "late_backfill");

		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_b'", String.class));
	}

	@Test
	void 재생성_경로가_기존_마킹을_보존한다() {
		// 재생성 잡은 저장된 값을 그대로 넘긴다 - 지표 시점은 수집 시점 사실이라 갱신 대상이 아니다
		ContentAnalysisWriter.insertFacts(db, json, "sc_c", "m", facts());
		ContentAnalysisWriter.updateSynthesis(db, "sc_c", "m", BASELINE, synthesis(), "late_backfill");

		ContentAnalysisWriter.updateSynthesis(db, "sc_c", "m", BASELINE,
				new Synthesis("새 요약", "새 패턴", "새 댓글", "normal", "새 근거"), "late_backfill");

		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_c'", String.class));
		assertEquals("새 요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_c'", String.class));
	}

	@Test
	void 행이_없으면_updateSynthesis는_0행이다() {
		assertEquals(0, ContentAnalysisWriter.updateSynthesis(
				db, "sc_missing", "m", BASELINE, synthesis(), "timely"));
	}

	@Test
	void updateSynthesisPending은_pending_행만_전이시킨다() {
		ContentAnalysisWriter.insertFacts(db, json, "sc_d", "facts-model", facts());

		int updated = ContentAnalysisWriter.updateSynthesisPending(
				db, "sc_d", "synth-model", BASELINE, synthesis(), "timely");

		assertEquals(1, updated);
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_d'", String.class));
		assertEquals("요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_d'", String.class));
	}

	@Test
	void updateSynthesisPending은_이미_확정된_행을_건드리지_않는다() {
		// 시나리오(2026-09-03 리뷰): split ON → 파트 B 배치 제출 → 운영자가 롤백하며 pending 행
		// 삭제 → 통합 ANALYZE가 같은 short_code를 완결 행으로 재생성 → 그 뒤 옛 파트 B 배치 결과가
		// 도착. pending 가드가 없으면 이 UPDATE가 방금 만든 완결 행을 덮어쓴다.
		ContentAnalysisWriter.insertFacts(db, json, "sc_e", "facts-model", facts());
		ContentAnalysisWriter.updateSynthesis(db, "sc_e", "synth-model", BASELINE, synthesis(), "timely");

		int updated = ContentAnalysisWriter.updateSynthesisPending(db, "sc_e", "late-batch",
				BASELINE, new Synthesis("낡은 요약", "낡은 패턴", "낡은 댓글", "low", "낡은 근거"), "late_backfill");

		assertEquals(0, updated); // 이미 timely로 확정된 행이라 대상이 아니다
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'sc_e'", String.class));
		assertEquals("요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'sc_e'", String.class));
		assertEquals("synth-model", db.queryForObject(
				"SELECT model FROM content_analyses WHERE short_code = 'sc_e'", String.class));
	}
}
