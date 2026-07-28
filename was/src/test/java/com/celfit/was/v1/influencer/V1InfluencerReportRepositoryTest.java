package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 전환기 게이트(findLatestCopy의 {@code summary IS NOT NULL}) 전용 테스트.
 * 시드 세계관: mixed — 구행(summary 有, analyzed_at 과거) + 07-28 개편 백필이 채운 신 스키마
 * 최신행(summary NULL, perf_summary만 有)이 섞인 계정 → 구행이 이겨야 한다.
 * newonly — 신 스키마 행만 있는 계정 → findLatestCopy는 empty(v1 컨트롤러가 카피 필드만 null로
 * 서빙하는 기존 동작 유지, 블록 구조는 그대로).
 */
class V1InfluencerReportRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1InfluencerReportRepository repository;

	@BeforeEach
	void setUpTables() {
		// 분석 DB 형상 DDL 사본(필요 컬럼만) — account_analyses(V1) 참조
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_analyses");
		jdbcTemplate.execute("""
				CREATE TABLE account_analyses (
				    handle       text NOT NULL,
				    analyzed_at  timestamptz NOT NULL,
				    tagline      text,
				    traits       jsonb,
				    summary      text,
				    trend_note   text,
				    chart_note   text,
				    ad_headline  text,
				    pace_note    text,
				    perf_summary text,
				    PRIMARY KEY (handle, analyzed_at)
				)""");

		// mixed — 구행(summary 有, 과거) + 신 스키마 최신행(summary NULL, perf_summary만 有)
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, tagline, traits, summary,
				  trend_note, chart_note, ad_headline, pace_note, perf_summary) VALUES
				  ('mixed', now() - interval '2 days', '구행 태그라인', '["a","b"]'::jsonb,
				   '구행 요약', '구행 추이', '구행 차트노트', '구행 광고헤드라인', '구행 페이스노트', NULL),
				  ('mixed', now() - interval '1 day', '신행 태그라인(무시되어야 함)', NULL,
				   NULL, NULL, NULL, NULL, NULL, '신 스키마 성과 요약')""");

		// newonly — 신 스키마 행만 존재(백필 대상, 구 카피 없음)
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, tagline, traits, summary,
				  trend_note, chart_note, ad_headline, pace_note, perf_summary) VALUES
				  ('newonly', now(), NULL, NULL, NULL, NULL, NULL, NULL, NULL, '신 스키마 성과 요약')""");
	}

	// 뮤테이션 실증(판별력 증명): 이 테스트를 남겨둔 채 V1InfluencerReportRepository.findLatestCopy의
	// "AND summary IS NOT NULL" 절을 임시로 제거하고 실행하면, 신 스키마 최신행(summary NULL)이
	// 반환되어 아래 summary()·tagline() 단언이 실패한다 — 원복 후 통과 확인(검증 로그 참조).
	@Test
	void 구_형식_행이_있으면_신_스키마_최신행을_무시하고_구행을_반환() {
		CopyRow copy = repository.findLatestCopy("mixed").orElseThrow();
		assertThat(copy.summary()).isEqualTo("구행 요약"); // 최신 신 스키마행(summary NULL) 무시
		assertThat(copy.tagline()).isEqualTo("구행 태그라인");
		assertThat(copy.trendNote()).isEqualTo("구행 추이");
		assertThat(copy.chartNote()).isEqualTo("구행 차트노트");
		assertThat(copy.adHeadline()).isEqualTo("구행 광고헤드라인");
		assertThat(copy.paceNote()).isEqualTo("구행 페이스노트");
	}

	@Test
	void 구_형식_행이_없으면_empty() {
		assertThat(repository.findLatestCopy("newonly")).isEmpty();
	}
}
