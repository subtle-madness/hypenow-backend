package com.celfit.was.crawlcost;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.crawlcost.CrawlCallDailyRepository.JobCallDaily;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 미러 테이블 조회 검증 — was는 crawl_call_daily를 analysis DB(기본 데이터소스)에서
 * 스키마 접두어 없이 읽는다(contents·account_summaries와 같은 자리). 테이블 자체는 analytics
 * 모듈의 Flyway 소관이라 was 테스트 DB에는 없다 — 여기서 직접 만든다(기존 어드민 조회
 * 테스트가 contents·accounts를 만드는 것과 같은 관용구).
 */
class CrawlCallDailyRepositoryTest extends IntegrationTest {

	@Autowired
	CrawlCallDailyRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	@BeforeEach
	void setUp() {
		jdbcClient.sql("DROP TABLE IF EXISTS crawl_call_daily").update();
		jdbcClient.sql("""
				CREATE TABLE crawl_call_daily (job text NOT NULL, called_on date NOT NULL,
				    calls bigint NOT NULL, PRIMARY KEY (job, called_on))
				""").update();
	}

	@Test
	void 미러_테이블이_비어_있으면_빈_목록이다() {
		assertThat(repository.findAll()).isEmpty();
	}

	@Test
	void 잡_날짜별_행을_그대로_읽는다() {
		jdbcClient.sql("""
				INSERT INTO crawl_call_daily VALUES
				 ('COLLECT', date '2026-08-13', 120),
				 ('COLLECT', date '2026-08-12', 98),
				 ('REELS',   date '2026-08-13', 7)
				""").update();

		List<JobCallDaily> rows = repository.findAll();

		assertThat(rows).containsExactlyInAnyOrder(
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 120),
				new JobCallDaily("COLLECT", LocalDate.of(2026, 8, 12), 98),
				new JobCallDaily("REELS", LocalDate.of(2026, 8, 13), 7));
	}
}
