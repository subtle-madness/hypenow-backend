package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.testsupport.TestDb;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PipelineStatsService의 DB 대는 두 대상 판정(accountTarget·copiedHandles) 전용 —
 * 순수 로직 테스트(PipelineStatsServiceTest)는 Docker 없이도 돌아야 해서 분리했다
 * (07-28 리뷰: Testcontainers 클래스에 순수 테스트까지 묶으면 Docker 없는 환경에서 전부 실행 불가).
 */
@Testcontainers
class PipelineStatsServiceDbTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	PipelineStatsService service;

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		// raw JdbcTemplate은 이 두 대상 판정 메서드(accountTarget·copiedHandles)가 건드리지 않는다 —
		// 같은 analysis DataSource를 재사용해도 안전(무거운 집계 raw 뷰 픽스처 불필요).
		service = new PipelineStatsService(db, ds, new AnalyticsSettings(db));
	}

	/**
	 * 07-28 드리프트 재발 방지 — AccountAnalysisJob.ELIGIBLE_WHERE와 동형이어야 할 대상 카운트.
	 * 구 스키마 행(perf_summary NULL)만 있는 계정은 입력 동일·쿨다운 미경과라도 대상에 잡혀야 한다
	 * (수정 전 쿼리는 이 계정을 0으로 셌다 — 뮤테이션으로 실증).
	 */
	@Test
	void accountTarget은_구_스키마_행만_있는_계정도_대상으로_센다() {
		db.update("""
				INSERT INTO account_summaries (handle, analyzed_count, last_posted_at)
				VALUES ('acct_legacy', 3, timestamptz '2026-07-01 09:00:00+09')""");
		// 입력 동일(stale 아님) + 쿨다운(기본 7일) 미경과(1시간 전 분석) — 그런데도 구 스키마라 대상이어야 한다
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at, tagline, summary)
				VALUES ('acct_legacy', now() - interval '1 hour', 'm',
				  timestamptz '2026-07-01 09:00:00+09', '옛 태그라인', '옛 요약')""");

		assertThat(service.accountTarget()).isEqualTo(1);
	}

	/** 신 스키마(perf_summary 있음)를 갖고 입력도 동일·쿨다운도 미경과인 계정은 대상 밖이어야 한다. */
	@Test
	void accountTarget은_신_스키마_카피를_보유한_계정은_대상에서_뺀다() {
		db.update("""
				INSERT INTO account_summaries (handle, analyzed_count, last_posted_at)
				VALUES ('acct_done', 3, timestamptz '2026-07-01 09:00:00+09')""");
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  tagline, perf_summary, content_summary)
				VALUES ('acct_done', now(), 'm', timestamptz '2026-07-01 09:00:00+09',
				  '태그라인', '성과 요약', '콘텐츠 요약')""");

		assertThat(service.accountTarget()).isZero();
	}

	@Test
	void copiedHandles은_구_스키마_최신_행은_카피_완료로_안_센다() {
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at, tagline, summary)
				VALUES ('acct_legacy', now(), 'm', timestamptz '2026-07-01 09:00:00+09', '옛 태그라인', '옛 요약')""");

		assertThat(service.copiedHandles()).isEmpty();
	}

	@Test
	void copiedHandles은_신_스키마_최신_행이면_카피_완료로_센다() {
		// 이력 2행 — 최신(analyzed_at 더 늦음) 행만 기준이어야 한다(DISTINCT ON ... ORDER BY analyzed_at DESC).
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at, tagline, summary)
				VALUES ('acct_upgraded', now() - interval '2 days', 'm',
				  timestamptz '2026-06-01 09:00:00+09', '옛 태그라인', '옛 요약')""");
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  tagline, perf_summary, content_summary)
				VALUES ('acct_upgraded', now(), 'm', timestamptz '2026-07-01 09:00:00+09',
				  '새 태그라인', '성과 요약', '콘텐츠 요약')""");

		assertThat(service.copiedHandles()).containsExactly("acct_upgraded");
	}
}
