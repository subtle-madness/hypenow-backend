package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.testsupport.TestDb;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PipelineStatsServiceTest {

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

	@Test
	void 잔여로_오늘예정과_완주여부() {
		// LIMIT 폐지(2026-07-23) 이후 잡은 잔여 전량을 매 실행 시도 — 오늘 예정=잔여 전체,
		// 완주까지는 잔여가 있으면 항상 "다음 1회"뿐(쿼타 이월은 RunHistory가 별도 추적).
		assertThat(PipelineStatsService.todayPlanned(24_551)).isEqualTo(24_551);
		assertThat(PipelineStatsService.daysToFull(24_551)).isEqualTo(1);
		// 잔여 0
		assertThat(PipelineStatsService.todayPlanned(0)).isZero();
		assertThat(PipelineStatsService.daysToFull(0)).isZero();
	}

	@Test
	void 트랙별_대조는_기분석_셋과의_교집합으로_4분할() {
		// 후보 5건: timely 2(a,b) + 윈도우 3(c,d,e). 기분석 셋엔 a,c,d와 후보 밖 x.
		Map<String, Boolean> candidates = new LinkedHashMap<>();
		candidates.put("a", true);
		candidates.put("b", true);
		candidates.put("c", false);
		candidates.put("d", false);
		candidates.put("e", false);
		PipelineStatsService.TrackSplit s =
				PipelineStatsService.split(candidates, Set.of("a", "c", "d", "x"));
		assertThat(s.timelyTotal()).isEqualTo(2);
		assertThat(s.timelyDone()).isEqualTo(1);
		assertThat(s.windowTotal()).isEqualTo(3);
		assertThat(s.windowDone()).isEqualTo(2);
		// 항등식: 후보 = 트랙 합, 후보 밖 기분석(x)은 어디에도 안 센다
		assertThat(s.timelyTotal() + s.windowTotal()).isEqualTo(candidates.size());
	}

	@Test
	void 아카이브_커버리지는_잡의_대상_선정과_같은_규칙으로_대조() {
		// 썸네일: 대상 {a,b,c} ∩ 기록 {a,z} = 1건 아카이브 (대상 밖 기록 z는 안 센다 — 잡과 동일).
		// 프로필: 파일명 일치(p1)만 최신, 불일치(p2)·파싱 불가(p3)는 갱신 대기(잡의 '변경 취급').
		PipelineStatsService.ArchiveCoverage c = PipelineStatsService.archiveCoverage(
				Set.of("a", "b", "c"), Set.of("a", "z"),
				Map.of("p1", "https://cdn.example/t51/111_n.jpg?sig=1",
						"p2", "https://cdn.example/t51/222_n.jpg",
						"p3", "not a url"),
				Map.of("p1", "111_n.jpg", "p2", "999_n.jpg"));
		assertThat(c.thumbTargets()).isEqualTo(3);
		assertThat(c.thumbArchived()).isEqualTo(1);
		assertThat(c.thumbPending()).isEqualTo(2);
		assertThat(c.profileTargets()).isEqualTo(3);
		assertThat(c.profileFresh()).isEqualTo(1);
		assertThat(c.profilePending()).isEqualTo(2);
		// 카드 한 줄용 합산
		assertThat(c.targets()).isEqualTo(6);
		assertThat(c.archived()).isEqualTo(2);
		assertThat(c.pending()).isEqualTo(4);
	}

	@Test
	void heavy_스냅샷은_항등식_후보는_기분석더하기미분석() {
		// v3 설계 문서 §1 실측(07-21): 후보 7,402 = timely 1,435 + 윈도우 5,967, 미분석 286.
		PipelineStatsService.Heavy h = new PipelineStatsService.Heavy(
				7_402, 1_435, 1_432, 5_967, 5_684,
				12_777, 11_072, 4_000, 1_104, 723, 700,
				new PipelineStatsService.ArchiveCoverage(107_886, 27_686, 5_699, 5_694),
				Instant.now());
		assertThat(h.timelyPending()).isEqualTo(3);
		assertThat(h.windowPending()).isEqualTo(283);
		assertThat(h.truePending()).isEqualTo(286);
		// 항등식: 후보 = (트랙별 기분석 + 미분석)의 합
		assertThat(h.timelyDone() + h.timelyPending() + h.windowDone() + h.windowPending())
				.isEqualTo(h.candidates());
	}
}
